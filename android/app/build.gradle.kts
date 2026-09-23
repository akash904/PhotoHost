import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Signing credentials, kept out of the build file and out of git.
 *
 * Absent on a fresh clone, and that is deliberate rather than an oversight: the build still has to
 * succeed for anybody who does not hold the upload key, so a missing file leaves release unsigned
 * instead of failing. What it must never do is quietly produce an unsigned build on the machine
 * that does have the key, which is why the signing config is applied whenever the file is there.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.gpicalter"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Deliberately NO applicationIdSuffix on debug: a different application id orphans the
        // persisted SAF tree grants, so switching variants would silently lose access to the drive.
        applicationId = "dev.gpicalter"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1-m0"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // largeHeap is deliberately OFF so bitmap/fd leaks surface early instead of being papered over.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

// Schemas are committed so migrations can be written against a known-good starting point and
// tested. fallbackToDestructiveMigration is never acceptable here: it silently deletes the index.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.exifinterface)

    // Embedded HTTP server. CIO, not Netty: Netty assumes a server JVM and hits missing-class
    // failures on ART. CIO is pure-Kotlin coroutines, HTTP/1.x only, which is all a LAN media
    // server needs.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    // Generates the self-signed certificate the server presents; clients pin its fingerprint.
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Client side. The viewer always speaks the HTTP API -- at 127.0.0.1 when this phone is also
    // the server -- so there is exactly one browsing code path rather than one for local and one
    // for remote.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    // Coil shares the same OkHttp stack, so thumbnails carry the auth token like every other call.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // ExoPlayer defaults to HttpURLConnection, which cannot honour the certificate pin.
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime)

    // QR pairing. zxing:core is pure Java and generation-only; the scanner side uses CameraX with
    // ML Kit's bundled model, so pairing does not depend on Google Play Services being present.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    // Present ONLY so probe 6 can benchmark DocumentFile.listFiles() against a raw
    // DocumentsContract query. Production code must never use DocumentFile for scanning.
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
