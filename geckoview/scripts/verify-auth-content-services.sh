#!/usr/bin/env bash
set -euo pipefail

artifact="${1:?x86_64 auth AAR path is required}"
[[ -f "$artifact" ]] || {
  echo "Missing AAR: $artifact" >&2
  exit 1
}
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }

python3 - "$artifact" <<'PY'
import re
import subprocess
import sys

artifact = sys.argv[1]
try:
    manifest = subprocess.check_output(
        ["unzip", "-p", artifact, "AndroidManifest.xml"],
        stderr=subprocess.STDOUT,
    ).decode("utf-8")
except (subprocess.CalledProcessError, UnicodeDecodeError) as error:
    print(f"Unable to read text AndroidManifest.xml from {artifact}: {error}", file=sys.stderr)
    raise SystemExit(1)


def service_numbers(kind):
    pattern = rf"GeckoChildProcessServices\${kind}(\d+)"
    return {int(number) for number in re.findall(pattern, manifest)}


expected = set(range(40))
tab = service_numbers("tab")
isolated = service_numbers("isolatedTab")
missing_tab = sorted(expected - tab)
missing_isolated = sorted(expected - isolated)
if missing_tab or missing_isolated:
    if missing_tab:
        print(f"auth AAR is missing tab content services: {missing_tab}", file=sys.stderr)
    if missing_isolated:
        print(
            f"auth AAR is missing isolatedTab content services: {missing_isolated}",
            file=sys.stderr,
        )
    print("expected Gecko's default tab0..tab39 and isolatedTab0..isolatedTab39", file=sys.stderr)
    raise SystemExit(1)

print(
    "verified auth AAR content services: "
    f"tab0..tab39 ({len(tab)}), isolatedTab0..isolatedTab39 ({len(isolated)})"
)
PY
