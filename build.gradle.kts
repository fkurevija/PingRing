import java.util.concurrent.TimeUnit

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Runs adb reverse and the debug-only LAN relay server (tools/lan_debug_relay.py)
tasks.register("runLanDebugRelay") {
    group = "debug tools"
    description = "Automatically runs adb reverse and tools/lan_debug_relay.py for physical device testing."

    val projectDir = layout.projectDirectory

    doFirst {
        val rootDirPath = projectDir.asFile.absolutePath
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val adbExecutablePath = if (androidHome != null) {
            val osName = System.getProperty("os.name").lowercase()
            val adbName = if (osName.contains("win")) "platform-tools/adb.exe" else "platform-tools/adb"
            val adbFile = File(androidHome, adbName)
            if (adbFile.exists()) adbFile.absolutePath else "adb"
        } else {
            "adb"
        }

        try {
            println("[PingRing] Executing adb reverse tcp:8942 tcp:8942...")
            ProcessBuilder(adbExecutablePath, "reverse", "tcp:8942", "tcp:8942")
                .directory(File(rootDirPath))
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            println("[PingRing] Warning: adb reverse failed (is device connected?): ${e.message}")
        }
    }
    doLast {
        val rootDirPath = projectDir.asFile.absolutePath
        val relayScript = File(rootDirPath, "tools/lan_debug_relay.py")
        if (relayScript.exists()) {
            val osName = System.getProperty("os.name").lowercase()
            val pythonCmd = if (osName.contains("win")) "python" else "python3"
            println("[PingRing] Launching LAN debug relay server in a new terminal window...")

            val pb = if (osName.contains("win")) {
                ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", pythonCmd, relayScript.absolutePath, "8942")
            } else if (osName.contains("mac") || osName.contains("os x")) {
                ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \"cd \\\"$rootDirPath\\\" && $pythonCmd tools/lan_debug_relay.py 8942\"")
            } else {
                val terminals = listOf("gnome-terminal", "konsole", "xterm")
                val terminal = terminals.firstOrNull {
                    try { ProcessBuilder("which", it).start().waitFor() == 0 } catch (_: Exception) { false }
                }
                when (terminal) {
                    "gnome-terminal" -> ProcessBuilder("gnome-terminal", "--", "bash", "-c", "cd \"$rootDirPath\" && $pythonCmd tools/lan_debug_relay.py 8942; exec bash")
                    "konsole" -> ProcessBuilder("konsole", "-e", "bash", "-c", "cd \"$rootDirPath\" && $pythonCmd tools/lan_debug_relay.py 8942; exec bash")
                    else -> ProcessBuilder("xterm", "-e", "bash", "-c", "cd \"$rootDirPath\" && $pythonCmd tools/lan_debug_relay.py 8942; exec bash")
                }
            }
            pb.directory(File(rootDirPath))
              .start()
        }
    }
}
