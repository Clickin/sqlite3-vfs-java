#!/usr/bin/env python3
"""Compare every upstream test outcome, including failures/skips; allow disjoint batches.

Usage: compare-junit.py BASELINE_REPORT_DIR CANDIDATE_REPORT_DIR [CANDIDATE_REPORT_DIR ...]
A matching baseline is not the same as an all-passing suite.
"""
import collections
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def outcomes(directories):
    result = {}
    for directory in directories:
        reports = sorted(Path(directory).glob("TEST-*.xml"))
        if not reports:
            raise ValueError("No JUnit reports in " + directory)
        for report in reports:
            suite = ET.parse(report).getroot()
            for case in suite.findall("testcase"):
                identity = case.get("classname") + "#" + case.get("name")
                if identity in result:
                    raise ValueError("Duplicate test outcome: " + identity)
                result[identity] = next(
                    (status for status in ("failure", "error", "skipped") if case.find(status) is not None),
                    "passed",
                )
    if not result:
        raise ValueError("No test cases found")
    return result


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    baseline = outcomes([sys.argv[1]])
    candidate = outcomes(sys.argv[2:])
    differences = [
        {"test": name, "baseline": baseline.get(name), "candidate": candidate.get(name)}
        for name in sorted(baseline.keys() | candidate.keys())
        if baseline.get(name) != candidate.get(name)
    ]
    print(json.dumps({
        "baseline": dict(collections.Counter(baseline.values())),
        "candidate": dict(collections.Counter(candidate.values())),
        "same_outcomes": not differences,
        "all_tests_passed": all(status == "passed" for status in candidate.values()),
        "differences": differences,
        "baseline_nonpassing": {name: status for name, status in baseline.items() if status != "passed"},
    }, indent=2))
    return bool(differences)


if __name__ == "__main__":
    sys.exit(main())
