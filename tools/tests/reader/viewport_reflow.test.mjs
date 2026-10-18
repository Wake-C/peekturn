import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { launchBrowser } from '../webimport/harness.mjs';

const path = process.env.REFLOW_SOURCE || new URL('../../../app/src/main/java/app/margin/reader/reader/ReflowAnchor.kt', import.meta.url);
const source = readFileSync(path, 'utf8');
const probe = source.match(/internal val visibleReflowAnchorScript = """([\s\S]*?)"""\.trimIndent\(\)/)[1];

test('a visible illustration is anchored before its visible caption or following paragraph', async () => {
  const browser = await launchBrowser();
  try {
    const tab = await browser.newPage({ viewport: { width: 412, height: 800 } });
    await tab.setContent('<style>body{margin:20px}img{display:block;width:300px;height:400px}</style><figure><img id="illustration" alt="A hill" src="data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22300%22 height=%22400%22/%3E"><figcaption>A visible caption.</figcaption></figure><p>A following paragraph is also visible.</p>');
    const anchor = await tab.evaluate(probe);
    const target = await tab.locator(anchor.cssSelector).evaluate(e => e.id);
    assert.equal(target, 'illustration');
  } finally {
    await browser.close();
  }
});

test('a text anchor inside a long paginated paragraph identifies visible words, not its beginning', async () => {
  const browser = await launchBrowser();
  try {
    const tab = await browser.newPage({ viewport: { width: 412, height: 800 } });
    const text = Array.from({ length: 2000 }, (_, i) => `WORD${i}`).join(' ');
    await tab.setContent(`<style>html{height:800px;column-width:372px;column-gap:40px;column-fill:auto}body{margin:20px;font:20px/1.5 sans-serif}</style><p id="long">${text}</p>`);
    await tab.evaluate(() => scrollTo(412 * 8, 0));
    const anchor = await tab.evaluate(probe);
    assert.equal(await tab.locator(anchor.cssSelector).getAttribute('id'), 'long');
    assert.ok(!anchor.text.highlight.startsWith('WORD0 '));
    const visible = await tab.evaluate(a => {
      const node = document.querySelector(a.cssSelector).firstChild;
      const offset = node.textContent.indexOf(a.text.before + a.text.highlight) + a.text.before.length;
      const range = document.createRange();
      range.setStart(node, offset);
      range.setEnd(node, offset + 1);
      return [...range.getClientRects()].some(r => r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight);
    }, anchor);
    assert.equal(visible, true);
  } finally {
    await browser.close();
  }
});
