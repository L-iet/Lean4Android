plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val selectedToolchainId = providers.gradleProperty("leanToolchainId").orElse("lean-4.32.1-android1")
val isolatedToolchainCandidate = providers.gradleProperty("isolatedToolchainCandidate")
    .map(String::toBooleanStrict)
    .orElse(false)
val selectedToolchainIdValue = selectedToolchainId.get().also {
    require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid leanToolchainId: $it" }
}
val toolchainDistribution = rootProject.layout.projectDirectory.dir("toolchain/output/$selectedToolchainIdValue")
val generatedToolchain = layout.buildDirectory.dir("generated/toolchain/$selectedToolchainIdValue")
val playAssetDelivery = providers.gradleProperty("playAssetDelivery").map(String::toBoolean).orElse(false)

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

val writeFilteredToolchainManifest by tasks.registering(Exec::class) {
    dependsOn(stageToolchainSysroot)
    val stagedRoot = generatedToolchain.map { it.dir("assets/toolchain") }
    val output = generatedToolchain.map { it.file("assets/toolchain-manifest.tsv") }
    inputs.file(toolchainDistribution.file("manifest.json"))
    inputs.dir(stagedRoot)
    outputs.file(output)
    commandLine(
        "python3",
        rootProject.layout.projectDirectory.file("toolchain/scripts/write-filtered-manifest.py").asFile,
        "--source-manifest",
        toolchainDistribution.file("manifest.json").asFile,
        "--staged-root",
        stagedRoot.get().asFile,
        "--output",
        output.get().asFile,
    )
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
        buildConfigField("String", "TOOLCHAIN_ID", "\"$selectedToolchainIdValue\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes.named("debug") {
        if (isolatedToolchainCandidate.get()) {
            require(selectedToolchainIdValue != "lean-4.32.1-android1") {
                "isolatedToolchainCandidate requires a non-default leanToolchainId"
            }
            applicationIdSuffix = ".android2candidate"
            versionNameSuffix = "-android2candidate"
        }
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
        if (!playAssetDelivery.get()) assets.srcDir(generatedToolchain.map { it.dir("assets") })
    }
    if (playAssetDelivery.get()) assetPacks += ":core_toolchain_pack"
}

tasks.named("preBuild").configure {
    dependsOn(stageToolchainNative, writeFilteredToolchainManifest)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-lsp"))
    implementation(project(":core-process"))
    implementation(project(":core-project"))
    implementation(project(":core-toolchain"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
