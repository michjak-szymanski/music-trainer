import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeVersion = "1.12.1"
// Overridable for throwaway installer tests (-Pmsi.name=..., -Pmsi.upgradeUuid=...).
val msiName = providers.gradleProperty("msi.name").orNull ?: "MusicTrainer"
val msiUpgradeUuid = providers.gradleProperty("msi.upgradeUuid").orNull ?: "666f7322-8ac2-4264-b725-ae340e176bd9"
val coroutinesVersion = "1.11.0"

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    androidLibrary {
        namespace = "com.musictrainer.shared"
        compileSdk = 37
        minSdk = 29
        androidResources { enable = true }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutinesVersion")
                implementation("net.java.dev.jna:jna:5.19.1") // themed Windows title bar
            }
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
        }
    }
}

compose.resources {
    packageOfResClass = "com.musictrainer.resources"
}

// After the MSI is built, add a "Launch Music Trainer" checkbox to its final page (Windows only).
val addMsiLaunchOption by tasks.registering(Exec::class) {
    description = "Adds a 'Launch Music Trainer' option to the installer's finish page."
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    val msi = layout.buildDirectory.file(
        "compose/binaries/main/msi/$msiName-${providers.gradleProperty("app.version").get()}.msi"
    )
    executable = "powershell"
    args(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", rootProject.file("packaging/windows/add-launch-option.ps1").absolutePath,
        "-Msi", msi.get().asFile.absolutePath,
        "-ExeName", "$msiName.exe",
    )
}
tasks.matching { it.name == "packageMsi" }.configureEach { finalizedBy(addMsiLaunchOption) }

compose.desktop {
    application {
        mainClass = "com.musictrainer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = msiName
            // Bump for every release: Windows only upgrades when the version is higher.
            packageVersion = providers.gradleProperty("app.version").get()
            description = "Sight-reading trainer for MIDI keyboards"
            vendor = "Music Trainer"
            licenseFile.set(rootProject.file("LICENSE"))
            // From `./gradlew :shared:suggestRuntimeModules`; java.desktop provides javax.sound.
            modules("java.desktop", "java.instrument", "jdk.unsupported", "jdk.httpserver")
            windows {
                menu = true
                menuGroup = "Music Trainer"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keep constant across versions so new MSIs upgrade the installed app.
                upgradeUuid = msiUpgradeUuid
                iconFile.set(project.file("icons/icon.ico"))
            }
            linux {
                packageName = "music-trainer"
                shortcut = true
                menuGroup = "Education;Music"
                appCategory = "Education"
                debMaintainer = "michjak.szymanski@gmail.com"
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
