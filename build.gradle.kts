// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Runs the debug-only LAN relay server (tools/lan_debug_relay.py) used to
// bridge chat/discovery between the Android Emulator and a physical device
// when the app's "Use LAN Debug Transport" developer setting is enabled.
// Kept running for the lifetime of the task since the relay serves forever;
// intended to be launched via the "LAN Debug Relay" run configuration,
// typically alongside the "app" configuration in a Compound configuration.
tasks.register<Exec>("runLanDebugRelay") {
    group = "debug tools"
    description = "Runs tools/lan_debug_relay.py so the Emulator and a physical device can exchange PingRing messages during testing."
    workingDir = rootDir
    commandLine("python", "tools/lan_debug_relay.py")
}