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
        google()
        mavenCentral()
    }
}

rootProject.name = "Lean4Android"
include(":app")
include(":core-model")
include(":core-lsp")
include(":core-process")
include(":core-project")
include(":core-toolchain")
include(":core_toolchain_pack")
