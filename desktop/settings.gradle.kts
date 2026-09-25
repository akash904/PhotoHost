pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Room and the bundled SQLite driver are published only to Google's repository.
        google {
            content { includeGroupByRegex("androidx.*") }
        }
        mavenCentral()
    }
}

rootProject.name = "photoHostPC"
