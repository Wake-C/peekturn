// Synthetic content in the public template shapes seen on Substack (including custom domains),
// WordPress and Ghost. Exercise the real capture, sanitizer and EPUB builder, not selector mocks.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildEpub, builderScripts, extractCapturedText, launchBrowser, withFixture } from './harness.mjs';

const marker = 'BLOG_BODY_END';
const body = `
  <p>BLOG_BODY_START A reader should be able to stay with a complete article, from the first idea
  to its conclusion. Looking up a word can help, but returning to the sentence matters just as
  much. This synthetic paragraph supplies ordinary prose for the real extraction pipeline.</p>
  <h2>A useful reading habit</h2>
  <p>Pick a subject you care about and read a little each day. Pay attention to the examples,
  compare them with what you know, and keep the parts that change your understanding. A short
  list, a table and a code sample belong to the article just as much as its longer paragraphs.</p>
  <ul><li>Keep your place.</li><li>Read the example.</li></ul>
  <table><tr><th>Format</th><th>Use</th></tr><tr><td>Article</td><td>Context</td></tr></table>
  <pre><code>const example = 42;</code></pre>
  <figure><img alt="Reading diagram" src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWZkAAAAASUVORK5CYII="><figcaption>Diagram caption.</figcaption></figure>
  <p><a href="/about">About the author</a></p>
  <p hidden>HIDDEN_BODY_TEXT</p>
  <p>${marker} This final paragraph must survive all the way into the stored EPUB. Navigation,
  subscription forms, recommendations and reader comments are outside the article body.</p>`;

function fixture({ root, articleClass = '', widget = '', gate = '', url = 'https://blog.example/p/reading', outside = '' }) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
    <base href="${url}"><link rel="canonical" href="${url}">
    <title>A complete reading habit</title><meta name="author" content="Test Writer">
    <meta property="og:type" content="article">
    <style>body { max-width: 700px; margin: auto; } [hidden] { display: none; }</style>
    </head><body><nav>NAVIGATION_OUTSIDE_BODY</nav><main>
    <article class="${articleClass}"><h1>A complete reading habit</h1>
    <div ${root}>${body}${widget}</div>${gate}
    <section id="comments"><p>COMMENTS_OUTSIDE_BODY ${'A reader response with unrelated details. '.repeat(40)}</p></section>
    </article>${outside}</main></body></html>`;
}

const templates = [
  ['Substack', { root: 'class="body markup"', articleClass: 'newsletter-post', url: 'https://writer.substack.com/p/reading',
    widget: '<div class="subscription-widget-wrap"><p>SIGNUP_WIDGET Subscribe for new posts.</p></div>' +
      '<div class="image-link-expand"><button>IMAGE_CONTROL</button><svg width="24" height="24"><path d="M0 0L24 24"/></svg></div>' }],
  ['Substack custom domain', { root: 'class="body markup"', articleClass: 'newsletter-post', url: 'https://newsletter.example/p/reading' }],
  ['WordPress classic', { root: 'class="entry-content"',
    widget: '<div class="sharedaddy">SIGNUP_WIDGET Share this</div><div id="jp-relatedposts">RELATED_POSTS</div>' }],
  ['WordPress blocks', { root: 'class="wp-block-post-content"' }],
  ['Ghost', { root: 'class="gh-content"', widget: '<div class="kg-signup-card">SIGNUP_WIDGET Join us</div>' +
    '<div class="kg-video-card"><div class="kg-video-overlay"><svg width="24" height="24"><path d="M0 0L24 24"/></svg></div>' +
    '<div class="kg-video-player-container">VIDEO_CONTROL 00:00 1x</div></div>' }],
  ['schema article body', { root: 'itemprop="articleBody"' }],
  ['static blog', { root: 'class="post-content"' }]
];

test('blog templates preserve the body, media and metadata without changing the live page', { timeout: 120000 }, async t => {
  const browser = await launchBrowser();
  try {
    for (const [name, shape] of templates) {
      await t.test(name, async () => {
        await withFixture(browser, fixture(shape), builderScripts, async tab => {
          const before = await tab.evaluate(() => document.body.innerHTML);
          const built = await buildEpub(tab, marker);
          assert.equal(built.error, undefined);
          assert.equal(built.hasMarker, true);
          assert.equal(built.dcTitle, 'A complete reading habit');
          assert.deepEqual(built.dcCreators, ['Test Writer']);
          assert.equal(built.dcSource, shape.url || 'https://blog.example/p/reading');
          for (const value of ['BLOG_BODY_START', marker, 'A useful reading habit', '<ul', '<table', '<pre', '<img', 'Diagram caption.']) {
            assert.ok(built.chapter.content.includes(value), `${name}: missing ${value}`);
          }
          assert.match(built.chapter.content, /href="https:\/\/[^/]+\/about"/);
          assert.doesNotMatch(built.chapter.content, /NAVIGATION_OUTSIDE_BODY|COMMENTS_OUTSIDE_BODY|SIGNUP_WIDGET|RELATED_POSTS|HIDDEN_BODY_TEXT|IMAGE_CONTROL|VIDEO_CONTROL/);
          assert.equal((built.chapter.content.match(/<img\b/g) || []).length, 1);
          assert.equal(await tab.evaluate(() => document.body.innerHTML), before);
        }, shape.url || 'https://blog.example/p/reading');
      });
    }
  } finally { await browser.close(); }
});

test('a visible Substack or Ghost paywall refuses even a long preview before building an EPUB', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    for (const shape of [
      { root: 'class="body markup"', articleClass: 'newsletter-post', gate: '<div class="paywall">This post is for paid subscribers</div>' },
      { root: 'class="gh-content"', gate: '<aside class="gh-post-upgrade-cta">This post is for subscribers only</aside>' }
    ]) {
      const built = await withFixture(browser, fixture(shape), builderScripts, tab => buildEpub(tab, marker));
      assert.equal(built.error, 'preview');
      assert.equal(built.bytes, undefined);
    }
  } finally { await browser.close(); }
});

test('an unlocked paid article with a hidden gate still imports', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const html = fixture({ root: 'class="body markup"', articleClass: 'newsletter-post',
      gate: '<div class="paywall" hidden>This post is for paid subscribers</div>' });
    const built = await withFixture(browser, html, builderScripts, tab => buildEpub(tab, marker));
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
  } finally { await browser.close(); }
});

test('the existing capture review can still refuse an adapted article', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(browser, fixture(templates[0][1]), builderScripts,
      tab => buildEpub(tab, marker, 'no-article'));
    assert.equal(built.error, 'no-article');
  } finally { await browser.close(); }
});

test('multiple blog bodies keep the existing extractor instead of selecting an arbitrary post', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const html = fixture({ root: 'class="entry-content"',
      outside: `<article><div class="entry-content">${body}</div></article>` });
    const originalScripts = builderScripts.filter(name => name !== 'peekturn-article-adapters.js');
    const original = await withFixture(browser, html, originalScripts, tab => extractCapturedText(tab, marker));
    const adapted = await withFixture(browser, html, builderScripts, tab => extractCapturedText(tab, marker));
    assert.equal(adapted.text, original.text);
  } finally { await browser.close(); }
});

test('unknown editorial layouts such as Anthropic and OpenAI keep the same capture and EPUB', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    // No platform-specific body marker: both corporate sites use their own component layouts.
    for (const url of ['https://www.anthropic.com/research/example', 'https://openai.com/index/example/']) {
      const html = fixture({ root: 'class="editorial-prose"', url });
      const originalScripts = builderScripts.filter(name => name !== 'peekturn-article-adapters.js');
      const original = await withFixture(browser, html, originalScripts, tab => extractCapturedText(tab, marker));
      await withFixture(browser, html, builderScripts, async tab => {
        const adapted = await extractCapturedText(tab, marker);
        assert.equal(adapted.text, original.text);
        const built = await buildEpub(tab, marker);
        assert.equal(built.error, undefined);
        assert.equal(built.hasMarker, true);
      });
    }
  } finally { await browser.close(); }
});
