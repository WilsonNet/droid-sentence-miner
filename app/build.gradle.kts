plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

// Static analysis: every detekt rule (incl. ktlint formatting and Compose rules), warnings fail
// the build. Findings that predate the linter live in detekt-baseline.xml; new code must be clean.
// Regenerate only after fixing old findings: ./gradlew detektBaseline (see docs/building.md).
detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
    // ./gradlew detekt -PdetektAutoCorrect applies ktlint's formatting fixes (the ktlint CLI can't be downloaded here).
    autoCorrect = providers.gradleProperty("detektAutoCorrect").isPresent
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

android {
    namespace = "io.github.wilsonnet.droidsentenceminer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.wilsonnet.droidsentenceminer"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionName/-PversionCode from the git tag (.github/workflows/release.yml).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Phones/handhelds (arm) + the x86_64 emulator; 32-bit x86 would add ~52 MB of native OCR libs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Committed debug key: debug builds from any machine can upgrade each other in place.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key comes from the environment (GitHub secrets in CI); unset ⇒ unsigned release APK.
        System.getenv("DSM_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("DSM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSM_KEY_ALIAS")
                keyPassword = System.getenv("DSM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Android Lint at its strictest: every check (also the ones off by default), warnings are
    // errors. Existing findings are in lint-baseline.xml; ./gradlew lintDebug fails on new ones.
    lint {
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        checkTestSources = true
        baseline = file("lint-baseline.xml")
    }
}

// CHANGELOG.md is bundled as an asset for the one-time "What's new" dialog (update/WhatsNew.kt).
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.mkdirs()
        changelog.get().asFile.copyTo(File(out, "CHANGELOG.md"), overwrite = true)
    }
}

val copyChangelog = tasks.register<CopyChangelog>("copyChangelog") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.mlkit.japanese)
    implementation(libs.onnxruntime.android)
    implementation(libs.ankidroid.api)

    detektPlugins(libs.detekt.ktlint.wrapper)
    detektPlugins(libs.compose.rules.detekt)

    testImplementation(libs.junit)
    testImplementation(libs.konsist)
    // Property-based tests (generators + shrinking) for parsers, scanners and mappers: docs/building.md.
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
