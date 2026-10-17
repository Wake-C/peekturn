# Pronunciation integration

Code paths are relative to `app/src/main/java/app/margin/reader/`.

Application-scoped `speech/PronunciationPlayer.kt` owns latest-only recorded-word and system-speech playback, cancellation and audio focus; screen lifecycle events stop playback without disposing the shared player. Word lookup, download and preparation can fall back to installed English speech without changing the selected word. Examples/phrases never enter the recording lookup. Accent is a preference, not a playback gate; wrong-language voices remain unavailable. `ui/RecordingAttribution.kt` presents actual per-recording creator/source/license credits.

`speech/WordAudioSource.kt` owns Commons file discovery, free-license/creator eligibility, attribution-link validation and bounded cancellable metadata/audio reads. The native decoder consumes bounded memory through `RecordingDataSource`, not an uncontrolled network address. Recording callbacks require both request generation and the actual player identity; failure of an obsolete TTS initialization cannot invalidate a word recording. Recording-to-speech fallback retains the same request's granted audio focus.
