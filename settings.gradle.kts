pluginManagement {
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

rootProject.name = "QuickVoiceDialer"
include(":app")
include(":core:model")
include(":core:call")
include(":core:design")
include(":core:data")
include(":core:audio")
include(":core:telecom")
include(":core:voip")
include(":core:quickvoice")
include(":core:update")
include(":feature:home")
include(":feature:call")
include(":feature:quickvoice")
include(":feature:settings")
