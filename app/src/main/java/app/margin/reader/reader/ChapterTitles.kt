package app.margin.reader.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private val chapterWhitespace = Regex("\\s+")

/**
 * The publication's real chapter names.
 *
 * Readium builds its own locator title from the table of contents by matching the authored href of
 * the current reading-order link, so a position can still arrive without a title when the two href
 * forms differ (fragments, `./` prefixes, percent-encoding, leading directories) or when the
 * chapter is not in the table of contents at all. Positions are resolved here by resource identity
 * instead, and an unknown chapter stays blank rather than being invented.
 *
 * A title is only applied when the table of contents entry resolves to the one reading-order
 * resource the position belongs to: a shortened entry that several resources could match, or a
 * file name that also exists in another directory, claims none of them.
 *
 * The index is built once per publication, not per page change.
 */
internal class ChapterTitles private constructor(
    private val tocExact: Map<String, String>,
    private val tocByResource: Map<String, String>,
    private val resources: Set<String>,
    private val spineTitles: Map<String, String>,
    private val carriedTitles: Map<String, String> = emptyMap()
) {
    /**
     * Mainstream readers run the nearest preceding section name forward: a resource the table of
     * contents does not name keeps the last named section until the next named section begins. This
     * is what names Part One's untitled body after the part's title page, and why Part Two's own
     * entry stops Part One's name.
     */
    /** Readium's own title, else this publication's name for the locator's resource, else blank. */
    fun titleFor(locator: Locator): String {
        val href = locator.href.toString()
        val key = href.resourceKey()
        // Readium falls back to the resource's own file name when its table-of-contents match fails,
        // and a reader must never see "part0005" where a chapter name belongs. A name that is only the
        // file or its stem is therefore not a title, and resolution continues below.
        cleanChapterTitle(locator.title)?.takeUnless { it.namesResource(key) }?.let { return it }
        return tableOfContentsTitle(href, key) ?: spineTitle(key)
            ?: carriedTitles[resources.alignedResource(key) ?: key].orEmpty()
    }

    /** Stable chapter identity for reuse: the reading-order resource, never its display title. */
    fun keyFor(locator: Locator): String = locator.href.toString().resourceKey()

    private fun tableOfContentsTitle(href: String, key: String): String? {
        tocExact[href]?.let { return it }
        tocExact[key]?.let { return it }
        tocByResource[key]?.let { return it }
        // The position and the table of contents can write the same resource with different leading
        // directories. Only entries that resolve to this one reading-order resource may bridge that,
        // so a shortened entry that fits several resources is not a name for any of them.
        val resource = resources.alignedResource(key) ?: return null
        tocByResource[resource]?.let { return it }
        return tocByResource
            .filterKeys { it.alignsWith(resource) && resources.alignedResource(it) == resource }
            .values.distinct().singleOrNull()
    }

    /** This resource's own name from the table of contents or reading order, without inheritance. */
    private fun ownTitle(href: String, key: String): String? =
        tableOfContentsTitle(href, key) ?: spineTitle(key)

    private fun spineTitle(key: String): String? =
        (resources.alignedResource(key) ?: key).let { spineTitles[it] }

    companion object {
        fun of(tableOfContents: List<Link>, readingOrder: List<Link>): ChapterTitles {
            val exact = linkedMapOf<String, String>()
            val byResource = linkedMapOf<String, String>()

            // A book may title a part and leave its chapters untitled; those chapters are still in
            // that part, so the nearest titled ancestor names them. Inheritance follows the book's own
            // hierarchy and never crosses to a sibling, which is why a named chapter cannot lend its
            // name to the next file.
            fun collect(children: List<Link>, inherited: String?) {
                for (link in children) {
                    val href = link.href.toString()
                    val title = cleanChapterTitle(link.title)
                        ?.takeUnless { it.namesResource(href.resourceKey()) } ?: inherited
                    if (title != null) {
                        // Deeper entries are collected last and own their resource, as in Readium.
                        exact[href] = title
                        byResource[href.resourceKey()] = title
                    }
                    collect(link.children, title)
                }
            }
            collect(tableOfContents, null)

            // Every reading-order resource identifies a chapter, whether or not it is titled.
            val resources = linkedSetOf<String>()
            val spineTitles = linkedMapOf<String, String>()
            for (link in readingOrder) {
                val key = link.href.toString().resourceKey()
                resources += key
                cleanChapterTitle(link.title)?.takeUnless { it.namesResource(key) }
                    ?.let { spineTitles[key] = it }
            }
            val titles = ChapterTitles(exact, byResource, resources, spineTitles)
            // Run each named section forward over the untitled resources that follow it, stopping at
            // the next named section, so a part's body carries the part's name.
            val carried = linkedMapOf<String, String>()
            var current: String? = null
            for (link in readingOrder) {
                val linkHref = link.href.toString()
                val linkKey = linkHref.resourceKey()
                val own = titles.ownTitle(linkHref, linkKey)
                if (own != null) {
                    current = own
                } else if (current != null && titles.ownTitle(linkHref, linkKey) == null) {
                    carried[resources.alignedResource(linkKey) ?: linkKey] = current
                }
            }
            return ChapterTitles(exact, byResource, resources, spineTitles, carried)
        }
    }
}

/** The one resource this key belongs to, or null when that is ambiguous or unknown. */
private fun Set<String>.alignedResource(key: String): String? = filter { it.alignsWith(key) }.singleOrNull()

/** True when both keys name the same resource written with or without leading directories. */
private fun String.alignsWith(other: String): Boolean = when {
    this == other -> true
    length < other.length -> other.endsWith("/$this")
    other.length < length -> endsWith("/$other")
    else -> false
}

/** Whitespace-normalized title, or null when the publication gives none. */
private fun cleanChapterTitle(title: String?): String? =
    title?.replace(chapterWhitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

/** Resource identity: no fragment, no query, no directory-only prefixes. */
private fun String.resourceKey(): String {
    var value = substringBefore('#').substringBefore('?')
    while (true) {
        value = when {
            value.startsWith("./") -> value.removePrefix("./")
            value.startsWith("/") -> value.removePrefix("/")
            else -> break
        }
    }
    return value
}

/** True when a reported "title" is only the resource's file name or its stem. */
private fun String.namesResource(key: String): Boolean {
    val file = key.substringAfterLast('/')
    if (file.isEmpty()) return false
    val stem = file.substringBeforeLast('.', file)
    return equals(file, ignoreCase = true) || equals(stem, ignoreCase = true)
}
