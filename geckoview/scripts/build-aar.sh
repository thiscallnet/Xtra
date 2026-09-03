#!/usr/bin/env bash
set -euo pipefail

abi="${1:-arm64-v8a}"
profile="${2:-safe}"
build_jobs="${GECKO_BUILD_JOBS:-2}"
force_rebuild="${GECKO_FORCE_REBUILD:-0}"
symbols_dir="${GECKO_SYMBOLS_DIR:-}"
preflight_only="${GECKO_PREFLIGHT_ONLY:-0}"
reuse_source="${GECKO_REUSE_SOURCE:-0}"
case "$abi:$profile" in
  arm64-v8a:safe|\
  arm64-v8a:nowebrtc|\
  armeabi-v7a:safe|\
  armeabi-v7a:nowebrtc|\
  x86_64:auth|\
  x86_64:nowebrtc|\
  x86_64:nowebspeech|\
  x86_64:minimal|\
  x86_64:twitch-auth-radical-r1|\
  arm64-v8a:twitch-auth-radical-r1|\
  armeabi-v7a:twitch-auth-radical-r1|\
  x86:twitch-auth-radical-r1)
    ;;
  *)
    echo "Unsupported GeckoView build combination: $abi/$profile" >&2
    exit 2
    ;;
esac
if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
  echo "GECKO_BUILD_JOBS must be a positive integer: $build_jobs" >&2
  exit 2
fi
if [[ "$force_rebuild" != "0" && "$force_rebuild" != "1" ]]; then
  echo "GECKO_FORCE_REBUILD must be 0 or 1: $force_rebuild" >&2
  exit 2
fi
if [[ "$preflight_only" != "0" && "$preflight_only" != "1" ]]; then
  echo "GECKO_PREFLIGHT_ONLY must be 0 or 1: $preflight_only" >&2
  exit 2
fi
if [[ "$reuse_source" != "0" && "$reuse_source" != "1" ]]; then
  echo "GECKO_REUSE_SOURCE must be 0 or 1: $reuse_source" >&2
  exit 2
fi
if [[ -n "$symbols_dir" && "$force_rebuild" != "1" ]]; then
  echo "GECKO_SYMBOLS_DIR requires GECKO_FORCE_REBUILD=1" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/toolchain-cache.sh"
source_dir="${GECKO_SOURCE_DIR:-$repo_root/source}"
source_url="$(sed -n '1p' "$repo_root/SOURCE_REVISION")"
source_revision="$(sed -n '2p' "$repo_root/SOURCE_REVISION")"
artifact_dir="$repo_root/artifacts"
mozconfig="$source_dir/.mozconfig"
patch_file="$repo_root/patches/0001-disable-android-hls.patch"
no_webrtc_test_patch_file="$repo_root/patches/0002-exclude-webrtc-dependent-android-test.patch"
source_lock="$repo_root/SOURCE_LOCK.json"
source_cache_dir="${GECKO_SOURCE_CACHE_DIR:-${GECKO_SOURCE_SEED_DIR:-$repo_root/source-snapshot}}"
compile_recipe_version="$(tr -d '[:space:]' < "$repo_root/COMPILE_RECIPE_VERSION")"
config_files=(
  "$repo_root/SOURCE_REVISION"
  "$source_lock"
  "$repo_root/COMPILE_RECIPE_VERSION"
  "$repo_root/mozconfigs/common.mozconfig"
)
profile_config_files=("$repo_root/mozconfigs/${abi}-${profile}.mozconfig")
if [[ "$profile" == "twitch-auth-radical-r1" ]]; then
  profile_config_files=(
    "$repo_root/mozconfigs/twitch-auth-radical-r1.mozconfig"
    "${profile_config_files[0]}"
  )
fi
config_files+=("${profile_config_files[@]}")
config_files+=("$patch_file")
if [[ "$profile" == "nowebrtc" || "$profile" == "nowebspeech" || "$profile" == "minimal" || "$profile" == "twitch-auth-radical-r1" ]]; then
  config_files+=("$no_webrtc_test_patch_file")
fi
twitch_auth_lite_patch_file="$repo_root/patches/0003-twitch-auth-lite-build-cuts.patch"
if [[ "$profile" == "twitch-auth-radical-r1" ]]; then
  config_files+=("$twitch_auth_lite_patch_file")
fi
if [[ "$abi" == "x86" ]]; then
  # The Mozilla clang bundle used by Gecko 150 omits the i686 Android
  # compiler-rt runtime. The pinned NDK supplies the supported x86 runtime.
  config_files+=(
    "$repo_root/scripts/build-aar.sh"
    "$repo_root/scripts/toolchain-cache.sh"
    "$repo_root/scripts/verify-x86-config.py"
  )
fi

if [[ -z "$source_url" || -z "$source_revision" ]]; then
  echo "SOURCE_REVISION must contain a source URL and revision" >&2
  exit 1
fi
if [[ ! "$source_revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "SOURCE_REVISION must contain a 40-character Mercurial revision" >&2
  exit 1
fi
if [[ -z "$compile_recipe_version" ]]; then
  echo "COMPILE_RECIPE_VERSION must not be empty" >&2
  exit 1
fi

command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }
command -v sccache >/dev/null || { echo "sccache is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v patch >/dev/null || { echo "patch is required" >&2; exit 1; }

mapfile -t source_lock_values < <(python3 - "$source_lock" <<'PY'
import json
import sys

lock = json.loads(open(sys.argv[1], encoding="utf-8").read())
for key in ("acquisition", "archiveUrl", "archiveSha256", "sourceGitRevision"):
    print(lock.get(key) or "")
PY
)
# Windows Python writes text output with CRLF, even when the recipe files are LF.
# Strip that transport byte from every parsed lock value.
for index in "${!source_lock_values[@]}"; do
  source_lock_values[$index]="${source_lock_values[$index]%$'\r'}"
done
source_mode="${source_lock_values[0]}"
source_archive_url="${source_lock_values[1]}"
source_archive_sha256="${source_lock_values[2]}"
source_git_revision="${source_lock_values[3]}"
source "$repo_root/scripts/source-common.sh"

case "$source_mode" in
  github-commit-archive)
    command -v curl >/dev/null || { echo "curl is required for archive acquisition" >&2; exit 1; }
    command -v tar >/dev/null || { echo "tar is required for archive acquisition" >&2; exit 1; }
    ;;
  mercurial-seed-cache)
    command -v hg >/dev/null || { echo "Mercurial (hg) is required" >&2; exit 1; }
    ;;
  *) echo "Unsupported source acquisition mode: $source_mode" >&2; exit 1 ;;
esac

python3 "$repo_root/scripts/validate-recipe.py" >/dev/null
export MOZBUILD_STATE_PATH="${MOZBUILD_STATE_PATH:-${HOME:?}/.mozbuild}"
export SCCACHE_DIR="${SCCACHE_DIR:-$repo_root/.sccache}"
mkdir -p "$MOZBUILD_STATE_PATH" "$SCCACHE_DIR" "$artifact_dir"
bootstrap_marker="$MOZBUILD_STATE_PATH/.xtra-gecko-bootstrap-complete-${source_git_revision}"

report_disk() {
  local phase="$1"
  echo "::group::Disk usage: $phase"
  df -h / || true
  if [[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]]; then
    du -sh "$RUNNER_TEMP"/* 2>/dev/null | sort -h || true
  fi
  for path in "$MOZBUILD_STATE_PATH" "$SCCACHE_DIR" "$source_cache_dir" \
    "$source_dir" "${objdir:-}"; do
    [[ -n "$path" && -e "$path" ]] || continue
    du -sh "$path" 2>/dev/null || true
  done
  echo "::endgroup::"
}

report_x86_environment() {
  local phase="${1:?x86 diagnostic phase is required}"
  [[ "$abi" == "x86" ]] || return 0

  local name value compiler ndk_llvm mozilla_clang
  ndk_llvm="$MOZBUILD_STATE_PATH/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64"
  mozilla_clang="$MOZBUILD_STATE_PATH/clang/bin/clang"
  echo "::group::x86 compiler environment: $phase"
  echo "x86_diagnostic_phase=$phase"
  echo "x86_target=i686-linux-android"
  echo "x86_android_api=26"
  echo "x86_configure_command=./mach configure"
  echo "x86_build_command=./mach build --jobs=$build_jobs"
  for name in CC CXX HOST_CC HOST_CXX AR LD RANLIB AS CPP \
    CPPFLAGS CFLAGS CXXFLAGS LDFLAGS HOST_CFLAGS HOST_CXXFLAGS HOST_LDFLAGS \
    SCCACHE SCCACHE_REAL_BINARY SCCACHE_DIR SCCACHE_ERROR_LOG \
    SCCACHE_GHA_ENABLED SCCACHE_GHA_RW_MODE SCCACHE_GHA_VERSION \
    MOZCONFIG MOZBUILD_STATE_PATH MOZ_OBJDIR MOZ_AUTOMATION MOZ_WEBRTC \
    MOZ_TWITCH_AUTH_LITE; do
    value="${!name-}"
    [[ -n "$value" ]] || value='<unset>'
    printf 'x86_env_%s=%s\n' "$name" "$value"
  done
  echo "x86_active_mozconfig=$mozconfig"
  echo 'x86_active_mozconfig_contents<<EOF'
  sed 's/^/  /' "$mozconfig"
  echo 'EOF'
  for compiler in "$ndk_llvm/bin/clang" "$ndk_llvm/bin/clang++" \
    "$mozilla_clang" "$MOZBUILD_STATE_PATH/clang/bin/clang++"; do
    if [[ -x "$compiler" ]]; then
      echo "x86_compiler=$compiler"
      "$compiler" --version | head -n 1 || true
      echo "x86_compiler_resource_dir=$($compiler --print-resource-dir 2>/dev/null || true)"
    else
      echo "x86_compiler_missing=$compiler"
    fi
  done
  echo "x86_path_clang=$(command -v clang || true)"
  echo "x86_path_clangxx=$(command -v clang++ || true)"
  echo 'x86_configure_arguments=see active mozconfig above and MOZ_CONFIGURE_OPTIONS after configure'
  echo '::endgroup::'
}

build_id_for() {
  local binary="${1:?ELF path is required}"
  readelf -n "$binary" 2>/dev/null |
    sed -n 's/^[[:space:]]*Build ID: //p' |
    head -n 1
}

capture_diagnostic_symbols() {
  local libxul="$objdir/dist/bin/libxul.so"
  local libnss3="$objdir/dist/bin/libnss3.so"
  local symbol_log="$symbols_dir/buildsymbols.log"
  local symbol_status

  command -v readelf >/dev/null || {
    echo "readelf is required for diagnostic symbol capture" >&2
    return 1
  }
  [[ -f "$libxul" ]] || {
    echo "Unstripped libxul.so is missing: $libxul" >&2
    return 1
  }

  mkdir -p "$symbols_dir"
  cp "$libxul" "$symbols_dir/libxul.so"
  if [[ -f "$libnss3" ]]; then
    cp "$libnss3" "$symbols_dir/libnss3.so"
  else
    echo "warning: unstripped libnss3.so is missing: $libnss3" >&2
  fi
  cp "$mozconfig" "$symbols_dir/mozconfig"
  cp "$repo_root/SOURCE_REVISION" "$symbols_dir/SOURCE_REVISION"
  cp "$source_lock" "$symbols_dir/SOURCE_LOCK.json"
  cp "$repo_root/COMPILE_RECIPE_VERSION" "$symbols_dir/COMPILE_RECIPE_VERSION"
  [[ -f "$objdir/config.status.json" ]] && cp "$objdir/config.status.json" "$symbols_dir/config.status.json"
  {
    echo "source_revision=$source_revision"
    echo "source_git_revision=$source_git_revision"
    echo "profile=$profile"
    echo "abi=$abi"
    echo "configuration_digest=$config_digest"
    echo "compile_recipe_version=$compile_recipe_version"
    printf 'config_file_sha256=%s\n' "$(for config_file in "${config_files[@]}"; do sha256sum "$config_file"; done)"
  } > "$symbols_dir/provenance.txt"

  for binary in "$symbols_dir/libxul.so" "$symbols_dir/libnss3.so"; do
    [[ -f "$binary" ]] || continue
    readelf -n "$binary" > "$symbols_dir/$(basename "$binary").notes.txt"
    readelf -S "$binary" > "$symbols_dir/$(basename "$binary").sections.txt"
    echo "diagnostic_binary=$(basename "$binary") build_id=$(build_id_for "$binary")"
  done

  started="$SECONDS"
  set +e
  (
    cd "$source_dir"
    ./mach buildsymbols
  ) 2>&1 | tee "$symbol_log"
  symbol_status="${PIPESTATUS[0]}"
  set -e
  echo "$symbol_status" > "$symbols_dir/buildsymbols.exit"
  echo "timing phase=buildsymbols seconds=$((SECONDS - started)) exit=$symbol_status"
  if [[ "$symbol_status" -ne 0 ]]; then
    echo "warning: mach buildsymbols failed; retaining unstripped ELF files" >&2
  fi

  mkdir -p "$symbols_dir/breakpad"
  : > "$symbols_dir/breakpad/files.txt"
  while IFS= read -r symbol_file; do
    relative="${symbol_file#"$objdir/"}"
    destination="$symbols_dir/breakpad/$relative"
    mkdir -p "$(dirname "$destination")"
    cp "$symbol_file" "$destination"
    printf '%s\n' "$relative" >> "$symbols_dir/breakpad/files.txt"
  done < <(
    find "$objdir" -type f \( -name '*.sym' -o -name '*.sym.gz' -o \
      -iname '*symbols*.zip' -o -iname '*symbols*.tar*' \) -print | sort
  )
  if [[ ! -s "$symbols_dir/breakpad/files.txt" ]]; then
    echo "No Breakpad/Socorro symbol archive was found under $objdir" |
      tee "$symbols_dir/breakpad/README.txt"
  fi
}

ensure_no_webrtc_test_patch_state() {
  apply_no_webrtc_test_patch() {
    (
      cd "$source_dir"
      patch --batch "$@" -p1 < "$no_webrtc_test_patch_file"
    )
  }

  if apply_no_webrtc_test_patch --forward --dry-run >/dev/null 2>&1; then
    apply_no_webrtc_test_patch --forward
  elif apply_no_webrtc_test_patch --reverse --dry-run >/dev/null 2>&1; then
    echo "no-WebRTC test patch already applied"
  else
    echo "Pinned Gecko source does not accept the no-WebRTC test patch" >&2
    exit 1
  fi

  test ! -e "$source_dir/mobile/android/geckoview/src/androidTest/java/org/mozilla/geckoview/test/VideoCaptureTest.kt" || {
    echo "no-WebRTC test source was not removed: VideoCaptureTest.kt" >&2
    exit 1
  }
  echo "no-WebRTC test source removed: VideoCaptureTest.kt"
}

ensure_twitch_auth_lite_patch_state() {
  apply_twitch_auth_lite_patch() {
    (
      cd "$source_dir"
      patch --batch "$@" -p1 < "$twitch_auth_lite_patch_file"
    )
  }

  if apply_twitch_auth_lite_patch --forward --dry-run >/dev/null 2>&1; then
    apply_twitch_auth_lite_patch --forward
  elif apply_twitch_auth_lite_patch --reverse --dry-run >/dev/null 2>&1; then
    echo "Twitch auth-lite source patch already applied"
  else
    echo "Twitch auth-lite patch forward dry-run diagnostics:" >&2
    apply_twitch_auth_lite_patch --forward --dry-run >&2 || true
    echo "Pinned Gecko source does not accept the Twitch auth-lite source patch" >&2
    exit 1
  fi

  grep -q 'MOZ_TWITCH_AUTH_LITE' "$source_dir/toolkit/moz.configure" || {
    echo "Twitch auth-lite configure flag was not installed" >&2
    exit 1
  }
  echo "Twitch auth-lite source patch applied"
}

verify_radical_android_dependencies() {
  [[ "$profile" == "twitch-auth-radical-r1" ]] || return 0

  local accessibility_header="$source_dir/accessible/android/SessionAccessibility.h"
  local accessibility_build="$source_dir/accessible/android/moz.build"
  local android_window="$source_dir/widget/android/nsWindow.cpp"
  [[ -f "$accessibility_header" ]] || {
    echo "Required Android accessibility header is missing: $accessibility_header" >&2
    return 1
  }
  grep -Fq 'EXPORTS.mozilla.a11y +=' "$accessibility_build" || {
    echo "Android accessibility header export declaration is missing" >&2
    return 1
  }
  grep -Fq '"SessionAccessibility.h"' "$accessibility_build" || {
    echo "SessionAccessibility.h is not exported by accessible/android/moz.build" >&2
    return 1
  }
  grep -Fq '#include "mozilla/a11y/SessionAccessibility.h"' "$android_window" || {
    echo "Android widget accessibility include contract changed unexpectedly" >&2
    return 1
  }
  echo "radical_android_dependencies=validated accessibility=enabled"
}

run_radical_android_test_preflight() {
  [[ "$profile" == "twitch-auth-radical-r1" ]] || return 0

  local android_test_dir="$source_dir/mobile/android/geckoview/src/androidTest"
  local removed_test="$android_test_dir/java/org/mozilla/geckoview/test/VideoCaptureTest.kt"
  [[ ! -e "$removed_test" ]] || {
    echo "WebRTC-dependent Android test remains in the source set: $removed_test" >&2
    return 1
  }
  if grep -RIl --include='*.kt' --include='*.java' 'org\.webrtc' "$android_test_dir"; then
    echo "Android-test source still imports WebRTC while MOZ_WEBRTC=false" >&2
    return 1
  fi
  echo "android_test_preflight=passed source=VideoCaptureTest.kt-absent webrtc-imports=absent"
}

verify_diagnostic_build_ids() {
  local packaged_libxul="$symbols_dir/packaged-libxul.so"
  local unstripped_id packaged_id

  [[ -f "$symbols_dir/libxul.so" ]] || {
    echo "Diagnostic libxul.so was not captured: $symbols_dir/libxul.so" >&2
    return 1
  }
  unzip -p "${aar_files[0]}" "jni/$abi/libxul.so" > "$packaged_libxul"
  readelf -n "$packaged_libxul" > "$symbols_dir/packaged-libxul.so.notes.txt"
  unstripped_id="$(build_id_for "$symbols_dir/libxul.so")"
  packaged_id="$(build_id_for "$packaged_libxul")"
  {
    echo "unstripped_libxul_build_id=$unstripped_id"
    echo "packaged_libxul_build_id=$packaged_id"
  } | tee "$symbols_dir/build-id-report.txt"
  [[ -n "$unstripped_id" && "$unstripped_id" == "$packaged_id" ]] || {
    echo "Diagnostic and packaged libxul Build IDs do not match" >&2
    return 1
  }
}

config_digest="$({
  for config_file in "${config_files[@]}"; do
    sha256sum "$config_file" | awk '{print $1}'
  done
} | sha256sum | awk '{print $1}')"
artifact_name="xtra-geckoview-${abi}-${profile}-${source_revision:0:12}-${config_digest:0:12}"
artifact="$artifact_dir/$artifact_name.aar"
maven_archive="$artifact_dir/$artifact_name.target.maven.zip"
cache_dir="${GECKO_COMPILED_CACHE_DIR:-}"
cache_artifact="${cache_dir:+$cache_dir/$artifact_name.aar}"
cache_maven="${cache_dir:+$cache_dir/$artifact_name.target.maven.zip}"

if [[ "${GECKO_BOOTSTRAP_ONLY:-0}" == "1" ]]; then
  prepare_gecko_source
  report_disk source-extraction
  ensure_clean_source_before_patch
  assert_hls_patch_state

  started="$SECONDS"
  (
    cd "$source_dir"
    ./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"
  )
  install_gecko_rust_toolchain
  validate_restored_toolchain "$MOZBUILD_STATE_PATH"
  touch "$bootstrap_marker"
  echo "bootstrap_marker=complete path=$bootstrap_marker"
  echo "timing phase=bootstrap seconds=$((SECONDS - started))"
  report_disk bootstrap
  exit 0
fi

if [[ "$force_rebuild" != "1" && -n "$cache_dir" && -f "$cache_artifact" && -f "$cache_maven" ]]; then
  echo "compiled_gecko_cache=hit abi=$abi profile=$profile compile_digest=$config_digest"
  "$repo_root/scripts/verify-aar.sh" "$cache_artifact" "$abi" "$profile" \
    "$source_revision" "$config_digest" "$cache_maven"
  if [[ "$abi:$profile" == "x86_64:auth" ]]; then
    "$repo_root/scripts/verify-auth-content-services.sh" "$cache_artifact"
  fi
  cp "$cache_artifact" "$artifact"
  cp "$cache_maven" "$maven_archive"
  cp "${cache_artifact%.aar}.json" "${artifact%.aar}.json"
  "$repo_root/scripts/measure-artifacts.sh" "$artifact_name" "$artifact" \
    - not-tested "$artifact_dir/measurements.tsv"
  if ! sccache --show-stats; then
    echo "warning: unable to collect sccache statistics" >&2
  fi
  exit 0
fi
echo "compiled_gecko_cache=miss abi=$abi profile=$profile compile_digest=$config_digest"

prepare_gecko_source
report_disk source-extraction
ensure_clean_source_before_patch
assert_hls_patch_state
if [[ "$profile" == "nowebrtc" || "$profile" == "nowebspeech" || "$profile" == "minimal" || "$profile" == "twitch-auth-radical-r1" ]]; then
  ensure_no_webrtc_test_patch_state
fi
if [[ "$profile" == "twitch-auth-radical-r1" ]]; then
  ensure_twitch_auth_lite_patch_state
fi

if [[ "${GECKO_SKIP_BOOTSTRAP:-0}" == "1" ]]; then
  use_gecko_rust_toolchain
  ensure_restored_toolchain_marker "$MOZBUILD_STATE_PATH" "$bootstrap_marker"
  echo "timing phase=bootstrap skipped marker=$bootstrap_marker"
else
  started="$SECONDS"
  (
    cd "$source_dir"
    ./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"
  )
  install_gecko_rust_toolchain
  validate_restored_toolchain "$MOZBUILD_STATE_PATH"
  touch "$bootstrap_marker"
  echo "bootstrap_marker=complete path=$bootstrap_marker"
  echo "timing phase=bootstrap seconds=$((SECONDS - started))"
fi
report_disk bootstrap

if [[ "$abi" == "x86" ]]; then
  select_x86_android_compiler "$MOZBUILD_STATE_PATH"
fi

if [[ "$abi" == "x86" ]]; then
  # x86 carries one explicit sccache wrapper in CC/CXX. Keeping the common
  # --with-ccache option here would make Gecko's nested Gradle configure wrap
  # those already-wrapped commands a second time.
  sed '/^ac_add_options --with-ccache=sccache$/d' \
    "$repo_root/mozconfigs/common.mozconfig" > "$mozconfig"
else
  cat "$repo_root/mozconfigs/common.mozconfig" > "$mozconfig"
fi
cat "${profile_config_files[@]}" >> "$mozconfig"
if [[ "$abi" == "x86" ]]; then
  # Gradle's nested machConfigure is a new process and does not reliably
  # inherit the shell-only WASM compiler selection. Persist all x86 compiler
  # selections so the nested configure uses the same toolchain as the probe.
  {
    printf 'export CC=%q\n' "$CC"
    printf 'export CXX=%q\n' "$CXX"
    printf 'export WASM_CC=%q\n' "$WASM_CC"
    printf 'export WASM_CXX=%q\n' "$WASM_CXX"
  } >> "$mozconfig"
fi
export MOZCONFIG="$mozconfig"

objdir="$(sed -n 's/^mk_add_options MOZ_OBJDIR=//p' "$mozconfig" | sed 's#@TOPSRCDIR@#'"$source_dir"'#')"
if [[ -z "$objdir" ]]; then
  echo "Unable to determine Gecko object directory" >&2
  exit 1
fi

report_x86_environment configure
started="$SECONDS"
(
  cd "$source_dir"
  ./mach configure
)
echo "timing phase=configure seconds=$((SECONDS - started))"
if [[ "$abi" == "x86" ]]; then
  python3 "$repo_root/scripts/verify-x86-config.py" "$objdir/config.status.json"
fi
report_disk configure
python3 "$repo_root/scripts/verify-config.py" "$objdir/config.status.json" "$profile"
verify_radical_android_dependencies

if [[ "$preflight_only" == "1" ]]; then
  run_radical_android_test_preflight
  echo "timing phase=configure-preflight seconds=0"
  echo "configure_preflight=passed profile=$profile"
  exit 0
fi

started="$SECONDS"
report_x86_environment native-build
(
  cd "$source_dir"
  ./mach build --jobs="$build_jobs"
)
echo "timing phase=native-build seconds=$((SECONDS - started)) jobs=$build_jobs"
report_disk native-build

if [[ -n "$symbols_dir" ]]; then
  capture_diagnostic_symbols
fi

started="$SECONDS"
(
  cd "$source_dir"
  ./mach package
)
echo "timing phase=package seconds=$((SECONDS - started))"
report_disk package

aar_dir="$objdir/gradle/build/mobile/android/geckoview/outputs/aar"
if [[ ! -d "$aar_dir" ]]; then
  echo "Expected GeckoView AAR directory is missing: $aar_dir" >&2
  exit 1
fi
mapfile -t aar_files < <(find "$aar_dir" -maxdepth 1 -type f -name '*.aar' -print | sort)
if [[ "${#aar_files[@]}" -ne 1 ]]; then
  printf 'Expected exactly one GeckoView AAR, found %s:\n' "${#aar_files[@]}" >&2
  printf '  %s\n' "${aar_files[@]}" >&2
  exit 1
fi

maven_dir="$objdir/gradle/maven"
if [[ ! -d "$maven_dir" ]]; then
  echo "Expected GeckoView Maven directory is missing: $maven_dir" >&2
  exit 1
fi
if [[ -z "$(find "$maven_dir" -type f -print -quit)" ]]; then
  echo "Expected GeckoView Maven directory is empty: $maven_dir" >&2
  exit 1
fi

target_maven_zip="$objdir/gradle/target.maven.zip"
started="$SECONDS"
python3 "$repo_root/scripts/create-maven-archive.py" "$maven_dir" "$target_maven_zip"
echo "timing phase=maven-archive seconds=$((SECONDS - started))"
report_disk maven-archive
if [[ ! -s "$target_maven_zip" ]]; then
  echo "Maven archive was not created: $target_maven_zip" >&2
  exit 1
fi
cp "${aar_files[0]}" "$artifact"
cp "$target_maven_zip" "$maven_archive"
if [[ -n "$symbols_dir" ]]; then
  verify_diagnostic_build_ids
fi
"$repo_root/scripts/verify-aar.sh" "$artifact" "$abi" "$profile" \
  "$source_revision" "$config_digest" "$maven_archive"
if [[ "$abi:$profile" == "x86_64:auth" ]]; then
  "$repo_root/scripts/verify-auth-content-services.sh" "$artifact"
fi
"$repo_root/scripts/measure-artifacts.sh" "$artifact_name" "$artifact" \
  - not-tested "$artifact_dir/measurements.tsv"

if [[ -n "$cache_dir" ]]; then
  mkdir -p "$cache_dir"
  cp "$artifact" "$cache_artifact"
  cp "$maven_archive" "$cache_maven"
  cp "${artifact%.aar}.json" "$cache_dir/$artifact_name.json"
fi
if ! sccache --show-stats; then
  echo "warning: unable to collect sccache statistics" >&2
fi
