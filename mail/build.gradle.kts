plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    api(libs.jetbrains.annotations)

    implementation(libs.androidx.annotation)
    implementation(libs.mime4j.core)
    implementation(libs.mime4j.dom)
    implementation(libs.okio)
    implementation(libs.commons.io)
    implementation(libs.moshi)

    // Only used for DefaultHostnameVerifier, mirroring upstream.
    implementation(libs.apache.httpclient5)

    implementation(libs.jzlib)
    implementation(libs.jutf7)
}
