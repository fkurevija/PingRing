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
 * The report is generated from the debug unit-test run only. The instrumented
 * suite is a *separate* dataset and is deliberately not merged in: it needs a
 * booted device, and this repo keeps `check` device-free so an ordinary build
 * needs no emulator. The androidTest source set is substantial - 18 classes,
 * 161 tests - and is what covers the Compose screens, so the absence of a
 * merged number here is a measurement boundary, not a gap in the tests.
 *
 * See [coverageGateExclusions] for why the per-class floor is not applied to
 * everything this report measures.
 */
val jacocoExclusions = listOf(
    // Generated inner classes: Kotlin lambdas and anonymous objects compile to
    // `Owner$lambdaName$1`, and data-class `Companion`/`Decoded` members to
    // `Owner$Companion`. Neither is a class a test can meaningfully cover, and
    // leaving them in counts 139 lambda classes as violations.
    //
    // This was `"**/*$$*"` and matched exactly zero classes, because `$$` is
    // Scala's inner-class convention, not Kotlin's. Kotlin emits a single `$`.
    // The pattern was doing nothing at all while every generated lambda in the
    // project was being reported as an uncovered class.
    "**/*$*",
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
 * Make `check` fail when unit-testable logic drops below the floor.
 *
 * The gate is registered below and was originally left unwired, which is how a
 * configured 80% rule came to be permanently off. Wiring it to `check` rather
 * than to `build` keeps the everyday lifecycle task honest: AGP already excludes
 * the connected tests from both, so this is the one verification task that now
 * fails for untested logic rather than merely reporting the gap.
 *
 * It is cheap - it reuses the execution data the unit-test task has already
 * written and adds no second test run.
 */
tasks.named("check") {
    dependsOn("jacocoVerify")
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
 * ## Why the floor is scoped rather than universal
 *
 * This gate reads the *unit-test* execution data only, and that boundary
 * decides what it can honestly assert. Two groups of class are reachable only
 * from a device, and the instrumented suite - not this data - is what covers
 * them:
 *
 *  - the Compose screens, which need a real composition and a real context;
 *  - the framework-bound plumbing, which needs a `BluetoothAdapter`, a
 *    `Vibrator`, a `PackageManager` or a `TelephonyManager` to exist at all.
 *
 * Held to an 80% floor on unit data alone they would fail at 0% while being
 * thoroughly tested, which would be a gate that cries wolf. So they are
 * excluded, and what remains is the code a JVM test *can* reach: the pure
 * decision objects, which is where the untested-logic risk actually lives.
 *
 * The list below is deliberately explicit and rots visibly: a new
 * framework-bound class fails this gate until somebody names it here with a
 * reason. That friction is the point. The alternative - guessing at
 * reachability - is what lets a genuinely untested policy class slip through
 * dressed as plumbing.
 */
val jacocoCoverageGateEnabled = true

/**
 * What the 80% floor is applied to, on top of the generated-code exclusions.
 *
 * Everything listed here is exercised by the instrumented suite and is reported
 * by `createDebugAndroidTestCoverageReport`; none of it is testable from a JVM.
 */
val coverageGateExclusions = jacocoExclusions + listOf(
    // Compose UI. Covered by the instrumented suite.
    "**/ui/screens/**",
    "**/ui/theme/**",
    "**/ui/accessibility/**",
    "**/ui/permissions/**",
    "**/ui/navigation/**",
    // Framework-bound plumbing: each needs a live Android framework object that
    // does not exist off-device. Reach for instrumented tests, not a JVM mock.
    "**/data/DiscoveryRepository*",
    "**/data/NearbyConnectionsManager*",
    "**/data/LanDebugConnectionsManager*",
    "**/data/WifiAwareRangingManager*",
    "**/data/GnssRangingManager*",
    "**/data/ChatNotifier*",
    "**/data/AppearanceSettingsRepository*",
    "**/data/DeviceOrientationTracker*",
    "**/data/ForegroundPresenceTracker*",
    "**/data/QrImageAnalyzer*",
    "**/data/DiagnosticsProbe*",
    "**/data/DeviceEnvironment*",
    "**/data/PingRingRuntime*",
    "**/data/PingRingService*",
    "**/MainActivity*",
    "**/DeviceEnvironment*",
    "**/data/RangingSourceProbe*",
    "**/data/BleRangeProbe*",
    "**/data/RadarAccessibilityProbe*",
    // Type declarations with no executable logic to cover. JaCoCo counts the
    // constructor or the abstract members as uncovered lines, which reads as 0%
    // for a class that has no behaviour at all.
    "**/data/NearbyTransport*",
    "**/data/LastKnownPeerDirection*",
)

if (jacocoCoverageGateEnabled) {
    tasks.register<JacocoCoverageVerification>("jacocoVerify") {
        group = "verification"
        description = "Fails the build if any unit-testable class drops below the 80% line-coverage floor."
        dependsOn(tasks.named("jacocoTestReport"))
        executionData.setFrom(files(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")))
        classDirectories.setFrom(
            files(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
                .asFileTree.matching { exclude(coverageGateExclusions) }
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
                excludes = coverageGateExclusions
            }
        }
    }
}