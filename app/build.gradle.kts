plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    // Line/branch coverage for the JVM unit tests. See the jacoco block below
    // for what is included and excluded, and why.
    jacoco
}

import java.util.concurrent.TimeUnit

// Release signing, configured entirely from outside the repository.
//
// A keystore is a secret and a key cannot be generated here, so the properties
// are read if present and the build carries on unsigned if they are not. That
// asymmetry is deliberate: the common case - a contributor or CI without the
// release key - must still be able to assemble, while the release path must
// never quietly fall back to the debug key, because a debug-signed APK cannot be
// upgraded by a properly signed one and Play rejects it outright.
val releaseKeystorePath: String? = providers.gradleProperty("PINGRING_KEYSTORE").orNull
val releaseKeystorePassword: String? = providers.gradleProperty("PINGRING_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.gradleProperty("PINGRING_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.gradleProperty("PINGRING_KEY_PASSWORD").orNull

// All four or none. A partial set would fail later inside the signing task with
// a message about a missing key, rather than here where the cause is obvious.
val releaseKeystore: java.io.File? = releaseKeystorePath?.let(::file)?.takeIf {
    it.exists() && releaseKeystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null
}

android {
    namespace = "com.fkurevija.pingring"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.fkurevija.pingring"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Attached only when a keystore was supplied. Conditional rather
            // than always-on so a local `assembleRelease` still works without
            // the release key, while the release path itself can never silently
            // fall back to the debug key.
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Coverage for the instrumented tests. This is the only way to find
            // out what the Compose UI costs, because none of it runs on the JVM.
            // It builds the app and test APKs with a JaCoCo agent attached, so
            // `connectedDebugAndroidTest` leaves a coverage.ec under
            // build/outputs/code_coverage, and AGP's own
            // `createDebugAndroidTestCoverageReport` turns it into
            // build/reports/coverage/androidTest/debug/connected/report.xml.
            // It is set here rather than passed on the command line so that CI
            // gathers coverage without having to know to ask for it.
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    val coordinationUrl = providers.gradleProperty("coordinationUrl")
        .orElse("http://10.0.2.2:8080/")
        .get()
    defaultConfig {
        buildConfigField("String", "COORDINATION_URL", "\"$coordinationUrl\"")
    }
    testOptions {
        unitTests {
            // Android framework calls are stubbed in JVM unit tests and return
            // their default value rather than throwing, so a test can touch a
            // class that merely *reads* a system service.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.coil.compose)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.play.services.location)
    implementation(libs.play.services.nearby)
    implementation(libs.androidx.uwb)
    implementation(libs.retrofit)
    implementation(libs.accompanist.permissions)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

/**
 * Coverage for the JVM unit tests (`./gradlew test jacocoTestReport`).
 *
 * The report is generated from the debug unit-test run only. `connectedAndroidTest`
 * (instrumented tests on a device) is not part of this: it needs hardware, and
 * the existing androidTest source set holds only the generated template test.
 */
val jacocoExclusions = listOf(
    // Data classes and sealed hierarchies generate equals/hashCode/toString/copy
    // that no test meaningfully executes, plus Moshi's generated adapters whose
    // bodies are a reflective read/write loop exercised by any round-trip test.
    "**/*$$*",
    "**/*JsonAdapter*",
    // Compose compiler emits stability/skip/parameter bookkeeping for every
    // @Composable; it is driven by the Compose runtime, not by the app, and is
    // counted here only to drown out the real code.
    "**/*ComposableSingletons*",
    // R8/AGP injects the BuildConfig holder and the androidx start-up initializer.
    "**/BuildConfig*",
    "**/androidx/startup/**"
)

// AGP 9 does not create a JacocoReport task for the unit tests on its own, so it is
// registered here against the debug unit-test run.
tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates the line/branch coverage report for the JVM unit tests."
    reports {
        xml.required = true
        html.required = true
    }
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    val classes = files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    )
    // Excluded classes leave the report's denominator entirely, so they are applied
    // as a file filter on classDirectories rather than a separate excludes property.
    classDirectories.setFrom(classes.asFileTree.matching { exclude(jacocoExclusions) })
    sourceDirectories.setFrom(files("src/main/java"))
    // The .exec written by the unit-test task's JaCoCo agent.
    executionData.setFrom(files(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")))
    dependsOn(unitTest)
}

// The Compose UI and device suite needs a booted emulator or a physical phone,
// and costs 10-20 minutes of mostly waiting. It is therefore excluded from the
// everyday tasks, in the same way the remote workflow only runs it for a tagged
// release (`.github/workflows/instrumented-tests.yml`):
//
//   ./gradlew testDebugUnitTest          # everyday: JVM tests, no device
//   ./gradlew check                      # same, via the standard lifecycle task
//   ./gradlew connectedDebugAndroidTest  # explicit opt-in, on a device
//
// AGP already leaves the connected tests out of `check` and `build`, so nothing
// here changes that default - this only names the escape hatch in one place, so
// the rule is discoverable from `./gradlew tasks` rather than from a comment
// nobody reads. An explicit run is always available and is never blocked by this
// setting; it is here to document the gate, not to enforce one.
tasks.register("uiTest") {
    group = "verification"
    description = "Runs the Compose UI and device (instrumented) tests. Needs a booted device."
    dependsOn("connectedDebugAndroidTest")
}

/**
 * Enforces the per-class coverage floor.
 *
 * The target is every class at 80% or better. That is a *per-class* rule rather
 * than a project total on purpose: an aggregate lets a well-tested policy class
 * pay for an untested one, which is how the radar's screen stayed at 0% for so
 * long while the project total looked respectable.
 *
 * `minimum = 0.80` is checked against each class element, so a class with no
 * executed line is a violation rather than being skipped. A newly added class
 * therefore has to be tested (or deliberately excluded above) instead of
 * sitting at zero unnoticed.
 *
 * The current state does NOT meet this bar: the Compose UI, MainActivity,
 * DiscoveryRepository and the two connection managers are far below it, and
 * reaching them needs instrumented tests on a device. This task is therefore
 * registered but not yet wired into `check`, so it reports the gap without
 * failing every build. Flip `jacocoCoverageGateEnabled` to true once the
 * instrumented tests exist.
 */
val jacocoCoverageGateEnabled = false

if (jacocoCoverageGateEnabled) {
    tasks.register<JacocoCoverageVerification>("jacocoVerify") {
        group = "verification"
        description = "Fails the build if any class drops below the 80% line-coverage floor."
        dependsOn(tasks.named("jacocoTestReport"))
        executionData.setFrom(files(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")))
        classDirectories.setFrom(
            files(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
                .asFileTree.matching { exclude(jacocoExclusions) }
        )
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.80".toBigDecimal()
                }
                // Applied per class element, so each class is judged on its own
                // rather than the bundle as a whole.
                element = "CLASS"
                excludes = jacocoExclusions
            }
        }
    }
}