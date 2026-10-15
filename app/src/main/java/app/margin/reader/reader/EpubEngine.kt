package app.margin.reader.reader

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class EpubEngine(context: Context) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.applicationContext.contentResolver, http)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context.applicationContext, httpClient = http, assetRetriever = assets, pdfFactory = null),
        contentProtections = emptyList()
    )

    suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        val asset = assets.retrieve(file).getOrNull()
            ?: throw IOException("This file is not a readable EPUB archive.")
        val publication = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            val htmlResources = (manifest.readingOrder + manifest.resources)
                .filter { it.mediaType?.isHtml == true }
                .associate { it.url().removeQuery().removeFragment() to it.mediaType!! }
            container = securePublicationContainer(container, htmlResources)
        }).getOrNull()
            ?: run { asset.close(); throw IOException("This EPUB could not be opened. Protected books are not supported.") }
        if (!publication.conformsTo(Publication.Profile.EPUB) || publication.readingOrder.isEmpty()) {
            publication.close()
            throw IOException("Choose an EPUB with readable chapters.")
        }
        if (!(publication.readingOrder + publication.tableOfContents).all(::isSafeNavigationLink)) {
            publication.close()
            throw IOException("This EPUB contains an unsafe navigation identifier.")
        }
        publication
    }
}
