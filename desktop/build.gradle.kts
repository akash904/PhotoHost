import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

group = "dev.gpicalter"
version = "0.1-m0"

// Bytecode for 21, built by whatever JDK runs Gradle (Android Studio's JBR 25 on the dev machine).
// 21 rather than 25 so the packaged runtime can be any current LTS without a rebuild.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Same schema directory convention as gpicAlter. The exported JSON is compared against the phone's
// app/schemas to prove both servers create the same database.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// The bundled SQLite loads a native library. JDK 24+ warns about that and a future JDK will refuse
// it unless native access is granted explicitly.
val jvmFlags = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass.set("dev.gpicalter.desktop.MainKt")
    applicationDefaultJvmArgs = jvmFlags
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Embedded HTTP server: the same engine as the phone, so routes behave identically.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.network.tls.certificates)

    // The index. Room's JVM target with SQLite compiled in, so nothing depends on a system SQLite.
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)

    // Media. ImageIO is in the JDK; TwelveMonkeys replaces its JPEG reader with one that survives
    // CMYK and broken colour profiles, and adds WebP.
    implementation(libs.twelvemonkeys.jpeg)
    implementation(libs.twelvemonkeys.webp)
    implementation(libs.metadata.extractor)

    implementation(libs.zxing.core)

    // Ktor logs through SLF4J; without a binding it prints a warning and drops everything.
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    // The phone's own client stack: OkHttp with a pinned trust manager. Testing through anything
    // else would prove compatibility with a client nobody uses.
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(jvmFlags)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
