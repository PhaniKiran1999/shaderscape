import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. PAgent binds to this app's skill through a signature-level permission, so
// Shaderscape must be signed with the SAME release key as PAgent; the variable names match PAgent's
// so one keystore.properties (and the same secret values) serves both. CI passes them in environment
// variables (see .github/workflows/release-apk.yml); locally, put them in <project root>/keystore.properties
// (git-ignored). With neither, release builds stay unsigned.
val releaseSigning = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: releaseSigning.getProperty(name)
val releaseStoreFile = signingValue("PAGENT_KEYSTORE_FILE")

android {
    namespace = "com.kiran.android.shaderscape"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kiran.android.shaderscape"
        minSdk = 24
        targetSdk = 37
        // CI sets these from the run number so each build installs over the last one.
        versionCode = System.getenv("SHADERSCAPE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SHADERSCAPE_VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("PAGENT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("PAGENT_KEY_ALIAS")
                keyPassword = signingValue("PAGENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":wallpaper-engine"))
    implementation(libs.agent.skill.sdk)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}