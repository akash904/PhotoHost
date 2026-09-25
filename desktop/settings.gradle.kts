pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK used for packaging (see packageExe) into its own cache, rather
    // than requiring one to be installed system-wide. Android Studio's bundled JDK can compile but
    // ships without jpackage.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
