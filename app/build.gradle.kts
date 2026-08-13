plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val toolchainDistribution = rootProject.layout.projectDirectory.dir(
    "toolchain/output/lean-4.32.1-android1",
)
val generatedToolchain = layout.buildDirectory.dir("generated/toolchain")

val stageToolchainNative by tasks.registering(Sync::class) {
    from(toolchainDistribution.dir("native"))
    into(generatedToolchain.map { it.dir("jniLibs") })
}

val stageToolchainSysroot by tasks.registering(Sync::class) {
    from(toolchainDistribution.dir("sysroot")) {
        exclude(
            "**/*.a",
            "**/*.export",
            "**/*.o",
            "**/*.c",
            "**/*.depend",
            "**/*.so",
        )
    }
    into(generatedToolchain.map { it.dir("assets/toolchain") })
}

android {
    namespace = "org.lean4android.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.lean4android.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/*.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets.named("main") {
        jniLibs.srcDir(generatedToolchain.map { it.dir("jniLibs") })
        assets.srcDir(generatedToolchain.map { it.dir("assets") })
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageToolchainNative, stageToolchainSysroot)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-process"))
    implementation(project(":core-toolchain"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
