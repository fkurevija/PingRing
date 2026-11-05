#!/usr/bin/env python3
"""File a development request as a GitHub issue, refusing near-duplicates.

Requests otherwise live in a chat thread, where they are answered once and then
lost: there is no list of what has been asked for, no way to see that three
people wanted the same thing, and nothing that says a request was declined
rather than forgotten. An issue is the opposite of all three - it is a list,
it is searchable, and it has a state.

The de-duplication is the point. The same request arrives more than once, and
two issues for one request splits the discussion and halves the chance either
gets noticed. So this searches what is already open before opening anything,
and refuses when it finds a close match. Refusing rather than warning is
deliberate: a warning that scrolls past is the same as no warning, and the
person filing has usually not searched - that is why they are here.

Usage:
    python tools/request_issue.py "Add a wristband vibrate option"
    python tools/request_issue.py --details-file notes.md
    python tools/request_issue.py --search-only "battery drain"   # never files
    python tools/request_issue.py --create "Add group chat mute"  # actually file

Preview only unless --create is passed, so a dry run cannot file anything.

Requires the GitHub CLI (`gh`) authenticated. Matching runs against open issues
only: a closed issue that was declined is a decision worth surfacing, but
re-filing over it silently would undo that, so closed ones are reported
separately rather than treated as duplicates.
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

# Words that carry no signal about what a request is *for*. Without this,
# "add a setting for the chat" and "remove a setting from the radar" score as
# near-identical on the strength of "setting" alone.
STOP_WORDS = frozenset("""
a an the and or for with without to from in on at of by is are be been it its
this that these those add adds added adding remove removes removed removing
make makes made making let lets allow allows support supports want wants need
needs would could should can cant please pingring app when after before
new use used using option options feature features setting settings
""".split())

# Pairs above this are treated as the same request. Tuned against the real
# corpus by tools/test_request_issue.py: "add a wristband vibrate option" vs
# "add a wristband vibration option" scores 0.75 and must match, while
# "make the radar show a compass heading" vs "add a wristband vibrate option"
# scores 0.0 and must not. 0.5 sits above the noise and below the genuine
# near-misses that are worth a human look.
DUPLICATE_THRESHOLD = 0.5

REPO = "combustiblelemonlabs/PingRing"


def stem(word):
    """Reduce one word to a crude stem.

    Three steps, and the later two are the ones that matter. 'vibrate' and
    'vibration' are the pair this whole script is judged on, and neither a
    suffix list containing 'ing' nor a trailing-'e' trim catches it:
    'vibration' ends in '-ion', so 'ing' never matches it. Dropping 'ion'
    leaves 'vibrat', and dropping the trailing 'e' from 'vibrate' lands on the
    same stem. Before that the pair scored 0.33 and slipped under the
    threshold - which the test below caught rather than the threshold being
    quietly lowered to suit.

    It is not a real stemmer and does not need to be: it only has to make the
    handful of inflection pairs that show up when one person types a request
    and another types it again compare equal. Anything subtler would need a
    dependency this repo does not otherwise have.
    """
    for suffix in ("ing", "ion", "ed", "es", "s"):
        if len(word) > len(suffix) + 3 and word.endswith(suffix):
            word = word[: -len(suffix)]
            break
    if len(word) > 3 and word.endswith("e"):
        word = word[:-1]
    return word


def normalise(text):
    """Lowercase word stems, with stop words and filler removed."""
    kept = []
    for word in re.findall(r"[a-z0-9]+", (text or "").lower()):
        if word in STOP_WORDS:
            continue
        stemmed = stem(word)
        if stemmed not in STOP_WORDS and len(stemmed) > 1:
            kept.append(stemmed)
    return frozenset(kept)


def similarity(left, right):
    """Jaccard similarity of two texts, in 0.0..1.0.

    Jaccard rather than containment: containment alone would call
    "add vibrate option" a duplicate of a long issue that happens to contain
    those words, which is how a short request ends up blocked by an
    unrelated one.
    """
    a, b = normalise(left), normalise(right)
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def classify(candidate, existing):
    """Split `existing` into (duplicates, partial_overlaps) against `candidate`.

    Each entry is a (score, issue) pair. Anything at or above
    DUPLICATE_THRESHOLD is a duplicate; anything that overlaps at all but stays
    under it is only worth mentioning, since it may be a different request that
    happens to share vocabulary.
    """
    duplicates, overlaps = [], []
    for issue in existing:
        title = issue.get("title", "")
        score = similarity(candidate, title)
        if score >= DUPLICATE_THRESHOLD:
            duplicates.append((score, issue))
        elif score > 0:
            overlaps.append((score, issue))
    duplicates.sort(key=lambda pair: pair[0], reverse=True)
    overlaps.sort(key=lambda pair: pair[0], reverse=True)
    return duplicates, overlaps


def build_title(raw):
    """Title for the issue: as typed, trimmed and collapsed to one line.

    Not reworded. The submitter's own phrasing is the thing that makes a later
    duplicate recognisable to them, and a script that tidies the grammar of
    every title makes two identical requests look like two different ones.
    """
    return " ".join(raw.split())[:120]


def build_body(details, submitter):
    """The issue body, with a provenance footer.

    The footer records where the request came from so that a request lifted out
    of a chat thread can still be traced back to the conversation that produced
    it - otherwise the context is lost the moment it is filed.
    """
    parts = [details.strip(), "", "---", ""]
    if submitter:
        parts.append(f"*Submitted by: {submitter}*")
    parts.append("*Filed with `tools/request_issue.py` from the development "
                 "request form.*")
    return "\n".join(parts)


def gh(*args):
    """Run the GitHub CLI, raising SystemExit with its message on failure."""
    if shutil.which("gh") is None:
        raise SystemExit(
            "The GitHub CLI ('gh') is not on PATH. Install it from "
            "https://cli.github.com and run `gh auth login`."
        )
    result = subprocess.run(
        ["gh", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise SystemExit(f"gh {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def fetch_issues(state):
    """Open or closed issues as dicts.

    A failed query raises rather than returning an empty list. Returning []
    would be read downstream as "no duplicates found", which turns a network
    error into a false all-clear and files the duplicate anyway - the one
    outcome this script exists to prevent.
    """
    out = gh(
        "issue", "list",
        "--repo", REPO,
        "--state", state,
        "--limit", "200",
        "--json", "number,title,state,url",
    )
    try:
        return json.loads(out or "[]")
    except json.JSONDecodeError:
        raise SystemExit(
            "Could not read the issue list from gh. Not filing blind, since "
            "that would skip de-duplication."
        )


def report(kind, entries):
    for score, issue in entries:
        print(f"  [{kind}] #{issue.get('number', '?')} "
              f"(score {score:.2f}) {issue.get('title', '')}")
        if issue.get("url"):
            print(f"           {issue['url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("request", nargs="?", default="", help="one-line summary")
    parser.add_argument("--details-file", help="file with the full description")
    parser.add_argument("--submitter", default="", help="who is asking")
    parser.add_argument(
        "--search-only",
        action="store_true",
        help="report matches and exit without filing anything",
    )
    parser.add_argument(
        "--create",
        action="store_true",
        help="actually open the issue (default is preview only)",
    )
    args = parser.parse_args()

    details = ""
    if args.details_file:
        path = Path(args.details_file)
        if not path.is_file():
            raise SystemExit(f"No such file: {path}")
        details = path.read_text(encoding="utf-8")

    title = build_title(args.request)
    if not title and not details:
        raise SystemExit(
            "Give a one-line summary, or --details-file with the description."
        )
    if not title:
        title = build_title(details.splitlines()[0])

    print(f"Request: {title}")
    if args.submitter:
        print(f"From:    {args.submitter}")

    print("\nSearching open issues...")
    open_issues = fetch_issues("open")
    duplicates, overlaps = classify(title, open_issues)

    print(f"\n{len(open_issues)} open issue(s) checked.")
    if duplicates:
        print("\nThis looks like a duplicate of an open request:")
        report("dup", duplicates)
        print("\nNot filing. Add to the existing issue instead - that is where "
              "the discussion already is.")
        return 1

    if overlaps:
        print("\nRelated, but not the same request:")
        report("related", overlaps)

    print("\nSearching closed issues...")
    closed_dupes, _ = classify(title, fetch_issues("closed"))
    if closed_dupes:
        print("\nA previously closed request looks similar:")
        report("closed", closed_dupes)
        print("\nIt was closed, so it does not block a new issue - but read it "
              "first, since it may record a decision not to build this.")

    if args.search_only:
        print("\n--search-only: nothing was filed.")
        return 0

    if not args.create:
        print("\nNo duplicate found. Preview only - re-run with --create to file it.")
        return 0

    url = gh(
        "issue", "create",
        "--repo", REPO,
        "--title", title,
        "--body", build_body(details or title, args.submitter),
    )
    print(f"\nFiled: {url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())


