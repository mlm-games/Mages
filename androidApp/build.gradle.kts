plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.apk.dist)
}

android {

    kotlin {
        jvmToolchain(17)
    }

    namespace = "org.mlm.mages"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.mlm.mages"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 461
        versionName = "2.0.4"

        // have to keep versionName here for fdroid, do not change

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
        val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
        val targetAbi = providers.gradleProperty("targetAbi").orNull

        splits {
            abi {
                isEnable = enableApkSplits
                reset()
                if (enableApkSplits) {
                    if (targetAbi != null) include(targetAbi) 
                    else include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
                isUniversalApk = includeUniversalApk && enableApkSplits
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.kmp.settings.core)
    implementation(libs.element.call.embedded)

    implementation(libs.connector)
    implementation(libs.connector.ui)
}

apkDist {
    artifactNamePrefix = "mages"
}