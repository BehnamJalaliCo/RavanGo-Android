pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RavanGo"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Core — shared foundations, no feature knowledge.
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:ui")
include(":core:navigation")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:media")
include(":core:testing")

// Engines — heavy, UI-less subsystems. Each can be tested and evolved in isolation.
include(":engine:render")
include(":engine:camera")
include(":engine:audio")
include(":engine:beauty")
include(":engine:teleprompter")
include(":engine:editor")
include(":engine:ai")

// Platform services — integrations with external backends.
include(":platform:auth")
include(":platform:cloud")
include(":platform:billing")

// Features — screens and view models.
include(":feature:onboarding")
include(":feature:home")
include(":feature:scripts")
include(":feature:teleprompter")
include(":feature:camera")
include(":feature:beauty")
include(":feature:editor")
include(":feature:ai")
include(":feature:projects")
include(":feature:account")
include(":feature:paywall")
