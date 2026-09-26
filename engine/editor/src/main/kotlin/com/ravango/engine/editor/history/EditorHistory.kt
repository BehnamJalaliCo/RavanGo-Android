package com.ravango.engine.editor.history

/**
 * Undo/redo over immutable snapshots.
 *
 * - Every [commit] stores the previous snapshot; the stack is capped at [capacity] (oldest dropped).
 * - Continuous gestures (slider drags, handle drags) pass a [coalesceKey]: consecutive commits with the same key replace
 *   the current snapshot instead of adding steps, so one drag = one undo step. [seal] ends the current gesture.
 *
 * Not thread-safe: call from one thread (the view model's main thread).
 */
class EditorHistory<T : Any>(initial: T, private val capacity: Int = DEFAULT_CAPACITY) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var openKey: String? = null

    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /**
     * Records [next] as the new current state. Returns false when [next] equals the current state (nothing recorded).
     */
    fun commit(next: T, coalesceKey: String? = null): Boolean {
        if (next == current) return false
        if (coalesceKey != null && coalesceKey == openKey) {
            current = next
            redoStack.clear()
            return true
        }
        undoStack.addLast(current)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        current = next
        openKey = coalesceKey
        return true
    }

    /** Ends the current coalescing gesture; the next commit starts a new undo step even with the same key. */
    fun seal() {
        openKey = null
    }

    fun undo(): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = previous
        openKey = null
        return current
    }

    fun redo(): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > capacity) undoStack.removeFirst()
        current = next
        openKey = null
        return current
    }

    /** Replaces the current state without an undo step (e.g. background results like a finished reverse render). */
    fun replaceCurrent(state: T) {
        current = state
    }

    /** Resets history to a single state (e.g. after loading a draft). */
    fun reset(state: T) {
        undoStack.clear()
        redoStack.clear()
        openKey = null
        current = state
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
