import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from a properties file kept outside the repo (storeFile, storePassword,
// keyAlias, keyPassword). Without it, `assembleRelease` falls back to the debug key.
val signingProps = Properties().apply {
    val path = System.getenv("WAFFLE_SIGNING")
        ?: "${System.getProperty("user.home")}/.config/waffle/keystore.properties"
    file(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Short hash of the commit being built, shown in About → Diagnostics. "-dirty" means the build had
// uncommitted changes; "unknown" means git wasn't available (a source download, for example).
val gitCommit: String = runCatching {
    fun git(vararg args: String) = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    val hash = git("rev-parse", "--short", "HEAD").standardOutput.asText.get().trim()
    val dirty = git("status", "--porcelain").standardOutput.asText.get().isNotBlank()
    hash.ifEmpty { null }?.let { if (dirty) "$it-dirty" else it }
}.getOrNull() ?: "unknown"

android {
    namespace = "com.barrettcoats.waffle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barrettcoats.waffle"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

base {
    archivesName.set("waffle-${android.defaultConfig.versionName}")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // "Send photos from your phone": a tiny LAN web server (BSD-3) and its QR code (Apache-2.0).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
