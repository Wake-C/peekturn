// Bridge between the vendored converter and the app. It sequences the two calls the converter
// exposes (extract, build), refuses a capture that is not the article, supplies the title and
// author the vendored reader leaves to the page, and hands the archive to Kotlin in chunks;
// no site rules live here.
(function () {
    // An element with `display: contents` has no box of its own, but its children are still
    // rendered. The vendored visibility helper (utils.js) measures boxes, so every such wrapper is
    // marked hidden and the text inside it disappears from the capture. Tailwind's `contents`
    // utility is common on article bodies, which is how a full page turns into a preview card.
    // This override must run after utils.js; peekturn-import.js is the last converter script injected.
    var nativeIsElementVisible =
        typeof window.isElementVisible === 'function' ? window.isElementVisible : null;
    if (nativeIsElementVisible) {
        window.isElementVisible = function (elem) {
            if (nativeIsElementVisible(elem)) return true;
            if (!elem || elem.nodeType !== 1) return false;
            try {
                if (window.getComputedStyle(elem).display !== 'contents') return false;
                var range = document.createRange();
                range.selectNodeContents(elem);
                return range.getClientRects().length > 0;
            } catch (e) {
                return false;
            }
        };
    }

    // utils.js defines a downloadBlob of its own, so the capture has to be installed last: this
    // script is the final one the host injects, and the converter resolves the name at call time.
    window.downloadBlob = function (blob, name) {
        if (window.__peekturnEpub) window.__peekturnEpub({ blob: blob, name: String(name || 'article.epub') });
    };

    var TIMEOUT_EXTRACT = 90000;
    var TIMEOUT_BUILD = 120000;
    var TRANSFER_SLICE = 262144;
    var REVIEW_SAMPLE = 20000;
    var running = false;

    function call(name, value) {
        try { PeekturnBridge[name](value); } catch (e) { /* the activity may already be closing */ }
    }

    function status(code) { call('status', code); }

    function failure(code) {
        var error = new Error(code);
        error.peekturnCode = code;
        return error;
    }

    function withTimeout(promise, millis, message) {
        return new Promise(function (resolve, reject) {
            var timer = setTimeout(function () { reject(failure(message)); }, millis);
            promise.then(function (value) { clearTimeout(timer); resolve(value); },
                         function (error) { clearTimeout(timer); reject(error); });
        });
    }

    // The content script registers one message listener; the first response is the page record.
    function extractPage() {
        return new Promise(function (resolve, reject) {
            var refusal = window.PeekturnArticleAdapters && window.PeekturnArticleAdapters.refusal();
            if (refusal) { reject(failure(refusal)); return; }
            var listeners = window.__peekturnListeners || [];
            if (listeners.length === 0) { reject(failure('no-article')); return; }
            var settled = false;
            function respond(page) {
                if (settled) return;
                settled = true;
                if (page) resolve(page); else reject(failure('no-article'));
            }
            for (var i = 0; i < listeners.length; i++) {
                try { listeners[i]({ type: 'extract-page', includeStyle: false, readerMode: true }, null, respond); } catch (e) { }
            }
            setTimeout(function () { respond(null); }, TIMEOUT_EXTRACT);
        });
    }

    function articleText(page) {
        try {
            var template = document.createElement('template');
            template.innerHTML = page && page.content ? page.content : '';
            var text = ((template.content && template.content.textContent) || template.textContent || '');
            return text.replace(/\s+/g, ' ').trim();
        } catch (e) {
            return '';
        }
    }

    // The app owns the article/preview rule; this side only forwards a bounded sample of the
    // captured text. A full article is far above the sample limit, so truncation cannot hide it.
    function review(page) {
        try {
            return PeekturnBridge.review(articleText(page).slice(0, REVIEW_SAMPLE)) || 'no-article';
        } catch (e) {
            return 'no-article';
        }
    }

    function usableTarget(href) {
        try {
            var url = new URL(href, document.baseURI || location.href);
            if (url.protocol !== 'https:') return null;
            var current = new URL(location.href);
            if (url.origin !== current.origin) return null;
            if (url.href.split('#')[0] === current.href.split('#')[0]) return null;
            return url.href;
        } catch (e) {
            return null;
        }
    }

    // A preview page sometimes links to its own full article. Only an https, same-origin, different
    // address is offered; the activity decides whether to follow it once and never guesses an id.
    function findFullArticle() {
        var canonical = document.querySelector('link[rel="canonical"][href]');
        if (canonical) {
            var fromCanonical = usableTarget(canonical.getAttribute('href'));
            if (fromCanonical) return fromCanonical;
        }
        var links = document.querySelectorAll('a[href]');
        var pattern = /(read|view|open|show)\s+(the\s+)?(full|complete|rest|more)|continue\s+reading|full\s+article|全文|阅读全文|查看全文|展开全文/i;
        for (var i = 0; i < links.length; i++) {
            var label = ((links[i].textContent || '') + ' ' + (links[i].getAttribute('aria-label') || '')).trim();
            if (!pattern.test(label)) continue;
            var target = usableTarget(links[i].getAttribute('href'));
            if (target) return target;
        }
        return null;
    }

    function pageProbe() {
        var text = ((document.body && document.body.innerText) || '').trim();
        return {
            length: text.length,
            sample: text.slice(0, 4000)
        };
    }

    // The headline can come from several layers, and the top social tags are not always article
    // text: on a social site, og:title and document.title can both name the author's profile while
    // the rendered article carries the real headline. Structured Article/BlogPosting metadata wins
    // first, then the heading the article owns, and only then the generic social/document fallback.
    function cleanTitle(value) {
        return String(value === undefined || value === null ? '' : value).replace(/\s+/g, ' ').trim();
    }

    var ARTICLE_TYPES = /(?:^|[/:#])(?:Article|BlogPosting|NewsArticle|TechArticle|ScholarlyArticle|Report)$/;

    function isArticleType(type) {
        var types = Object.prototype.toString.call(type) === '[object Array]' ? type : [type];
        for (var i = 0; i < types.length; i++) {
            if (typeof types[i] === 'string' && ARTICLE_TYPES.test(types[i])) return true;
        }
        return false;
    }

    function jsonLdHeadline(node, depth) {
        if (!node || typeof node !== 'object' || depth > 6) return '';
        if (Object.prototype.toString.call(node) === '[object Array]') {
            for (var i = 0; i < node.length; i++) {
                var fromItem = jsonLdHeadline(node[i], depth + 1);
                if (fromItem) return fromItem;
            }
            return '';
        }
        if (isArticleType(node['@type'])) {
            var headline = cleanTitle(node.headline) || cleanTitle(node.name);
            if (headline) return headline;
        }
        var nestedKeys = ['@graph', 'mainEntity', 'mainEntityOfPage', 'itemListElement', 'item', 'hasPart'];
        for (var k = 0; k < nestedKeys.length; k++) {
            var nested = jsonLdHeadline(node[nestedKeys[k]], depth + 1);
            if (nested) return nested;
        }
        return '';
    }

    function structuredHeadline() {
        var scripts = document.querySelectorAll('script[type="application/ld+json"]');
        for (var i = 0; i < scripts.length; i++) {
            var raw = scripts[i].textContent || '';
            if (!raw.trim()) continue;
            try {
                var headline = jsonLdHeadline(JSON.parse(raw), 0);
                if (headline) return headline;
            } catch (e) { /* malformed structured data is not a title */ }
        }
        return '';
    }

    function microdataHeadline() {
        var item = document.querySelector('[itemtype*="Article" i], [itemtype*="BlogPosting" i]');
        var headline = item && item.querySelector('[itemprop="headline"]');
        return cleanTitle(headline && (headline.getAttribute('content') || headline.textContent));
    }

    // The heading inside the article element belongs to the article being read; a heading in the
    // page header or a sidebar does not. No text-length ranking: the document hierarchy decides.
    function articleHeading() {
        var heading = document.querySelector('article h1, [role="article"] h1');
        return cleanTitle(heading && heading.textContent);
    }

    // Readability can leave the article's own heading in the captured markup.
    function capturedHeading(page) {
        try {
            var template = document.createElement('template');
            template.innerHTML = page && page.content ? page.content : '';
            var heading = template.content.querySelector('h1');
            return cleanTitle(heading && heading.textContent);
        } catch (e) {
            return '';
        }
    }

    function socialTitle() {
        var meta = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
        return cleanTitle(meta && meta.getAttribute('content'));
    }

    // saveEbook.js builds asynchronously and reports through downloadBlob, which the shim owns.
    function articleTitle(page) {
        return structuredHeadline() ||
            microdataHeadline() ||
            articleHeading() ||
            capturedHeading(page) ||
            socialTitle() ||
            cleanTitle(page && page.title) ||
            cleanTitle(document.title);
    }

    // The vendored reader answers authors from <head> alone: json-ld, meta author,
    // citation_author and a name-valued article:author. A social article page can name its
    // author as a profile link instead, keeping the display name in the visible byline or
    // in its card title ("Display Name (@handle) on Site"). Neither is read there, so the
    // book reached the library with no creator even though the page shows one.
    //
    // A display name is accepted only when the profile it belongs to is the same one the
    // page named as its author: a byline link must resolve to exactly that profile URL
    // (origin and path, not just a matching final segment), and the card fallback needs the
    // same profile handle in article:author, twitter:creator and the card title. A person
    // mentioned in the body, a rehosted profile link, a publisher, or the site's own title
    // is never credited.
    var MAX_AUTHOR_NAME = 200;

    function cleanAuthorName(value) {
        var name = cleanTitle(value);
        if (!name || name.length > MAX_AUTHOR_NAME || name.charAt(0) === '@') return '';
        if (/^(https?|ftp|mailto|urn|tel):/i.test(name) || /^\/\//.test(name) || /^www\./i.test(name)) {
            return '';
        }
        if (/^(follow|following|subscribe|view profile|profile|more)$/i.test(name)) return '';
        return name;
    }

    function authorHandle(value) {
        var text = cleanTitle(value);
        if (!text) return '';
        var direct = text.replace(/^@/, '');
        if (/^(?=.*[A-Za-z_])[A-Za-z0-9_]{1,30}$/.test(direct)) return direct.toLowerCase();
        try {
            var url = new URL(text, document.baseURI || location.href);
            if (url.protocol !== 'https:' && url.protocol !== 'http:') return '';
            var segments = url.pathname.split('/').filter(function (part) { return part !== ''; });
            var last = segments.length > 0 ? segments[segments.length - 1] : '';
            return /^(?=.*[A-Za-z_])[A-Za-z0-9_]{1,30}$/.test(last) ? last.toLowerCase() : '';
        } catch (e) {
            return '';
        }
    }

    // The profile a link points at, not the name in its last segment: two hosts can serve a
    // page with the same slug, and only one of them is the profile the article named.
    function profileIdentity(value) {
        var text = cleanTitle(value);
        if (!text) return '';
        try {
            var url = new URL(text, document.baseURI || location.href);
            if (url.protocol !== 'https:' && url.protocol !== 'http:') return '';
            var path = url.pathname.replace(/\/+$/, '');
            if (!path) return '';
            return url.origin + path;
        } catch (e) {
            return '';
        }
    }

    function statedAuthorEvidence() {
        var profiles = [];
        var creators = [];
        var profileStated = false;
        function addProfile(value, allowRelative) {
            var text = cleanTitle(value);
            if (!text) return;
            if (!allowRelative && !/^(https?:)?\/\//i.test(text) && text.charAt(0) !== '/') return;
            profileStated = true;
            var identity = profileIdentity(text);
            if (identity && profiles.indexOf(identity) < 0) profiles.push(identity);
        }
        function addCreator(value) {
            var handle = authorHandle(value);
            if (handle && creators.indexOf(handle) < 0) creators.push(handle);
        }
        var nodes = document.querySelectorAll(
            'meta[property="article:author" i], meta[name="article:author" i]');
        for (var i = 0; i < nodes.length; i++) {
            addProfile(nodes[i].getAttribute('content'), false);
        }
        var links = document.querySelectorAll('link[rel~="author"][href]');
        for (var j = 0; j < links.length; j++) addProfile(links[j].getAttribute('href'), true);
        // twitter:creator is a handle by definition. It is read beside the profile URL
        // because the card fallback needs both to identify the same author.
        var creatorNodes = document.querySelectorAll(
            'meta[name="twitter:creator" i], meta[property="twitter:creator" i]');
        for (var k = 0; k < creatorNodes.length; k++) addCreator(creatorNodes[k].getAttribute('content'));
        return { profiles: profiles, creators: creators, profileStated: profileStated };
    }

    function bylineAuthor(evidence) {
        var profiles = evidence.profiles;
        // A handle is only comparable to a link when no profile URL was stated: otherwise
        // the page gave a full identity, and the same slug on another host is not it.
        var handles = evidence.profileStated ? [] : evidence.creators;
        if (profiles.length === 0 && handles.length === 0) return '';
        var root = document.querySelector('article, [role="article"], main') || document.body;
        if (!root) return '';
        var links = root.querySelectorAll('a[href]');
        // Keys come from page-provided profile identities, so they must not inherit
        // Object.prototype names such as "constructor".
        var names = Object.create(null);
        var labelled = Object.create(null);
        for (var i = 0; i < links.length; i++) {
            var href = links[i].getAttribute('href');
            var identity = profileIdentity(href);
            var linkHandle = authorHandle(href);
            // An explicit profile URL is compared in full. The handle-only route exists
            // for pages whose sole author statement is a card creator handle.
            var key = '';
            if (profiles.length > 0) {
                if (identity && profiles.indexOf(identity) > -1) key = identity;
            } else if (linkHandle && handles.indexOf(linkHandle) > -1) {
                key = linkHandle;
            }
            if (!key) continue;
            var text = cleanTitle(links[i].textContent);
            if (/^@[A-Za-z0-9_]+$/.test(text)) {
                var labelHandle = authorHandle(text);
                var profileHandle = profiles.length > 0 ? authorHandle(identity) : key;
                if (labelHandle && labelHandle === profileHandle) labelled[key] = true;
                continue;
            }
            var name = cleanAuthorName(text);
            if (name && !names[key]) names[key] = name;
        }
        var order = profiles.length > 0 ? profiles : handles;
        for (var j = 0; j < order.length; j++) {
            if (labelled[order[j]] && names[order[j]]) return names[order[j]];
        }
        return '';
    }

    // The card title is the weak source, so it is accepted only in the shape the real page
    // emits: Display Name (@handle) on Site, with the same handle stated in article:author
    // and twitter:creator. Without that agreement it could name a site or an unrelated
    // profile that merely appears in the title.
    function cardAuthor(evidence) {
        if (evidence.profiles.length === 0 || evidence.creators.length === 0) return '';
        var profileHandles = evidence.profiles.map(authorHandle);
        var metas = document.querySelectorAll('meta[property="og:title" i], meta[name="twitter:title" i]');
        for (var i = 0; i < metas.length; i++) {
            var match = /^(.+?)\s+\(@([A-Za-z0-9_]{1,30})\)\s+on\s+.+$/i.exec(
                cleanTitle(metas[i].getAttribute('content')));
            if (!match) continue;
            var handle = match[2].toLowerCase();
            if (evidence.creators.indexOf(handle) < 0) continue;
            if (profileHandles.indexOf(handle) < 0) continue;
            var name = cleanAuthorName(match[1]);
            if (name) return name;
        }
        return '';
    }

    function capturedAuthors(page) {
        var stated = page && page.metadata && Array.isArray(page.metadata.authors) ?
            page.metadata.authors : [];
        var authors = [];
        for (var i = 0; i < stated.length; i++) {
            var name = typeof stated[i] === 'string' ? stated[i].trim() : '';
            if (name && authors.indexOf(name) < 0) authors.push(name);
        }
        if (authors.length > 0) return authors;
        var evidence = statedAuthorEvidence();
        var fromByline = bylineAuthor(evidence);
        if (fromByline) return [fromByline];
        var fromCard = cardAuthor(evidence);
        return fromCard ? [fromCard] : [];
    }

    function buildEpub(page) {
        return new Promise(function (resolve, reject) {
            if (typeof buildEbook !== 'function') { reject(failure('failed')); return; }
            if (!page.url) page.url = 'page.xhtml';
            page.title = articleTitle(page);
            if (!page.metadata || typeof page.metadata !== 'object') page.metadata = {};
            page.metadata.authors = capturedAuthors(page);
            var timer = setTimeout(function () { reject(failure('failed')); }, TIMEOUT_BUILD);
            window.__peekturnEpub = function (result) {
                clearTimeout(timer);
                resolve(result);
            };
            try {
                buildEbook([page], { title: page.title }, null);
            } catch (e) {
                window.__peekturnEpub = null;
                clearTimeout(timer);
                reject(e);
            }
        });
    }

    function pushArchive(blob, name) {
        return blob.arrayBuffer().then(function (buffer) {
            var bytes = new Uint8Array(buffer);
            var parts = [];
            for (var i = 0; i < bytes.length; i += 0x8000) {
                parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)));
            }
            var base64 = btoa(parts.join(''));
            for (var offset = 0, index = 0; offset < base64.length; offset += TRANSFER_SLICE, index++) {
                PeekturnBridge.pushChunk(index, base64.slice(offset, offset + TRANSFER_SLICE), window.__peekturnToken || '');
            }
            PeekturnBridge.finish(name, window.__peekturnToken || '');
        });
    }

    window.PeekturnImport = {
        probe: pageProbe,
        findFullArticle: findFullArticle,
        start: function () {
            if (running) return;
            running = true;
            status('extracting');
            withTimeout(extractPage(), TIMEOUT_EXTRACT + 5000, 'extraction timed out')
                .then(function (page) {
                    var verdict = review(page);
                    if (verdict !== 'import') {
                        call('fullArticle', findFullArticle() || '');
                        call('fail', verdict);
                        return null;
                    }
                    status('building');
                    return withTimeout(buildEpub(page), TIMEOUT_BUILD + 5000, 'building timed out');
                })
                .then(function (result) {
                    if (!result) return;
                    status('sending');
                    return pushArchive(result.blob, result.name);
                })
                .catch(function (error) {
                    call('fail', (error && error.peekturnCode) || 'failed');
                })
                .then(function () { running = false; });
        }
    };
})();
