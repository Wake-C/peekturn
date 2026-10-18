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

Downloaded audio is decoded from a bounded in-memory response for playback only. It is not persisted, not cached as a local library and not bundled in the APK, so no provider audio is distributed under Margin's code license. A request carries only the requested word — never a sentence, chapter title, book text or credential — and a redirect is refused rather than followed, so that word is never resent to a different host. Because the service states no creator and no license, the app credits it by name and address only ("Youdao Dictionary", <https://dict.youdao.com>) and claims nothing more; the endpoint is unofficial and unendorsed, and its availability may change at any time. A word for which this endpoint has no audio is voiced by the [Microsoft read-aloud service](#online-sentence-speech) and, when that also answers nothing, by [Baidu Translate](#online-sentence-speech), and whichever of the two supplied the audio is credited in its place.

Earlier builds queried the [Wikimedia Commons Action API](https://commons.wikimedia.org/w/api.php) for accredited recordings with per-file creator, source and license credits; that history remains in the [verification record](agent-doc/reference/verification/VERIFICATION.md) and is not the current source. No Cambridge, Oxford or Eudic content is copied or claimed as licensed.

## Online sentence speech

Pronouncing a phrase, a generated example or a word whose dictionary recording is unavailable uses a public, key-free speech service: primarily [Microsoft's read-aloud service](https://speech.platform.bing.com), the same one Microsoft Edge uses for its read-aloud voices, and — when that returns no usable audio — [Baidu Translate's speech endpoint](https://fanyi.baidu.com/gettts) (`lan=en`, `spd=3`, `source=web`, `text=<text>`) as a fallback. Neither service publishes a licensed API, and Margin holds no key, license or permission for either; both are called as public endpoints, so availability, coverage and speech quality are not guaranteed and can change or disappear without notice. The Microsoft service offers US and UK English voices, so the reader's accent preference is requested there; the Baidu service offers one English voice, so a Baidu answer cannot honour that preference and is a substitution — which the app states as a notice rather than pretending the accent was met. Margin requests speech only for text of at most roughly 190 characters, and a longer passage is never sent online. The audio is machine-synthesized by whichever service answers rather than a verified human recording or a freely licensed audio asset, and Margin claims no license over it.

Downloaded audio is decoded from a bounded in-memory response for playback only. It is not persisted, not cached as a local library and not bundled in the APK, so no service audio is distributed under Margin's code license. A request carries only the exact text the reader asked to hear — a phrase, an example or a single word — and never surrounding context, book text, chapter titles or credentials; the Baidu request is a plain GET that is refused rather than followed if it redirects, and the Microsoft audio is read over a WebSocket the app opens to that host, so the text is never sent to a host the app did not choose. Because the services state no creator and no license, the app credits whichever one supplied the audio by name and address only ("Microsoft Read Aloud", <https://speech.platform.bing.com> or "Baidu Translate", <https://fanyi.baidu.com>) and implies no endorsement by either.

Earlier builds requested an alternative public speech endpoint, Google Translate's `translate_tts`; it was removed because that host was unreachable from the reader's network — the same network where the Microsoft and Baidu endpoints answered — and no request is made to it now.

## Web page import

Margin converts a web page into an EPUB before importing it, so the reader, dictionary, speech and
statistics keep working on one format. The conversion runs on the device inside the import browser.

- [save-as-ebook](https://github.com/alexadam/save-as-ebook), MIT. Files vendored under
  `app/src/main/assets/webimport/`: `extractHtml.js`, `sanitizeHtml.js`, `cssSanitizer.js`,
  `utils.js`, `saveEbook.js`. Upstream revision `8a8c030db758efdfedb09d675eceab76d7836d86`.
  Margin does not modify these files; `margin-shim.js` supplies the small extension host they expect
  and `margin-import.js` sequences their extraction and build calls.
- [Mozilla Readability](https://github.com/mozilla/readability), Apache-2.0, vendored as
  `readability.js` from the same upstream (its `libs/readability.js`).
- [JSZip](https://stuk.github.io/jszip/), MIT, vendored as `jszip.js` from the same upstream
  (its `libs/jszip.js`).

Each project's own license text follows verbatim below. JSZip is dual licensed; Margin uses it under
the MIT option.

### save-as-ebook MIT license (retained verbatim)

```
The MIT License (MIT)

Copyright (c) 2015 Alexandru Adam

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
```

### JSZip MIT license (retained verbatim)

```
JSZip is dual licensed. At your choice you may use it under the MIT license *or* the GPLv3
license.

The MIT License
===============

Copyright (c) 2009-2016 Stuart Knightley, David Duponchel, Franz Buchinger, António Afonso

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

### Mozilla Readability Apache License 2.0 (retained verbatim)

```
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

