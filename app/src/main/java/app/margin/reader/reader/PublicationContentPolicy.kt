package app.margin.reader.reader

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource

/** Wrap only parsed HTML reads. The original container/archive and non-HTML resources stay intact. */
internal fun securePublicationContainer(
    original: Container<Resource>,
    htmlResources: Map<Url, MediaType>
): Container<Resource> = TransformingContainer(original) { url, resource ->
    val type = htmlResources[url.removeQuery().removeFragment()]
    if (type == null) resource else TransformingResource(resource) { bytes ->
        val charset = type.charset ?: Charsets.UTF_8
        Try.success(securePublicationHtml(bytes.toString(charset)).toByteArray(charset))
    }
}

// Readium 3.1 injects these external scripts AFTER our resource transform. Neither publisher
// inline code nor publisher-local scripts receive script permission, including Android bridge access.
private const val PUBLICATION_CSP = "default-src 'none'; " +
    "script-src https://readium/assets/readium/scripts/readium-reflowable.js https://readium/assets/readium/scripts/readium-fixed.js; " +
    "style-src 'unsafe-inline' https://readium/publication/ https://readium/assets/readium/; " +
    "img-src https://readium/publication/ data:; " +
    "font-src https://readium/publication/ https://readium/assets/readium/fonts/ https://readium/assets/fonts/ data:; " +
    "media-src https://readium/publication/; " +
    "connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
    "object-src 'none'; base-uri 'none'; form-action 'none'; manifest-src 'none'"

private val activeElements = setOf(
    "script", "base", "iframe", "frame", "frameset", "object", "embed", "applet",
    "animate", "animatemotion", "animatetransform", "set"
)
private val requestHints = setOf("preload", "modulepreload", "prefetch", "preconnect", "dns-prefetch", "prerender")
private val urlAttributes = setOf("href", "src", "action", "formaction", "data", "poster", "background")

internal fun securePublicationHtml(source: String): String {
    val document = Jsoup.parse(source)
    document.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml)
    for (element in document.getAllElements().toList()) {
        val tag = element.normalName().substringAfterLast(':')
        if (tag in activeElements) {
            element.remove()
            continue
        }
        if (tag == "meta" && element.hasAttr("http-equiv") &&
            !element.attr("http-equiv").equals("content-type", ignoreCase = true)) {
            element.remove()
            continue
        }
        if (tag == "link" && element.attr("rel").lowercase(Locale.ROOT).split(Regex("\\s+")).any { it in requestHints }) {
            element.remove()
            continue
        }
        for (attribute in element.attributes().asList()) {
            val name = attribute.key.lowercase(Locale.ROOT).substringAfterLast(':')
            if (name.startsWith("on") || name == "ping" || name == "srcdoc" ||
                (name in urlAttributes && isActiveUrl(attribute.value)) ||
                ((name == "id" || name == "name") && !isSafeHtmlFragment(attribute.value)) ||
                (name == "href" && Url(attribute.value)?.let(::isSafeNavigationUrl) == false)) {
                element.removeAttr(attribute.key)
            }
        }
    }
    // Precede every publisher resource. Readium's later injected trusted CSS precedes this meta,
    // while its trusted external script is appended before </head>, after the policy.
    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", PUBLICATION_CSP)
    document.head().prependElement("meta").attr("http-equiv", "x-dns-prefetch-control").attr("content", "off")
    return document.outerHtml()
}

private fun isActiveUrl(value: String): Boolean {
    val normalized = value.filterNot { it <= ' ' || it == '\u007f' }.lowercase(Locale.ROOT)
    return normalized.startsWith("javascript:") || normalized.startsWith("vbscript:") ||
        normalized.startsWith("file:") || normalized.startsWith("content:") ||
        normalized.startsWith("intent:") || normalized.startsWith("data:text/html") ||
        normalized.startsWith("data:application/xhtml+xml")
}
