# Learning integration

Code paths are relative to `app/src/main/java/app/margin/reader/` unless they name a repository-level file.

`learning/ExplanationClient.kt` owns provider requests, structured-output compatibility and clipping Readium's selected-text snippets to the [product context window](SPEC.md#selection-context). Requests to `api.openai.com` use strict JSON schema mode; other hosts, including DeepSeek and custom compatible endpoints, use JSON object mode. Both paths require application validation; host selection does not guarantee model support.

Reader-Activity-owned `learning/LearningSession.kt` owns in-flight work and bounded successful-answer reuse, and closes when that Activity is destroyed. Cache identity includes source selection/context and active profile/configuration. Dictionary presentation does not restart model work. Popup dismissal ends unfinished lookup work; completed answers survive within the same reader session. Explicit Retry bypasses reuse; failures do not become successful cached answers.

Dictionary packaging uses a `.dict` extension to preserve precompressed gzip bytes through Android asset packaging. `tools/build_dictionary.py` owns rebuilding; the [dictionary notices](../../../THIRD_PARTY_NOTICES.md#ecdict-offline-dictionary) own provenance and rebuild instructions.
