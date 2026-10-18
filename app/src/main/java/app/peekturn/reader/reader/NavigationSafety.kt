package app.peekturn.reader.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/** Readium 3.1 inserts decoded htmlId inside a double-quoted native evaluateJavascript string.
 * Keep this guard shared by manifest links, served anchors and durable/search locators.
 */
internal fun isSafeHtmlFragment(fragment: String?): Boolean = fragment == null || fragment.none {
    it == '"' || it == '\\' || it.isISOControl() || it == '\u2028' || it == '\u2029'
}

internal fun isSafeNavigationUrl(url: Url): Boolean = isSafeHtmlFragment(url.fragment)

internal fun isSafeNavigationLink(link: Link): Boolean =
    isSafeNavigationUrl(link.url()) && link.children.all(::isSafeNavigationLink) && link.alternates.all(::isSafeNavigationLink)

internal fun isSafeReaderLocator(locator: Locator): Boolean =
    isSafeNavigationUrl(locator.href) && locator.locations.fragments.all(::isSafeHtmlFragment)
