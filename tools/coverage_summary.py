#!/usr/bin/env python3
"""Print a JaCoCo XML report as a Markdown table for the CI job summary.

Reads the report rather than scraping the HTML, so the numbers here are the same
ones in the report artifact. Reports the project total and, more usefully, a
per-class breakdown of anything below a threshold - because the headline figure
for this project is dominated by Compose UI that only instrumented tests can
reach, and "14%" says nothing about which of the 70 concrete classes are fine
and which have no test at all.

Usage: coverage_summary.py <report.xml> [threshold-percent] [label]
"""
import sys
import xml.etree.ElementTree as ET


def counters(node):
    """The LINE counter of a report/class node, as (covered, total)."""
    for counter in node.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered")), int(counter.get("missed")) + int(counter.get("covered"))
    return 0, 0


def main():
    if len(sys.argv) < 2:
        print("usage: coverage_summary.py <report.xml> [threshold] [label]", file=sys.stderr)
        return 2

    path = sys.argv[1]
    threshold = int(sys.argv[2]) if len(sys.argv) > 2 else 80
    label = sys.argv[3] if len(sys.argv) > 3 else "Coverage"

    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        # A missing or unparseable report must not fail the job: the tests have
        # already passed by this point, and coverage is reported, not enforced.
        print(f"## {label}\n\nCould not read the coverage report: {error}\n")
        return 0

    # The JaCoCo XML root is <report> itself, so there is no wrapper to unwrap -
    # but be tolerant, because a future format change here would otherwise be
    # reported as "no data" rather than as an error worth looking at.
    report = root if root.tag == "report" else root.find("report")
    if report is None:
        print(f"## {label}\n\nCoverage report has no <report> element (root was <{root.tag}>).\n")
        return 0

    total_covered, total_lines = counters(report)
    overall = 100.0 * total_covered / total_lines if total_lines else 0.0

    rows = []
    for package in report.findall("package"):
        short = package.get("name", "").replace("com/fkurevija/pingring/", "")
        for klass in package.findall("class"):
            name = klass.get("name", "")
            # "$" marks a lambda, an inner or an anonymous class the compiler
            # synthesised. They are never meaningful coverage targets on their own;
            # they follow whatever code encloses them.
            if "$" in name:
                continue
            covered, lines = counters(klass)
            if lines == 0:
                continue
            percent = 100.0 * covered / lines
            rows.append((short, name.split("/")[-1], percent, covered, lines))

    lines_out = [
        f"## {label}",
        "",
        f"**{overall:.1f}%** of {total_lines:,} lines ({total_covered:,} covered) across "
        f"{len(rows)} concrete classes.",
        "",
    ]

    at_floor = [row for row in rows if row[2] >= threshold]
    below = [row for row in rows if row[2] < threshold]
    lines_out.append(
        f"{len(at_floor)} of {len(rows)} classes at or above {threshold}%. "
        f"Concrete classes exclude compiler-generated lambdas and inner classes."
    )

    # Split the misses, because they are two different problems and only one of
    # them is a note you can act on today.
    #
    # Uncovered means no test reaches it - usually a theme or navigation file
    # that is not worth a test in the first place. Listing forty of those spends
    # the reader's attention on things nobody will fix, and the cap then silently
    # drops the classes that *are* partially covered, which are exactly the ones
    # worth naming.
    #
    # Partial means a test exists and stops short. Those are the rows that say
    # what to do next, so they get the table.
    zero = sorted((row for row in below if row[3] == 0), key=lambda row: -row[4])
    partial = sorted((row for row in below if row[3] > 0), key=lambda row: row[2])

    if partial:
        # "1 classes" is the kind of detail that makes a report look automated
        # in the unflattering sense.
        detail = "class" if len(partial) == 1 else "classes"
        lines_out += [
            "",
            f"<details><summary>{len(partial)} {detail} partly covered, below {threshold}%</summary>",
            "",
            "| Class | Covered | Lines | % |",
            "| --- | ---: | ---: | ---: |",
        ]
        for short, name, percent, covered, lines in partial[:40]:
            # The package name has already had the common prefix stripped, so
            # the separator has to go back on or every row reads as one word.
            qualified = f"{short}/{name}" if short else name
            lines_out.append(f"| `{qualified}` | {covered} | {lines} | {percent:.1f}% |")
        if len(partial) > 40:
            lines_out.append(f"| …and {len(partial) - 40} more | | | |")
        lines_out += ["", "</details>"]

    if zero:
        biggest = zero[0]
        detail = "class" if len(zero) == 1 else "classes"
        lines_out.append(
            f"\n**No coverage at all: {len(zero)} {detail}** "
            f"({sum(row[4] for row in zero):,} lines), the largest being "
            f"`{biggest[1]}` ({biggest[4]:,} lines). They are in the report "
            f"artifact, not repeated here."
        )

    print("\n".join(lines_out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
