package com.ravango.platform.cloud.merge

import com.ravango.core.model.SyncStatus

/**
 * Sync metadata of one version of a record.
 *
 * @param updatedAt client wall-clock ms of the last edit (the last-writer-wins key).
 * @param baseUpdatedAt for local records: the `updatedAt` of the server version this device last synced
 *   (null when unknown). Lets us tell "the server still has what we last saw" from "someone else edited it".
 */
data class RecordMeta(
    val updatedAt: Long,
    val deleted: Boolean,
    val status: SyncStatus = SyncStatus.SYNCED,
    val baseUpdatedAt: Long? = null,
)

enum class ConflictStrategy {
    /** Newest `updated_at` wins; the losing concurrent edit is discarded (settings-like data). */
    LAST_WRITER_WINS,
    /** Newest wins, but a concurrent losing edit is preserved as a "(conflict copy)" record (scripts). */
    KEEP_CONFLICT_COPY,
}

sealed interface MergeDecision {
    /** Nothing to do (already identical / remote tombstone for a row we never had). */
    data object Skip : MergeDecision
    /** Overwrite local with the remote version and mark it SYNCED. */
    data object ApplyRemote : MergeDecision
    /** Keep the local pending edit; it is pushed next. */
    data object KeepLocal : MergeDecision
    /** Remote wins, but the local edit is saved as a new "(conflict copy)" record first. */
    data object ApplyRemoteKeepLocalCopy : MergeDecision
    /** Local wins (and is pushed), but the concurrent remote edit is saved as a new "(conflict copy)" record. */
    data object KeepLocalSaveRemoteCopy : MergeDecision
}

/**
 * Merge policy for pulled rows — local-first, last-writer-wins on the client `updated_at`, never silently losing
 * a script:
 *
 * 1. No local row: take the remote row (a remote tombstone for an unknown row is skipped).
 * 2. Local row SYNCED (no local edits since the last sync): the server copy is authoritative → take it
 *    (skip when it is our own echo: same `updated_at` and tombstone state).
 * 3. Local row PENDING/CONFLICT with identical content: adopt the remote row (nothing is lost).
 * 4. Local row PENDING/CONFLICT and the server still holds the version we last synced (`baseUpdatedAt`):
 *    no concurrent edit → keep local; it is pushed next.
 * 5. Otherwise both sides changed concurrently → the newer `updated_at` wins (ties favour local so the
 *    device the user is holding wins). With [ConflictStrategy.KEEP_CONFLICT_COPY] the losing side is preserved
 *    as a copy unless it is a deletion (a deleted script needs no copy) — so neither an edit nor a deletion
 *    racing an edit on another device can destroy text.
 */
object MergePolicy {

    fun decide(
        local: RecordMeta?,
        remote: RecordMeta,
        strategy: ConflictStrategy,
        sameContent: Boolean,
    ): MergeDecision {
        if (local == null) return if (remote.deleted) MergeDecision.Skip else MergeDecision.ApplyRemote

        if (local.status == SyncStatus.SYNCED) {
            val echo = local.updatedAt == remote.updatedAt && local.deleted == remote.deleted && sameContent
            return if (echo) MergeDecision.Skip else MergeDecision.ApplyRemote
        }

        // Local has unpushed changes.
        if (sameContent && local.deleted == remote.deleted) return MergeDecision.ApplyRemote

        val remoteUnchangedSinceSync = local.baseUpdatedAt != null && remote.updatedAt == local.baseUpdatedAt
        if (remoteUnchangedSinceSync) return MergeDecision.KeepLocal

        val remoteWins = remote.updatedAt > local.updatedAt
        val copies = strategy == ConflictStrategy.KEEP_CONFLICT_COPY
        return if (remoteWins) {
            if (copies && !local.deleted) MergeDecision.ApplyRemoteKeepLocalCopy else MergeDecision.ApplyRemote
        } else {
            // Without a known base we cannot prove the remote changed concurrently (it may be our own older echo),
            // so only copy when we know the remote diverged.
            val remoteKnownConcurrent = local.baseUpdatedAt != null
            if (copies && !remote.deleted && remoteKnownConcurrent) MergeDecision.KeepLocalSaveRemoteCopy else MergeDecision.KeepLocal
        }
    }

    /** Title for a preserved conflicting version, e.g. "Intro (conflict copy)". Idempotent. */
    fun conflictCopyTitle(title: String, suffix: String = CONFLICT_SUFFIX): String {
        val trimmed = title.trim()
        return if (trimmed.endsWith(suffix)) trimmed else "$trimmed $suffix".trim()
    }

    const val CONFLICT_SUFFIX = "(conflict copy)"
}
