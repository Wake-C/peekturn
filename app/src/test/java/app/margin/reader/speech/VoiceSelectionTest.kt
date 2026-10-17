package app.margin.reader.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoiceSelectionTest {
    private fun voice(
        name: String,
        locale: Locale = Locale.US,
        network: Boolean = false,
        quality: Int = Voice.QUALITY_NORMAL,
        features: Set<String> = emptySet()
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)

    /** A substituted accent may speak, but the state must say so instead of claiming the request. */
    private fun assertSubstitutedEnglish(availability: SpeechAvailability) {
        assertTrue("Usable English must speak instead of failing: $availability", availability is SpeechAvailability.Available)
        assertNotNull("A substituted accent needs a notice", (availability as SpeechAvailability.Available).noticeRes)
    }

    @Test fun offlineEnglishVoiceIsPreferredOverHigherQualityNetworkVoice() {
        val offline = voice("offline", quality = Voice.QUALITY_LOW)
        val network = voice("network", network = true, quality = Voice.QUALITY_VERY_HIGH)
        assertEquals(offline, englishVoices(setOf(network, offline), Locale.US).first())
    }

    @Test fun requestedAccentComesBeforeTheOtherInstalledEnglishCountry() {
        val us = voice("us")
        val uk = voice("uk", locale = Locale.UK)
        assertEquals(listOf(uk, us), englishVoices(setOf(us, uk), Locale.UK))
        assertEquals(listOf(us, uk), englishVoices(setOf(us, uk), Locale.US))
    }

    @Test fun oppositeCountryEnglishStaysUsableAndUnsupportedAccentNamesAreRejected() {
        val us = voice("us")
        assertEquals(listOf(us), englishVoices(setOf(us), Locale.UK))
        assertEquals(Locale.US, speechLocale("US"))
        assertEquals(Locale.UK, speechLocale("UK"))
        assertNull(speechLocale("AU"))
        assertNull(speechLocale(""))
    }

    @Test fun advertisedButUninstalledVoiceIsNotPlayable() {
        val missing = voice("missing", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertTrue(englishVoices(setOf(missing), Locale.US).isEmpty())
    }

    @Test fun nonEnglishVoicesAreNeverCandidates() {
        assertTrue(englishVoices(setOf(voice("chinese", locale = Locale.CHINESE)), Locale.US).isEmpty())
        assertTrue(englishVoices(setOf(voice("french", locale = Locale.FRENCH)), Locale.UK).isEmpty())
        assertTrue(englishVoices(setOf(voice("english-generic", locale = Locale.ENGLISH)), Locale.US).isNotEmpty())
    }

    @Test fun matchingNetworkVoiceIsUsableWhenNoOfflineVoiceIsInstalled() {
        val network = voice("network", network = true)
        assertEquals(listOf(network), englishVoices(setOf(network), Locale.US))
    }

    @Test fun stableNameBreaksEquivalentVoiceTies() {
        val first = voice("a")
        val second = voice("b")
        assertEquals(first, englishVoices(setOf(second, first), Locale.US).first())
        assertEquals(first, englishVoices(setOf(first, second), Locale.US).first())
    }

    @Test fun emptyOemEnumerationUsesLanguageAvailabilityAndReportsGenericEnglishHonestly() {
        val result = configureSpeech(
            Locale.US, { emptySet() }, { error("No voice was enumerated") },
            { locale -> assertEquals(Locale.US, locale); TextToSpeech.LANG_AVAILABLE },
            { voice("system", locale = Locale.ENGLISH) }
        )
        assertSubstitutedEnglish(result)
    }

    @Test fun rejectedExactVoiceFallsBackToCountryAvailability() {
        val exact = voice("installed")
        val result = configureSpeech(
            Locale.US, { setOf(exact) }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE }, { exact }
        )
        assertEquals(SpeechAvailability.Available(), result)
    }

    @Test fun acceptedExactVoiceDoesNotNeedLanguageFallback() {
        val exact = voice("installed")
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { setOf(exact) }, { TextToSpeech.SUCCESS },
            { error("Installed exact voice already selected") }, { exact }
        ))
    }

    /** A UK preference with only US English installed must still speak, honestly labelled. */
    @Test fun ukRequestWithOnlyInstalledUsEnglishStillSpeaksWithNotice() {
        val installedUs = voice("installed-us", locale = Locale.US)
        val result = configureSpeech(
            Locale.UK, { setOf(installedUs) }, { TextToSpeech.SUCCESS },
            { TextToSpeech.LANG_NOT_SUPPORTED }, { installedUs }
        )
        assertSubstitutedEnglish(result)
    }

    @Test fun languageApiOppositeCountryEnglishIsUsedWithNotice() {
        assertSubstitutedEnglish(configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_AVAILABLE }, { voice("opaque", locale = Locale.UK) }
        ))
        assertSubstitutedEnglish(configureSpeech(
            Locale.UK, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE }, { voice("opaque", locale = Locale.US) }
        ))
    }

    @Test fun acceptedVoiceWithAnActualOtherCountryStillSpeaksWithNotice() {
        val exact = voice("requested")
        assertSubstitutedEnglish(configureSpeech(
            Locale.US, { setOf(exact) }, { TextToSpeech.SUCCESS },
            { error("Exact selection succeeded") }, { voice("actual", locale = Locale.UK) }
        ))
    }

    /** A reported voice with no country must stay unconfirmed instead of inheriting the request. */
    @Test fun selectedVoiceWithAGenericActualCountryStillNeedsANotice() {
        val exact = voice("requested", locale = Locale.UK)
        assertSubstitutedEnglish(configureSpeech(
            Locale.UK, { setOf(exact) }, { TextToSpeech.SUCCESS },
            { error("Exact selection succeeded") }, { voice("generic", locale = Locale.ENGLISH) }
        ))
    }

    @Test fun genericCountryLabelsAndScriptsUseLocaleFieldsNotVoiceNames() {
        val british = Locale.Builder().setLanguage("en").setScript("Latn").setRegion("GB").build()
        val exact = voice("not-an-accent-label", locale = british)
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.UK, { setOf(exact) }, { TextToSpeech.SUCCESS },
            { error("Already selected") }, { exact }
        ))
    }

    @Test fun missingDataAndUnsupportedLocaleRemainDistinct() {
        assertEquals(SpeechAvailability.MissingData, configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_MISSING_DATA }, { null }
        ))
        assertEquals(SpeechAvailability.Unsupported, configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_NOT_SUPPORTED }, { null }
        ))
    }

    @Test fun countryVariantAvailabilityWorksWithoutEnumerationOrActualVoice() {
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE }, { null }
        ))
    }

    @Test fun unknownActualAccentWithOnlyLanguageSupportNeedsNotice() {
        assertSubstitutedEnglish(configureSpeech(
            Locale.UK, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_AVAILABLE }, { null }
        ))
    }

    @Test fun voiceDataChangesAreReevaluatedOnRetryInsteadOfCachingAnEmptyResult() {
        var availability = TextToSpeech.LANG_MISSING_DATA
        var installed = emptySet<Voice>()
        val select = {
            configureSpeech(Locale.US, { installed }, { TextToSpeech.SUCCESS }, { availability }, { null })
        }
        assertEquals(SpeechAvailability.MissingData, select())
        availability = TextToSpeech.LANG_COUNTRY_AVAILABLE
        assertEquals(SpeechAvailability.Available(), select())
        installed = setOf(voice("newly-installed"))
        availability = TextToSpeech.LANG_NOT_SUPPORTED
        assertEquals(SpeechAvailability.Available(), select())
    }

    @Test fun unavailableEnumerationStillTriesTheStandardLanguageApi() {
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { throw IllegalStateException("Enumeration unavailable") }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE }, { null }
        ))
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { null }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE }, { null }
        ))
    }

    @Test fun genericActualVoiceOverridesCountryGuaranteeWithHonestNotice() {
        assertSubstitutedEnglish(configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE }, { voice("generic", locale = Locale.ENGLISH) }
        ))
    }

    @Test fun actualMatchingVoiceCanConfirmAccentWithLanguageOnlyAvailability() {
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_AVAILABLE }, { voice("matching") }
        ))
    }

    /** OEM engines report ISO 639-2/T languages and ISO 3166-1 alpha-3 countries; they still name English. */
    @Test fun iso3EngineIdentifiersStillNameEnglishAndTheRequestedCountry() {
        val oem = voice("oem-english", Locale("eng", "GBR"))
        val availability = configureSpeech(
            Locale.UK, { setOf(oem) }, { TextToSpeech.SUCCESS },
            { TextToSpeech.LANG_NOT_SUPPORTED }, { oem }
        )
        assertEquals(SpeechAvailability.Available(), availability)
    }

    @Test fun iso3CountryIdentifiersStillNameTheRequestedAccent() {
        val american = voice("oem-american", Locale("en", "USA"))
        assertEquals(SpeechAvailability.Available(), configureSpeech(
            Locale.US, { setOf(american) }, { TextToSpeech.SUCCESS },
            { error("An ISO3 country must not make the selected voice a substitution") }, { american }
        ))
        assertEquals(listOf(american), englishVoices(setOf(american), Locale.US))
        assertEquals(Locale.US, otherEnglishCountry(Locale("en", "GBR")))
    }

    /** Normalizing identifiers must never turn a non-English voice into an English one. */
    @Test fun iso3IdentifiersOfOtherLanguagesStayUnavailable() {
        assertTrue(englishVoices(setOf(voice("french", locale = Locale("fra", "FRA"))), Locale.US).isEmpty())
        assertEquals(SpeechAvailability.Unsupported, configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_AVAILABLE }, { voice("french", locale = Locale("fra", "FRA")) }
        ))
    }

    @Test fun actualOtherLanguageAndUnavailableDataNeverPlay() {
        assertEquals(SpeechAvailability.Unsupported, configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_AVAILABLE }, { voice("other", locale = Locale.FRENCH) }
        ))
        assertEquals(SpeechAvailability.MissingData, configureSpeech(
            Locale.US, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_COUNTRY_AVAILABLE },
            { voice("download", features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) }
        ))
    }

    /**
     * Discovery follows the platform's TTS service action, not a binding permission: real engines such
     * as eSpeak-NG export the service without one and must still be found, while the engine the user
     * chose stays with the default candidate instead of being tried twice.
     */
    @Test fun declaredTtsServicesAreDiscoveredWithoutABindingPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spare = ComponentName("com.example.spare", "com.example.spare.TtsService")
        val chosen = ComponentName("com.example.chosen", "com.example.chosen.TtsService")
        declareTtsService(context, spare)
        declareTtsService(context, chosen)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, chosen.packageName)

        val engines = installedSpeechEngines(context)

        assertTrue("A declared TTS service needs no binding permission to be discovered",
            engines.contains(spare.packageName))
        assertFalse("The user's default engine stays with the default candidate",
            engines.contains(chosen.packageName))
    }

    /** Declares a TTS service the way a real engine does: the platform action plus the default category. */
    private fun declareTtsService(context: Context, component: ComponentName) {
        val manager = shadowOf(context.packageManager)
        manager.addServiceIfNotPresent(component)
        manager.addIntentFilterForService(component, IntentFilter(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    /**
     * Every answer the engine gives is recorded, in the order it was asked, so a failure can report what
     * the engine said instead of guessing at what it must have meant.
     */
    @Test fun everyEngineAnswerIsRecordedInTheOrderItWasAsked() {
        val answers = mutableListOf<SpeechObservation>()
        val availability = configureSpeech(
            Locale.UK, { emptySet() }, { TextToSpeech.ERROR },
            { TextToSpeech.LANG_NOT_SUPPORTED }, { null }) { answers += it }
        assertEquals(SpeechAvailability.Unsupported, availability)
        assertEquals(
            listOf(
                SpeechObservation.VoicesListed(0),
                SpeechObservation.LanguageProbed(Locale.UK, TextToSpeech.LANG_NOT_SUPPORTED),
                SpeechObservation.LanguageProbed(Locale.US, TextToSpeech.LANG_NOT_SUPPORTED),
                SpeechObservation.LanguageProbed(Locale.ENGLISH, TextToSpeech.LANG_NOT_SUPPORTED)
            ),
            answers
        )
    }

    /** An engine that refuses to enumerate has not said it lists no voices, and the record keeps the two apart. */
    @Test fun anEnumerationThatFailsIsNotRecordedAsAnEmptyList() {
        val answers = mutableListOf<SpeechObservation>()
        configureSpeech(
            Locale.US, { throw IllegalStateException("This engine will not list voices") },
            { TextToSpeech.ERROR }, { TextToSpeech.LANG_NOT_SUPPORTED }, { null }) { answers += it }
        assertEquals(listOf(SpeechObservation.VoicesListed(null)), answers.take(1))
        assertNotEquals(SpeechObservation.VoicesListed(0), answers.first())
    }

    /**
     * The device's own voice is the last resort, so it is offered only when no English route worked, and
     * only for text another voice could read honestly: the app cannot name that voice, and it says so.
     */
    @Test fun theDeviceOwnVoiceIsOfferedOnlyAfterEveryEnglishRouteFails() {
        assertNull("An offered English voice is never bypassed", substitutedDeviceVoiceNotice(SpeechAvailability.Available(), "hello"))
        assertNotNull("A substituted device voice is labelled", substitutedDeviceVoiceNotice(SpeechAvailability.Unsupported, "hello"))
        assertNotNull("A missing-data engine may still hold a working voice", substitutedDeviceVoiceNotice(SpeechAvailability.MissingData, "hello"))
        assertNull("Another script is never handed to a voice the app cannot name",
            substitutedDeviceVoiceNotice(SpeechAvailability.Unsupported, "你好"))
        assertNull(substitutedDeviceVoiceNotice(SpeechAvailability.Unsupported, "Привет"))
        assertNull(substitutedDeviceVoiceNotice(SpeechAvailability.Unsupported, "123"))
    }

    /** English is recognised by its own script, so an accented Latin sentence stays English. */
    @Test fun englishTextIsRecognisedByItsOwnScript() {
        assertTrue(isEnglishText("hello"))
        assertTrue(isEnglishText("A café's menu, 5 items."))
        assertFalse(isEnglishText("你好"))
        assertFalse(isEnglishText("Привет"))
        assertFalse(isEnglishText("1234"))
    }

    /**
     * The three device-speech failures are different device facts and read differently: an engine that
     * never started, one that offered no English voice, and one whose own voice data it says is missing.
     */
    @Test fun eachDeviceSpeechFailureKeepsItsOwnMessage() {
        val neverStarted = deviceSpeechFailure(engineStarted = false, missingEnglishData = false)
        val noEnglish = deviceSpeechFailure(engineStarted = true, missingEnglishData = false)
        val missingData = deviceSpeechFailure(engineStarted = true, missingEnglishData = true)

        assertEquals(DeviceSpeechFailure.NotStarted, neverStarted)
        assertEquals(DeviceSpeechFailure.NoEnglishVoice, noEnglish)
        assertEquals(DeviceSpeechFailure.MissingData, missingData)
        assertEquals(3, setOf(neverStarted.message, noEnglish.message, missingData.message).size)
        assertEquals(3, setOf(neverStarted.verdict, noEnglish.verdict, missingData.verdict).size)
    }

    /** A report names every observed fact, so the reader can send it back without adding anything. */
    @Test fun theReportNamesTheEnginesTheAnswersAndTheResult() {
        val evidence = SpeechEvidence()
        evidence.addEngine("com.example.vendor")
        evidence.observe(SpeechObservation.VoicesListed(4))
        evidence.observe(SpeechObservation.LanguageProbed(Locale.US, TextToSpeech.LANG_MISSING_DATA))
        evidence.observe(SpeechObservation.LanguageProbed(Locale.UK, TextToSpeech.LANG_NOT_SUPPORTED))
        evidence.observe(SpeechObservation.LanguageProbed(Locale.ENGLISH, TextToSpeech.LANG_COUNTRY_AVAILABLE))

        val report = evidence.report(DeviceSpeechFailure.MissingData)

        assertTrue("The engine that was asked belongs in the report", report.contains("com.example.vendor"))
        assertTrue(report.contains(probeName(Locale.US)))
        assertTrue(report.contains(languageAnswer(TextToSpeech.LANG_MISSING_DATA)))
        assertTrue(report.contains(languageAnswer(TextToSpeech.LANG_NOT_SUPPORTED)))
        assertTrue(report.contains(DeviceSpeechFailure.MissingData.verdict))
    }

    /** An engine that would not list voices must not read like one that listed none. */
    @Test fun aRefusedEnumerationDoesNotReadLikeAnEmptyOne() {
        val refused = SpeechEvidence().apply { observe(SpeechObservation.VoicesListed(null)) }
        val empty = SpeechEvidence().apply { observe(SpeechObservation.VoicesListed(0)) }
        assertNotEquals(
            empty.report(DeviceSpeechFailure.NoEnglishVoice),
            refused.report(DeviceSpeechFailure.NoEnglishVoice)
        )
    }

    /** The engine the reader chose speaks first, so its answers are the report's language answers. */
    @Test fun theReportKeepsTheAnswersOfTheEngineThatWasAskedFirst() {
        val first = SpeechEvidence()
        val later = SpeechEvidence()
        for (evidence in listOf(first, later)) {
            evidence.addEngine("com.example.chosen")
            evidence.observe(SpeechObservation.VoicesListed(0))
            for (probe in listOf(Locale.US, Locale.UK, Locale.ENGLISH)) {
                evidence.observe(SpeechObservation.LanguageProbed(probe, TextToSpeech.LANG_NOT_SUPPORTED))
            }
        }
        later.observe(SpeechObservation.LanguageProbed(Locale.FRENCH, TextToSpeech.LANG_MISSING_DATA))

        assertEquals(
            first.report(DeviceSpeechFailure.NoEnglishVoice),
            later.report(DeviceSpeechFailure.NoEnglishVoice)
        )
    }
}
