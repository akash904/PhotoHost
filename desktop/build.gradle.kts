import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

group = "io.github.akash904.photohost"
version = "0.9.1"

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

// Same schema directory convention as the phone app. The exported JSON is compared against the phone's
// app/schemas to prove both servers create the same database.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// The bundled SQLite loads a native library. JDK 24+ warns about that and a future JDK will refuse
// it unless native access is granted explicitly.
val jvmFlags = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass.set("io.github.akash904.photohost.desktop.MainKt")
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

/**
 * The version Windows sees: in the exe, the installer and Apps & features. Purely numeric
 * MAJOR.MINOR.PATCH, because jpackage and the Store both require it, and it must only ever go up:
 * an installer with a lower version will not upgrade over a higher one.
 */
val appVersion = project.version.toString()

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
    mainClass.set("io.github.akash904.photohost.desktop.AppIconKt")
    args(iconFile.get().asFile.absolutePath, layout.buildDirectory.file("icon/preview.png").get().asFile.absolutePath)
    outputs.file(iconFile)
}

/** jpackage's app-image arguments, shared by the plain folder and the Store package's image. */
fun Exec.jpackageAppImage(outDir: Provider<Directory>) {
    val libDir = layout.buildDirectory.dir("install/${project.name}/lib")
    inputs.dir(libDir)
    outputs.dir(outDir)

    executable = packagingJdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath
    args(
        "--type", "app-image",
        "--name", "PhotoHost",
        "--app-version", appVersion,
        "--vendor", "PhotoHost",
        "--description", "Photo library server for the PhotoHost phone app",
        "--input", libDir.get().asFile.absolutePath,
        "--main-jar", "${project.name}-${project.version}.jar",
        "--main-class", "io.github.akash904.photohost.desktop.MainKt",
        "--add-modules", runtimeModules.joinToString(","),
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
        "--dest", outDir.get().asFile.absolutePath,
        "--icon", iconFile.get().asFile.absolutePath,
    )
    args("--copyright", "Copyright (C) 2026 Akash Verma")
    jvmFlags.forEach { args("--java-options", it) }
    // Without a cap the JVM reserves up to a quarter of the PC's RAM and, being lazy about giving it
    // back, an idle server was observed at 644 MB. Decoding is subsampled, so even a 50 MP photo
    // needs a few tens of MB; 512 MB leaves room for several thumbnails in parallel.
    args("--java-options", "-Xmx512m")
}

val packageExe = tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "Builds build/package/PhotoHost/PhotoHost.exe with a bundled Java runtime."
    dependsOn(tasks.installDist, cleanPackage, generateIcon)
    jpackageAppImage(layout.buildDirectory.dir("package"))
}

// `gradlew packageInstaller` -> build/installer/PhotoHost-Setup-<version>.exe, an Inno Setup
// installer around the app image above. See packaging/PhotoHost.iss for why it is shaped the way it is
// (Microsoft Store requirements).
//
// Signing is optional and never configured in this file, because a certificate belongs to one
// machine: set PHOTOHOST_SIGNTOOL to a full signtool command line ending in $f, e.g.
//   "C:\Program Files (x86)\Windows Kits\10\bin\x64\signtool.exe" sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /a $f
// and the setup, the uninstaller and PhotoHost.exe are all signed. Without it the build is unsigned.
tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Builds build/installer/PhotoHost-Setup-<version>.exe."
    dependsOn(packageExe)

    val iscc = providers.environmentVariable("ISCC").orNull
        ?: listOf(
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Inno Setup 6\\ISCC.exe",
            "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe",
            "C:\\Program Files\\Inno Setup 6\\ISCC.exe",
        ).firstOrNull { File(it).isFile }
        ?: "ISCC.exe"
    val outDir = layout.buildDirectory.dir("installer")
    val sign = providers.environmentVariable("PHOTOHOST_SIGNTOOL").orNull
    outputs.dir(outDir)

    executable = iscc
    args(
        "/Qp",
        "/DAppVersion=$appVersion",
        "/DSourceDir=${layout.buildDirectory.dir("package/PhotoHost").get().asFile.absolutePath}",
        "/DIconFile=${iconFile.get().asFile.absolutePath}",
        "/DOutputDir=${outDir.get().asFile.absolutePath}",
    )
    if (sign != null) args("/DSign", "/Sphotohost=$sign")
    args(file("packaging/PhotoHost.iss").absolutePath)
}

// `gradlew packageMsix` -> build/msix/PhotoHost-<version>.msix, for the Microsoft Store. See
// packaging/msix/AppxManifest.xml for how it differs from the setup .exe. Uploaded unsigned: the Store
// signs it. Needs the Windows SDK (makeappx, makepri); set WINDOWS_SDK_BIN to its x64 folder if it is
// not in the usual place.

val msixDir = layout.buildDirectory.dir("msix")

/** The newest Windows SDK's x64 tools folder, as a path, or null when there is none. */
val windowsSdkBin: String? = providers.environmentVariable("WINDOWS_SDK_BIN").orNull
    ?: File("C:\\Program Files (x86)\\Windows Kits\\10\\bin")
        .listFiles { f -> f.name.startsWith("10.") && File(f, "x64\\makeappx.exe").isFile }
        ?.maxByOrNull { f -> f.name.split('.').joinToString("") { it.padStart(6, '0') } }
        ?.let { File(it, "x64").absolutePath }

/** Plain values only in task actions: the configuration cache cannot keep references to this script. */
fun Exec.sdkTool(name: String) {
    val sdk = windowsSdkBin
    executable = File(sdk ?: ".", name).absolutePath
    doFirst {
        if (sdk == null) throw GradleException("Windows SDK not found: install it, or set WINDOWS_SDK_BIN to the folder holding $name")
    }
}

val cleanMsix = tasks.register<Delete>("cleanMsix") {
    delete(msixDir)
}

// Its own app image, because only the package carries the second launcher its StartupTask needs.
val msixImage = tasks.register<Exec>("msixImage") {
    dependsOn(tasks.installDist, cleanMsix, generateIcon)
    jpackageAppImage(msixDir.map { it.dir("image") })
    args("--add-launcher", "PhotoHostAtSignIn=${file("packaging/msix/at-sign-in.properties").absolutePath}")
}

val msixAssets = tasks.register<JavaExec>("msixAssets") {
    dependsOn(cleanMsix)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.akash904.photohost.desktop.AppIconKt")
    val out = msixDir.map { it.dir("pri/Assets") }
    args("--msix-assets", out.get().asFile.absolutePath)
    outputs.dir(out)
}

val msixLayout = tasks.register<Sync>("msixLayout") {
    dependsOn(msixImage, msixAssets)
    into(msixDir.map { it.dir("layout") })
    from(msixDir.map { it.dir("image/PhotoHost") }) { into("PhotoHost") }
    from(msixDir.map { it.dir("pri/Assets") }) { into("Assets") }
    val version = appVersion
    from("packaging/msix/AppxManifest.xml") { filter { it.replace("@VERSION@", version) } }
}

// The resource index, so Windows finds the logos by their scale and targetsize qualifiers. Indexes
// only Assets\ (pri/ holds nothing else), not the thousands of runtime files.
val msixPri = tasks.register<Exec>("msixPri") {
    dependsOn(msixLayout)
    sdkTool("makepri.exe")
    val layoutDir = msixDir.get().dir("layout").asFile
    args(
        "new",
        "/pr", msixDir.get().dir("pri").asFile.absolutePath,
        "/cf", file("packaging/msix/priconfig.xml").absolutePath,
        "/mn", File(layoutDir, "AppxManifest.xml").absolutePath,
        "/of", File(layoutDir, "resources.pri").absolutePath,
        "/o",
    )
}

tasks.register<Exec>("packageMsix") {
    group = "distribution"
    description = "Builds build/msix/PhotoHost-<version>.msix for the Microsoft Store."
    dependsOn(msixPri)
    sdkTool("makeappx.exe")
    args(
        "pack",
        "/d", msixDir.get().dir("layout").asFile.absolutePath,
        "/p", msixDir.get().file("PhotoHost-$appVersion.msix").asFile.absolutePath,
        "/o",
    )
}

// The web page is shared with the phone app and lives once, in web/ at the repository root.
tasks.processResources {
    from(rootDir.resolve("../web")) { into("web") }
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
