#!/usr/bin/env python3
"""Check preference XML for hardcoded text and report translation coverage."""

from pathlib import Path
import json
import re
import sys
from collections import Counter
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
TRANSLATION_BASELINE = Path(__file__).with_name("translation-baseline.json")
ANDROID_ATTRIBUTE = re.compile(r'android:(title|summary)="([^"]*)"')
FORMAT_TOKEN = re.compile(
    r"\\n|%%|%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])"
)
TRANSLATION_MARKER = re.compile(r"XTRAP|XTRANL|XTRAL|⟦X|␞")
CONTROL_PICTURE = re.compile(r"[\u2400-\u243F]")
INVALID_ANDROID_ESCAPE = re.compile(r"(?<!\\)\\(?:[^nrt\\'\"u:]|u(?![0-9a-fA-F]{4}))|\\{2,}['\"]")
LOCALE_DIRECTORY = re.compile(r"^values-[a-z]{2}(?:-r[A-Z]{2})?$")
PLURAL_QUANTITIES = {
    "values-ar": {"zero", "one", "two", "few", "many", "other"},
    "values-cs": {"one", "few", "many", "other"},
    "values-de": {"one", "other"},
    "values-es": {"one", "many", "other"},
    "values-fr": {"one", "many", "other"},
    "values-gl": {"one", "other"},
    "values-in": {"other"},
    "values-it": {"one", "many", "other"},
    "values-ja": {"other"},
    "values-pt-rBR": {"one", "many", "other"},
    "values-pl": {"one", "few", "many", "other"},
    "values-ru": {"one", "few", "many", "other"},
    "values-sk": {"one", "few", "many", "other"},
    "values-tr": {"one", "other"},
    "values-zh-rCN": {"other"},
    "values-zh-rTW": {"other"},
}
ARRAY_REFERENCE = re.compile(r"^@string/([A-Za-z0-9_]+)$")

# The second-generation updater is intentionally shipped with the default
# English resources until its translations are reviewed by native speakers.
# Android falls back to values/ for these keys; keeping the allowlist explicit
# prevents this policy from hiding missing translations elsewhere in the app.
INTENTIONAL_FALLBACK_RESOURCES = {
    "automatic_updates",
    "channel_points_prediction_outcome_description",
    "check_automatically",
    "copy_diagnostics",
    "diagnostics_copied",
    "none",
    "update_available_banner",
    "update_checked_recently",
    "update_count_fixed",
    "update_count_improved",
    "update_count_new",
    "update_count_other",
    "update_count_security",
    "update_diagnostics",
    "update_diagnostics_asset",
    "update_diagnostics_error",
    "update_diagnostics_installed",
    "update_diagnostics_last_attempt",
    "update_diagnostics_last_check",
    "update_diagnostics_progress",
    "update_diagnostics_reason",
    "update_diagnostics_speed",
    "update_diagnostics_stage",
    "update_diagnostics_state",
    "update_diagnostics_status",
    "update_diagnostics_target",
    "update_diagnostics_timestamp",
    "update_download_failed_connection",
    "update_download_failed_connection_message",
    "update_download_failed_generic_message",
    "update_download_failed_server",
    "update_download_failed_server_message",
    "update_download_failed_storage",
    "update_download_failed_storage_message",
    "update_download_finished",
    "update_download_paused",
    "update_download_starting",
    "update_download_storage_unavailable",
    "update_download_storage_unavailable_message",
    "update_download_waiting_network",
    "update_download_waiting_retry",
    "update_download_waiting_wifi",
    "update_downloaded_ready",
    "update_downloaded_verified",
    "update_error_message",
    "update_eta_minutes",
    "update_eta_seconds",
    "update_full_release_notes",
    "update_install_cancelled_message",
    "update_install_failed_message",
    "update_install_permission_message",
    "update_install_permission_title",
    "update_meta_separator",
    "update_more_actions",
    "update_preparing_download",
    "update_ready_title",
    "update_release_note_item",
    "update_release_notes_earlier",
    "update_release_notes_earlier_expanded",
    "update_section_fixed",
    "update_section_improved",
    "update_section_new",
    "update_section_other",
    "update_section_security",
    "update_transfer_calculating_speed",
    "update_transfer_downloaded",
    "update_transfer_progress",
    "update_transfer_speed",
    "update_transfer_speed_eta",
    "update_transfer_waiting",
    "update_verification_failed_message",
    "update_verification_failed_title",
    "update_verifying",
    "update_view",
    "settings_tv_chat",
    "settings_tv_chat_layout",
    "settings_tv_chat_configure",
    "settings_tv_chat_hidden",
    "settings_tv_chat_side_panel",
    "settings_tv_chat_overlay",
    "settings_tv_chat_preset",
    "settings_tv_chat_position",
    "settings_tv_chat_width",
    "settings_tv_chat_height",
    "settings_tv_chat_opacity",
    "settings_tv_chat_reset",
    "tv_browser_remote_hint",
    "settings_tv_chat_preset_auto",
    "settings_tv_chat_preset_compact",
    "settings_tv_chat_preset_standard",
    "settings_tv_chat_preset_large",
    "settings_tv_chat_preset_full_height",
    "settings_tv_chat_preset_custom",
    "settings_tv_chat_anchor_top_left",
    "settings_tv_chat_anchor_top_center",
    "settings_tv_chat_anchor_top_right",
    "settings_tv_chat_anchor_center_left",
    "settings_tv_chat_anchor_center",
    "settings_tv_chat_anchor_center_right",
    "settings_tv_chat_anchor_bottom_left",
    "settings_tv_chat_anchor_bottom_center",
    "settings_tv_chat_anchor_bottom_right",
}


def duplicate_resource_keys(directory: Path) -> list[str]:
    counts: Counter[str] = Counter()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            kind = element.tag.rsplit("}", 1)[-1]
            if kind not in {"string", "plurals"}:
                continue
            name = element.attrib.get("name")
            if not name:
                continue
            counts[f"{kind}:{name}"] += 1
            if kind == "plurals":
                for item in element:
                    counts[f"{kind}:{name}:{item.attrib.get('quantity')}"] += 1
    return sorted(key for key, count in counts.items() if count > 1)


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


def all_string_keys(directory: Path) -> set[str]:
    keys: set[str] = set()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "string":
                continue
            name = element.attrib.get("name")
            if name:
                keys.add(name)
    return keys


def plural_quantities(directory: Path) -> dict[str, set[str]]:
    quantities: dict[str, set[str]] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "plurals":
                continue
            name = element.attrib.get("name")
            if name:
                quantities.setdefault(name, set()).update(
                    item.attrib.get("quantity", "") for item in element
                )
    return quantities


def resource_arrays(directory: Path) -> dict[str, tuple[bool, list[str]]]:
    arrays: dict[str, tuple[bool, list[str]]] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "string-array":
                continue
            name = element.attrib.get("name")
            if not name:
                continue
            arrays[name] = (
                element.attrib.get("translatable") != "false",
                [item.text or "" for item in element if item.tag.rsplit("}", 1)[-1] == "item"],
            )
    return arrays


def resource_values(directory: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            kind = element.tag.rsplit("}", 1)[-1]
            if kind == "string" and element.attrib.get("translatable") != "false":
                name = element.attrib.get("name")
                if name:
                    values[name] = element.text or ""
            elif kind == "plurals":
                name = element.attrib.get("name")
                if name:
                    for item in element:
                        values[f"{name}:{item.attrib.get('quantity')}"] = item.text or ""
    return values


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
    default_string_keys = all_string_keys(RES / "values")
    default_values = resource_values(RES / "values")
    default_plurals = plural_quantities(RES / "values")
    default_arrays = resource_arrays(RES / "values")
    baseline = json.loads(TRANSLATION_BASELINE.read_text(encoding="utf-8"))
    coverage_regressions: list[str] = []
    format_regressions: list[str] = []
    duplicate_regressions: list[str] = []
    escape_regressions: list[str] = []
    empty_regressions: list[str] = []
    marker_regressions: list[str] = []
    plural_regressions: list[str] = []
    array_regressions: list[str] = []
    directories = [RES / "values"] + [
        directory
        for directory in sorted(RES.glob("values-*"))
        if LOCALE_DIRECTORY.fullmatch(directory.name)
    ]
    for directory in directories:
        for key in duplicate_resource_keys(directory):
            duplicate_regressions.append(f"{directory.name}:{key}")
        for key, value in resource_values(directory).items():
            if INVALID_ANDROID_ESCAPE.search(value):
                escape_regressions.append(f"{directory.name}:{key}: invalid Android escape")
    print(f"Default translatable string/plural resources: {len(default_keys)}")
    translatable_arrays = {
        name: items
        for name, (translatable, items) in default_arrays.items()
        if translatable
    }
    print(f"Default translatable string arrays: {len(translatable_arrays)}")
    for name, items in translatable_arrays.items():
        references = [ARRAY_REFERENCE.fullmatch(item) for item in items]
        if not all(references):
            array_regressions.append(
                f"values:{name}: translatable array contains inline text; use @string references"
            )
            continue
        for reference in references:
            assert reference is not None
            key = reference.group(1)
            if key not in default_string_keys:
                array_regressions.append(f"values:{name}: missing string reference {key}")
            elif key not in default_keys:
                array_regressions.append(
                    f"values:{name}: reference {key} is not translatable"
                )

    for directory in directories[1:]:
        translated = resource_keys(directory)
        translated_values = resource_values(directory)
        locale_plurals = plural_quantities(directory)
        locale_arrays = resource_arrays(directory)
        missing = default_keys - translated
        print(
            f"{directory.name}: {len(translated)}/{len(default_keys)} present; "
            f"{len(missing)} fallback resources"
        )
        unexpected_missing = missing - INTENTIONAL_FALLBACK_RESOURCES
        allowed_missing = baseline.get(directory.name)
        if allowed_missing is None or len(unexpected_missing) > allowed_missing:
            coverage_regressions.append(
                f"{directory.name}: {len(unexpected_missing)} resources missing; "
                f"baseline allows {allowed_missing if allowed_missing is not None else 'no value'}"
            )
        required_quantities = PLURAL_QUANTITIES[directory.name]
        for name in default_plurals.keys() | locale_plurals.keys():
            if name in INTENTIONAL_FALLBACK_RESOURCES and name not in locale_plurals:
                continue
            missing_quantities = required_quantities - locale_plurals.get(name, set())
            if missing_quantities:
                plural_regressions.append(
                    f"{directory.name}:{name}: missing plural quantities "
                    f"{', '.join(sorted(missing_quantities))}"
                )
        for name, items in translatable_arrays.items():
            references = [ARRAY_REFERENCE.fullmatch(item) for item in items]
            if not all(references):
                continue
            locale_array = locale_arrays.get(name)
            if locale_array is not None and len(locale_array[1]) != len(items):
                array_regressions.append(
                    f"{directory.name}:{name}: item count changed from "
                    f"{len(items)} to {len(locale_array[1])}"
                )
            for reference in references:
                assert reference is not None
                key = reference.group(1)
                if key not in translated:
                    array_regressions.append(
                        f"{directory.name}:{name}: referenced string {key} is not translated"
                    )
        for key in default_values.keys() & translated_values.keys():
            if default_values[key].strip() and not translated_values[key].strip():
                empty_regressions.append(
                    f"{directory.name}:{key}: translation is empty while the default is not"
                )
            expected_tokens = Counter(FORMAT_TOKEN.findall(default_values[key]))
            actual_tokens = Counter(FORMAT_TOKEN.findall(translated_values.get(key, "")))
            if expected_tokens != actual_tokens:
                format_regressions.append(
                    f"{directory.name}:{key}: format tokens changed from "
                    f"{sorted(expected_tokens.elements())} to {sorted(actual_tokens.elements())}"
                )
            translated_value = translated_values.get(key, "")
            if TRANSLATION_MARKER.search(translated_value) or CONTROL_PICTURE.search(translated_value):
                marker_regressions.append(f"{directory.name}:{key}: translation marker leaked into resources")

    if coverage_regressions:
        print("Translation coverage regressed:", file=sys.stderr)
        print("\n".join(coverage_regressions), file=sys.stderr)

    if format_regressions:
        print("Translation format validation failed:", file=sys.stderr)
        print("\n".join(format_regressions), file=sys.stderr)

    if empty_regressions:
        print("Empty translations found:", file=sys.stderr)
        print("\n".join(empty_regressions), file=sys.stderr)

    if marker_regressions:
        print("Translation markers found:", file=sys.stderr)
        print("\n".join(marker_regressions), file=sys.stderr)

    if plural_regressions:
        print("Plural coverage validation failed:", file=sys.stderr)
        print("\n".join(plural_regressions), file=sys.stderr)

    if array_regressions:
        print("String-array validation failed:", file=sys.stderr)
        print("\n".join(array_regressions), file=sys.stderr)

    if duplicate_regressions:
        print("Duplicate resource definitions found:", file=sys.stderr)
        print("\n".join(duplicate_regressions), file=sys.stderr)

    if escape_regressions:
        print("Invalid Android string escapes found:", file=sys.stderr)
        print("\n".join(escape_regressions), file=sys.stderr)

    return 1 if (
        findings
        or coverage_regressions
        or format_regressions
        or empty_regressions
        or marker_regressions
        or plural_regressions
        or array_regressions
        or duplicate_regressions
        or escape_regressions
    ) else 0


if __name__ == "__main__":
    raise SystemExit(main())
