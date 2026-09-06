#!/usr/bin/env bash

toolchain_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rust_version_file="$toolchain_script_dir/../RUST_TOOLCHAIN_VERSION"
if [[ -z "${GECKO_RUST_VERSION:-}" && -f "$rust_version_file" ]]; then
  GECKO_RUST_VERSION="$(sed -n '1p' "$rust_version_file")"
fi
GECKO_RUST_VERSION="${GECKO_RUST_VERSION:-1.97.1}"
GECKO_RUST_TARGETS=(
  thumbv7neon-linux-androideabi
  aarch64-linux-android
  i686-linux-android
  x86_64-linux-android
)

select_x86_android_compiler() {
  local mozbuild_state_path="${1:?Mozilla state path is required}"
  local ndk_llvm="$mozbuild_state_path/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64"
  local ndk_bin="$ndk_llvm/bin"
  local clang="$ndk_bin/clang"
  local clangxx="$ndk_bin/clang++"
  local mozilla_clang="$mozbuild_state_path/clang/bin/clang"
  local mozilla_clangxx="$mozbuild_state_path/clang/bin/clang++"
  local builtins unwind probe_dir probe_output sccache_command
  local -a cc_command cxx_command cppflags_array cflags_array cxxflags_array ldflags_array

  [[ -x "$clang" ]] || {
    echo "x86 Android NDK clang is missing: $clang" >&2
    return 1
  }
  [[ -x "$clangxx" ]] || {
    echo "x86 Android NDK clang++ is missing: $clangxx" >&2
    return 1
  }
  [[ -x "$mozilla_clang" ]] || {
    echo "Mozilla clang is missing for WASI configure checks: $mozilla_clang" >&2
    return 1
  }
  [[ -x "$mozilla_clangxx" ]] || {
    echo "Mozilla clang++ is missing for WASI configure checks: $mozilla_clangxx" >&2
    return 1
  }

  builtins="$(find "$ndk_llvm/lib/clang" \( -type f -o -type l \) \
    -name 'libclang_rt.builtins-i686-android.a' -print -quit 2>/dev/null || true)"
  [[ -n "$builtins" ]] || {
    echo "x86 Android NDK compiler-rt builtins are missing under $ndk_llvm" >&2
    return 1
  }
  unwind="$(find "$ndk_llvm/lib/clang" \( -type f -o -type l \) \
    -name 'libunwind.a' -print -quit 2>/dev/null || true)"

  sccache_command="${SCCACHE:-$(command -v sccache)}"
  [[ -x "$sccache_command" ]] || {
    echo "x86 sccache wrapper is missing: $sccache_command" >&2
    return 1
  }

  # Gecko's nested Gradle configure reuses the configured CC/CXX values. Keep
  # the wrapper in those values and do not also enable --with-ccache for x86.
  # That gives every configure exactly one sccache layer.
  export CC="$sccache_command $clang"
  export CXX="$sccache_command $clangxx"
  # CC/CXX are the Android target compiler. Gecko configures a separate WASI
  # compiler for sandboxed libraries; the Android NDK has no WASI runtime, so
  # leave that probe on Mozilla's clang bundle.
  export WASM_CC="$mozilla_clang"
  export WASM_CXX="$mozilla_clangxx"
  echo "x86_target=i686-linux-android26"
  echo "x86_path_clang=$(command -v clang || true)"
  echo "x86_path_clangxx=$(command -v clang++ || true)"
  echo "x86_android_clang_resource_dir=$($clang --print-resource-dir)"
  echo "x86_android_compiler=$CC"
  echo "x86_android_compiler_version=$($clang --version | head -n 1)"
  echo "wasi_compiler=$WASM_CC"
  echo "x86_android_builtins=$builtins"
  echo "x86_CFLAGS=${CFLAGS:-}"
  echo "x86_CXXFLAGS=${CXXFLAGS:-}"
  echo "x86_LDFLAGS=${LDFLAGS:-}"
  if [[ -n "$unwind" ]]; then
    echo "x86_android_unwind=$unwind"
  else
    echo "x86_android_unwind=driver-default"
  fi

  probe_dir="$(mktemp -d)"
  probe_output="$probe_dir/probe"
  read -r -a cc_command <<< "$CC"
  read -r -a cxx_command <<< "$CXX"
  read -r -a cppflags_array <<< "${CPPFLAGS:-}"
  read -r -a cflags_array <<< "${CFLAGS:-}"
  read -r -a cxxflags_array <<< "${CXXFLAGS:-}"
  read -r -a ldflags_array <<< "${LDFLAGS:-}"
  echo "x86_probe_CC=${cc_command[*]}"
  echo "x86_probe_CXX=${cxx_command[*]}"
  echo "x86_probe_target=i686-linux-android26"
  echo "x86_probe_sysroot=$ndk_llvm/sysroot"
  echo "x86_probe_gcc_toolchain=$ndk_llvm"
  echo "x86_probe_linker=-fuse-ld=lld"
  echo "x86_probe_CPPFLAGS=${CPPFLAGS:-}"
  echo "x86_probe_CFLAGS=${CFLAGS:-}"
  echo "x86_probe_CXXFLAGS=${CXXFLAGS:-}"
  echo "x86_probe_LDFLAGS=${LDFLAGS:-}"
  if ! printf 'int main(void) { return 0; }\n' | \
    "${cc_command[@]}" --target=i686-linux-android26 \
    --sysroot="$ndk_llvm/sysroot" \
    --gcc-toolchain="$ndk_llvm" \
    -fuse-ld=lld "${cppflags_array[@]}" "${cflags_array[@]}" \
    "${ldflags_array[@]}" -x c - -o "$probe_output"; then
    rm -rf "$probe_dir"
    echo "x86 Android NDK compiler cannot link a minimal i686 Android binary" >&2
    return 1
  fi
  if ! printf 'int main() { return 0; }\n' | \
    "${cxx_command[@]}" --target=i686-linux-android26 \
    --sysroot="$ndk_llvm/sysroot" \
    --gcc-toolchain="$ndk_llvm" \
    -fuse-ld=lld "${cppflags_array[@]}" "${cxxflags_array[@]}" \
    "${ldflags_array[@]}" -x c++ - -o "$probe_output"; then
    rm -rf "$probe_dir"
    echo "x86 Android NDK compiler cannot link a minimal i686 C++ binary" >&2
    return 1
  fi
  rm -rf "$probe_dir"
  echo "x86_android_link_probe=passed"
}

gecko_rust_bin_dir() {
  local rustc_path
  command -v rustup >/dev/null 2>&1 || return 1
  rustc_path="$(rustup which --toolchain "$GECKO_RUST_VERSION" rustc 2>/dev/null || true)"
  [[ -n "$rustc_path" && -x "$rustc_path" ]] || return 1
  dirname "$rustc_path"
}

use_gecko_rust_toolchain() {
  local rust_bin_dir rustc_path rustc_version
  if [[ -n "${RUSTC:-}" && -x "$RUSTC" && -n "${CARGO:-}" && -x "$CARGO" ]]; then
    rustc_version="$($RUSTC --version 2>/dev/null || true)"
    if [[ "$rustc_version" == "rustc $GECKO_RUST_VERSION "* ]]; then
      export PATH="$(dirname "$RUSTC"):$PATH"
      echo "Using pinned Gecko Rust toolchain: $GECKO_RUST_VERSION"
      "$RUSTC" --version
      "$CARGO" --version
      return 0
    fi
  fi
  rust_bin_dir="$(gecko_rust_bin_dir)" || {
    echo "Required Rust toolchain $GECKO_RUST_VERSION is not installed" >&2
    return 1
  }
  export PATH="$rust_bin_dir:$PATH"
  export RUSTC="$rust_bin_dir/rustc"
  export CARGO="$rust_bin_dir/cargo"
  echo "Using pinned Gecko Rust toolchain: $GECKO_RUST_VERSION"
  "$RUSTC" --version
  "$CARGO" --version
}

install_gecko_rust_toolchain() {
  command -v rustup >/dev/null 2>&1 || {
    echo "rustup is required to install Rust $GECKO_RUST_VERSION" >&2
    return 1
  }
  if ! gecko_rust_bin_dir >/dev/null; then
    rustup toolchain install "$GECKO_RUST_VERSION" --profile minimal
  fi
  local target installed_targets
  installed_targets="$(rustup target list --installed --toolchain "$GECKO_RUST_VERSION")"
  for target in "${GECKO_RUST_TARGETS[@]}"; do
    if ! grep -Fqx "$target" <<< "$installed_targets"; then
      rustup target add --toolchain "$GECKO_RUST_VERSION" "$target"
    fi
  done
  use_gecko_rust_toolchain
}

validate_restored_toolchain() {
  local mozbuild_state_path="${1:?Mozilla state path is required}"
  local ndk_llvm="$mozbuild_state_path/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64"
  local clang="$mozbuild_state_path/clang/bin/clang"
  local cached_sccache="$mozbuild_state_path/sccache/sccache"
  local rustc_path cargo_path
  local missing=()

  [[ -d "$ndk_llvm" ]] || missing+=("directory: $ndk_llvm")
  [[ -x "$clang" ]] || missing+=("executable: $clang")
  [[ -x "$cached_sccache" ]] || missing+=("executable: $cached_sccache")

  rustc_path="${RUSTC:-}"
  cargo_path="${CARGO:-}"
  [[ -n "$rustc_path" && -x "$rustc_path" ]] || missing+=("pinned Rust $GECKO_RUST_VERSION rustc")
  [[ -n "$cargo_path" && -x "$cargo_path" ]] || missing+=("pinned Rust $GECKO_RUST_VERSION cargo")

  local rustc_version=""
  if [[ -n "$rustc_path" && -x "$rustc_path" ]]; then
    rustc_version="$($rustc_path --version 2>/dev/null || true)"
    if [[ "$rustc_version" != "rustc $GECKO_RUST_VERSION "* ]]; then
      missing+=("wrong Rust toolchain (expected $GECKO_RUST_VERSION): $rustc_version")
    fi
  fi

  local installed_targets
  installed_targets="$(rustup target list --installed --toolchain "$GECKO_RUST_VERSION" 2>/dev/null || true)"
  for target in "${GECKO_RUST_TARGETS[@]}"; do
    grep -Fqx "$target" <<< "$installed_targets" ||
      missing+=("Rust target $target for $GECKO_RUST_VERSION")
  done

  if ((${#missing[@]})); then
    echo "Restored Mozilla toolchain is incomplete; refusing to bootstrap over it." >&2
    printf 'Missing or invalid component: %s\n' "${missing[@]}" >&2
    return 1
  fi

  echo "Restored toolchain versions:"
  "$clang" --version
  echo "$rustc_version"
  "$cargo_path" --version
  "$cached_sccache" --version
}

ensure_restored_toolchain_marker() {
  local mozbuild_state_path="${1:?Mozilla state path is required}"
  local bootstrap_marker="${2:?Bootstrap marker path is required}"

  if ! use_gecko_rust_toolchain || ! validate_restored_toolchain "$mozbuild_state_path"; then
    return 1
  fi
  if [[ -f "$bootstrap_marker" ]]; then
    echo "restored-toolchain=validated marker=present"
  else
    touch "$bootstrap_marker"
    echo "restored-toolchain=validated marker=repair"
  fi
}
