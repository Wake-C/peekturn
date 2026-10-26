package app.peekturn.reader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.model.BookReadingTime
import app.peekturn.reader.model.ReadingDay
import app.peekturn.reader.model.ReadingStats
import app.peekturn.reader.model.VideoReadingTime
import app.peekturn.reader.video.VideoItem
import app.peekturn.reader.video.VideoMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PeekturnStoreVideoTest {
    private lateinit var context: Context
    private lateinit var store: PeekturnStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        store = PeekturnStore(context)
    }

    @Test fun videoPlaybackPreferencesSurviveStoreRecreation() = runBlocking {
        store.updateSettings { it.copy(videoQualityHeight = 720, videoAudioOnly = true, videoMergeCaptions = true) }
        val reopened = PeekturnStore(context)
        assertEquals(720, reopened.settings.value.videoQualityHeight)
        assertTrue(reopened.settings.value.videoAudioOnly)
        assertTrue(reopened.settings.value.videoMergeCaptions)
    }

    @Test fun upgradingVersionOneDatabaseKeepsLibraryPositionsReadingTimeAndCredentials() = runBlocking {
        createVersionOneDatabase()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("settings", """{"quickExplain":true,"accent":"UK"}""")
            .putString("profile_key_cipher:default", "AA==")
            .commit()

        val upgraded = PeekturnStore(context)
        upgraded.load()

        val book = upgraded.books.value.single()
        assertEquals("Kept", book.title)
        assertEquals("v1 locator", book.locatorJson)
        assertEquals(0.4, book.progression, 0.0)
        assertEquals(ReadingStats(listOf(ReadingDay("2026-10-14", 120)), listOf(BookReadingTime(1, 120))), upgraded.stats.value)
        assertTrue(upgraded.settings.value.quickExplain)
        assertEquals("UK", upgraded.settings.value.accent)
        // An unreadable stored key is reported, never thrown away: the ciphertext is still there.
        assertEquals("AA==", context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
            .getString("profile_key_cipher:default", null))
        assertNotNull(upgraded.error.value)
        assertNull(upgraded.lastBookId())

        // The additive tables exist and are usable after the upgrade.
        val video = upgraded.upsertVideo(VideoMetadata("u4LUix-BU0s", "Video", "Channel", 679_000, null))
        upgraded.addVideoTime(video.id, "2026-10-14", 30)
        assertEquals(listOf(video.id), upgraded.videos.value.map { it.id })
        assertEquals(listOf(VideoReadingTime(video.id, 30)), upgraded.stats.value.videos)
        assertEquals(listOf(ReadingDay("2026-10-14", 150)), upgraded.stats.value.days)
        // The book that already existed is untouched by video writes.
        assertEquals("v1 locator", upgraded.findBook(1)?.locatorJson)
    }

    @Test fun eachVideoKeepsItsOwnModeAcrossOtherChoicesAndProcessRecreation() = runBlocking {
        val audio = video("audio-book")
        val picture = video("picture-video")
        assertNull(audio.audioOnly)
        store.selectVideoAudioMode(audio.id, true)
        store.selectVideoAudioMode(picture.id, false)
        store.saveVideoPosition(audio.id, 12_345)
        store.selectVideoTrack(audio.id, "en")
        store.addVideoTime(audio.id, "2026-10-25", 23)
        val restored = PeekturnStore(context)
        restored.load()
        assertEquals(true, restored.findVideo(audio.id)!!.audioOnly)
        assertEquals(false, restored.findVideo(picture.id)!!.audioOnly)
        assertEquals(12_345L, restored.findVideo(audio.id)!!.positionMs)
        assertEquals("en", restored.findVideo(audio.id)!!.selectedTrackId)
        assertEquals(23L, restored.stats.value.videos.single().seconds)
        val refreshed = restored.upsertVideo(VideoMetadata("audio-book", "New title", "Channel", 60_000, null))
        assertEquals(true, refreshed.audioOnly)
    }

    @Test fun versionThreeModeMigrationKeepsPositionsTracksAndLearningTime() = runBlocking {
        createVersionOneDatabase()
        SQLiteDatabase.openDatabase(context.getDatabasePath("peekturn.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY AUTOINCREMENT,video_id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,author TEXT NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,thumbnail_url TEXT,position_ms INTEGER NOT NULL DEFAULT 0,last_watched_at INTEGER NOT NULL DEFAULT 0,selected_track_id TEXT,track_explicit INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE video_daily(video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(video_id,date))")
            db.execSQL("INSERT INTO videos(video_id,title,author,position_ms,selected_track_id,track_explicit) VALUES('kept-video','Kept video','Channel',12345,'en',1)")
            db.execSQL("INSERT INTO video_daily VALUES(1,'2026-10-25',42)")
            db.version = 3
        }
        val upgraded = PeekturnStore(context)
        upgraded.load()
        val kept = upgraded.findVideo(1)!!
        assertNull(kept.audioOnly)
        assertEquals(12_345L, kept.positionMs)
        assertEquals("en", kept.selectedTrackId)
        assertTrue(kept.trackExplicit)
        assertEquals(42L, upgraded.stats.value.videos.single().seconds)
        assertEquals("v1 locator", upgraded.findBook(1)!!.locatorJson)
        assertEquals(120L, upgraded.stats.value.books.single().seconds)
        upgraded.selectVideoAudioMode(1, true)
        assertEquals(true, PeekturnStore(context).findVideo(1)!!.audioOnly)
    }

    @Test fun versionFourAddsReadModeWithoutChangingAudioIntentOrPosition() = runBlocking {
        createVersionOneDatabase()
        SQLiteDatabase.openDatabase(context.getDatabasePath("peekturn.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY AUTOINCREMENT,video_id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,author TEXT NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,thumbnail_url TEXT,position_ms INTEGER NOT NULL DEFAULT 0,last_watched_at INTEGER NOT NULL DEFAULT 0,selected_track_id TEXT,track_explicit INTEGER NOT NULL DEFAULT 0,audio_only INTEGER)")
            db.execSQL("CREATE TABLE video_daily(video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(video_id,date))")
            db.execSQL("INSERT INTO videos(video_id,title,author,position_ms,selected_track_id,track_explicit,audio_only) VALUES('kept','Kept','Channel',12345,'en',1,1)")
            db.execSQL("INSERT INTO video_daily VALUES(1,'2026-10-25',42)")
            db.version = 4
        }
        val upgraded = PeekturnStore(context)
        upgraded.load()
        val kept = upgraded.findVideo(1)!!
        assertFalse(kept.readMode)
        assertEquals(true, kept.audioOnly)
        assertEquals(12_345L, kept.positionMs)
        assertEquals("en", kept.selectedTrackId)
        assertTrue(kept.trackExplicit)
        assertEquals(42L, upgraded.stats.value.videos.single().seconds)
        assertEquals("v1 locator", upgraded.findBook(1)!!.locatorJson)
        upgraded.selectVideoPresentation(1, audioOnly = true, readMode = true)
        assertTrue(PeekturnStore(context).findVideo(1)!!.readMode)
        upgraded.selectVideoAudioMode(1, false)
        assertFalse(PeekturnStore(context).findVideo(1)!!.readMode)
    }

    @Test fun readModeIsPerVideoAndSurvivesMetadataRefresh() = runBlocking {
        val reading = video("reading")
        val watching = video("watching")
        store.selectVideoPresentation(reading.id, audioOnly = true, readMode = true)
        store.saveVideoPosition(reading.id, 19_000)
        val refreshed = store.upsertVideo(VideoMetadata("reading", "Updated title", "Channel", 60_000, null))
        assertTrue(refreshed.readMode)
        assertEquals(true, refreshed.audioOnly)
        assertEquals(19_000L, refreshed.positionMs)
        assertFalse(PeekturnStore(context).findVideo(watching.id)!!.readMode)
    }

    @Test fun upsertingVideoKeepsPositionWatchTimeAndChosenTrack() = runBlocking {
        val first = video("video-one", title = "Original title", durationMs = 60_000)
        store.saveVideoPosition(first.id, 12_500)
        store.markVideoOpened(first.id)
        store.selectVideoTrack(first.id, "en-manual")
        val watchedAt = store.findVideo(first.id)!!.lastWatchedAt
        assertTrue(watchedAt > 0)

        val refreshed = store.upsertVideo(VideoMetadata("video-one", "Renamed", "Other channel", 90_000, "https://i.ytimg.com/thumb.jpg"))

        assertEquals(first.id, refreshed.id)
        assertEquals("Renamed", refreshed.title)
        assertEquals("Other channel", refreshed.author)
        assertEquals(90_000L, refreshed.durationMs)
        assertEquals(12_500L, refreshed.positionMs)
        assertEquals(watchedAt, refreshed.lastWatchedAt)
        assertEquals("en-manual", refreshed.selectedTrackId)
        assertEquals(1, store.videos.value.size)
    }

    @Test fun unknownDurationNeverErasesTheLearnedOneAndPositionStaysInsideIt() = runBlocking {
        val known = video("known-duration", durationMs = 2_000)
        store.upsertVideo(VideoMetadata("known-duration", "Known", "Channel", 0, null))
        assertEquals(2_000L, store.findVideo(known.id)?.durationMs)

        store.saveVideoPosition(known.id, 5_000)
        assertEquals(2_000L, store.findVideo(known.id)?.positionMs)
        store.saveVideoPosition(known.id, -20)
        assertEquals(0L, store.findVideo(known.id)?.positionMs)

        val unknown = video("unknown-duration", durationMs = 0)
        store.saveVideoPosition(unknown.id, 5_000)
        assertEquals(5_000L, store.findVideo(unknown.id)?.positionMs)
        assertEquals(0L, store.findVideo(unknown.id)?.durationMs)
    }

    @Test fun deletingVideoRemovesOnlyItsOwnTimeAndPosition() = runBlocking {
        val removed = video("removed")
        val retained = video("retained")
        store.saveVideoPosition(removed.id, 4_000)
        store.addVideoTime(removed.id, "2026-10-14", 40)
        store.addVideoTime(retained.id, "2026-10-14", 25)

        store.deleteVideo(removed.id)
        // A late tracker flush for a deleted entry must not resurrect its time.
        store.addVideoTime(removed.id, "2026-10-14", 90)

        val restored = PeekturnStore(context)
        restored.load()
        assertNull(restored.findVideo(removed.id))
        assertEquals(listOf(retained.id), restored.videos.value.map { it.id })
        assertEquals(listOf(VideoReadingTime(retained.id, 25)), restored.stats.value.videos)
        assertEquals(listOf(ReadingDay("2026-10-14", 25)), restored.stats.value.days)
        assertTrue(restored.stats.value.books.isEmpty())
    }

    @Test fun bookAndVideoTimeWithTheSameNumericIdNeverMix() = runBlocking {
        val book = store.addBook("Reading", "Author", File.createTempFile("store-video-", ".epub", context.filesDir).absolutePath, null)
        val video = video("same-id-video")
        assertEquals(book.id, video.id)

        store.addReadingTime(book.id, "2026-10-14", 30)
        store.addVideoTime(video.id, "2026-10-14", 45)

        val stats = PeekturnStore(context).also { it.load() }.stats.value
        assertEquals(listOf(BookReadingTime(book.id, 30)), stats.books)
        assertEquals(listOf(VideoReadingTime(video.id, 45)), stats.videos)
        assertEquals(75L, stats.days.single().seconds)
        assertEquals("2026-10-14", stats.days.single().date)
    }

    @Test fun videoRowsAndChoicesSurviveStoreRecreation() = runBlocking {
        val item = video("persisted-video", title = "Persisted", durationMs = 120_000)
        store.saveVideoPosition(item.id, 60_000)
        store.selectVideoTrack(item.id, "en-auto")
        store.updateSettings { it.copy(videoMergeCaptions = true) }

        val restored = PeekturnStore(context)
        restored.load()
        val found = restored.findVideoBySource("persisted-video")
        assertNotNull(found)
        assertEquals(item.id, found!!.id)
        assertEquals("Persisted", found.title)
        assertEquals(60_000L, found.positionMs)
        assertEquals("en-auto", found.selectedTrackId)
        assertEquals(listOf(found), restored.videos.value)
        assertTrue(restored.settings.value.videoMergeCaptions)

        restored.selectVideoTrack(item.id, null)
        assertNull(restored.findVideo(item.id)?.selectedTrackId)
    }


    @Test fun versionTwoTrackIsNotMisreadAsAnExplicitLanguagePreference() = runBlocking {
        createVersionOneDatabase()
        SQLiteDatabase.openDatabase(context.getDatabasePath("peekturn.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY AUTOINCREMENT,video_id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,author TEXT NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,thumbnail_url TEXT,position_ms INTEGER NOT NULL DEFAULT 0,last_watched_at INTEGER NOT NULL DEFAULT 0,selected_track_id TEXT)")
            db.execSQL("CREATE TABLE video_daily(video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(video_id,date))")
            db.execSQL("INSERT INTO videos(video_id,title,author,position_ms,selected_track_id) VALUES('kept-video','Kept video','Channel',12345,'ko')")
            db.execSQL("INSERT INTO video_daily VALUES(1,'2026-10-14',42)")
            db.version = 2
        }
        val upgraded = PeekturnStore(context)
        upgraded.load()
        val old = upgraded.findVideo(1)!!
        assertEquals(12345L, old.positionMs)
        assertEquals("ko", old.selectedTrackId)
        assertFalse(old.trackExplicit)
        assertEquals(42L, upgraded.stats.value.videos.single().seconds)
        upgraded.selectVideoTrack(old.id, "ja")
        assertTrue(PeekturnStore(context).findVideo(old.id)!!.trackExplicit)
        assertEquals("v1 locator", upgraded.findBook(1)?.locatorJson)
    }

    private suspend fun video(videoId: String, title: String = "Video $videoId", durationMs: Long = 60_000): VideoItem =
        store.upsertVideo(VideoMetadata(videoId, title, "Channel", durationMs, null))

    /** The exact shipped version 1 schema, created through the same helper contract the app uses. */
    private fun createVersionOneDatabase() {
        val helper = object : SQLiteOpenHelper(context, "peekturn.db", null, 1) {
            override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,author TEXT NOT NULL,file_path TEXT NOT NULL,cover_path TEXT,locator TEXT,progression REAL NOT NULL DEFAULT 0,last_read_at INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE reading_daily(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book_id,date))")
                db.execSQL("INSERT INTO books(id,title,author,file_path,cover_path,locator,progression,last_read_at) " +
                    "VALUES(1,'Kept','Writer','${File(context.filesDir, "kept.epub").absolutePath}',NULL,'v1 locator',0.4,123)")
                db.execSQL("INSERT INTO reading_daily(book_id,date,seconds) VALUES(1,'2026-10-14',120)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                error("The fixture is only ever opened at version 1")
        }
        helper.writableDatabase
        helper.close()
    }
}
