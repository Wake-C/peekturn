// Shared browser harness for the webimport tests. Each test loads a synthetic fixture into a real
// Chromium page, injects the vendored converter scripts in the order the app injects them, and can
// either read the capture or run MarginImport.start() through the real EPUB builder.
import { createRequire } from 'node:module';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const here = dirname(fileURLToPath(import.meta.url));

export const assets = join(here, '..', '..', '..', 'app', 'src', 'main', 'assets', 'webimport');

export const vendorScripts = [
  'margin-shim.js',
  'jszip.js',
  'readability.js',
  'utils.js',
  'cssSanitizer.js',
  'sanitizeHtml.js',
  'extractHtml.js'
];

export function fixtureHtml(name) {
  return readFileSync(join(here, name), 'utf8');
}

function executableCandidates() {
  const candidates = [process.env.CHROMIUM_PATH, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'];
  const cache = join(homedir(), 'Library', 'Caches', 'ms-playwright');
  if (existsSync(cache)) {
    for (const entry of readdirSync(cache)) {
      if (!entry.startsWith('chromium_headless_shell-')) continue;
      candidates.push(join(cache, entry, 'chrome-headless-shell-mac-arm64', 'chrome-headless-shell'));
    }
  }
  return candidates.filter(candidate => candidate && existsSync(candidate));
}

export async function launchBrowser() {
  for (const executablePath of executableCandidates()) {
    try {
      return await chromium.launch({ headless: true, executablePath });
    } catch (error) {
      // Try the next installed browser; the last failure is reported by the caller.
    }
  }
  return chromium.launch({ headless: true });
}

export async function withFixture(browser, html, scripts, action) {
  const tab = await browser.newPage();
  try {
    await tab.setContent(html, { waitUntil: 'load' });
    for (const name of scripts) {
      const source = readFileSync(join(assets, name), 'utf8');
      await tab.evaluate(`${source}\n;true`);
    }
    return await action(tab);
  } finally {
    await tab.close();
  }
}

export async function extractCapturedText(tab, marker) {
  return await tab.evaluate((markerText) => new Promise((resolve) => {
    const listeners = window.__marginListeners || [];
    if (listeners.length === 0) {
      resolve({ text: '', error: 'extractor did not register' });
      return;
    }
    let settled = false;
    function finish(page) {
      if (settled) return;
      settled = true;
      if (!page) {
        resolve({ text: '', error: 'extractor returned no page' });
        return;
      }
      const container = document.createElement('div');
      container.innerHTML = page.content || '';
      const text = (container.textContent || '').trim();
      resolve({ text, error: null, hasMarker: text.includes(markerText) });
    }
    try {
      listeners[0]({ type: 'extract-page', includeStyle: false, readerMode: true }, null, finish);
    } catch (error) {
      finish(null);
    }
    setTimeout(() => finish(null), 30000);
  }), marker);
}

export async function buildEpub(tab, marker) {
  return await tab.evaluate(async (markerText) => {
    const chunks = [];
    window.__marginToken = 'test';
    window.MarginBridge = {
      status: function () { },
      fullArticle: function () { },
      review: function () { return 'import'; },
      pushChunk: function (index, chunk) { chunks[index] = chunk; },
      finish: function (name) { window.__marginFinish({ name: name }); },
      fail: function (code) { window.__marginFinish({ error: code }); }
    };
    const result = await new Promise((resolve) => {
      let done = false;
      window.__marginFinish = function (value) {
        if (done) return;
        done = true;
        resolve(value);
      };
      try {
        window.MarginImport.start();
      } catch (error) {
        resolve({ error: String(error) });
      }
      setTimeout(() => { if (!done) { done = true; resolve({ error: 'build timed out' }); } }, 60000);
    });
    if (result.error) return result;
    const raw = atob(chunks.join(''));
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    const zip = await JSZip.loadAsync(bytes.buffer);
    const decode = (text) => {
      if (text === null) return null;
      const decoder = document.createElement('textarea');
      decoder.innerHTML = text;
      return decoder.value;
    };
    const opfName = Object.keys(zip.files).find((name) => /\.opf$/i.test(name));
    const opf = opfName ? await zip.file(opfName).async('string') : '';
    const dcTitle = (opf.match(/<dc:title[^>]*>([\s\S]*?)<\/dc:title>/i) || [null, null])[1];
    const dcCreators = [...opf.matchAll(/<dc:creator[^>]*>([\s\S]*?)<\/dc:creator>/gi)]
      .map((match) => match[1]);
    const dcSource = (opf.match(/<dc:source>([\s\S]*?)<\/dc:source>/i) || [null, null])[1];
    const chapters = Object.keys(zip.files)
      .filter((name) => /\.xhtml$/i.test(name) && !/\btoc\.xhtml$/i.test(name));
    let chapter = null;
    for (const name of chapters) {
      const text = await zip.file(name).async('string');
      if (!text.includes(markerText)) continue;
      const chapterTitle = (text.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [null, null])[1];
      const chapterH1 = (text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [null, null])[1];
      chapter = {
        name: name,
        length: text.length,
        title: decode(chapterTitle),
        h1: decode(chapterH1)
      };
      break;
    }
    return {
      bytes: bytes.length,
      opf: opfName,
      dcTitle: decode(dcTitle),
      dcCreators: dcCreators.map(decode),
      dcSource: decode(dcSource),
      chapters: chapters,
      chapter: chapter,
      hasMarker: chapter !== null
    };
  }, marker);
}
