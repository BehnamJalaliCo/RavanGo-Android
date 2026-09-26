package com.ravango.engine.editor

import com.google.common.truth.Truth.assertThat
import com.ravango.engine.editor.history.EditorHistory
import org.junit.Test

class EditorHistoryTest {
    @Test
    fun undoRedo_roundTrip() {
        val h = EditorHistory(0)
        h.commit(1); h.commit(2)
        assertThat(h.undo()).isEqualTo(1)
        assertThat(h.undo()).isEqualTo(0)
        assertThat(h.undo()).isNull()
        assertThat(h.redo()).isEqualTo(1)
        assertThat(h.redo()).isEqualTo(2)
        assertThat(h.canRedo).isFalse()
    }

    @Test
    fun commitClearsRedo() {
        val h = EditorHistory(0)
        h.commit(1); h.undo(); h.commit(5)
        assertThat(h.canRedo).isFalse()
        assertThat(h.undo()).isEqualTo(0)
    }

    @Test
    fun equalStateIsNotRecorded() {
        val h = EditorHistory(3)
        assertThat(h.commit(3)).isFalse()
        assertThat(h.canUndo).isFalse()
    }

    @Test
    fun sliderDragCoalescesIntoOneStep() {
        val h = EditorHistory(0)
        h.commit(10, "exposure"); h.commit(20, "exposure"); h.commit(30, "exposure")
        h.seal()
        assertThat(h.undoDepth).isEqualTo(1)
        assertThat(h.undo()).isEqualTo(0)
        h.redo()
        h.commit(40, "exposure")
        assertThat(h.undoDepth).isEqualTo(2)
    }

    @Test
    fun differentKeysAreSeparateSteps() {
        val h = EditorHistory(0)
        h.commit(1, "a"); h.commit(2, "b")
        assertThat(h.undoDepth).isEqualTo(2)
    }

    @Test
    fun capacityDropsOldest() {
        val h = EditorHistory(0, capacity = 100)
        for (i in 1..150) h.commit(i)
        assertThat(h.undoDepth).isEqualTo(100)
        var last: Int? = null
        while (h.canUndo) last = h.undo()
        assertThat(last).isEqualTo(50)
    }
}
