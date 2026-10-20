package app.peekturn.reader.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant

private const val MAX_EXIT_RECORDS = 5

/**
 * The system's own summaries for this app's recent process exits, on Android 11 and newer. Only
 * fields safe to hand to support are read: timestamp, reason, status and importance. The
 * ApplicationExitInfo description, trace stream and any raw system log are deliberately untouched.
 * A null result means the query itself was unavailable.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal fun recentAppExitLines(context: Context): List<String>? {
    val records = context.getSystemService(ActivityManager::class.java)
        ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        ?: return null
    return records.take(MAX_EXIT_RECORDS).map { record ->
        "  ${Instant.ofEpochMilli(record.timestamp)} reason=${exitReason(record.reason)} " +
            "status=${record.status} importance=${record.importance}"
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun exitReason(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    else -> "reason-$reason"
}
