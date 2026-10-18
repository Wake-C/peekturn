// Bridge between the vendored converter and the app. It only sequences the two calls the converter
// exposes (extract, build) and hands the archive to Kotlin in chunks; no site rules live here.
(function () {
    // utils.js defines a downloadBlob of its own, so the capture has to be installed last: this
    // script is the final one the host injects, and the converter resolves the name at call time.
    window.downloadBlob = function (blob, name) {
        if (window.__marginEpub) window.__marginEpub({ blob: blob, name: String(name || 'article.epub') });
    };

    var TIMEOUT_EXTRACT = 90000;
    var TIMEOUT_BUILD = 120000;
    var TRANSFER_SLICE = 262144;
    var running = false;

    function call(name, value) {
        try { MarginBridge[name](value); } catch (e) { /* the activity may already be closing */ }
    }

    function status(code) { call('status', code); }

    function withTimeout(promise, millis, message) {
        return new Promise(function (resolve, reject) {
            var timer = setTimeout(function () { reject(new Error(message)); }, millis);
            promise.then(function (value) { clearTimeout(timer); resolve(value); },
                         function (error) { clearTimeout(timer); reject(error); });
        });
    }

    // The content script registers one message listener; the first response is the page record.
    function extractPage() {
        return new Promise(function (resolve, reject) {
            var listeners = window.__marginListeners || [];
            if (listeners.length === 0) { reject(new Error('extractor not loaded')); return; }
            var settled = false;
            function respond(page) {
                if (settled) return;
                settled = true;
                if (page) resolve(page); else reject(new Error('no readable article on this page'));
            }
            for (var i = 0; i < listeners.length; i++) {
                try { listeners[i]({ type: 'extract-page', includeStyle: false, readerMode: true }, null, respond); } catch (e) { }
            }
            setTimeout(function () { respond(null); }, TIMEOUT_EXTRACT);
        });
    }

    // saveEbook.js builds asynchronously and reports through downloadBlob, which the shim owns.
    // document.title carries the site name; og:title is the article's own headline.
    function articleTitle(page) {
        var meta = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
        var content = meta && (meta.getAttribute('content') || '').trim();
        return content || page.title || document.title;
    }

    function buildEpub(page) {
        return new Promise(function (resolve, reject) {
            if (typeof buildEbook !== 'function') { reject(new Error('builder not loaded')); return; }
            if (!page.url) page.url = 'page.xhtml';
            page.title = articleTitle(page);
            window.__marginEpub = resolve;
            try {
                buildEbook([page], { title: page.title }, null);
            } catch (e) {
                window.__marginEpub = null;
                reject(e);
                return;
            }
            setTimeout(function () { reject(new Error('building the EPUB timed out')); }, TIMEOUT_BUILD);
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
                MarginBridge.pushChunk(index, base64.slice(offset, offset + TRANSFER_SLICE), window.__marginToken || '');
            }
            MarginBridge.finish(name, window.__marginToken || '');
        });
    }

    window.MarginImport = {
        start: function () {
            if (running) return;
            running = true;
            status('extracting');
            withTimeout(extractPage(), TIMEOUT_EXTRACT + 5000, 'extraction timed out')
                .then(function (page) {
                    status('building');
                    return withTimeout(buildEpub(page), TIMEOUT_BUILD + 5000, 'building timed out');
                })
                .then(function (result) {
                    status('sending');
                    return pushArchive(result.blob, result.name);
                })
                .catch(function (error) {
                    call('fail', String(error && error.message ? error.message : error));
                })
                .then(function () { running = false; });
        }
    };
})();
