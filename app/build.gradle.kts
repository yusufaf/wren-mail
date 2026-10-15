plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// version.txt is the single source of truth; release-please bumps it. Reading it through
// a provider makes it a configuration-cache input, so a bump invalidates the cache.
val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("version.txt"))
    .asText.map { it.trim() }.get()

// versionCode = MAJOR * 10000 + MINOR * 100 + PATCH ("1.2.3" -> 10203), so every release
// has a larger versionCode than the one before it.
fun versionCodeFor(version: String): Int {
    // At most 9 digits per part, so the arithmetic below cannot overflow a Long.
    val match = Regex("""(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""").matchEntire(version)
    requireNotNull(match) {
        "version.txt must hold MAJOR.MINOR.PATCH (e.g. 1.2.3), but it holds \"$version\""
    }
    val (major, minor, patch) = match.destructured.toList().map { it.toLong() }
    require(minor < 100 && patch < 100) {
        "MINOR and PATCH must each be below 100 to fit the versionCode scheme, but version.txt holds $version"
    }
    val code = major * 10_000 + minor * 100 + patch
    // Google Play's ceiling; the platform itself takes any positive Int.
    require(code in 1..2_100_000_000) {
        "version $version gives versionCode $code, outside 1..2100000000"
    }
    return code.toInt()
}

// CI provides the release keystore through the environment (see release.yml). Without it
// the release APK is left unsigned rather than signed with the debug key, which could never
// update an installed release. -Pwren.debugSignRelease=true opts in to a debug-signed
// release build for sideloading a minified build over a debug install.
fun signingEnv(name: String): String =
    requireNotNull(providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }) {
        "WREN_KEYSTORE_FILE is set, so $name must be set too"
    }

val releaseKeystore: String? =
    providers.environmentVariable("WREN_KEYSTORE_FILE").orNull?.takeIf { it.isNotEmpty() }
val debugSignRelease = providers.gradleProperty("wren.debugSignRelease").orNull.toBoolean()

android {
    namespace = "dev.yusufaf.wren"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.yusufaf.wren"
        minSdk = 30
        targetSdk = 36
        versionCode = versionCodeFor(appVersion)
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingEnv("WREN_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("WREN_KEY_ALIAS")
                keyPassword = signingEnv("WREN_KEY_PASSWORD")
            }
        }
    }

    // F-Droid and IzzyOnDroid cannot read the encrypted dependency blob AGP adds by default.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed, the APK is build/outputs/apk/release/app-release.apk, the path release.yml
            // verifies and uploads; unsigned, it is app-release-unsigned.apk.
            signingConfig = when {
                releaseKeystore != null -> signingConfigs.getByName("release")
                debugSignRelease -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":mail"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    // DefaultHostnameVerifier, the same check the trust manager makes; see CertificateProblems.kt.
    implementation(libs.apache.httpclient5)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
