package com.ravango.feature.projects.common

import com.ravango.core.common.result.Outcome
import com.ravango.core.common.result.outcomeOf
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.data.repository.ScriptRepository
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.navigation.AiStudioRoute
import com.ravango.core.navigation.CameraRoute
import javax.inject.Inject

enum class TemplateStartMode { WITH_SCRIPT, WITH_AI, WITHOUT_SCRIPT }

/** Localized strings the UI resolves (per-app locale) and hands to the starter. */
data class TemplateTexts(val title: String, val localizedOutline: String)

/** AI Studio tool id for script generation (shared string contract with feature:ai). */
const val AI_TOOL_GENERATE_SCRIPT = "GENERATE_SCRIPT"

/** Creates the script/project a template needs and returns the route to open next. */
class TemplateStarter @Inject constructor(
    private val scripts: ScriptRepository,
    private val projects: ProjectRepository,
) {
    suspend fun start(template: ProjectTemplate, mode: TemplateStartMode, texts: TemplateTexts): Outcome<Any> = outcomeOf {
        when (mode) {
            TemplateStartMode.WITH_SCRIPT -> {
                val script = scripts.create(texts.title, texts.localizedOutline)
                val project = projects.create(texts.title, template.aspectRatio, scriptId = script.id, templateId = template.id)
                CameraRoute(scriptId = script.id, projectId = project.id, templateId = template.id)
            }
            TemplateStartMode.WITH_AI -> {
                val script = scripts.create(texts.title, texts.localizedOutline)
                AiStudioRoute(scriptId = script.id, tool = AI_TOOL_GENERATE_SCRIPT)
            }
            TemplateStartMode.WITHOUT_SCRIPT -> {
                val project = projects.create(texts.title, template.aspectRatio, templateId = template.id)
                CameraRoute(projectId = project.id, templateId = template.id)
            }
        }
    }
}
