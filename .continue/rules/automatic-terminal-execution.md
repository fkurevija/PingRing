---
globs: ['**/*']
description: Safe, focused terminal use for Android development
---

Use terminal commands when needed to verify a change, inspect build/test results, or diagnose the Android toolchain. Prefer the narrowest relevant Gradle task; unit tests are run with `.\gradlew.bat testDebugUnitTest`.

Do not run destructive file or Git commands, publish changes, install dependencies, or download large models without asking first. Never treat a failed command as a successful check.