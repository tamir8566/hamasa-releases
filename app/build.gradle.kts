import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

// CI passes -PbuildNumber=<GitHub run number>; local builds fall back to 1.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.tamir.hamasa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tamir.hamasa"
        // 29: the Samsung Health Data SDK (app/libs) requires Android 10+.
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "גרסה $buildNumber"
        // No AI key ever lives in the APK: every AI call goes through the Supabase "ai" function (data/ai/AiProxy),
        // which lets in only the recovery codes listed on the server. The fields stay, blank, for the direct clients.
        buildConfigField("String", "GEMINI_KEY", "\"\"")
        buildConfigField("String", "CLAUDE_KEY", "\"\"")
        vectorDrawables { useSupportLibrary = true }
    }

    // One permanent key for every build (debug and release), so each new version installs over the previous one.
    // The keystore and its passwords are NOT in this public repository: the build workflow fetches
    // app/hamasa.keystore and app/signing.properties from the owner's private repository (.github/workflows).
    val signingFile = file("signing.properties")
    val signingProps = Properties()
    if (signingFile.exists()) signingFile.inputStream().use { signingProps.load(it) }
    val hasSigning = signingFile.exists() && file("hamasa.keystore").exists()
    signingConfigs {
        if (hasSigning) {
            create("hamasa") {
                storeFile = file("hamasa.keystore")
                storePassword = signingProps.getProperty("storePassword", "")
                keyAlias = signingProps.getProperty("keyAlias", "hamasa")
                keyPassword = signingProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            // without the private files (a stranger's build) the default debug key signs: it builds, but cannot update the real app
            if (hasSigning) signingConfig = signingConfigs.getByName("hamasa")
            // Smaller APK = faster updates: R8 drops unused library code (the icons library is most of the size).
            // The app's own classes are kept whole and not renamed (app/proguard-rules.pro).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = false
            // without the private files (a stranger's build) the default debug key signs: it builds, but cannot update the real app
            if (hasSigning) signingConfig = signingConfigs.getByName("hamasa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // compressed dex inside the APK: a smaller download for every update (installs are unchanged)
        dex { useLegacyPackaging = true }
    }

    // JVM screenshot tests (Robolectric + Roborazzi): the unit tests need the real resources
    // (drawables, manifest) and native graphics rendering.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.systemProperty("robolectric.graphicsMode", "NATIVE")
                test.maxHeapSize = "2g"
                // which test runs / hangs is visible in the CI log
                test.testLogging { events("started", "passed", "failed", "skipped", "standard_out"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Stage 3: Galaxy Watch data through Health Connect (read only). 1.1.0 stable and its RCs need
    // compileSdk 36; 1.1.0-beta01 is the newest that builds with compileSdk 35 (docs/watch.md).
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")
    // Galaxy Watch data read DIRECTLY from Samsung Health (sleep, heart rate, workouts, steps; docs/watch.md).
    // The AAR is downloaded by hand from Samsung's developer site, see app/libs/README.md. Gson is its runtime
    // dependency (the AAR has no pom here); the parcelize plugin above supplies its @Parcelize runtime.
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation("com.google.code.gson:gson:2.13.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // ComponentActivity used by createComposeRule() in the screenshot tests (debug variant only).
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ---- JVM screenshot tests only (never shipped in the APK) ----
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.43.1")
}
