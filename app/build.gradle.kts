import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// --------------------------------------------------------------------------
// Engine selection.
//
// NetValve routes real traffic through the gVisor netstack bridge (built as an
// AAR by ./netstack/build-aar.sh, which needs the Go toolchain + Android NDK).
// To keep the project buildable and testable WITHOUT that native toolchain, the
// packet engine lives behind the `PacketPipeline` interface and the concrete
// implementation is chosen by swapping source directories:
//
//   * default (no flag)         -> src/loopback/kotlin  (pure-Kotlin dev stub)
//   * -Pnetvalve.netstack=true  -> src/netstack/kotlin   (real gVisor engine)
//
// The loopback engine compiles and runs everywhere (CI, this reviewer's
// machine, instrumentation tests) but does NOT forward traffic upstream; it is
// a development/test double. Production builds MUST pass the flag after building
// the AAR. See docs/LIMITATIONS.md.
// --------------------------------------------------------------------------
val useNetstack: Boolean =
    (project.findProperty("netvalve.netstack") as String?)?.toBooleanStrictOrNull() ?: false

android {
    namespace = "dev.netvalve"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netvalve"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Debug builds use the default debug keystore. A release keystore can be
        // supplied via keystore.properties (kept out of version control).
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            create("release") {
                val props = Properties().apply { load(keystorePropsFile.inputStream()) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Swap the engine implementation source set based on the build flag.
    sourceSets {
        getByName("main") {
            if (useNetstack) {
                kotlin.srcDir("src/netstack/kotlin")
            } else {
                kotlin.srcDir("src/loopback/kotlin")
            }
        }
    }

    defaultConfig {
        buildConfigField("boolean", "USE_NETSTACK", useNetstack.toString())
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // ---- Netstack engine (only when the flag is on) --------------------------
    if (useNetstack) {
        // Produced by ./netstack/build-aar.sh into app/libs/netstack.aar
        implementation(files("libs/netstack.aar"))
    }

    // ---- Core ----------------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // ---- Compose -------------------------------------------------------------
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- Async / serialization ----------------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ---- Persistence ---------------------------------------------------------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)

    // ---- DI ------------------------------------------------------------------
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ---- Unit tests (plain JVM) ----------------------------------------------
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // ---- Instrumentation tests -----------------------------------------------
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
