package com.openminis.app.provider

/**
 * [T-stall-resume] Resume-from-partial for mid-stream stalls.
 *
 * `streamStallWatchdog` throws [com.openminis.app.data.model.LLMError.TransientError]
 * with `stalledAfterFirstEvent=true` when the stream WAS alive and then went
 * quiet. For that case a plain retry discards the partial text and regenerates
 * from scratch — duplicated content, wasted tokens, and a visible "rewind" in
 * the UI. Instead we carry the already-streamed text into the next attempt as
 * a continuation instruction so the model picks up where it stopped.
 *
 * Deliberately bounded: only the trailing [MAX_CHARS] of the partial text is
 * echoed back, and the instruction makes clear the partial is context, not a
 * fresh prompt. `note()` is a pure function so the retry wiring stays testable
 * without a ViewModel.
 */
object StallResume {
    private const val MAX_CHARS = 6000

    /** Build the continuation note, or null when there is nothing to resume. */
    fun note(partialText: String, maxChars: Int = MAX_CHARS): String {
        val partial = partialText.trim()
        if (partial.isEmpty()) return ""
        val tail = partial.takeLast(maxChars)
        return buildString {
            append("<stall-resume>")
            append("The previous attempt was interrupted mid-stream after producing the text below. ")
            append("Continue from where it stopped. Do NOT repeat the text. Do NOT introduce the quote. ")
            append("Just continue the answer.\n")
            append("\"\"\"\n")
            append(tail)
            append("\n\"\"\"</stall-resume>")
        }
    }
}