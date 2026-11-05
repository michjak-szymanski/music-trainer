import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.musictrainer.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.musictrainer"
        minSdk = 29
        targetSdk = 37
        versionName = providers.gradleProperty("app.version").get()
        // 1.1.0 -> 10100: always increases with the version, as Android requires for updates.
        versionCode = versionName!!.split(".").map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be side-loaded directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
}
