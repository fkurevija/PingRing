import java.util.concurrent.TimeUnit

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

fun getAdbExecutable(): String {
    val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (androidHome != null) {
        val osName = System.getProperty("os.name").lowercase()
        val adbName = if (osName.contains("win")) "platform-tools/adb.exe" else "platform-tools/adb"
        val adbFile = File(androidHome, adbName)
        if (adbFile.exists()) return adbFile.absolutePath
    }
    return "adb"
}

// Runs adb reverse and the debug-only LAN relay server (tools/lan_debug_relay.py)
tasks.register("runLanDebugRelay") {
    group = "debug tools"
    description = "Automatically runs adb reverse and tools/lan_debug_relay.py for physical device testing."
    doFirst {
        val adb = getAdbExecutable()
        try {
            println("[PingRing] Executing adb reverse tcp:8942 tcp:8942...")
            ProcessBuilder(adb, "reverse", "tcp:8942", "tcp:8942")
                .directory(rootDir)
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            println("[PingRing] Warning: adb reverse failed (is device connected?): ${e.message}")
        }
    }
    doLast {
        val relayScript = File(rootDir, "tools/lan_debug_relay.py")
        if (relayScript.exists()) {
            val pythonCmd = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
            println("[PingRing] Starting LAN debug relay server...")
            ProcessBuilder(pythonCmd, relayScript.absolutePath, "8942")
                .directory(rootDir)
                .start()
                .waitFor()
        }
    }
}
