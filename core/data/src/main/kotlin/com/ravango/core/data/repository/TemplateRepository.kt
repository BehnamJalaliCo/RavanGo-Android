package com.ravango.core.data.repository

import com.ravango.core.data.seed.BuiltInPresets
import com.ravango.core.model.ProjectTemplate
import javax.inject.Inject
import javax.inject.Singleton

/** Templates are bundled today; this seam allows serving them from remote config later. */
@Singleton
class TemplateRepository @Inject constructor() {
    fun templates(): List<ProjectTemplate> = BuiltInPresets.templates
    fun template(id: String): ProjectTemplate? = BuiltInPresets.templates.firstOrNull { it.id == id }
}
