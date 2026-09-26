package com.ravango.feature.scripts.editor

import androidx.compose.ui.text.input.TextFieldValue

/**
 * Undo/redo history for the script body. Consecutive typing within [coalesceWindowMs] that only inserts or deletes a
 * little text is merged into one step (like word processors); toolbar actions and pastes are always separate steps.
 * Selection-only changes are not recorded.
 */
class UndoStack(private val capacity: Int = 200, private val coalesceWindowMs: Long = 1_000) {
    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastRecordAt = Long.MIN_VALUE
    private var lastWasTyping = false

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Records the transition [before] → [after]. [discrete] marks a toolbar/AI/paste edit. */
    fun record(before: TextFieldValue, after: TextFieldValue, now: Long, discrete: Boolean = false) {
        if (before.text == after.text) return
        val typing = !discrete && kotlin.math.abs(after.text.length - before.text.length) <= 2
        val coalesce = typing && lastWasTyping && now - lastRecordAt <= coalesceWindowMs && undo.isNotEmpty()
        if (!coalesce) {
            undo.addLast(before)
            if (undo.size > capacity) undo.removeFirst()
        }
        redo.clear()
        lastRecordAt = now
        lastWasTyping = typing
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastWasTyping = false
        return previous
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastWasTyping = false
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastWasTyping = false
    }
}
