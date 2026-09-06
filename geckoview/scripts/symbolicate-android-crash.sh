#!/usr/bin/env bash
set -euo pipefail

symbols_input="${1:?symbol-bearing libxul.so or symbol directory is required}"
tombstone="${2:?tombstone or PC list is required}"

if [[ -d "$symbols_input" ]]; then
  symbols_input="$symbols_input/libxul.so"
fi
[[ -f "$symbols_input" ]] || {
  echo "Missing symbol-bearing libxul.so: $symbols_input" >&2
  exit 1
}
[[ -f "$tombstone" ]] || {
  echo "Missing tombstone or PC list: $tombstone" >&2
  exit 1
}

symbolizer="${LLVM_SYMBOLIZER:-}"
if [[ -z "$symbolizer" ]]; then
  symbolizer="$(command -v llvm-symbolizer || true)"
fi
if [[ -n "$symbolizer" && -x "$symbolizer" ]]; then
  symbolizer_kind=llvm
else
  symbolizer="$(command -v llvm-addr2line || command -v addr2line || true)"
  [[ -n "$symbolizer" ]] || {
    echo "llvm-symbolizer, llvm-addr2line, or addr2line is required" >&2
    exit 1
  }
  symbolizer_kind=addr2line
fi

mapfile -t frames < <(
  sed -nE 's/^[[:space:]]*#[0-9]+[[:space:]]+pc[[:space:]]+([0-9a-fA-F]+).*libxul\.so.*/\1/p; s/^[[:space:]]*(0x)?([0-9a-fA-F]+)[[:space:]]*$/\2/p' "$tombstone"
)
if [[ "${#frames[@]}" -eq 0 ]]; then
  echo "No libxul PCs found in $tombstone" >&2
  exit 1
fi

for pc in "${frames[@]}"; do
  address="0x$pc"
  if [[ "$symbolizer_kind" == llvm ]]; then
    result="$("$symbolizer" --inlining --demangle --obj="$symbols_input" "$address")"
  else
    result="$("$symbolizer" -f -C -i -e "$symbols_input" "$address")"
  fi
  printf '%s -> %s\n' "$address" "${result//$'\n'/ | }"
done
