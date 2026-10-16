package app.margin.reader.reader

/** Selection ownership survives native toolbar recreation; tokens own asynchronous native reads. */
internal class SelectionSession {
    var generation: Long = 0L
        private set
    private var quickConsumed = false

    fun beginNativeMode(): Long {
        generation++
        return generation
    }

    fun isCurrent(token: Long): Boolean = token == generation

    fun paddingAction(
        token: Long,
        hasSelection: Boolean,
        gestureInput: Long = 0L,
        currentInput: Long = 0L
    ): ReaderPaddingAction = when {
        !isCurrent(token) || gestureInput != currentInput -> ReaderPaddingAction.Ignore
        hasSelection -> ReaderPaddingAction.ClearSelection
        else -> ReaderPaddingAction.Navigate
    }

    fun consumeQuick(token: Long): Boolean {
        if (!isCurrent(token) || quickConsumed) return false
        quickConsumed = true
        return true
    }

    fun lookupOpened() { quickConsumed = true }

    fun adjust() {
        generation++
        quickConsumed = true
    }

    fun clear() {
        generation++
        quickConsumed = false
    }
}

internal enum class ReaderPaddingAction { Ignore, ClearSelection, Navigate }
