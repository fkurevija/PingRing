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
        val osName = System.getProperty("os.name").lowercase()
        val adbName = if (osName.contains("win")) "platform-tools/adb.exe" else "platform-tools/adb"

        // Android Studio does not put the SDK's platform-tools on the PATH of a
        // Gradle daemon, and ANDROID_HOME/ANDROID_SDK_ROOT are often unset
        // there even when they are set in the shell that launched Studio. So
        // the env vars are only the first place to look: local.properties is
        // what Android Studio itself writes and always keeps current, and
        // $HOME/Library/Android/sdk is the macOS default.
        val sdkRoots = buildList {
            System.getenv("ANDROID_HOME")?.let { add(it) }
            System.getenv("ANDROID_SDK_ROOT")?.let { add(it) }
            File(rootDirPath, "local.properties")
                .takeIf { it.isFile }
                ?.let { props ->
                    // Values are escaped for a .properties file (\: and \\), so
                    // unescape before using the path.
                    runCatching {
                        java.util.Properties().apply { props.inputStream().use { load(it) } }
                            .getProperty("sdk.dir")
                            ?.replace("\\:", ":")
                            ?.replace("\\\\", "\\")
                    }.getOrNull()
                }
                ?.let { add(it) }
            if (osName.contains("mac")) {
                System.getProperty("user.home")?.let { add("$it/Library/Android/sdk") }
            }
        }.distinct()

        val adbExecutablePath = sdkRoots
            .map { File(it, adbName) }
            .firstOrNull { it.isFile }
            ?.absolutePath
            ?: "adb"

        /**
         * Runs `adb <args>` and returns its trimmed stdout, or null if adb could
         * not be run or exited non-zero.
         *
         * The exit code matters and used to be thrown away: `adb reverse`
         * without `-s` fails with "more than one device/emulator" whenever an
         * emulator and a physical phone are both attached, which is exactly the
         * setup the LAN debug transport exists for. The failure was invisible
         * because a non-zero exit is not an exception, so the tunnel silently
         * never got created and the phone could not reach the relay.
         */
        fun adb(vararg args: String): String? = try {
            val process = ProcessBuilder(listOf(adbExecutablePath) + args)
                .directory(File(rootDirPath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else if (process.exitValue() == 0) {
                output.trim()
            } else {
                println("[PingRing] adb ${args.joinToString(" ")} failed: ${output.trim()}")
                null
            }
        } catch (e: Exception) {
            println("[PingRing] adb ${args.joinToString(" ")} could not run: ${e.message}")
            null
        }

        // One tunnel per device, addressed explicitly by serial. `adb reverse`
        // with no -s is ambiguous as soon as two targets are attached, and it
        // would only ever have configured whichever one adb happened to pick.
        //
        // Emulators are included: they normally reach the relay through
        // 10.0.2.2, so the tunnel is harmless there, and it means a device that
        // falls back to 127.0.0.1 still works.
        val devices = adb("devices")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("*") }
            ?.mapNotNull { line ->
                val serial = line.split(Regex("\\s+")).firstOrNull()
                val state = line.split(Regex("\\s+")).getOrNull(1)
                if (serial != null && state == "device") serial else null
            }
            ?.toList()
            .orEmpty()

        if (devices.isEmpty()) {
            println("[PingRing] No adb devices in state 'device'. Attach a phone or emulator, then run this again.")
        } else {
            var configured = 0
            devices.forEach { serial ->
                if (adb("-s", serial, "reverse", "tcp:8942", "tcp:8942") != null) {
                    println("[PingRing] adb reverse tcp:8942 set on $serial")
                    configured++
                } else {
                    println("[PingRing] Could not set adb reverse on $serial; it cannot reach the relay on 127.0.0.1")
                }
            }
            println("[PingRing] adb reverse configured on $configured of ${devices.size} device(s): ${devices.joinToString(", ")}")
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
