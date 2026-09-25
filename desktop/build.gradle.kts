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

    // The real Windows file dialog. Swing's JFileChooser only imitates an XP-era one, and AWT's
    // FileDialog cannot choose folders; IFileOpenDialog does both, and looks like every other app.
    implementation(libs.jna.platform)

    // Ktor logs through SLF4J; without a binding it prints a warning and drops everything.
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    // The phone's own client stack: OkHttp with a pinned trust manager. Testing through anything
    // else would prove compatibility with a client nobody uses.
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.content.negotiation)
}

// ------------------------------------------------------------------ Windows packaging
//
// `gradlew packageExe` -> build/package/PhotoHost/PhotoHost.exe, a folder that runs on any 64-bit
// Windows PC with nothing installed: it carries its own trimmed Java runtime. The folder can be
// zipped or copied anywhere. An MSI installer comes later; jpackage needs the WiX toolset for that.
//
// jpackage is not in Android Studio's bundled JDK, so the packaging JDK is a Gradle toolchain
// (Temurin 25), downloaded into Gradle's cache on first use.

val packagingJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

// From `jdeps --print-module-deps` over every runtime jar, plus jdk.charsets, which jdeps cannot see
// because EXIF text in legacy encodings is decoded by charset name at run time.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.instrument", "java.management", "java.sql",
    "jdk.unsupported", "jdk.charsets",
)

val cleanPackage = tasks.register<Delete>("cleanPackage") {
    delete(layout.buildDirectory.dir("package"))
}

// The exe's icon is drawn by the app's own code (AppIcon.kt, a transcription of the phone app's
// adaptive icon), so the window, the taskbar and Explorer can never disagree about what it looks like.
val iconFile = layout.buildDirectory.file("icon/PhotoHost.ico")
val generateIcon = tasks.register<JavaExec>("generateIcon") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.gpicalter.desktop.AppIconKt")
    args(iconFile.get().asFile.absolutePath, layout.buildDirectory.file("icon/preview.png").get().asFile.absolutePath)
    outputs.file(iconFile)
}

tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "Builds build/package/PhotoHost/PhotoHost.exe with a bundled Java runtime."
    dependsOn(tasks.installDist, cleanPackage, generateIcon)

    val libDir = layout.buildDirectory.dir("install/${project.name}/lib")
    val outDir = layout.buildDirectory.dir("package")
    inputs.dir(libDir)
    outputs.dir(outDir)

    executable = packagingJdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath
    args(
        "--type", "app-image",
        "--name", "PhotoHost",
        // jpackage wants a purely numeric version.
        "--app-version", "0.1.0",
        "--vendor", "PhotoHost",
        "--description", "Photo library server for the PhotoHost phone app",
        "--input", libDir.get().asFile.absolutePath,
        "--main-jar", "${project.name}-${project.version}.jar",
        "--main-class", "dev.gpicalter.desktop.MainKt",
        "--add-modules", runtimeModules.joinToString(","),
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
        "--dest", outDir.get().asFile.absolutePath,
        "--icon", iconFile.get().asFile.absolutePath,
    )
    jvmFlags.forEach { args("--java-options", it) }
    // Without a cap the JVM reserves up to a quarter of the PC's RAM and, being lazy about giving it
    // back, an idle server was observed at 644 MB. Decoding is subsampled, so even a 50 MP photo
    // needs a few tens of MB; 512 MB leaves room for several thumbnails in parallel.
    args("--java-options", "-Xmx512m")
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
