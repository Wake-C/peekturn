package app.peekturn.reader.reader

internal fun shouldExitForResourceFailure(failedHref: String, spineHtmlHrefs: Set<String>): Boolean =
    failedHref in spineHtmlHrefs
