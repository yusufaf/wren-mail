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
// TODO(you): implement. Accept only MAJOR.MINOR.PATCH, and fail the build with a clear
// message for anything else or when MINOR or PATCH is 100 or more (they would collide).
fun versionCodeFor(version: String): Int = TODO("versionCodeFor")

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
        if (signingEnv("WREN_KEYSTORE_FILE").isPresent) {
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
