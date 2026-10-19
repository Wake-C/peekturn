// Runs before the vendored converter: the extension scripts expect a chrome extension host.
// This shim provides only what they call, so the same, unmodified pipeline can run inside the app.
(function () {
    var listeners = [];
    window.__peekturnListeners = listeners;
    window.chrome = {
        runtime: {
            onMessage: { addListener: function (fn) { listeners.push(fn); } },
            sendMessage: function (message, callback) {
                // The converter heartbeats while a build runs; forwarding it proves the build is alive.
                try { if (message && message.type === 'job-heartbeat') PeekturnBridge.status('heartbeat'); } catch (e) { }
                if (typeof callback === 'function') callback({});
            },
            lastError: null,
            getURL: function (path) { return path; }
        },
        i18n: { getMessage: function (key) { return key; } },
        storage: {
            local: {
                get: function (_keys, callback) { if (typeof callback === 'function') callback({}); },
                set: function (_values, callback) { if (typeof callback === 'function') callback(); }
            }
        }
    };
    // The converter's utils.js declares its own downloadBlob; the bridge re-installs its capture
    // after every converter script has loaded, so the finished archive reaches Kotlin, not a download.
    window.__peekturnEpub = null;
})();
