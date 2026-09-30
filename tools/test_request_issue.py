"""Tests for the request de-duplication (tools/request_issue.py).

Run with `python tools/test_request_issue.py`. Only the matching is exercised,
and it is pure: no `gh`, no network, no GitHub account.

Matching is the part worth testing, because it is the part that decides
whether a real request gets filed. Two failure modes matter and they pull in
opposite directions: failing to match a genuine duplicate files the same
request twice and splits the discussion, while matching too eagerly blocks a
different request behind an unrelated one. Both are asserted here with real
phrasings, because the threshold is only meaningful against the pairs it has
to separate.
"""
import importlib.util
import pathlib

TOOLS = pathlib.Path(__file__).parent


def load(module_name):
    spec = importlib.util.spec_from_file_location(
        f"{module_name}_under_test", TOOLS / f"{module_name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


request_issue = load("request_issue")


def issue(number, title):
    return {
        "number": number,
        "title": title,
        "state": "open",
        "url": f"https://github.com/fkurevija/PingRing/issues/{number}",
    }


def test_the_same_request_written_twice_is_caught():
    # The case the tool exists for. Different words, same ask - and a request
    # arriving in different words is the norm, not the exception.
    for existing, incoming in [
        ("Add a wristband vibrate option",
         "Add a wristband vibration option"),
        ("Beacon should vibrate the whole group",
         "Beacon should buzz the whole group"),
        ("Let members mute group chat",
         "Allow members to mute group chat"),
        ("Radar needs a bigger max capacity",
         "Radar needs a larger max capacity"),
    ]:
        duplicates, _ = request_issue.classify(incoming, [issue(1, existing)])
        assert duplicates, f"missed a duplicate: {existing!r} vs {incoming!r}"
    print("[test] reworded duplicates are caught")


def test_different_requests_are_not_blocked():
    # The opposite failure, and the more annoying one: a blocked request looks
    # like the tool ignoring you. These share the vocabulary of a small app
    # without being the same ask.
    for existing, incoming in [
        ("Add a wristband vibrate option", "Show a compass heading on the radar"),
        ("Beacon should vibrate the whole group", "Battery drains overnight"),
        ("Let members mute group chat", "Support more than eight members"),
    ]:
        duplicates, _ = request_issue.classify(incoming, [issue(1, existing)])
        assert not duplicates, (
            f"blocked an unrelated request: {existing!r} vs {incoming!r}"
        )
    print("[test] unrelated requests are not blocked")


def test_an_identical_title_scores_one():
    title = "Add a wristband vibrate option"
    assert request_issue.similarity(title, title) == 1.0
    print("[test] a title matches itself exactly")


def test_disjoint_titles_score_zero():
    assert request_issue.similarity(
        "wristband vibration", "battery drain"
    ) == 0.0
    print("[test] unrelated titles score zero")


def test_a_related_request_is_reported_without_blocking():
    # Shares a noun but is a different request. It must be surfaced - the
    # person filing may not know it exists - without refusing.
    existing = [issue(7, "Show a compass heading on the radar")]
    duplicates, overlaps = request_issue.classify(
        "Compass heading should follow the tracked member", existing
    )

    assert not duplicates, "a near-miss must not refuse the request"
    assert overlaps, "a near-miss should still be reported"
    print("[test] near-misses are reported, not refused")


def test_an_empty_corpus_matches_nothing():
    duplicates, overlaps = request_issue.classify("Anything at all", [])
    assert duplicates == [] and overlaps == []
    print("[test] an empty issue list matches nothing")


def test_blank_input_is_not_a_match():
    # A title that is entirely stop words normalises to nothing. Scoring that
    # against a real issue must not divide by zero.
    assert request_issue.similarity("", "Add a beacon") == 0.0
    assert request_issue.similarity("the a of", "Add a beacon") == 0.0
    duplicates, _ = request_issue.classify("", [issue(1, "Add a beacon")])
    assert not duplicates
    print("[test] empty and all-filler input matches nothing")


def test_issues_missing_a_title_do_not_crash():
    # gh is asked for specific fields, but a malformed row must not take the
    # whole run down - losing de-duplication is worse than one bad entry.
    duplicates, overlaps = request_issue.classify(
        "Add a beacon", [{"number": 3}, {"title": "Totally other"}]
    )
    assert not duplicates and not overlaps
    print("[test] a malformed issue row is skipped, not fatal")


def test_the_closest_duplicate_is_reported_first():
    existing = [
        issue(1, "Something about beacons"),
        issue(2, "Add a wristband vibrate option"),
        issue(3, "Vibrate on beacon"),
    ]
    duplicates, _ = request_issue.classify(
        "Add a wristband vibration option", existing
    )

    assert duplicates, "the near-identical title must be found"
    assert duplicates[0][1]["number"] == 2, (
        f"expected #2 first, got #{duplicates[0][1]['number']}"
    )
    print("[test] the closest match is ranked first")


def test_a_title_is_collapsed_to_one_line():
    # Titles are shown in a list; a newline in one breaks that row.
    messy = "Add a wristband\n   vibrate option  "
    title = request_issue.build_title(messy)

    assert "\n" not in title
    assert title == "Add a wristband vibrate option"
    print("[test] a multi-line title collapses to one line")


def test_a_very_long_title_is_truncated():
    # GitHub's own limit is well above this; the point is that a paste of a
    # whole paragraph cannot produce a title that swallows the issue list.
    title = request_issue.build_title("wristband " * 60)
    assert len(title) <= 120
    print("[test] an over-long title is truncated")


def test_the_body_records_where_the_request_came_from():
    body = request_issue.build_body("Bands buzz at concerts.", "Iloha")

    assert "Bands buzz at concerts." in body
    assert "Iloha" in body, "the submitter must be recorded"
    assert "request_issue.py" in body
    print("[test] the body keeps the details and the submitter")


def test_the_body_works_without_a_named_submitter():
    body = request_issue.build_body("No name given", "")

    assert "No name given" in body
    assert "Submitted by" not in body, (
        "an empty submitter must not leave a blank attribution line"
    )
    print("[test] an unnamed submitter leaves no empty attribution")


def main():
    test_the_same_request_written_twice_is_caught()
    test_different_requests_are_not_blocked()
    test_an_identical_title_scores_one()
    test_disjoint_titles_score_zero()
    test_a_related_request_is_reported_without_blocking()
    test_an_empty_corpus_matches_nothing()
    test_blank_input_is_not_a_match()
    test_issues_missing_a_title_do_not_crash()
    test_the_closest_duplicate_is_reported_first()
    test_a_title_is_collapsed_to_one_line()
    test_a_very_long_title_is_truncated()
    test_the_body_records_where_the_request_came_from()
    test_the_body_works_without_a_named_submitter()
    print("[test] ALL CHECKS PASSED")


if __name__ == "__main__":
    main()


