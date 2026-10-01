# PingRing — documentation is part of the change

Always active. No `paths:` frontmatter on purpose: the trigger is *changing
code*, not *editing docs*, so a conditional rule would fire at exactly the
wrong moment.

## The rule

**If a change alters what PingRing does, what it can do, or what it cannot do,
update the docs in the same change — before it is committed.**

A commit that changes behaviour and leaves the prose describing the old
behaviour is unfinished, not merely untidy. The docs in this repo are the only
record of *why* something is the way it is, and they are written in a voice
that commits to claims. An out-of-date claim is worse than a missing one,
because a reader has no way to tell which parts are still true.

## Which docs, and when

| Changed | Update |
|---|---|
| Behaviour visible to a user, or a new/removed capability | `README.md` (Key Features), `docs/features.md`, `docs/user-guide.md` |
| A design decision, or the reasoning behind one | `docs/architecture.md` |
| Known gaps, unverified claims, deliberately bounded work | `docs/roadmap.md` |
| Build steps, commands, test layout | `docs/building.md` |

Two habits worth keeping from the existing prose:

- **The docs state what is *not* built, explicitly.** When a layer is partial,
  say which layers exist and which are missing. "RTT has policy, capability and
  correlation, but no session" is useful; "RTT is supported" is a lie.
- **Carry the reasoning, not just the change.** If a decision has a reason
  someone could later undo by accident — a threshold, an ordering, a refusal
  reported ahead of another — write that reason down next to it.

## Before you say a task is done

- Grep the docs for the claim you just invalidated. Stale phrasing survives
  edits that only *add* text; it usually needs the old sentence replaced.
- Watch for contradictions the change creates *between* documents. Two docs
  disagreeing is the failure mode that survived the last pass here: one doc
  claimed a feature was wired up while another listed it as pending.

## Not this

- Do not restate the whole document. Change the sentences that became false.
- Do not add a roadmap item for work that was simply done.
- Do not document something as working on the strength of a passing test alone.
  Several things in this repo are unit tested and still unverified on hardware;
  `docs/roadmap.md` has an **Unverified — needs real hardware** section for
  exactly that, and a claim belongs there instead.