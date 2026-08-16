#!/usr/bin/env python3
"""Check preference XML for hardcoded text and report translation coverage."""

from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
TRANSLATION_BASELINE = Path(__file__).with_name("translation-baseline.json")
ANDROID_ATTRIBUTE = re.compile(r'android:(title|summary)="([^"]*)"')
LOCALE_DIRECTORY = re.compile(r"^values-[a-z]{2}(?:-r[A-Z]{2})?$")


def resource_keys(directory: Path) -> set[str]:
    keys: set[str] = set()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] not in {"string", "plurals"}:
                continue
            if element.attrib.get("translatable") == "false":
                continue
            name = element.attrib.get("name")
            if name:
                keys.add(name)
    return keys


def hardcoded_preference_text() -> list[str]:
    findings: list[str] = []
    for path in (RES / "xml").glob("*.xml"):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for attribute, value in ANDROID_ATTRIBUTE.findall(line):
                if value.startswith(("@", "?")) or value == "%s":
                    continue
                findings.append(f"{path.relative_to(ROOT)}:{line_number}: android:{attribute}=\"{value}\"")
    return findings


def main() -> int:
    findings = hardcoded_preference_text()
    if findings:
        print("Hardcoded preference text found:", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)

    default_keys = resource_keys(RES / "values")
    baseline = json.loads(TRANSLATION_BASELINE.read_text(encoding="utf-8"))
    coverage_regressions: list[str] = []
    print(f"Default translatable string/plural resources: {len(default_keys)}")
    for directory in sorted(RES.glob("values-*")):
        if not LOCALE_DIRECTORY.fullmatch(directory.name):
            continue
        translated = resource_keys(directory)
        missing = default_keys - translated
        print(f"{directory.name}: {len(translated)}/{len(default_keys)} present; {len(missing)} fallback resources")
        allowed_missing = baseline.get(directory.name)
        if allowed_missing is None or len(missing) > allowed_missing:
            coverage_regressions.append(
                f"{directory.name}: {len(missing)} missing; baseline allows {allowed_missing if allowed_missing is not None else 'no value'}"
            )

    if coverage_regressions:
        print("Translation coverage regressed:", file=sys.stderr)
        print("\n".join(coverage_regressions), file=sys.stderr)

    return 1 if findings or coverage_regressions else 0


if __name__ == "__main__":
    raise SystemExit(main())
