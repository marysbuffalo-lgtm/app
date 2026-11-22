pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // com.google.mediapipe:tasks-genai and com.google.ai.edge.litertlm:litertlm-android
        google()
        // org.kiwix:libkiwix (contains the org.kiwix.libzim bindings), org.jsoup:jsoup
        mavenCentral()
    }
}

rootProject.name = "KiwixRag"
include(":app")
