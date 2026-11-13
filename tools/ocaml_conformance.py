#!/usr/bin/env python3
"""Differential conformance harness: execute paired OCaml/OW module programs.

Success fixtures compare the exact numeric output. Rejection fixtures must fail
in both compilers, which catches accidental loss of OW signature sealing.
The interpreter, static checker and integer AOT path are separate assertions.
"""
from __future__ import annotations

from pathlib import Path
from subprocess import run
from tempfile import TemporaryDirectory
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "ocaml_conformance"
OW_JAR = ROOT / "target" / "OwIDE.jar"

POSITIVE = (
    "lexical_nested",
    "implementation_include",
    "first_class",
    "dependent_result",
    "higher_order",
    "native_llvm",
)
NEGATIVE = (
    "negative_missing_signature",
    "negative_mixed_opaque",
    "negative_private_child",
)

def invoke(*args: str) -> str:
    result = run(args, cwd=ROOT, capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise AssertionError(
            f"Command failed ({result.returncode}): {' '.join(args)}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result.stdout.strip()


def expect_rejection(*args: str) -> None:
    result = run(args, cwd=ROOT, capture_output=True, text=True, timeout=90)
    if result.returncode == 0:
        raise AssertionError(f"Incorrectly accepted: {' '.join(args)}\n{result.stdout}")
    if not (result.stderr.strip() or result.stdout.strip()):
        raise AssertionError(f"Silent compiler failure: {' '.join(args)}")


def main() -> None:
    for binary in ("java", "ocamlc", "clang"):
        if not shutil.which(binary):
            raise RuntimeError(f"Required conformance tool not installed: {binary}")
    if not OW_JAR.exists():
        raise RuntimeError("Build the OW jar first: mvn -B package")
    with TemporaryDirectory(prefix="ow-ocaml-parity-") as dirname:
        outdir = Path(dirname)
        for name in POSITIVE:
            ow_file = FIXTURES / f"{name}.ow"
            if name == "native_llvm":
                ow_file = ROOT / "examples" / "module_calculus" / "native_llvm.ow"
            ml_file = FIXTURES / f"{name}.ml"
            ocaml_binary = outdir / f"{name}-ocaml"
            invoke("ocamlc", "-o", str(ocaml_binary), str(ml_file))
            expected = invoke(str(ocaml_binary))
            invoke("java", "-jar", str(OW_JAR), "check", str(ow_file))
            actual = invoke("java", "-jar", str(OW_JAR), "run", str(ow_file))
            if actual != expected:
                raise AssertionError(f"{name}: OW={actual!r}, OCaml={expected!r}")
            if name == "native_llvm":
                llvm = outdir / "native.ll"
                native_binary = outdir / "ow-native"
                invoke("java", "-jar", str(OW_JAR), "llvm",
                       str(ow_file), str(llvm))
                invoke("clang", str(llvm), "-o", str(native_binary))
                llvm_output = invoke(str(native_binary))
                if llvm_output != expected:
                    raise AssertionError(
                        f"native LLVM={llvm_output!r}, OCaml={expected!r}"
                    )
            print(f"PASS comparable behavior: {name} = {expected}")
        for name in NEGATIVE:
            ow_file = FIXTURES / f"{name}.ow"
            ml_file = FIXTURES / f"{name}.ml"
            expect_rejection("ocamlc", "-o", str(outdir / name), str(ml_file))
            expect_rejection("java", "-jar", str(OW_JAR), "check", str(ow_file))
            print(f"PASS both reject: {name}")
    print(f"PASS {len(POSITIVE)} OCaml/OW equivalences and {len(NEGATIVE)} shared rejections")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
