# PingRing — testing and code conventions

Always active. The patterns below are inferred from the existing suite; follow
them so new tests read like the ones already there.

## Policy objects are pure, Android-free and exhaustively tested

`RangingSourcePolicy`, `ConnectionMethodPolicy`, `FocusPolicy`,
`WifiRttCorrelation` and their siblings hold a decision, take only data, and
return a result. Anything needing `Context` or `Build` is a separate `probe*`
function next to the object.

- **Sweep combinations, don't assert one outcome.** The high-value test here
  loops over every combination of the capability flags and asserts the
  *invariant* ("never select an unusable source"), so a later edit that reorders
  a `when` fails rather than quietly changing behaviour.
- **Two failure modes pull in opposite directions and both matter**: selecting
  a source the device cannot produce, and selecting a coarser one than it
  could. Assert the order of the fallback, not a single answer.
- **Every `describe()`/sentence enum gets an exhaustive sentence test.**
- **When adding a gate or an ordering rule, mutation-test it.** Revert the
  change and confirm the new tests actually fail. A test that passes with the
  fix removed is not testing anything.
- Name tests for the behaviour, in backticks: ``fun `a weak signal at close
  range means something is in the way`()``.
- **Explain the failure the test prevents in a comment.** The existing comments
  say *why* the threshold is -80 dBm or why 15 s, not what the code does. Match
  that — the reasoning is the durable part.

## Behaviour that cannot be asserted in a test

Say so in the docs rather than implying coverage. This repo has a
**Unverified — needs real hardware** section in `docs/roadmap.md` for exactly
this, and a passing unit test is not evidence a radio works.

## Code

- Match the surrounding file's voice: comments explain *why*, in full
  sentences, and argue the trade-off rather than restating the signature.
- New dependencies need a stated reason; the existing ones are deliberately few
  (see `docs/architecture.md` on offline operation).
- Run `.\gradlew.bat testDebugUnitTest assembleDebug` before claiming done.
  Note `lintDebug` currently fails on a **pre-existing** error in
  `PingRingCodeParser.kt` (`java.net.URLDecoder#decode` needs API 33) that is
  unrelated to most changes — confirm whether it is yours before touching it,
  and do not silently fix it as a drive-by.