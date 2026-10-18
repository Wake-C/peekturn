package app.peekturn.reader.webimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticlePageNavigationPolicyTest {
    @Test fun `a discoverable same-origin full article link is followed`() {
        assertEquals(
            "https://x.com/i/article/123",
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281",
                "https://x.com/i/article/123"
            )
        )
    }

    @Test fun `a cross-origin full article target is refused`() {
        assertNull(
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281",
                "https://example.com/i/article/123"
            )
        )
        assertNull(
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281",
                "https://x.com.example.net/i/article/123"
            )
        )
    }

    @Test fun `an unchanged or cleartext target is refused`() {
        assertNull(
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281#top",
                "https://x.com/HiTw93/status/2042632355756806281"
            )
        )
        assertNull(
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281",
                "http://x.com/i/article/123"
            )
        )
        assertNull(
            sameOriginArticleTarget(
                "https://x.com/HiTw93/status/2042632355756806281",
                "/i/article/123"
            )
        )
    }
}
