plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val selectedToolchainId = providers.gradleProperty("leanToolchainId").orElse("lean-4.32.1-android1").get().also {
    require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid leanToolchainId: $it" }
}
val packagedSysrootBytes = providers.gradleProperty("leanPackagedSysrootBytes").orElse("2194903155").get().also {
    require(it.toLongOrNull()?.let { value -> value > 0 } == true) { "Invalid leanPackagedSysrootBytes: $it" }
}
val runtimeManifestSha256 = providers.gradleProperty("leanRuntimeManifestSha256")
    .orElse("f7e389dcee7bd8f146fcd9e7f05ca6ddc9243bd3e99e3261a5dee79e7d1aa797")
    .get()
    .also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid leanRuntimeManifestSha256: $it" } }

android {
    namespace = "org.lean4android.toolchain"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "TOOLCHAIN_ID", "\"$selectedToolchainId\"")
        // Keep synchronized with: du -sb app/build/generated/toolchain/assets/toolchain
        buildConfigField("long", "PACKAGED_SYSROOT_BYTES", "${packagedSysrootBytes}L")
        // SHA-256 of generated assets/toolchain-manifest.tsv.
        buildConfigField("String", "RUNTIME_MANIFEST_SHA256", "\"$runtimeManifestSha256\"")
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
