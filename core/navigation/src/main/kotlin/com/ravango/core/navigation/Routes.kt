package com.ravango.core.navigation

import kotlinx.serialization.Serializable

/*
 * Type-safe navigation destinations for the whole app. Features never depend on each other; they receive
 * navigation callbacks and the app module wires routes together.
 */

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute

// Scripts
@Serializable data class ScriptsRoute(val folderId: String? = null, val pickForPrompter: Boolean = false)
@Serializable data class ScriptEditorRoute(val scriptId: String? = null, val folderId: String? = null, val initialText: String? = null)

// Teleprompter (stand-alone, full screen)
@Serializable data class TeleprompterRoute(val scriptId: String)
@Serializable data class TeleprompterSettingsRoute(val scriptId: String? = null)

// Capture
@Serializable data class CameraRoute(
    val scriptId: String? = null,
    val projectId: String? = null,
    val templateId: String? = null,
    val audioOnly: Boolean = false,
)
@Serializable data object BeautyPresetsRoute

// Editing
@Serializable data class EditorRoute(val projectId: String)
@Serializable data class ExportRoute(val projectId: String)

// Projects
@Serializable data class ProjectsRoute(val tab: Int = 0)
@Serializable data object TemplatesRoute

// AI
@Serializable data class AiStudioRoute(val scriptId: String? = null, val tool: String? = null)

// Account & app
@Serializable data object AccountRoute
@Serializable data object SignInRoute
@Serializable data object SettingsRoute
@Serializable data object CloudRoute
@Serializable data object PrivacyRoute
@Serializable data object TermsRoute
@Serializable data object AboutRoute
@Serializable data class PaywallRoute(val source: String = "", val feature: String? = null)
