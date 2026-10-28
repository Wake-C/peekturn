package app.peekturn.reader.video

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.peekturn.reader.AppLanguage
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme

/**
 * Experimental, opt-in anonymous challenge flow. WebView and OkHttp use separate cookie stores;
 * only allowlisted verification cookies are copied to YouTubeSource's memory-only HTTP session.
 * No account login cookies, general browser profile, or signed stream URLs are transferred.
 */
class VideoVerificationActivity : AppCompatActivity() {
    companion object { const val EXTRA_VIDEO_ID = "verification_video_id" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PeekturnApp).graph
        val appSettings = graph.store.settings.value
        AppLanguage.apply(this, appSettings.uiLanguage)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf(YouTubeUrls::isVideoId)
        val startUrl = videoId?.let { "https://www.youtube.com/watch?v=$it" }
            ?: "https://www.youtube.com/"

        setContent {
            val webView = remember {
                WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // This experiment supports anonymous challenges, never Google login.
                            val url = request.url
                            return url.scheme != "https" || url.host == "accounts.google.com"
                        }
                    }
                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(startUrl)
                }
            }
            var noVerificationCookie by remember { mutableStateOf(false) }
            PeekturnTheme(theme = appSettings.appTheme) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    Text(stringResource(R.string.video_ui_verify_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.video_ui_verify_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onRelease = { view ->
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            view.destroy()
                        }
                    )
                    if (noVerificationCookie) {
                        Text(
                            stringResource(R.string.video_ui_verify_no_cookies),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::finish, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.video_ui_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val manager = CookieManager.getInstance()
                                val accepted = graph.videoSource.acceptWebVerificationCookies(
                                    manager.getCookie("https://www.youtube.com/"),
                                    manager.getCookie("https://www.google.com/")
                                )
                                graph.developerLog.recordEvent(
                                    "video", if (accepted) "verification-cookies:accepted" else "verification-cookies:none"
                                )
                                if (accepted) {
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                } else {
                                    noVerificationCookie = true
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.video_ui_verify_retry)) }
                    }
                }
            }
        }
    }
}
