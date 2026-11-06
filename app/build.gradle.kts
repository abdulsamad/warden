import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Single source of truth for the version: VERSION_NAME in gradle.properties (semver, e.g. 1.2.3).
// versionCode is derived from it (1.2.3 -> 10203) so the two can never drift apart.
val appVersionName: String = providers.gradleProperty("VERSION_NAME").get()
val appVersionCode: Int = run {
    val parts = appVersionName.split('.').map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..99 }) {
        "VERSION_NAME must be MAJOR.MINOR.PATCH with each part 0-99, got '$appVersionName'"
    }
    parts[0]!! * 10_000 + parts[1]!! * 100 + parts[2]!!
}

// Release signing comes from ~/.android/keystores/warden/signing.env (one folder per project, next to
// release.keystore), never from the repo. See docs/RELEASING.md. Without it, release packaging
// fails instead of silently using another key.
val signingEnvFile = File(System.getProperty("user.home"), ".android/keystores/warden/signing.env")
val releaseSigningKeys = listOf(
    "WARDEN_RELEASE_KEYSTORE",
    "WARDEN_RELEASE_STORE_PASSWORD",
    "WARDEN_RELEASE_KEY_ALIAS",
    "WARDEN_RELEASE_KEY_PASSWORD",
)
val releaseSigning: Map<String, String?> = run {
    val env = Properties()
    if (signingEnvFile.isFile) signingEnvFile.inputStream().use { env.load(it) }
    releaseSigningKeys.associateWith { env.getProperty(it)?.trim()?.takeIf(String::isNotEmpty) }
}
val canSignRelease: Boolean = releaseSigning.values.all { it != null }

android {
    namespace = "app.warden"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.abdulsamad.warden"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseSigning.getValue("WARDEN_RELEASE_KEYSTORE")!!)
                storePassword = releaseSigning.getValue("WARDEN_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("WARDEN_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("WARDEN_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Warden runs 24/7: R8 shrinks and optimizes the code (smaller dex, AOT-friendly, less
            // RAM and CPU than a debug build), and unused resources are dropped.
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed only with the release key; see the packageRelease guard below.
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        // IGrantService: the one call Warden makes through Shizuku (see GrantUserService).
        aidl = true
    }

    // adb installs into every user by default, which on Samsung includes the Dual Messenger /
    // Dual app profile (user 95). Warden only belongs in the main profile.
    installation {
        installOptions += listOf("--user", "0")
    }
}

// Refuse to package a release without the release key, rather than emitting an unsigned APK that
// someone might publish by mistake. Debug builds and lint are unaffected.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        if (!canSignRelease) {
            throw GradleException(
                "Release signing is not configured. $signingEnvFile must define " +
                    "${releaseSigningKeys.joinToString()} (see docs/RELEASING.md).",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    // Optional no-computer grant: runs the one-time `pm grant` through the Shizuku app.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
