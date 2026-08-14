plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.lean4android.toolchain"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "TOOLCHAIN_ID", "\"lean-4.32.1-android1\"")
        // Keep synchronized with: du -sb app/build/generated/toolchain/assets/toolchain
        buildConfigField("long", "PACKAGED_SYSROOT_BYTES", "2194903155L")
        // SHA-256 of generated assets/toolchain-manifest.tsv.
        buildConfigField("String", "RUNTIME_MANIFEST_SHA256", "\"f7e389dcee7bd8f146fcd9e7f05ca6ddc9243bd3e99e3261a5dee79e7d1aa797\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-process"))
    testImplementation(libs.junit)
}
