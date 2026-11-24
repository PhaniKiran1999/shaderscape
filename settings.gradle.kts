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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Agent Skill SDK: https://github.com/PhaniKiran1999/agent-skill-sdk
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.PhaniKiran1999.*") }
        }
    }
}

rootProject.name = "Wallpapers"

include(":app")
include(":wallpaper-engine")
 

// To build against a local checkout of the SDK at ../agent-skill-sdk instead of the
// published version, put useLocalSkillSdk=true in local.properties (per machine).
val useLocalSkillSdk = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}.getProperty("useLocalSkillSdk")?.trim() == "true"
if (useLocalSkillSdk) {
    includeBuild("../agent-skill-sdk") {
        dependencySubstitution {
            substitute(module("com.github.PhaniKiran1999:agent-skill-sdk")).using(project(":skill-sdk"))
        }
    }
}
