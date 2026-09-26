package com.ravango.core.model

import kotlinx.serialization.Serializable

/**
 * A teleprompter script.
 *
 * [body] uses RavanGo's lightweight markup (parsed by the teleprompter engine):
 * - `## Section title` — a section marker; appears in the jump list and can be used as a start point.
 * - `==text==` — highlighted text.
 * - `**text**` — emphasis (rendered bold).
 * - `[pause]` or `[مکث]` — a visual pause cue.
 * - `[[note]]` — a director note: shown dimmed, excluded from reading-time estimates.
 */
@Serializable
data class Script(
    val id: String = newId(),
    val title: String,
    val body: String,
    val folderId: String? = null,
    val direction: ContentDirection = ContentDirection.AUTO,
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    /** Per-script teleprompter appearance/behaviour. `null` means "use the user's defaults". */
    val prompterSettings: TeleprompterSettings? = null,
    /** Character offset where the prompter last stopped / should start (for "resume" and "start from here"). */
    val startCharOffset: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
) {
    val wordCount: Int get() = countWords(body)
}

@Serializable
data class ScriptFolder(
    val id: String = newId(),
    val name: String,
    val colorArgb: ArgbColor = 0xFF8B7CF6,
    val sortIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val deletedAt: Long? = null,
)

@Serializable
enum class ScriptSortOrder { UPDATED_DESC, CREATED_DESC, TITLE_ASC, LAST_OPENED_DESC }

/** Counts spoken words, ignoring markup and director notes. Works for Persian and Latin scripts. */
fun countWords(text: String): Int {
    val cleaned = text
        .replace(Regex("\\[\\[.*?]]", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\\[(pause|مکث)]", RegexOption.IGNORE_CASE), " ")
        .replace("==", " ")
        .replace("**", " ")
        .replace(Regex("(?m)^##\\s*"), " ")
    return cleaned.split(Regex("[\\s\\u200c\\u200f\\u200e]+")).count { token -> token.any { it.isLetterOrDigit() } }
}
