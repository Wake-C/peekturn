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
