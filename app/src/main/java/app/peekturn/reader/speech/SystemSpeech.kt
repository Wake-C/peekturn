package app.peekturn.reader.speech

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import android.speech.tts.TextToSpeech
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Online speech requests still accept the ISO-3 country names some providers report. */
internal fun normalizeCountry(country: String): String = when (country.uppercase()) {
    "USA" -> "US"
    "GBR", "UK" -> "GB"
    else -> country.uppercase()
}

/** A cached connection belongs to one Android default and one foreground configuration snapshot. */
internal data class SpeechConfigurationKey(
    val enginePackage: String?,
    val revision: Long
)

private val speechRevision = AtomicLong()

/** Returning from system/provider settings refreshes even an unchanged package's default voice. */
internal fun refreshSpeechConfiguration() { speechRevision.incrementAndGet() }

internal fun systemSpeechConfigurationKey(context: Context): SpeechConfigurationKey =
    SpeechConfigurationKey(defaultSpeechEngine(context), speechRevision.get())

/** Internal navigation keeps the cache identity; returning from an external surface refreshes it. */
internal class SystemSpeechLifecycle : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private var foregroundRefreshPending = true
    private var lastResumed: WeakReference<Activity>? = null

    override fun onActivityResumed(activity: Activity) {
        if (foregroundRefreshPending || lastResumed?.get() === activity) refreshSpeechConfiguration()
        foregroundRefreshPending = false
        lastResumed = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) { startedActivities++ }
    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities == 0 && !activity.isChangingConfigurations) foregroundRefreshPending = true
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (lastResumed?.get() === activity) lastResumed = null
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}

/** Optional evidence only: Android has no public SDK getter for the bound engine. */
internal fun boundSpeechEngine(speech: TextToSpeech): String? = runCatching {
    TextToSpeech::class.java.getMethod("getCurrentEngine").invoke(speech) as? String
}.getOrNull()?.takeIf { it.isNotBlank() }

/** This preference applies to online pronunciation; system speech keeps the engine's own voice. */
internal fun speechLocale(accent: String): Locale? = when (accent) {
    "US" -> Locale.US
    "UK" -> Locale.UK
    else -> null
}
