plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "org.transline.geoworker"
version = "1.0.0"
application {
    mainClass = "org.transline.geoworker.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}