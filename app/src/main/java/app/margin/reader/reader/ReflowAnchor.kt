package app.margin.reader.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url

/** Null means a chapter transition is in flight; retry on the next native locator emission. */
internal fun reflowLocator(current: Locator, visible: JSONObject?): Locator? {
    val documentHref = visible?.optString("resourceHref")?.takeIf { it.isNotBlank() }
    if (documentHref != null) {
        val document = Url(documentHref)?.removeQuery()?.removeFragment() ?: return null
        val expected = requireNotNull(AbsoluteUrl("https://readium/publication/"))
            .resolve(current.href).removeQuery().removeFragment()
        if (!expected.isEquivalent(document)) return null
    } else if (visible?.optJSONObject("text")?.optString("highlight")?.isNotBlank() == true) {
        return null
    }
    val quote = visible?.optJSONObject("text") ?: return current
    val highlight = quote.optString("highlight").takeIf { it.isNotBlank() } ?: return current
    val selector = visible.optString("cssSelector").takeIf { it.isNotBlank() } ?: return current
    return current.copy(
        locations = current.locations.copy(otherLocations = current.locations.otherLocations + ("cssSelector" to selector)),
        text = Locator.Text(before = quote.optString("before"), highlight = highlight, after = quote.optString("after"))
    )
}

/** Read-only viewport probe. It never changes DOM, selection or scrolling and never interpolates
 * book text into executable code. Readium restores the resulting bounded text quote normally.
 * A block locator is insufficient: one paragraph can span several paginated screens.
 */
internal val visibleReflowAnchorScript = """
(function() {
    function visible(rect) {
        return rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.left < innerWidth && rect.bottom > 0 && rect.top < innerHeight;
    }
    function hasVisibleRect(range) {
        var rects = range.getClientRects();
        for (var i = 0; i < rects.length; i++) if (visible(rects[i])) return true;
        return false;
    }
    function selector(element) {
        var parts = [];
        while (element && element !== document.documentElement) {
            var index = 1, sibling = element.previousElementSibling;
            while (sibling) {
                if (sibling.localName === element.localName) index++;
                sibling = sibling.previousElementSibling;
            }
            parts.unshift(element.localName + ':nth-of-type(' + index + ')');
            element = element.parentElement;
        }
        return 'html > ' + parts.join(' > ');
    }
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), node;
    var range = document.createRange();
    while ((node = walker.nextNode())) {
        if (!node.data.trim() || getComputedStyle(node.parentElement).visibility === 'hidden') continue;
        range.selectNodeContents(node);
        if (!hasVisibleRect(range)) continue;
        // The prefix predicate is monotonic even across lines and CSS columns.
        // Binary search avoids scanning every character in a chapter-long text node.
        var low = 1, high = node.length;
        while (low < high) {
            var middle = Math.floor((low + high) / 2);
            range.setStart(node, 0);
            range.setEnd(node, middle);
            if (hasVisibleRect(range)) high = middle;
            else low = middle + 1;
        }
        var start = low - 1;
        while (start < node.length && /\s/.test(node.data[start])) start++;
        if (start === node.length) continue;
        var end = Math.min(node.length, start + 48);
        return {resourceHref: location.href, cssSelector: selector(node.parentElement), text: {
            before: node.data.substring(Math.max(0, start - 80), start),
            highlight: node.data.substring(start, end),
            after: node.data.substring(end, Math.min(node.length, end + 80))
        }};
    }
    return {resourceHref: location.href};
})();
""".trimIndent()
