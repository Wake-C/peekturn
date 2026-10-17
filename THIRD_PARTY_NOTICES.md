# Third-party notices

## ECDICT offline dictionary

Margin includes a transformed SQLite edition of [ECDICT](https://github.com/skywind3000/ECDICT), independently downloaded from its upstream repository, not copied from another application.

- Upstream revision: `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`.
- Source: <https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv>.
- Upstream license: <https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE> (MIT).
- Source CSV SHA-256: `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`.
- Bundled dictionary: 770,482 exact normalized entries from 770,611 upstream rows. English definitions, Chinese translations, phonetic and part-of-speech fields are preserved when available. Not every upstream entry contains every field.
- Transformation: trim/collapse whitespace and lowercase lookup keys; keep the first normalized duplicate; exclude empty/non-English-letter keys and entries with neither definition nor translation; decode upstream literal newline separators. No stemming, inferred meanings, fuzzy matches or network lookup.
- Rebuild with `python3 tools/build_dictionary.py`; optionally provide `--source /path/to/ecdict.csv` for an already downloaded identical source. The builder checks the recorded source hash and SQLite integrity, then writes the gzip asset, retained upstream license and provenance manifest under `app/src/main/assets/dict/`.
- `provenance.json` records source, row count, schema version, sizes and SHA-256 hashes. Android extracts the bundled gzip on an IO dispatcher to private no-backup storage, verifies the extracted hash before installation, and performs read-only exact SQLite queries offline.

### ECDICT MIT license (retained verbatim)

MIT License

Copyright (c) 2025 Linwei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Readium Kotlin Toolkit

EPUB parsing, rendering, navigation, selection and location support use the unmodified Readium Kotlin libraries distributed through Maven Central. Readium is copyright (c) 2017 Readium and licensed under BSD-3-Clause.

The complete upstream license is bundled in the APK at `assets/LICENSE-Readium.txt` and is available in this repository at [app/src/main/assets/LICENSE-Readium.txt](app/src/main/assets/LICENSE-Readium.txt).

## Android and supporting libraries

AndroidX/Jetpack Compose, Kotlin/coroutines, OkHttp, Gson and the Gradle wrapper are independently obtained dependencies. Their exact versions belong to Gradle manifests. The Apache-2.0 license text is bundled at `assets/LICENSE-Apache-2.0.txt`. Jsoup uses the MIT license; its code is supplied transitively or through the build dependency, not copied from another app.

The new Margin implementation is licensed under Apache-2.0; dependency and dictionary licenses remain their own.

## Online word audio

Word-audio actions request pronunciation audio for one word from the public [Youdao dictionary audio endpoint](https://dict.youdao.com/dictvoice) (`audio=<word>` with `type=1` for UK and `type=2` for US). Youdao does not publish this as a licensed API, and Margin holds no key, license or permission for it; it is called as a public endpoint, so availability, coverage and audio quality are not guaranteed and can change without notice. The bytes are provider-supplied rather than a verified human recording or a freely licensed audio asset, and Margin claims no license over them: the audio is a service response, neither licensed to Margin nor redistributed, while Margin's own code remains Apache-2.0 as stated above.

Downloaded audio is decoded from a bounded in-memory response for playback only. It is not persisted, not cached as a local library and not bundled in the APK, so no provider audio is distributed under Margin's code license. A request carries only the requested word — never a sentence, chapter title, book text or credential — and a redirect is refused rather than followed, so that word is never resent to a different host. Because the service states no creator and no license, the app credits it by name and address only ("Youdao Dictionary", <https://dict.youdao.com>) and claims nothing more; the endpoint is unofficial and unendorsed, and its availability may change at any time. A word for which this endpoint has no audio is spoken by the [sentence speech service](#online-sentence-speech) instead, and that service is credited in its place.

Earlier builds queried the [Wikimedia Commons Action API](https://commons.wikimedia.org/w/api.php) for accredited recordings with per-file creator, source and license credits; that history remains in the [verification record](agent-doc/reference/verification/VERIFICATION.md) and is not the current source. No Cambridge, Oxford or Eudic content is copied or claimed as licensed.

## Online sentence speech

Pronouncing a phrase, a generated example or a word whose dictionary recording is unavailable uses the public [Google Translate speech endpoint](https://translate.google.com/translate_tts) (`ie=UTF-8`, `client=tw-ob`, `tl=<en-US|en-GB>`, `q=<text>`), requested once per item. Google does not publish this as a licensed API, and Margin holds no key, license or permission for it; it is called as a public endpoint, so availability, coverage and speech quality are not guaranteed and can change or disappear without notice. Margin requests it only for text of at most roughly 190 characters, and a longer passage is never sent online. The audio is machine-synthesized by the service rather than a verified human recording or a freely licensed audio asset, and Margin claims no license over it.

Downloaded audio is decoded from a bounded in-memory response for playback only. It is not persisted, not cached as a local library and not bundled in the APK, so no service audio is distributed under Margin's code license. A request carries only the exact text the reader asked to hear — a phrase, an example or a single word — and never surrounding context, book text, chapter titles or credentials; a redirect is refused rather than followed, so that text is never resent to a different host. Because the service states no creator and no license, the app credits it by name and address only ("Google Translate", <https://translate.google.com>) and implies no endorsement by it.
