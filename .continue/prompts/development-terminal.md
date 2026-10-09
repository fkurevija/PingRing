---
name: development-terminal
description: Run a focused Android build or test command
---

Choose the narrowest Gradle command that verifies the current task. For unit tests, run `.\gradlew.bat testDebugUnitTest`; for Compose/device tests, use `.\gradlew.bat uiTest` only when a booted device is available. Do not install dependencies, download models, or run destructive commands without asking. Report the command and its actual result.