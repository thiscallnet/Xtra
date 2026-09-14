#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/toolchain-cache.sh"

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

state_dir="$temp_dir/state"
ndk_llvm="$state_dir/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64"
mozilla_clang_dir="$state_dir/clang/bin"
wrapper_dir="$temp_dir/wrappers"
mkdir -p "$ndk_llvm/bin" "$ndk_llvm/lib/clang/21/lib/linux/i386" \
  "$ndk_llvm/sysroot" "$mozilla_clang_dir" "$wrapper_dir"

cat > "$ndk_llvm/bin/clang" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *" --version "* ]]; then
  echo 'Android clang test compiler'
  exit 0
fi
output=''
previous=''
for argument in "$@"; do
  if [[ "$previous" == '-o' ]]; then
    output="$argument"
  fi
  previous="$argument"
done
cat >/dev/null
[[ -n "$output" ]] && : > "$output"
EOF
cp "$ndk_llvm/bin/clang" "$ndk_llvm/bin/clang++"
cp "$ndk_llvm/bin/clang" "$mozilla_clang_dir/clang"
cp "$ndk_llvm/bin/clang" "$mozilla_clang_dir/clang++"
chmod +x "$ndk_llvm/bin/clang" "$ndk_llvm/bin/clang++"
chmod +x "$mozilla_clang_dir/clang" "$mozilla_clang_dir/clang++"
touch "$ndk_llvm/lib/clang/21/lib/linux/libclang_rt.builtins-i686-android.a"

cat > "$wrapper_dir/sccache" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
chmod +x "$wrapper_dir/sccache"
export SCCACHE="$wrapper_dir/sccache"

select_x86_android_compiler "$state_dir"
[[ "$CC" == "$wrapper_dir/sccache $ndk_llvm/bin/clang" ]]
[[ "$CXX" == "$wrapper_dir/sccache $ndk_llvm/bin/clang++" ]]
[[ "$WASM_CC" == "$mozilla_clang_dir/clang" ]]
[[ "$WASM_CXX" == "$mozilla_clang_dir/clang++" ]]

config_path="$temp_dir/config.status.json"
cat > "$config_path" <<EOF
{"substs": {
  "CC": "$wrapper_dir/sccache $ndk_llvm/bin/clang --target=i686-linux-android26",
  "CXX": "$wrapper_dir/sccache $ndk_llvm/bin/clang++ --target=i686-linux-android26",
  "WASM_CC": ["$mozilla_clang_dir/clang", "--target=wasm32-wasi"],
  "WASM_CXX": ["$mozilla_clang_dir/clang++", "--target=wasm32-wasi"],
  "CCACHE": "",
  "MOZ_CONFIGURE_OPTIONS": ""
}}
EOF
python3 "$repo_root/scripts/verify-x86-config.py" "$config_path" >/dev/null

cat > "$config_path" <<EOF
{"substs": {
  "CC": "$wrapper_dir/sccache $wrapper_dir/sccache $ndk_llvm/bin/clang",
  "CXX": "$wrapper_dir/sccache $ndk_llvm/bin/clang++",
  "WASM_CC": ["$mozilla_clang_dir/clang", "--target=wasm32-wasi"],
  "WASM_CXX": ["$mozilla_clang_dir/clang++", "--target=wasm32-wasi"],
  "CCACHE": "",
  "MOZ_CONFIGURE_OPTIONS": ""
}}
EOF
if python3 "$repo_root/scripts/verify-x86-config.py" "$config_path" >/dev/null 2>&1; then
  echo 'double-sccache configuration was accepted' >&2
  exit 1
fi

echo 'x86-android-toolchain-tests=passed'
