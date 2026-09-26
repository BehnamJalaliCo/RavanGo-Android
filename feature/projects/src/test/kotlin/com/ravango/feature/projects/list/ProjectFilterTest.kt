package com.ravango.feature.projects.list

import com.google.common.truth.Truth.assertThat
import com.ravango.core.model.Project
import com.ravango.core.model.ProjectStatus
import org.junit.Test

class ProjectFilterTest {

    private fun project(id: String, title: String, updated: Long, status: ProjectStatus = ProjectStatus.RECORDED, export: String? = null, deleted: Long? = null) =
        Project(id = id, title = title, status = status, lastExportUri = export, createdAt = 0, updatedAt = updated, deletedAt = deleted)

    private val recorded = project("a", "Morning vlog", 30)
    private val editing = project("b", "ریلز ۲", 20, ProjectStatus.EDITING)
    private val exported = project("c", "Product launch", 10, ProjectStatus.EXPORTED, export = "/x.mp4")
    private val withDraft = project("d", "banana", 40)
    private val removed = project("e", "gone", 50, deleted = 1)
    private val all = listOf(recorded, editing, exported, withDraft, removed)

    @Test fun `all tab excludes deleted and sorts by recent`() {
        val result = filterProjects(all, emptySet(), ProjectsTab.ALL, "", ProjectSort.RECENT)
        assertThat(result.map { it.id }).containsExactly("d", "a", "b", "c").inOrder()
    }

    @Test fun `drafts are edited or auto-saved projects that are not exported`() {
        val result = filterProjects(all, setOf("d", "c"), ProjectsTab.DRAFTS, "", ProjectSort.RECENT)
        assertThat(result.map { it.id }).containsExactly("d", "b").inOrder()
    }

    @Test fun `exported tab uses status or export uri`() {
        val exportedByUri = project("f", "shared", 5, ProjectStatus.EDITING, export = "content://x")
        val result = filterProjects(all + exportedByUri, emptySet(), ProjectsTab.EXPORTED, "", ProjectSort.RECENT)
        assertThat(result.map { it.id }).containsExactly("c", "f").inOrder()
    }

    @Test fun `search ignores case and digit script`() {
        assertThat(filterProjects(all, emptySet(), ProjectsTab.ALL, "VLOG", ProjectSort.RECENT).map { it.id }).containsExactly("a")
        assertThat(filterProjects(all, emptySet(), ProjectsTab.ALL, "ریلز 2", ProjectSort.RECENT).map { it.id }).containsExactly("b")
    }

    @Test fun `name sort is case insensitive and hidden ids are skipped`() {
        val result = filterProjects(all, emptySet(), ProjectsTab.ALL, "", ProjectSort.NAME, hiddenIds = setOf("b"))
        assertThat(result.map { it.title }).containsExactly("banana", "Morning vlog", "Product launch").inOrder()
    }

    @Test fun `tab index maps safely`() {
        assertThat(ProjectsTab.fromIndex(1)).isEqualTo(ProjectsTab.DRAFTS)
        assertThat(ProjectsTab.fromIndex(9)).isEqualTo(ProjectsTab.ALL)
    }
}
