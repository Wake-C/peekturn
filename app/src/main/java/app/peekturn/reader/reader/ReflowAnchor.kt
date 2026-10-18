package app.peekturn.reader.reader

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
    val selector = visible?.optString("cssSelector")?.takeIf { it.isNotBlank() }
    val quote = visible?.optJSONObject("text")
    if (visible?.optString("kind") == "element") {
        // Readium's own firstVisibleElementLocator uses an empty highlight for image elements.
        // R2EpubPageFragment.loadLocator enters scrollToLocator for a non-null highlight, and
        // rangeFromLocator falls back to this selector because the quote is empty.
        if (selector == null) return current
        val locations = current.locations.otherLocations.toMutableMap().apply {
            remove("domRange")
            put("cssSelector", selector)
        }
        return current.copy(
            locations = current.locations.copy(otherLocations = locations),
            text = Locator.Text(highlight = "")
        )
    }
    if (quote == null) return current
    val highlight = quote.optString("highlight").takeIf { it.isNotBlank() } ?: return current
    if (selector == null) return current
    val locations = current.locations.otherLocations.toMutableMap().apply {
        put("cssSelector", selector)
        remove("domRange")
        visible?.optJSONObject("domRange")?.let { put("domRange", it) }
    }
    return current.copy(
        locations = current.locations.copy(otherLocations = locations),
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
    // The first non-whitespace character of the visible column, not merely the first text node
    // that happens to have a visible rect.
    function visibleText() {
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
            return {node: node, start: start};
        }
        return null;
    }
    // An image/figure at the top of the page outranks a caption or paragraph lower in the same
    // column. Comparing document order preserves the illustration instead of anchoring its text.
    function visibleMedia(selectorList) {
        var candidates = document.body.querySelectorAll(selectorList);
        for (var i = 0; i < candidates.length; i++) {
            var element = candidates[i];
            if (getComputedStyle(element).visibility === 'hidden') continue;
            var rect = element.getBoundingClientRect();
            if (!visible(rect)) continue;
            // A media element beginning in an earlier column cannot restore this page: snapping
            // to its own start would move the reader backward.
            if (rect.left < -1 || rect.left >= innerWidth) continue;
            return element;
        }
        return null;
    }
    // Prefer the rendered media itself over a figure wrapper, so the anchor points at the image
    // rather than at a block that also contains its caption.
    var media = visibleMedia('img, svg, video, canvas, object') || visibleMedia('figure, picture');
    function precedes(element, node) {
        var position = element.compareDocumentPosition(node);
        return (position & Node.DOCUMENT_POSITION_FOLLOWING) !== 0 ||
            (position & Node.DOCUMENT_POSITION_CONTAINED_BY) !== 0;
    }
    function childIndex(parent, node) {
        for (var i = 0; i < parent.childNodes.length; i++) {
            if (parent.childNodes[i] === node) return i;
        }
        return -1;
    }
    var text = visibleText();
    if (media && (!text || precedes(media, text.node))) {
        return {resourceHref: location.href, kind: 'element', cssSelector: selector(media)};
    }
    if (text) {
        var node = text.node, start = text.start;
        var parent = node.parentElement;
        var textNodeIndex = parent ? childIndex(parent, node) : -1;
        if (textNodeIndex >= 0) {
            var end = Math.min(node.length, start + 48);
            return {resourceHref: location.href, kind: 'text', cssSelector: selector(parent),
                // Official Readium domRange extension: a repeated quote needs the exact child
                // text node and UTF-16 offset, not just text that can tie elsewhere.
                domRange: {start: {
                    cssSelector: selector(parent),
                    textNodeIndex: textNodeIndex,
                    charOffset: start
                }},
                text: {
                    before: node.data.substring(Math.max(0, start - 80), start),
                    highlight: node.data.substring(start, end),
                    after: node.data.substring(end, Math.min(node.length, end + 80))
                }};
        }
    }
    return {resourceHref: location.href};
})();
""".trimIndent()

/** The first visible character's offset inside the locator's own block, for narration start. */
internal val narrationVisibleOffsetScript = """
(function(){try{var l=readium.findFirstVisibleLocator();if(!l||!l.locations||!l.locations.cssSelector)return {offset:-1};var e=document.querySelector(l.locations.cssSelector);if(!e)return {offset:-1};var w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),before=0,n;while(n=w.nextNode()){var d=n.data||'',rg=document.createRange();rg.selectNodeContents(n);var rs=rg.getClientRects();for(var i=0;i<rs.length;i++){var r=rs[i];if(r.bottom<=0)continue;if(r.top>=window.innerHeight)break;var x=Math.min(Math.max(r.left+1,0),Math.max(window.innerWidth-1,0)),y=Math.min(Math.max(Math.max(r.top,0)+1,0),Math.max(window.innerHeight-1,0)),c=null;if(document.caretRangeFromPoint)c=document.caretRangeFromPoint(x,y);else if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);if(p)c={startContainer:p.offsetNode,startOffset:p.offset};}if(c&&c.startContainer&&c.startContainer.nodeType===3&&e.contains(c.startContainer))return {offset:before+c.startOffset};return {offset:before};}before+=d.length;}return {offset:0};}catch(e){return {offset:-1};}})()
""".trimIndent()
