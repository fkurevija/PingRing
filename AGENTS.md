# AGENTS.md

Drop-in operating instructions for coding agents. Read this file before every task.

**Working code only. Finish the job. Plausibility is not correctness.**

This file follows the [AGENTS.md](https://agents.md) open standard (Linux Foundation / Agentic AI Foundation).

---

## 0. Non-negotiables

These rules override everything else in this file when in conflict:

1. **No flattery, no filler.** Skip openers like "Great question", "You're absolutely right", "Excellent idea", "I'd be happy to". Start with the answer or the action.
2. **Disagree when you disagree.** If the user's premise is wrong, say so before doing the work. Agreeing with false premises to be polite is the single worst failure mode in coding agents.
3. **Never fabricate.** Not file paths, not commit hashes, not API names, not test results, not library functions. If you don't know, read the file, run the command, or say "I don't know, let me check."
4. **Stop when confused.** If the task has two plausible interpretations, ask. Do not pick silently and proceed.
5. **Touch only what you must.** Every changed line must trace directly to the user's request. No drive-by refactors, reformatting, or "while I was in there" cleanups.
6. **Never assume, never guess, always validate.** A mechanism you have not observed is a hypothesis, not a fact — never state it as one, and never build a fix on top of it. When behaviour surprises you, get evidence *before* theorising: read the actual source, capture real logs (`adb logcat`), and reproduce it.
7. **Check the latest version before adding a dependency.** When adding any package or library, look up its current stable version first and integrate it deliberately.

---

## 1. Before writing code

**Goal: understand the problem and the codebase before producing a diff.**

- State your plan in one or two sentences before editing. For anything non-trivial, produce a numbered list of steps with a verification check for each.
- Read the files you will touch. Read the files that call the files you will touch.
- Match existing patterns in the codebase.
- Surface assumptions out loud.

### The planning cycle

For anything beyond a trivial fix, plan before you touch code:
1. **Write the plan** to `plans/` (gitignored) or present the numbered steps. State the goal, steps, and verification.
2. **Review adversarially** before implementing.
3. **Keep the plan current** as you implement.

---

## 2. Writing code: simplicity first

**Goal: the minimum code that solves the stated problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- Handle failures that can actually happen.
- Bias toward deleting code over adding code.

---

## 3. Surgical changes

**Goal: clean, reviewable diffs. Change only what the request requires.**

- Do not "improve" adjacent code, comments, formatting, or imports that are not part of the task.
- Match the project's existing Kotlin / Jetpack Compose style exactly.

---

## 4. Goal-driven execution

**Goal: define success as something you can verify, then loop until verified.**

- State success criteria before writing code.
- Run tests and builds to verify changes (`./gradlew testDebugUnitTest assembleDebug`). Do not claim success without checking output.

---

## 5. Tool use and verification

- Prefer running the code and tests to guessing.
- Never report "done" based on a plausible-looking diff alone. Plausibility is not correctness.
- When debugging, address root causes, not symptoms.

---

## 6. Communication style

- Direct, not diplomatic.
- Concise by default. No padding, no ceremonial closings.
- No excessive bullet points, no unprompted headers, no emoji.

---

## 7. Project context

PingRing — Local-first, offline-first peer-to-peer mesh Indoor Positioning System (IPS), Proximity Detection, and Off-Grid Communication Android application.

### Stack

- **Language**: Kotlin 2.0+ (Strict null safety, coroutines, flows).
- **UI**: Jetpack Compose, Material 3, Navigation 3, Edge-to-edge layout.
- **Architecture**: MVVM, Kotlin Coroutines, StateFlow, Clean Architecture policies and pure decision objects.
- **Connectivity & Sensors**: Nearby Connections (`P2P_CLUSTER`), BLE discovery & extended advertising, Jetpack UWB (`androidx.core.uwb`), Wi-Fi RTT / Wi-Fi Aware, GNSS, NFC HCE Tap-to-Pair.
- **Persistence**: DataStore, Room Database.
- **Testing**: JUnit 4, Kotlin Coroutines Test, Compose UI Testing, JaCoCo Code Coverage (80% per-class floor).

### Commands

- Build Debug APK: `./gradlew assembleDebug`
- Run Unit Tests: `./gradlew testDebugUnitTest`
- Run Instrumented/UI Tests: `./gradlew uiTest` (requires a booted device/emulator)
- Generate Coverage Report: `./gradlew app:jacocoTestReport`
- Run Android Lint: `./gradlew lintDebug`

### Layout

```
app/src/main/java/com/combustiblelemonlabs/pingring/
  MainActivity.kt
  data/
    AppearanceSettingsRepository.kt
    DiscoveryRepository.kt
    NearbyConnectionsManager.kt
    LanDebugConnectionsManager.kt
    AdvancedSensorFusionFilter.kt
    MultiPathMitigationPolicy.kt
    PolicyVisualizationPolicy.kt
    DataExportManager.kt
    RangingSourcePolicy.kt
    RangingSourceProbe.kt
    BleRangePolicy.kt
    BleRangeProbe.kt
    RadarAccessibilityPolicy.kt
    RadarAccessibilityProbe.kt
    RadioPowerPolicy.kt
    ChatModels.kt
    nfc/
      PingRingHostApduService.kt
      NfcInviteExchange.kt
      NfcInviteApdu.kt
    session/
  ui/
    navigation/
      NavKeys.kt
    screens/
      RadarScreen.kt
      RadarCompactChatPane.kt
      RadarChatCard.kt
      AppSettingsScreen.kt
      PingRingSettingsScreen.kt
      ConnectionSettingsScreen.kt
      NotificationSettingsScreen.kt
      AccessibilitySettingsScreen.kt
      DeveloperSettingsScreen.kt
      HostSetupScreen.kt
      PingRingJoinScreen.kt
      AboutScreen.kt
      NfcJoinControl.kt
      PingRingCodeBlock.kt
      PingRingCodeParser.kt
    theme/
```

---

## 8. Forbidden

- Editing generated files or build directories manually.
- Blindly guessing root causes without checking logs or tests.
- Pushing to a remote repository without explicit approval.
