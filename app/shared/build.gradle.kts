import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("maven-publish")
}

kotlin {


    // 2. Настройка iOS Targets с автоматическим объединенным XCFramework
    val xcfName = "SharedLocationTracker"
    val xcf = XCFramework(xcfName)

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        // iosX64() // Добавлен для эмуляторов на x86 (Intel Mac)
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = xcfName
            isStatic = true
            xcf.add(this) // Добавляем фреймворк в общий XCFramework
        }
    }

    // 3. Конфигурация Android в стиле Kotlin Multiplatform 2.x
    android {
        namespace = "org.transline.geoworker.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
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

    // 4. Зависимости (ваши Compose + библиотечные зависимости)
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            // При необходимости для Android гео:
            // implementation(libs.play.services.location)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0") // или актуальную версию
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            
            // Времена и таймштампы (для расчетов 30 мин)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// 5. Настройка публикации AAR артефакта в GitHub Packages
/*
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/YOUR_GITHUB_ORGANIZATION/location-tracker-kmp")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as? String
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as? String
            }
        }
    }
    publications {
        register<MavenPublication>("androidRelease") {
            // Берем скомпилированный AAR фаил Android
            // artifact(tasks.named("bundleReleaseAar"))
            groupId = "org.transline.geoworker"
            artifactId = "shared-android"
            version = "1.0.0"
        }
    }
}
*/