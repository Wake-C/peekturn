package app.peekturn.reader

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.peekturn.reader.model.SentenceSpeechProvider
import app.peekturn.reader.reader.BuiltinBook
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MainViewModelTest {
    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("peekturn.db")
        graph = AppGraph(context, seedBuiltinBook = false)
    }

    @After fun tearDown() {
        viewModels.clear()
        graph.scope.cancel()
        graph.speech.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun coldLaunchReopensLastSuccessfullyOpenedBookButReturnToLibraryDoesNot() = runBlocking {
        val book = addLastBook()
        graph.store.savePosition(book.id, "saved locator", 0.75)
        val vm = viewModel("first launch")
        initialize(vm, reopen = true)
        assertTrue(vm.state.value.ready)
        assertEquals(book.id, vm.state.value.pendingBookId)
        assertEquals("saved locator", graph.store.findBook(book.id)?.locatorJson)

        vm.consumeOpen(book.id)
        initialize(vm, reopen = true)
        assertNull(vm.state.value.pendingBookId)
        assertEquals(book.id, graph.store.lastBookId())

        val freshLaunch = viewModel("fresh launch")
        initialize(freshLaunch, reopen = true)
        assertEquals(book.id, freshLaunch.state.value.pendingBookId)
    }

    @Test fun recreatedLibraryDoesNotAutomaticallyReopenTheLastBook() = runBlocking {
        val book = addLastBook()
        val vm = viewModel("restored library")
        initialize(vm, reopen = false)
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.pendingBookId)
        assertEquals(book.id, graph.store.lastBookId())
    }

    @Test fun explicitVideoEntryDoesNotReopenTheLastEpub() = runBlocking {
        val book = addLastBook()
        val vm = viewModel("video entry")
        vm.showVideos()
        initialize(vm, reopen = true)
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.pendingBookId)
        assertEquals(book.id, graph.store.lastBookId())
    }

    @Test fun returningFromSharedVideoClearsPendingEpubNavigation() = runBlocking {
        val book = addLastBook()
        val vm = viewModel("pending book")
        initialize(vm, reopen = true)
        assertEquals(book.id, vm.state.value.pendingBookId)
        vm.showVideos()
        assertNull(vm.state.value.pendingBookId)
        assertEquals(book.id, graph.store.lastBookId())
    }

    @Test fun missingLastBookFileFallsBackToLibraryAndClearsTheLaunchLoop() = runBlocking {
        val book = addLastBook()
        assertTrue(File(book.filePath).delete())
        val vm = viewModel("missing file")
        initialize(vm, reopen = true)
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.pendingBookId)
        assertNotNull(vm.state.value.message)
        assertNull(graph.store.lastBookId())
        assertNotNull(graph.store.findBook(book.id))

        val nextLaunch = viewModel("next launch")
        initialize(nextLaunch, reopen = true)
        assertNull(nextLaunch.state.value.pendingBookId)
        assertNull(nextLaunch.state.value.message)
    }

    @Test fun deletingTheLastBookLeavesAnEmptyLibraryOnTheNextLaunch() = runBlocking {
        val book = addLastBook()
        graph.store.deleteBook(book.id)
        val vm = viewModel("empty library")
        initialize(vm, reopen = true)
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.pendingBookId)
        assertNull(vm.state.value.message)
        assertTrue(graph.store.books.value.isEmpty())
    }

    @Test fun includedPublicDomainBookIsImportedOnceAndStaysDeleted() = runBlocking {
        val seeded = AppGraph(context, seedBuiltinBook = true)
        try {
            val first = MainViewModel(seeded)
            initialize(first, reopen = false)
            val included = seeded.store.books.value.single {
                it.title == BuiltinBook.TITLE && it.author == BuiltinBook.AUTHOR
            }
            assertTrue(File(included.filePath).isFile)

            seeded.store.deleteBook(included.id)
            val next = MainViewModel(seeded)
            initialize(next, reopen = false)
            assertTrue(seeded.store.books.value.none {
                it.title == BuiltinBook.TITLE && it.author == BuiltinBook.AUTHOR
            })
        } finally {
            seeded.scope.cancel()
            seeded.speech.shutdown()
        }
    }

    @Test fun savedProviderChangeCancelsSpeechStartedBeforeTheSaveCompleted() = runBlocking {
        graph.store.updateSettings { it.copy(sentenceSpeechProvider = SentenceSpeechProvider.ANDROID) }
        ShadowLooper.idleMainLooper()
        graph.speech.speakExample("The next page is waiting.", "US", SentenceSpeechProvider.ANDROID)
        ShadowLooper.idleMainLooper()
        assertTrue(graph.speech.state.value.playing)

        graph.store.updateSettings { it.copy(accent = "UK") }
        ShadowLooper.idleMainLooper()
        assertTrue("Unrelated saved settings do not cancel the request", graph.speech.state.value.playing)

        graph.store.updateSettings { it.copy(sentenceSpeechProvider = SentenceSpeechProvider.MICROSOFT) }
        ShadowLooper.idleMainLooper()
        assertFalse("The authoritative provider change cancels the old request", graph.speech.state.value.playing)
        assertEquals("The next page is waiting.", graph.speech.state.value.text)
    }

    @Test fun latestVideoWinsOverBookAndResumesOnlyOnce() = runBlocking {
        addLastBook()
        val video = graph.store.upsertVideo(app.peekturn.reader.video.VideoMetadata("resume-video", "Video", "Author", 90_000, null))
        graph.store.saveVideoPosition(video.id, 42_000)
        graph.store.selectVideoPresentation(video.id, audioOnly = true, readMode = true)
        graph.store.markVideoOpened(video.id)
        val vm = viewModel("video resume")
        initialize(vm, reopen = true)
        assertNull(vm.state.value.pendingBookId)
        assertEquals(video.id, vm.state.value.pendingVideoId)
        assertEquals(1, vm.state.value.tab)
        assertEquals(42_000L, graph.store.findVideo(video.id)!!.positionMs)
        assertTrue(graph.store.findVideo(video.id)!!.readMode)
        vm.consumeVideoOpen(video.id)
        initialize(vm, reopen = true)
        assertNull(vm.state.value.pendingVideoId)
    }

    @Test fun bookOpenedAfterVideoWinsEvenWhenBackgroundVideoSavesPosition() = runBlocking {
        val video = graph.store.upsertVideo(app.peekturn.reader.video.VideoMetadata("background-video", "Video", "Author", 90_000, null))
        graph.store.markVideoOpened(video.id)
        val book = addLastBook()
        graph.store.saveVideoPosition(video.id, 28_000)
        graph.store.markVideoOpened(video.id, remember = false)
        val vm = viewModel("book wins")
        initialize(vm, reopen = true)
        assertEquals(book.id, vm.state.value.pendingBookId)
        assertNull(vm.state.value.pendingVideoId)
    }

    @Test fun deletingLastVideoDoesNotFallBackToStaleBook() = runBlocking {
        addLastBook()
        val video = graph.store.upsertVideo(app.peekturn.reader.video.VideoMetadata("deleted-video", "Video", "Author", 90_000, null))
        graph.store.markVideoOpened(video.id)
        graph.store.deleteVideo(video.id)
        val vm = viewModel("deleted video")
        initialize(vm, reopen = true)
        assertNull(vm.state.value.pendingBookId)
        assertNull(vm.state.value.pendingVideoId)
    }

    @Test fun explicitVideoListAndRecreatedHomeDoNotReopenVideo() = runBlocking {
        val video = graph.store.upsertVideo(app.peekturn.reader.video.VideoMetadata("explicit-video", "Video", "Author", 90_000, null))
        graph.store.markVideoOpened(video.id)
        val vm = viewModel("video list")
        vm.showVideos()
        initialize(vm, reopen = true)
        assertNull(vm.state.value.pendingVideoId)
        val restored = viewModel("restored home")
        initialize(restored, reopen = false)
        assertNull(restored.state.value.pendingVideoId)
    }

    @Test fun missingVideoTargetClearsOnceAndDoesNotReviveBook() = runBlocking {
        addLastBook()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .putString("last_content_kind", "video").putLong("last_content_id", 9999).commit()
        val vm = viewModel("missing video")
        initialize(vm, reopen = true)
        assertEquals(1, vm.state.value.tab)
        assertNull(vm.state.value.pendingBookId)
        assertNull(vm.state.value.pendingVideoId)
        assertNotNull(vm.state.value.message)
        val next = viewModel("after missing video")
        initialize(next, reopen = true)
        assertNull(next.state.value.pendingBookId)
        assertNull(next.state.value.pendingVideoId)
        assertNull(next.state.value.message)
    }

    @Test fun startupVideoIntentIsPausedButExplicitVideoIntentKeepsNormalPlayback() {
        assertTrue(app.peekturn.reader.video.VideoActivity.intent(context, 1, resumeOnLaunch = true)
            .getBooleanExtra("resume_on_launch", false))
        assertFalse(app.peekturn.reader.video.VideoActivity.intent(context, 1)
            .getBooleanExtra("resume_on_launch", true))
    }

    @Test fun readerFailureClearsTypedBookResumeButNeverAYoungerVideo() = runBlocking {
        addLastBook()
        graph.store.clearLastBook()
        assertNull(graph.store.lastContent())
        val video = graph.store.upsertVideo(app.peekturn.reader.video.VideoMetadata("reader-failure", "Video", "Author", 90_000, null))
        graph.store.markVideoOpened(video.id)
        graph.store.clearLastBook()
        assertEquals(app.peekturn.reader.data.LastContent.Video(video.id), graph.store.lastContent())
    }

    @Test fun legacyLastBookPreferenceStillReopens() = runBlocking {
        val book = addLastBook()
        context.getSharedPreferences("peekturn", Context.MODE_PRIVATE).edit()
            .remove("last_content_kind").remove("last_content_id").commit()
        val vm = viewModel("legacy resume")
        initialize(vm, reopen = true)
        assertEquals(book.id, vm.state.value.pendingBookId)
    }

    private fun viewModel(key: String) = MainViewModel(graph).also { viewModels.put(key, it) }

    private suspend fun initialize(vm: MainViewModel, reopen: Boolean) {
        vm.initialize(reopen)
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    private suspend fun addLastBook() = graph.store.addBook(
        "Last read", "Author", File.createTempFile("last-book-", ".epub", context.filesDir).absolutePath, null
    ).also { graph.store.markOpened(it.id) }
}
