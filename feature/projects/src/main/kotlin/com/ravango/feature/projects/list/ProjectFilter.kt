package com.ravango.feature.projects.list

import com.ravango.core.common.format.normalizeDigits
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus

enum class ProjectsTab {
    ALL, DRAFTS, EXPORTED;

    companion object {
        fun fromIndex(index: Int): ProjectsTab = entries.getOrElse(index) { ALL }
    }
}

enum class ProjectSort { RECENT, NAME }

fun Project.isExported(): Boolean = status == ProjectStatus.EXPORTED || lastExportUri != null

/** A draft is work in progress: has an auto-saved edit (or is being edited) and has not been exported yet. */
fun Project.isDraft(hasDraft: Boolean): Boolean = !isExported() && (hasDraft || status == ProjectStatus.EDITING)

/** Case-, digit-script- and ZWNJ-insensitive form used for searching Persian and Latin titles alike. */
internal fun searchKey(text: String): String =
    text.normalizeDigits().replace("‌", "").replace('ي', 'ی').replace('ك', 'ک').lowercase().trim()

fun filterProjects(
    projects: List<Project>,
    draftIds: Set<String>,
    tab: ProjectsTab,
    query: String,
    sort: ProjectSort,
    hiddenIds: Set<String> = emptySet(),
    titleComparator: Comparator<String> = String.CASE_INSENSITIVE_ORDER,
): List<Project> {
    val q = searchKey(query)
    val filtered = projects.filter { p ->
        p.deletedAt == null && p.id !in hiddenIds &&
            when (tab) {
                ProjectsTab.ALL -> true
                ProjectsTab.DRAFTS -> p.isDraft(p.id in draftIds)
                ProjectsTab.EXPORTED -> p.isExported()
            } &&
            (q.isEmpty() || searchKey(p.title).contains(q))
    }
    return when (sort) {
        ProjectSort.RECENT -> filtered.sortedByDescending { it.updatedAt }
        ProjectSort.NAME -> filtered.sortedWith(compareBy(titleComparator) { it.title.trim() })
    }
}
