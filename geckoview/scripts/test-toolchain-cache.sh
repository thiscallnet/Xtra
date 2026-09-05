#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/toolchain-cache.sh"

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

fake_bin="$temp_dir/bin"
state_dir="$temp_dir/state"
marker="$state_dir/.xtra-gecko-bootstrap-complete-test"
mkdir -p "$fake_bin" \
  "$state_dir/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64" \
  "$state_dir/clang/bin" \
  "$state_dir/sccache"

true_path="$(type -P true)"
for command_name in sccache; do
  cp "$true_path" "$fake_bin/$command_name"
done
cp "$true_path" "$state_dir/clang/bin/clang"
cp "$true_path" "$state_dir/sccache/sccache"

for command_name in rustc cargo; do
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "rustc 1.97.1 (test)"\n' \
    "$command_name" > "$fake_bin/$command_name"
  chmod +x "$fake_bin/$command_name"
done
cat > "$fake_bin/rustup" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == target && "$2" == list && "$3" == --installed ]]; then
  printf '%s\n' \
    thumbv7neon-linux-androideabi \
    aarch64-linux-android \
    i686-linux-android \
    x86_64-linux-android
  exit 0
fi
exit 1
EOF
chmod +x "$fake_bin/rustup"
export PATH="$fake_bin:$PATH"
export RUSTC="$fake_bin/rustc"
export CARGO="$fake_bin/cargo"
export GECKO_RUST_VERSION=1.97.1

# A: marker exists and the restored toolchain is complete.
touch "$marker"
ensure_restored_toolchain_marker "$state_dir" "$marker" > "$temp_dir/marker-present.log"
grep -q 'restored-toolchain=validated marker=present' "$temp_dir/marker-present.log"

# B: marker is repaired when the restored toolchain is complete.
rm -f "$marker"
ensure_restored_toolchain_marker "$state_dir" "$marker" > "$temp_dir/marker-repair.log"
grep -q 'restored-toolchain=validated marker=repair' "$temp_dir/marker-repair.log"
test -f "$marker"

# C: an incomplete restored toolchain fails without creating a marker.
incomplete_state="$temp_dir/incomplete-state"
incomplete_marker="$incomplete_state/.xtra-gecko-bootstrap-complete-test"
mkdir -p "$incomplete_state"
if ensure_restored_toolchain_marker "$incomplete_state" "$incomplete_marker"; then
  echo 'Incomplete restored toolchain unexpectedly validated' >&2
  exit 1
fi
test ! -e "$incomplete_marker"

# D: Rust 1.98 is rejected even when the rest of the restored tree exists.
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "rustc 1.98.0 (test)"\n' \
  rustc > "$fake_bin/rustc"
chmod +x "$fake_bin/rustc"
export RUSTC="$fake_bin/rustc"
incompatible_marker="$state_dir/.xtra-gecko-bootstrap-complete-incompatible"
if validate_restored_toolchain "$state_dir"; then
  echo 'Incompatible Rust toolchain unexpectedly validated' >&2
  exit 1
fi
test ! -e "$incompatible_marker"

echo 'toolchain-cache-tests=passed'
