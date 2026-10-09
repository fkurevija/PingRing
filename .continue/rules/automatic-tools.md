---
globs: ['**/*']
description: Focused guidance for using tools on this Android project
---

This is an Android application written in Kotlin with Jetpack Compose.

- Search for the relevant implementation and tests before editing.
- Read only the files needed to understand the change; follow existing patterns.
- Make the smallest complete change. Do not rewrite whole files or edit unrelated code.
- Preserve Android 10 (API 29)+ behavior and existing localization.
- For behavior changes, add or update focused unit tests.
- Verify changes with `.\gradlew.bat testDebugUnitTest`; use `.\gradlew.bat uiTest` only when UI/device behavior requires it and a device is available.
- Summarize changed files and validation results; state clearly when a check could not be run.