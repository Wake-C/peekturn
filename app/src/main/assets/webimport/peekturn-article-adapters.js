// Prefer the article body published by common blog templates. Detection uses markup so custom
// domains work too. The vendored converter still owns visibility, sanitization, images and EPUBs;
// unknown or ambiguous layouts keep its Readability path. Only clones are changed.
(function () {
    var templates = [
        {
            body: 'article.newsletter-post .body.markup',
            omit: '.subscription-widget-wrap, .subscribe-widget, .share-post, .post-ufi, .image-link-expand',
            paywall: '.paywall'
        },
        {
            body: 'article .entry-content, article .wp-block-post-content, ' +
                'main .entry-content, main .wp-block-post-content',
            omit: '.sharedaddy, #jp-relatedposts, .wp-block-comments, .wp-block-post-comments'
        },
        {
            body: 'article .gh-content, main .gh-content',
            omit: '.kg-signup-card, .kg-video-overlay, .kg-video-player-container',
            paywall: '.gh-post-upgrade-cta'
        },
        {
            body: '[itemprop~="articleBody"]',
            omit: ''
        },
        {
            // Common static blog themes (including Jekyll and Hugo).
            body: 'article .post-content',
            omit: ''
        }
    ];

    function visible(element) {
        return typeof window.isElementVisible === 'function' && window.isElementVisible(element);
    }

    function selectedArticle() {
        for (var i = 0; i < templates.length; i++) {
            var template = templates[i];
            var roots = Array.prototype.filter.call(document.querySelectorAll(template.body), visible);
            // A list of posts is not a single article. Do not pick one by text length.
            if (roots.length !== 1 || !roots[0].textContent.trim()) continue;
            return { root: roots[0], template: template };
        }
        return null;
    }

    function refusal() {
        var article = selectedArticle();
        if (!article || !article.template.paywall) return '';
        var scope = article.root.closest('article, [role="article"]') || article.root.parentElement;
        var gates = scope.querySelectorAll(article.template.paywall);
        // A long preview can pass the text-length guard. A visible template paywall means the
        // rest is withheld; a hidden gate on an already unlocked article must not block import.
        return Array.prototype.some.call(gates, visible) ? 'preview' : '';
    }

    var nativeExtract = window.extractReadableContent;
    if (typeof nativeExtract === 'function') {
        window.extractReadableContent = function (state) {
            var article = selectedArticle();
            if (!article) return nativeExtract(state);
            var clone = article.root.cloneNode(true);
            // Keep the converter's live-page measurements and media replacements. Passing the
            // body straight to Readability again could discard lists, captions or short sections.
            applyStructuralMarks(clone, state);
            if (article.template.omit) {
                clone.querySelectorAll(article.template.omit).forEach(function (element) {
                    element.remove();
                });
            }
            return clone;
        };
    }

    window.PeekturnArticleAdapters = { refusal: refusal };
})();
