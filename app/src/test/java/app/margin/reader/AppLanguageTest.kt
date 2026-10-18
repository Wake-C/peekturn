package app.margin.reader

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppLanguageTest {
    @Test fun explicitEnglishReplacesChineseAndSystemRemovesOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locales = context.getSystemService(LocaleManager::class.java)
        AppLanguage.apply(context, "zh")
        assertEquals("zh", locales.applicationLocales[0].language)
        AppLanguage.apply(context, "en")
        assertEquals("en", locales.applicationLocales[0].language)
        AppLanguage.apply(context, "system")
        assertEquals(0, locales.applicationLocales.size())
    }

    @Test
    @Config(sdk = [28])
    fun newEntryActivitiesUseTheLatestChoiceBeforeAndroidPerAppLocales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            AppLanguage.apply(context, "zh")
            Robolectric.buildActivity(LocaleProbeActivity::class.java).setup().use {
                assertEquals("设置", it.get().getString(R.string.settings_title))
            }
            AppLanguage.apply(context, "en")
            Robolectric.buildActivity(LocaleProbeActivity::class.java).setup().use {
                assertEquals("Settings", it.get().getString(R.string.settings_title))
            }
        } finally {
            AppLanguage.apply(context, "system")
        }
    }

    class LocaleProbeActivity : AppCompatActivity()
}
