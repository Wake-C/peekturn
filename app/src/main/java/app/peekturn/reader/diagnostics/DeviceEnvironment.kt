package app.peekturn.reader.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import app.peekturn.reader.BuildConfig

private const val MAX_LISTED_ENGINES = 24

/**
 * A safe export header: app version, device manufacturer/model, Android release/SDK/build id and
 * the speech engines' package names and versions. It deliberately contains no device identifier,
 * no provider endpoint, profile name or credential, and never initializes an engine just to read
 * this snapshot.
 */
internal fun developerLogSnapshot(context: Context): String {
    val manager = context.packageManager
    val defaultEngine = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull()
    val engines = installedEnginePackages(manager)
    return buildString {
        appendLine(
            "App: ${diagnosticMetadata(BuildConfig.APPLICATION_ID)} " +
                "${diagnosticMetadata(BuildConfig.VERSION_NAME)} (${BuildConfig.VERSION_CODE})"
        )
        appendLine("Device: ${diagnosticMetadata(Build.MANUFACTURER.orEmpty())} ${diagnosticMetadata(Build.MODEL.orEmpty())}")
        appendLine(
            "Android: ${diagnosticMetadata(Build.VERSION.RELEASE.orEmpty())} " +
                "(SDK ${Build.VERSION.SDK_INT}, build ${diagnosticMetadata(Build.ID.orEmpty())})"
        )
        append("Default TTS engine: ")
        append(diagnosticMetadata(defaultEngine ?: "none"))
        if (defaultEngine != null) append(" ${packageVersion(manager, defaultEngine)}")
        appendLine()
        append("Installed TTS engines: ")
        if (engines.isEmpty()) {
            append("none")
        } else {
            append(
                engines.take(MAX_LISTED_ENGINES)
                    .joinToString(", ") { "${diagnosticMetadata(it)} ${packageVersion(manager, it)}" }
            )
            if (engines.size > MAX_LISTED_ENGINES) {
                append(" (+${engines.size - MAX_LISTED_ENGINES} more)")
            }
        }
        appendLine()
        appendRecentExits(context)
    }
}

private fun installedEnginePackages(manager: PackageManager): List<String> = try {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).addCategory(Intent.CATEGORY_DEFAULT)
    manager.queryIntentServices(intent, 0)
        .mapNotNull { it.serviceInfo?.packageName }
        .distinct()
        .sorted()
} catch (_: Exception) {
    emptyList()
}

/**
 * Android's own recent-exit summaries complement a Java uncaught exception. Only the reason, status,
 * timestamp and importance are copied; the system description, traces and package paths stay out.
 * There is no ApplicationExitInfo before Android 11, so older devices say so instead of guessing.
 */
private fun StringBuilder.appendRecentExits(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val lines = runCatching { recentAppExitLines(context) }.getOrNull()
        when {
            lines == null -> appendLine("Recent app exits: unavailable")
            lines.isEmpty() -> appendLine("Recent app exits: none recorded")
            else -> {
                appendLine("Recent app exits:")
                for (line in lines) appendLine(line)
            }
        }
    } else {
        appendLine("Recent app exits: unsupported before Android 11")
    }
}

private fun packageVersion(manager: PackageManager, packageName: String): String = try {
    diagnosticMetadata(manager.getPackageInfo(packageName, 0).versionName ?: "unknown")
} catch (_: Exception) {
    "unknown"
}
