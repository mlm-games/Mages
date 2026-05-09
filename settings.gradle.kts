enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
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
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "mages"
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")

//includeBuild("../kmp-settings") {
//    dependencySubstitution {
//        substitute(module("io.github.mlm-games:kmp-settings-core")).using(project(":settings-core"))
//        substitute(module("io.github.mlm-games:kmp-settings-ui-compose")).using(project(":settings-ui-compose"))
//        substitute(module("io.github.mlm-games:kmp-settings-ksp")).using(project(":settings-ksp"))
//    }
//}
