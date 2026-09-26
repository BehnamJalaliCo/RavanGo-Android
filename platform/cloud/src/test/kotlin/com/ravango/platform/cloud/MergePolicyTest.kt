package com.ravango.platform.cloud

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.SyncStatus
import com.ravango.platform.cloud.merge.ConflictStrategy.KEEP_CONFLICT_COPY
import com.ravango.platform.cloud.merge.ConflictStrategy.LAST_WRITER_WINS
import com.ravango.platform.cloud.merge.MergeDecision
import com.ravango.platform.cloud.merge.MergePolicy
import com.ravango.platform.cloud.merge.RecordMeta
import org.junit.Test

class MergePolicyTest {

    private fun remote(updatedAt: Long, deleted: Boolean = false) = RecordMeta(updatedAt, deleted)
    private fun synced(updatedAt: Long, deleted: Boolean = false) = RecordMeta(updatedAt, deleted, SyncStatus.SYNCED, updatedAt)
    private fun pending(updatedAt: Long, base: Long?, deleted: Boolean = false) = RecordMeta(updatedAt, deleted, SyncStatus.PENDING, base)

    @Test
    fun `unknown row is inserted, unknown tombstone skipped`() {
        assertThat(MergePolicy.decide(null, remote(10), LAST_WRITER_WINS, false)).isEqualTo(MergeDecision.ApplyRemote)
        assertThat(MergePolicy.decide(null, remote(10, deleted = true), LAST_WRITER_WINS, false)).isEqualTo(MergeDecision.Skip)
    }

    @Test
    fun `synced local takes server version, echo is skipped`() {
        assertThat(MergePolicy.decide(synced(10), remote(20), KEEP_CONFLICT_COPY, false)).isEqualTo(MergeDecision.ApplyRemote)
        // Server clock order is authoritative even if the other device's clock was behind.
        assertThat(MergePolicy.decide(synced(30), remote(20), KEEP_CONFLICT_COPY, false)).isEqualTo(MergeDecision.ApplyRemote)
        assertThat(MergePolicy.decide(synced(20), remote(20), KEEP_CONFLICT_COPY, true)).isEqualTo(MergeDecision.Skip)
        assertThat(MergePolicy.decide(synced(20), remote(25, deleted = true), LAST_WRITER_WINS, false)).isEqualTo(MergeDecision.ApplyRemote)
    }

    @Test
    fun `pending local with identical content adopts remote`() {
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(25), KEEP_CONFLICT_COPY, true)).isEqualTo(MergeDecision.ApplyRemote)
    }

    @Test
    fun `pending local is kept when the server still has our base version`() {
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(10), KEEP_CONFLICT_COPY, false)).isEqualTo(MergeDecision.KeepLocal)
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(10), LAST_WRITER_WINS, false)).isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `concurrent edit - newer remote wins and local edit is preserved as a copy for scripts`() {
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(40), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.ApplyRemoteKeepLocalCopy)
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(40), LAST_WRITER_WINS, false))
            .isEqualTo(MergeDecision.ApplyRemote)
    }

    @Test
    fun `concurrent edit - newer local wins and remote edit is preserved as a copy for scripts`() {
        assertThat(MergePolicy.decide(pending(50, base = 10), remote(40), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.KeepLocalSaveRemoteCopy)
        assertThat(MergePolicy.decide(pending(50, base = 10), remote(40), LAST_WRITER_WINS, false))
            .isEqualTo(MergeDecision.KeepLocal)
        // Ties favour the device in hand.
        assertThat(MergePolicy.decide(pending(40, base = 10), remote(40), LAST_WRITER_WINS, false))
            .isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `without a known base an older remote is treated as our own echo, not a conflict`() {
        assertThat(MergePolicy.decide(pending(50, base = null), remote(40), KEEP_CONFLICT_COPY, false)).isEqualTo(MergeDecision.KeepLocal)
        assertThat(MergePolicy.decide(pending(50, base = null), remote(60), KEEP_CONFLICT_COPY, false)).isEqualTo(MergeDecision.ApplyRemoteKeepLocalCopy)
    }

    @Test
    fun `deletions racing edits never destroy text`() {
        // Remote deleted (newer) while we edited: keep our edit as a copy, apply the deletion.
        assertThat(MergePolicy.decide(pending(30, base = 10), remote(40, deleted = true), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.ApplyRemoteKeepLocalCopy)
        // We deleted (newer) while the other device edited: the deletion wins, their edit survives as a copy.
        assertThat(MergePolicy.decide(pending(50, base = 10, deleted = true), remote(40), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.KeepLocalSaveRemoteCopy)
        // Remote edit newer than our deletion: the script comes back, nothing to copy.
        assertThat(MergePolicy.decide(pending(30, base = 10, deleted = true), remote(40), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.ApplyRemote)
        // Our edit newer than a remote deletion: we resurrect it on push.
        assertThat(MergePolicy.decide(pending(50, base = 10), remote(40, deleted = true), KEEP_CONFLICT_COPY, false))
            .isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `conflict copy title is idempotent`() {
        assertThat(MergePolicy.conflictCopyTitle("Intro")).isEqualTo("Intro (conflict copy)")
        assertThat(MergePolicy.conflictCopyTitle("Intro (conflict copy)")).isEqualTo("Intro (conflict copy)")
    }
}
