// The imported book must be named after the article, not after the social profile that surrounds
// it. These fixtures keep the real shape that produced the wrong EPUB title: an X-style article
// page whose og:title/twitter:title/document.title all name the author, and whose own <article>
// carries the headline. A JSON-LD BlogPosting fixture covers the structured-metadata route, and a
// profile page covers the negative case where the social title is the only honest name.
//
// Run: npm ci --prefix tools/tests/webimport && npm test --prefix tools/tests/webimport
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildEpub,
  builderScripts,
  fixtureHtml,
  launchBrowser,
  withFixture
} from './harness.mjs';

const structuredMarker = 'ARTICLE_TITLE_STRUCTURED_END';
const headingMarker = 'ARTICLE_TITLE_HEADING_END';
const profileMarker = 'ARTICLE_TITLE_PROFILE_END';

test('structured BlogPosting headline names the imported book', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_title_structured_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, structuredMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.equal(built.dcTitle, 'A Reading Workflow That Survives Busy Weeks');
    assert.deepEqual(built.dcCreators, ['Synthetic Author']);
    assert.equal(built.chapter.title, 'A Reading Workflow That Survives Busy Weeks');
    assert.equal(built.chapter.h1, 'A Reading Workflow That Survives Busy Weeks');
  } finally {
    await browser.close();
  }
});

test('article-owned heading beats the profile og:title', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_title_heading_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, headingMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.equal(built.dcTitle, 'Notes From a Year of Reading Slowly');
    assert.equal(built.chapter.title, 'Notes From a Year of Reading Slowly');
    assert.equal(built.chapter.h1, 'Notes From a Year of Reading Slowly');
    assert.notEqual(built.dcTitle, 'Synthetic Author (@synthetic) on X');
  } finally {
    await browser.close();
  }
});

test('a page without an article headline keeps its social title', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_title_profile_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, profileMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.equal(built.dcTitle, 'Ada Reader (@ada) on Example');
    assert.notEqual(built.dcTitle, 'Example Social');
  } finally {
    await browser.close();
  }
});
