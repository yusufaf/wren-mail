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

// "1.2.3" -> 10203, so every release has a larger versionCode than the one before it.
// MINOR and PATCH get two digits each, so the pattern only admits values below 100.
fun versionCodeFor(version: String): Int {
    val match = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(version)
    require(match != null) {
        "version.txt must be MAJOR.MINOR.PATCH with MAJOR up to 9999 and MINOR and PATCH " +
            "below 100 (e.g. 1.2.3), but it holds \"$version\""
    }
    val (major, minor, patch) = match.destructured
    val code = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    require(code > 0) { "version $version gives versionCode 0, which Android rejects" }
    return code
}

// CI provides the release keystore through the environment; locally these are unset and
// the release build stays debug-signed, so it can still be sideloaded over a debug build.
fun signingEnv(name: String) = providers.environmentVariable(name)

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
        val signingVars = listOf(
            "WREN_KEYSTORE_FILE", "WREN_KEYSTORE_PASSWORD", "WREN_KEY_ALIAS", "WREN_KEY_PASSWORD",
        )
        val missing = signingVars.filterNot { signingEnv(it).isPresent }
        // All or nothing: a partial set is a misconfiguration, not a request for a debug build.
        check(missing.isEmpty() || missing.size == signingVars.size) {
            "Release signing needs all of ${signingVars.joinToString()}; missing: ${missing.joinToString()}"
        }
        if (missing.isEmpty()) {
            create("release") {
                storeFile = file(signingEnv("WREN_KEYSTORE_FILE").get())
                storePassword = signingEnv("WREN_KEYSTORE_PASSWORD").get()
                keyAlias = signingEnv("WREN_KEY_ALIAS").get()
                keyPassword = signingEnv("WREN_KEY_PASSWORD").get()
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
