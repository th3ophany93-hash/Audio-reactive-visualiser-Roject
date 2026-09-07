pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "audio-reactive-video-studio"

// Phase 1 module set (MASTER_SPECIFICATION_v3.0 §116, §116.1).
// Modules for later phases (reactive, renderer, layers, effects, plugins, timeline,
// export, ui) are deliberately absent — see §116.1 and the forbidden-module guard
// in the root build script.
include(
    ":core:time",
    ":core:model",
    ":core:diagnostics",
    ":core:assets",
    ":core:project",
    ":audio:cache",
    ":audio:beat",
    ":audio:decoder",
    ":audio:analysis",
    ":audio:playback",
    ":app",
    ":testing:audio",
    ":testing:golden",
    ":testing:performance",
)
