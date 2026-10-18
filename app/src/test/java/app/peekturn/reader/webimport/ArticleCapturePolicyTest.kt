package app.peekturn.reader.webimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArticleCapturePolicyTest {
    // The card X rendered around an article whose body the extractor lost: title, byline,
    // reading-time label and footer, and none of the paragraphs.
    private val xArticleCardOnly =
        "Tw93@HiTw93How I Turn Learning Into a Workflow in the AI Era5 min readTw93Pake · MiaoYan · " +
            "Kaku · Waza · Kami · Mole.fitMore ArticlesJoin 162.4K followers11:54 PM · Apr 10, 202647.6KViews"

    private val xPostNotFound =
        "Post Not Found - X | 404 Error Post Log in Open app Post Log in Open app " +
            "We're unable to show this content The content may be private, deleted or only available on the app. " +
            "Open X App Hmm...this page doesn’t exist. Try searching for something else. Search"

    private val articleBody =
        "Before AI, my way of learning was much more old school. I would read books, go through the blogs of " +
            "well-known people in a field, and keep taking notes along the way. It was slow, but there was real " +
            "joy in it. Now that AI is here, my taste has not really changed. I still prefer to choose good " +
            "material and spend real time with it. Collecting high-quality source material comes first: papers, " +
            "key blog posts, courses and repositories. Then I read and filter, outline the article, and write it " +
            "section by section. AI helps me refine what I already have; it does not replace my thinking. When the " +
            "draft is in decent shape, I read it again myself, adjust it, and publish it. Real depth still depends " +
            "on judgement, patience and the willingness to turn input into output."

    @Test fun `a rendered long article is imported`() {
        assertEquals(CaptureDecision.Import, reviewArticleCapture(articleBody))
    }

    @Test fun `a long preview asking the reader to continue is refused as a preview`() {
        val preview = "A short introduction to a much longer article. ".repeat(14) +
            " Continue reading the full article"
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY),
            reviewArticleCapture(preview)
        )
    }

    @Test fun `a long preview with an explicit full-article link is refused as a preview`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY),
            reviewArticleCapture("$articleBody Read the full article")
        )
    }

    @Test fun `a long preview with a Chinese full-text link is refused as a preview`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY),
            reviewArticleCapture("$articleBody 阅读全文")
        )
    }

    @Test fun `a complete article with an unrelated read-more footer is imported`() {
        assertEquals(CaptureDecision.Import, reviewArticleCapture("$articleBody Read more"))
    }

    @Test fun `an X article card without its body is refused as a preview`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.PREVIEW_ONLY),
            reviewArticleCapture(xArticleCardOnly)
        )
    }

    @Test fun `an X post not found page is refused as unavailable`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.ERROR_PAGE),
            reviewArticleCapture(xPostNotFound)
        )
    }

    @Test fun `a sign-in wall asks for sign-in instead of importing`() {
        val wall = "Sign in to X. Log in to continue reading this article."
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.SIGN_IN_REQUIRED),
            reviewArticleCapture(wall)
        )
    }

    @Test fun `a short navigation shell is refused as no article`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.NO_ARTICLE),
            reviewArticleCapture("Home Explore Notifications Messages Settings")
        )
    }

    @Test fun `markup whitespace does not make a short error page look like an article`() {
        assertEquals(
            CaptureDecision.Refuse(CaptureRefusal.ERROR_PAGE),
            reviewArticleCapture(xPostNotFound + " ".repeat(2_000) + "\n".repeat(2_000))
        )
    }

    @Test fun `a normal blog article with a login header still imports`() {
        val page = "Log in Sign up $articleBody"
        assertEquals(CaptureDecision.Import, reviewArticleCapture(page))
    }

    @Test fun `a direct fetch declines a sign-in interstitial`() {
        val wall = "<html><body><h1>Sign in to continue</h1><p>Log in to view this page.</p></body></html>"
        assertFalse(acceptsDirectHtml(200, "text/html; charset=utf-8", 20_000, wall))
    }
}
