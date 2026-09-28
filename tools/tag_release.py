#!/usr/bin/env python3
"""Create a version tag for a build worth keeping.

`latest` is deliberately overwritten on every master push, so it is a pointer
to the newest build and nothing more. A `v*` tag instead produces an immutable
GitHub Release that is never replaced, which is what gives a build a permanent
address. This script picks the moment: it shows what has landed since the last
version tag so the choice is made against real commits rather than a vague
feeling that "enough has changed".

    python tools/tag_release.py            # preview, changes nothing
    python tools/tag_release.py --create   # create and push the tag

Tags are created annotated and pointed at an explicit commit, never at a branch
name, so the release cannot silently move when master advances.

The tag push is what triggers the versioned release path in the Build APK
workflow; this script does not build or publish anything itself.
"""

import argparse
import re
import subprocess
import sys

VERSION_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")

# Conventional-commit types whose changes a user of the app would notice.
# Anything outside this set - docs, CI, tests, refactors - is bookkeeping that
# does not on its own warrant a permanent release.
USER_FACING_TYPES = ("feat", "fix", "perf")


class Assessment:
    """Whether the pending commits look like a release is warranted."""

    def __init__(self, notable, reason, user_facing):
        self.notable = notable
        self.reason = reason
        self.user_facing = user_facing


def classify(subject):
    """Conventional-commit type of a subject, or '' when it has none."""
    match = re.match(r"^([a-z]+)(\([^)]*\))?!?:", subject.strip())
    return match.group(1) if match else ""


def assess_notability(commit_lines):
    """Judge whether these commits are worth a permanent version tag.

    Deliberately conservative in the direction of *not* suggesting: this only
    ever advises, and a wrong suggestion is noise. Commits with no
    conventional-commit type are counted as user-facing, because an unlabelled
    commit in this repo has so far meant real work rather than bookkeeping.
    """
    subjects = [line.split(" ", 1)[-1].replace(chr(0xFEFF), "").strip()
                for line in commit_lines if line.strip()]
    if not subjects:
        return Assessment(False, "nothing has changed since the last tag", [])

    user_facing = [
        s for s in subjects
        if classify(s) in USER_FACING_TYPES or classify(s) == ""
    ]
    if not user_facing:
        return Assessment(
            False,
            "only docs, CI, tests or internal changes - probably not worth a release",
            [],
        )

    kinds = sorted({classify(s) or "unlabelled" for s in user_facing})
    if len(user_facing) == 1 and len(subjects) > 3:
        return Assessment(
            True,
            f"one user-facing change among {len(subjects)} commits - release if it matters",
            user_facing,
        )
    return Assessment(
        True,
        f"{len(user_facing)} user-facing change(s) [{', '.join(kinds)}] - consider a release",
        user_facing,
    )


def git(*args, capture=True):
    result = subprocess.run(
        ["git", *args],
        capture_output=capture,
        text=True,
        encoding="utf-8",
        # Commit subjects are not guaranteed to be printable in the console's
        # encoding: one commit in this repo's history carries a stray BOM from an
        # earlier tooling mistake, and defaulting to "replace" keeps a cosmetic
        # encoding problem from aborting the script outright.
        errors="replace",
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise SystemExit(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip() if capture else ""


def latest_version_tag():
    """Highest existing v* tag, or None when the repo has never been tagged."""
    tags = git("tag", "--list").splitlines()
    versions = []
    for tag in tags:
        match = VERSION_TAG.match(tag.strip())
        if match:
            major, minor, patch = (int(part) for part in match.groups())
            versions.append(((major, minor, patch), tag.strip()))
    if not versions:
        return None
    return max(versions)[1]


def commits_since(ref):
    """Commits on master that the given tag does not already contain."""
    out = git("log", f"{ref}..master", "--oneline", "--no-decorate")
    return [line for line in out.splitlines() if line.strip()]


def is_clean_tree():
    return not git("status", "--porcelain").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--create",
        action="store_true",
        help="actually create and push the tag (default is preview only)",
    )
    parser.add_argument(
        "--message",
        default="",
        help="annotated tag message; defaults to the commit subjects",
    )
    args = parser.parse_args()

    head = git("rev-parse", "HEAD")
    short_head = head[:7]
    previous = latest_version_tag()
    pending = commits_since(previous) if previous else commits_since("HEAD~20" if _has_history() else "HEAD")

    print(f"Current commit: {short_head}")
    if previous:
        print(f"Last version tag: {previous}")
    else:
        print("Last version tag: none - this will be the first")

    if pending:
        print(f"\n{len(pending)} commit(s) since the last version tag:")
        for line in pending:
            # Strip the BOM wherever it appears. One commit in this repo's
            # history carries a stray U+FEFF from an earlier tooling mistake,
            # and on a cp1252 console printing it raises rather than degrading -
            # so a cosmetic encoding problem would otherwise abort the script.
            print("  " + line.replace(chr(0xFEFF), ""))
    else:
        print("\nNothing new since the last version tag.")
        print("Nothing worth keeping has changed; `latest` already points here.")
        return 1

    suggested = suggest_next(previous)
    print(f"\nSuggested tag: {suggested}")

    assessment = assess_notability(pending)
    print(f"Assessment: {assessment.reason}")
    if assessment.user_facing:
        print("  User-facing changes since the last tag:")
        for subject in assessment.user_facing:
            print(f"    - {subject}")

    if not args.create:
        print("\nPreview only. Re-run with --create to create and push this tag.")
        return 0

    if not is_clean_tree():
        raise SystemExit(
            "Working tree is dirty. Commit or stash first: a tag should point at "
            "a commit that is actually on the remote."
        )
    if git("status", "-sb").splitlines()[0].split("...")[0].strip() != short_head:
        print("Note: local master is ahead of its upstream; push first if so.")

    message = args.message or (
        f"{suggested}\n\n" + "\n".join(line.split(" ", 1)[-1] for line in pending)
    )
    # Strip invisible characters from the generated message: a tag message is
    # permanent metadata, and a stray U+FEFF in it would be visible in
    # `git tag -l` output forever.
    message = message.replace(chr(0xFEFF), "").strip()
    git("tag", "-a", suggested, "-m", message, head)
    try:
        git("push", "origin", f"refs/tags/{suggested}")
    except SystemExit:
        # Do not leave a local tag that the remote does not have: it would make
        # the next run think the release already exists.
        git("tag", "-d", suggested)
        raise
    print(f"\nPushed {suggested} -> {short_head}")
    print("The Build APK workflow will publish this as an immutable release.")
    return 0


def suggest_next(previous):
    """Next patch version after the last tag, or v0.1.0 for a fresh repo."""
    if not previous:
        return "v0.1.0"
    match = VERSION_TAG.match(previous)
    if not match:
        return "v0.1.0"
    major, minor, patch = (int(part) for part in match.groups())
    return f"v{major}.{minor}.{patch + 1}"


def _has_history():
    try:
        git("rev-parse", "--verify", "HEAD~1")
        return True
    except SystemExit:
        return False


if __name__ == "__main__":
    sys.exit(main())