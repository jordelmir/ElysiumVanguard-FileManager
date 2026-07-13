package com.elysium.vanguard.core.runtime.distros.gui.rfb

/**
 * Mirrors Android IME composition into a terminal-like remote endpoint.
 *
 * Soft keyboards commonly send progressively longer composing text before a
 * final commit. A normal text field can replace that composition internally;
 * a terminal cannot, so we replace the already-sent composition with
 * backspaces before emitting the new candidate. A matching final commit then
 * becomes a no-op and cannot duplicate text in the guest.
 */
internal class RfbImeComposer(
    private val sendText: (String) -> Unit,
    private val sendBackspace: () -> Unit
) {
    private var composing = ""

    fun setComposingText(value: CharSequence?) = replaceComposing(value?.toString().orEmpty())

    fun commitText(value: CharSequence?) {
        val committed = value?.toString().orEmpty()
        if (committed != composing) replaceComposing(committed)
        composing = ""
    }

    fun finishComposingText() {
        composing = ""
    }

    fun deleteBefore(count: Int) {
        repeat(count.coerceAtLeast(0)) { sendBackspace() }
        composing = composing.dropLastCodePoints(count)
    }

    private fun replaceComposing(next: String) {
        if (next == composing) return
        repeat(composing.codePointCount(0, composing.length)) { sendBackspace() }
        if (next.isNotEmpty()) sendText(next)
        composing = next
    }

    private fun String.dropLastCodePoints(count: Int): String {
        val remaining = (codePointCount(0, length) - count).coerceAtLeast(0)
        return substring(0, offsetByCodePoints(0, remaining))
    }
}
