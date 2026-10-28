// The imported book must keep the author the page actually stated, and must never invent one
// when the page names only a profile link, a site, or an unrelated account. These fixtures keep
// the real X shape: no json-ld article, no meta author, article:author is a profile URL, and the
// display name exists in the byline links or the social card title. A card-only positive and a
// same-slug/different-origin negative cover the two ways the bridge could over-credit a name.
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

const xMarker = 'ARTICLE_AUTHOR_X_END';
const cardMarker = 'ARTICLE_AUTHOR_CARD_END';
const absentMarker = 'ARTICLE_AUTHOR_ABSENT_END';
const mismatchMarker = 'ARTICLE_AUTHOR_MISMATCH_END';
const crossOriginMarker = 'ARTICLE_AUTHOR_CROSSORIGIN_END';
const headline = 'How I Turn Learning Into a Workflow in the AI Era';

test('a profile byline supplies the author the head only named by URL', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_author_x_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, xMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.deepEqual(built.dcCreators, ['Tw93']);
    assert.equal(built.dcTitle, headline);
    assert.equal(built.chapter.title, headline);
    assert.equal(built.chapter.h1, headline);
  } finally {
    await browser.close();
  }
});

test('the card title supplies the author when only the head states the profile', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_author_card_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, cardMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.deepEqual(built.dcCreators, ['Tw93']);
    assert.equal(built.dcTitle, headline);
    assert.equal(built.chapter.h1, headline);
  } finally {
    await browser.close();
  }
});

test('a page that states no author gets no creator', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_author_absent_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, absentMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.deepEqual(built.dcCreators, []);
    assert.equal(built.dcTitle, 'Notes From a Reading Desk');
    assert.equal(built.chapter.h1, 'Notes From a Reading Desk');
  } finally {
    await browser.close();
  }
});

test('a byline that disagrees with the stated author profile is not credited', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_author_mismatch_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, mismatchMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.deepEqual(built.dcCreators, []);
    assert.equal(built.dcTitle, headline);
    assert.equal(built.chapter.title, headline);
    assert.equal(built.chapter.h1, headline);
  } finally {
    await browser.close();
  }
});

test('a same-slug link on another origin is not the stated author', { timeout: 120000 }, async () => {
  const browser = await launchBrowser();
  try {
    const built = await withFixture(
      browser,
      fixtureHtml('article_author_crossorigin_fixture.html'),
      builderScripts,
      (tab) => buildEpub(tab, crossOriginMarker)
    );
    assert.equal(built.error, undefined);
    assert.equal(built.hasMarker, true);
    assert.deepEqual(built.dcCreators, []);
    assert.equal(built.dcTitle, 'Notes From a Reading Desk');
    assert.equal(built.chapter.h1, 'Notes From a Reading Desk');
  } finally {
    await browser.close();
  }
});
