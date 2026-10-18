package app.margin.reader

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Restores the same locale before any entry point, including a cold external share. */
internal object AppLanguage {
    fun apply(context: Context, choice: String) {
        val tags = choice.takeIf { it == "en" || it == "zh" }.orEmpty()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val requested = LocaleList.forLanguageTags(tags)
            if (manager.applicationLocales != requested) manager.applicationLocales = requested
        } else {
            val requested = LocaleListCompat.forLanguageTags(tags)
            if (AppCompatDelegate.getApplicationLocales() != requested) {
                AppCompatDelegate.setApplicationLocales(requested)
            }
        }
    }
}
