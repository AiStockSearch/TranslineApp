import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("plugin.serialization") version "2.0.0"
    id("maven-publish")
}

kotlin {

    // 2. Настройка iOS Targets с автоматическим объединенным XCFramework
    val xcfName = "SharedLocationTracker"
    val xcf = XCFramework(xcfName)

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = xcfName
            isStatic = true
            // Убирает warning "Cannot infer a bundle ID"
            binaryOption("bundleId", "org.transline.geoworker.shared")
            xcf.add(this)
        }
    }

    // 3. Конфигурация Android в стиле Kotlin Multiplatform 2.x
    android {
        namespace = "org.transline.geoworker.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources {
            enable = true
        }

        withHostTest {
            isIncludeAndroidResources = true
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // 4. Зависимости (Compose + Network/Serialization + Core)
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // DateTime
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

            // Serialization & Ktor Core
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            implementation("io.ktor:ktor-client-core:2.3.9")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.9")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.9")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)

            // Ktor Android Engine (OkHttp)
            implementation("io.ktor:ktor-client-okhttp:2.3.9")

            // EncryptedSharedPreferences (MasterKey / Android Keystore) — Task 1 approved
            implementation("androidx.security:security-crypto:1.1.0")

            // Notify Manager (NotificationCompat, BigPicture) — pin for AGP 9.0 / compileSdk 36
            implementation("androidx.core:core-ktx:1.15.0")
        }

        iosMain.dependencies {
            // Ktor Darwin Engine (iOS)
            implementation("io.ktor:ktor-client-darwin:2.3.9")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}