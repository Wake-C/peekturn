// Runs the real vendored capture pipeline against a fixture whose article body is wrapped in
// `display: contents`, the structure that made a full X article arrive as its preview card.
//
// The first pass deliberately omits peekturn-import.js: it records the vendored helper's failure.
// The second pass injects the real peekturn-import.js, so the assertion fails if the host-side
// visibility correction is removed or stops running after utils.js. The third pass runs the real
// saveEbook builder and asserts the final paragraph survives into the EPUB chapter.
//
// Run: npm ci --prefix tools/tests/webimport && npm test --prefix tools/tests/webimport
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildEpub,
  extractCapturedText,
  fixtureHtml,
  launchBrowser,
  vendorScripts,
  withFixture
} from './harness.mjs';

const fixture = fixtureHtml('display_contents_fixture.html');
const finalMarker = 'DISPLAY_CONTENTS_FIXTURE_END';

test('display: contents article survives the real vendored capture only with the host fix', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const baseline = await withFixture(browser, fixture, vendorScripts, (tab) => extractCapturedText(tab, finalMarker));
    const fixed = await withFixture(browser, fixture, [...vendorScripts, 'peekturn-import.js'], (tab) => extractCapturedText(tab, finalMarker));

    // The vendored helper measures boxes, so the unpatched run loses the wrapper and its article.
    assert.equal(baseline.error, null);
    assert.equal(baseline.hasMarker, false);
    assert.ok(baseline.text.length < 500);

    // The host correction makes the wrapper visible and the complete article reaches the capture.
    assert.equal(fixed.error, null);
    assert.equal(fixed.hasMarker, true);
    assert.ok(fixed.text.length > 1500);
  } finally {
    await browser.close();
  }
});

test('the full article end paragraph survives the real EPUB build', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixture,
      [...vendorScripts, 'saveEbook.js', 'peekturn-import.js'],
      (tab) => buildEpub(tab, finalMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.ok(built.bytes > 3000);
    assert.ok(built.chapter.length > 1500);
  } finally {
    await browser.close();
  }
});
