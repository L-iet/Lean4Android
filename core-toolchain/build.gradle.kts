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
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-model"))
    testImplementation(libs.junit)
}

