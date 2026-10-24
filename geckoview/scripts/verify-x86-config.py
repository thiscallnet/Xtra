#!/usr/bin/env python3
"""Check the effective x86 compiler commands before the native build."""

import json
import shlex
import sys
from pathlib import Path


def compiler_tokens(config, name):
    value = config.get("substs", {}).get(name, "")
    if not isinstance(value, str) or not value:
        raise ValueError(f"configure output has no {name} command")
    return value, shlex.split(value)


def check_compiler(command, tokens, compiler_name):
    sccache_count = sum(Path(token).name == "sccache" for token in tokens)
    if sccache_count > 1:
        raise ValueError(f"{compiler_name} contains more than one sccache wrapper: {command}")

    compiler_suffix = "clang++" if compiler_name == "CXX" else "clang"
    compiler_indexes = [
        index
        for index, token in enumerate(tokens)
        if Path(token).name == compiler_suffix
    ]
    if len(compiler_indexes) != 1:
        raise ValueError(f"{compiler_name} does not contain exactly one clang compiler: {command}")
    compiler = tokens[compiler_indexes[0]]
    if "/android-ndk-r29/" not in compiler:
        raise ValueError(f"{compiler_name} does not use the Android NDK compiler: {command}")


def print_substitution(substitutions, name):
    if name in substitutions:
        print(f"x86_config_{name}={json.dumps(substitutions[name], sort_keys=True)}")


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} CONFIG.STATUS.JSON", file=sys.stderr)
        return 2

    config_path = Path(sys.argv[1])
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        cc_command, cc_tokens = compiler_tokens(config, "CC")
        cxx_command, cxx_tokens = compiler_tokens(config, "CXX")
        check_compiler(cc_command, cc_tokens, "CC")
        check_compiler(cxx_command, cxx_tokens, "CXX")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"x86 compiler configuration is invalid: {error}", file=sys.stderr)
        return 1

    substitutions = config["substs"]
    if substitutions.get("CCACHE", ""):
        print(
            "x86 compiler configuration unexpectedly enables configure-level CCACHE; "
            "this would double-wrap CC/CXX",
            file=sys.stderr,
        )
        return 1
    options = substitutions.get("MOZ_CONFIGURE_OPTIONS", "")
    if "--with-ccache" in options:
        print(
            "x86 configure options unexpectedly contain --with-ccache; "
            "the compiler commands already carry one sccache wrapper",
            file=sys.stderr,
        )
        return 1

    wasm_cc = substitutions.get("WASM_CC", [])
    wasm_cxx = substitutions.get("WASM_CXX", [])
    if not isinstance(wasm_cc, list) or not wasm_cc:
        print("x86 compiler configuration has no WASM_CC command", file=sys.stderr)
        return 1
    if not isinstance(wasm_cxx, list) or not wasm_cxx:
        print("x86 compiler configuration has no WASM_CXX command", file=sys.stderr)
        return 1
    if "/android-ndk-r29/" in wasm_cc[0] or "/android-ndk-r29/" in wasm_cxx[0]:
        print("x86 WASI compiler unexpectedly uses the Android NDK compiler", file=sys.stderr)
        return 1

    print(f"x86_effective_CC={cc_command}")
    print(f"x86_effective_CXX={cxx_command}")
    for name in (
        "HOST_CC",
        "HOST_CXX",
        "AR",
        "LD",
        "RANLIB",
        "CFLAGS",
        "CXXFLAGS",
        "LDFLAGS",
        "CPPFLAGS",
        "HOST_CFLAGS",
        "HOST_CXXFLAGS",
        "HOST_LDFLAGS",
        "WASM_CC",
        "WASM_CXX",
        "MOZ_CONFIGURE_OPTIONS",
    ):
        print_substitution(substitutions, name)
    print("x86_effective_compiler_wrappers=one-sccache-layer")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
