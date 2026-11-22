#!/usr/bin/env python3
"""Check generated provider metadata against pinned EGL/GLES export contracts.

This is a generator/registration gate, not GL execution or a backend capability
test. Inputs are the actual headers, maps and implementation lists fetched by
aosp.lock. Actual registration bodies execute with inert function addresses;
the Windows build separately compiles the generated real thunks.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    "eglGetDisplay": 1, "eglGetProcAddress": 1, "eglSwapInterval": 1,
    "glClear": 14, "glGetString": 14, "glMatrixMode": 2,
    "glCreateShader": 12, "glBindVertexArray": 12,
    # Actual GLES2/gl2ext_api.in definitions, absent from the SDK maps.
    "glBeginQueryEXT": 12, "glTexStorage2DEXT": 12,
    # Actual public eglApi.cpp definitions, absent from the SDK map.
    "eglGetNextFrameIdANDROID": 1, "eglGetCompositorTimingANDROID": 1,
    "eglGetCompositorTimingSupportedANDROID": 1,
    "eglGetFrameTimestampsANDROID": 1, "eglGetFrameTimestampSupportedANDROID": 1,
    # Header declarations/proc-address additions alone confer no DSO owner.
    "glBindUniformLocationCHROMIUM": 0, "eglGetPlatformDisplayEXT": 0,
}


def section(text: str, begin: str, end: str) -> str:
    if text.count(begin) != 1 or text.count(end) != 1:
        raise ValueError("actual GL registration boundary changed: " + begin)
    return text[text.index(begin):text.index(end)]


def registration_contracts(folder: Path, generated: str, owners: dict[str, int], cxx: str) -> None:
    gl = (ROOT / "compat/bionic/egl_gles.cc").read_text()
    native = (ROOT / "compat/bionic/egl_native_buffer.cc").read_text()
    declarations = section(gl, "enum Lib : uint8_t", "void* ResolveGl(int index);")
    handwritten = section(gl, "const GlThunk kHandwritten[] = {", "gesher::SymbolTable& ByName() {")
    registration = section(gl, "gesher::SymbolTable& ByName() {", "[[maybe_unused]] const bool g_registered")
    native_registration = section(native, "void RegisterNativeBufferEgl(", "}  // namespace gesher::bionic::gl")
    names = re.search(r'enum : int \{[\s\S]*?const GlName kGlNames\[kGlCount\] = \{[\s\S]*?\n\};', generated)
    thunks = re.search(r'const GlThunk kGlThunks\[\] = \{[\s\S]*?\n\};', generated)
    if not names or not thunks:
        raise ValueError("generated GL registration tables changed")
    function_names = set(re.findall(r'reinterpret_cast<void\*>\(&([A-Za-z_]\w*)\)', handwritten + thunks[0]))
    native_entries = re.findall(r'\bE\("([A-Za-z_]\w*)",\s*([A-Za-z_]\w*)\)', native_registration)
    if not native_entries:
        raise ValueError("actual native-buffer registration entries missing")
    # These current wrappers are real default-visible libEGL definitions in
    # the fetched eglApi.cpp, including the extension spellings. The general
    # routing also has to keep unrelated proc-address-only names ownerless.
    for name, _ in native_entries:
        if owners.get(name, 0) != 1:
            raise AssertionError("native-buffer wrapper has no pinned libEGL evidence: " + name)
    native_functions = "\n".join("void " + name + "() {}" for name in sorted({name for _, name in native_entries}))
    inert_functions = "\n".join("void " + name + "() {}" for name in sorted(function_names))
    expected = ",\n".join('{"' + name + '", reinterpret_cast<void*>(&native_buffer::' + function + ')}'
                           for name, function in native_entries)
    prefix = r'''
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#define BIONIC_SYSV
using __eglMustCastToProperFunctionPointerType = void (*)();
namespace gesher { using SymbolTable = std::unordered_map<std::string, void*>; }
namespace gesher::bionic {
// Explicit observation boundary for registry writes; no GL/Win32 implementation.
SymbolTable aggregate;
std::unordered_map<std::string, SymbolTable> libraries;
void AddBionicSymbol(const char* name, void* address) { aggregate[name] = address; }
void AddBionicSymbol(const char* owner, const char* name, void* address) {
  aggregate[name] = address;
  libraries[owner][name] = address;
}
namespace gl {
'''
    suffix = r'''
struct Expected { const char* name; void* address; };
const Expected native_expected[] = { @EXPECTED@ };
} }
namespace {
unsigned checks = 0;
void Check(bool condition, const char* message) {
  ++checks;
  if (!condition) {
    std::fprintf(stderr, "FAIL GL registration: %s (check %u)\n", message, checks);
    std::exit(1);
  }
}
void CheckEntry(const char* name, void* address, unsigned owners) {
  using namespace gesher::bionic;
  const auto proc = gl::ByName().find(name);
  Check(proc != gl::ByName().end() && proc->second == address, name);
  const auto internal = aggregate.find(name);
  Check(internal != aggregate.end() && internal->second == address, name);
  Check(reinterpret_cast<void*>(gl::EglGetProcAddress(name)) == address, name);
  unsigned mask = 1;
  for (const char* owner : {"libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so"}) {
    const auto& table = libraries[owner];
    auto it = table.find(name);
    Check((owners & mask) ? it != table.end() && it->second == address : it == table.end(), name);
    mask <<= 1;
  }
}
}
int main() {
  using namespace gesher::bionic::gl;
  Register();
  for (const auto& entry : native_expected) CheckEntry(entry.name, entry.address, 1);
  CheckEntry("eglSwapInterval", reinterpret_cast<void*>(&SwapInterval), 1);
  CheckEntry("eglGetProcAddress", reinterpret_cast<void*>(&EglGetProcAddress), 1);
  Check(EglGetProcAddress(nullptr) == nullptr, "null proc lookup");
  Check(EglGetProcAddress("missing_test_entry") == nullptr, "missing proc lookup");
  int replacement;
  RegisterNativeBufferSymbol("eglQueryString", &replacement);
  CheckEntry("eglQueryString", &replacement, 1);
  RegisterNativeBufferSymbol("eglGetPlatformDisplayEXT", &replacement);
  CheckEntry("eglGetPlatformDisplayEXT", &replacement, 0);
  RegisterNativeBufferSymbol("not_in_gl_headers", &replacement);
  CheckEntry("not_in_gl_headers", &replacement, 0);
  std::printf("GL registration contracts: %u checks passed; actual registration/proc lookup with inert addresses\n", checks);
}
'''.replace("@EXPECTED@", expected)
    setup = (prefix + declarations + names[0] + "\n" + inert_functions + "\n" + thunks[0] + "\n" + handwritten +
             "namespace native_buffer {\n" + native_functions + "\n" + native_registration +
             "}\nusing native_buffer::RegisterNativeBufferEgl;\n")
    controls = {
        "actual": registration,
        "missing-native-replacement": registration.replace(
            "  RegisterNativeBufferEgl(RegisterNativeBufferSymbol);", "  // negative control: omitted callback", 1),
        "invented-EGL-owner": registration.replace("  unsigned owners = 0;", "  unsigned owners = 1;", 1),
    }
    for label, body in controls.items():
        if label != "actual" and body == registration:
            raise ValueError("GL registration negative control no longer applies: " + label)
        source = setup + body + suffix
        unit = folder / (label + ".cc")
        unit.write_text(source)
        executable = folder / (label + (".exe" if os.name == "nt" else ""))
        command = shlex.split(cxx) + ["-std=c++20", "-O1", "-Wall", "-Wextra", "-Werror",
                                     str(unit), "-o", str(executable)]
        print("REGISTRATION_SOURCE_SHA256 " + label + " " + hashlib.sha256(source.encode()).hexdigest(), flush=True)
        print("COMPILE " + json.dumps(command), flush=True)
        subprocess.run(command, check=True, timeout=120)
        result = subprocess.run([str(executable)], capture_output=True, text=True, timeout=30)
        if label == "actual":
            print(result.stdout, end="", flush=True)
            if result.returncode != 0:
                raise AssertionError("actual GL registration failed: " + result.stderr)
        elif result.returncode == 0 or "FAIL GL registration:" not in result.stderr:
            raise AssertionError("negative control was not rejected by the ownership oracle: " + label)
        else:
            print("NEGATIVE_CONTROL " + label + " rejected: " + result.stderr.strip(), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-dir", type=Path, required=True,
                        help="pinned frameworks/native/opengl/include directory")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    include = args.include_dir.resolve()
    generator = ROOT / "compat/bionic/gen_gl_thunks.py"
    spec = importlib.util.spec_from_file_location("gl_generator", generator)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    owners = module.provider_owners(str(include))
    for name, mask in CASES.items():
        if owners.get(name, 0) != mask:
            raise AssertionError(f"{name}: expected owners {mask}, got {owners.get(name, 0)}")
    cmake = (ROOT / "compat/bionic/ndk_graphics.cmake").read_text()
    block = re.search(r"set\(_gl_handwritten\s+(.*?)\)\n", cmake, re.DOTALL).group(1)
    handwritten = re.sub(r"#[^\n]*", "", block).split()
    inputs = [Path(__file__), generator, Path(module.EXTRA_HEADER), ROOT / "compat/bionic/ndk_graphics.cmake",
              ROOT / "compat/bionic/egl_gles.cc", ROOT / "compat/bionic/egl_native_buffer.cc"]
    inputs += [include / name for name in module.HEADERS]
    inputs += [include.parent / "libs" / name for name in (
        "libEGL.map.txt", "libGLESv1_CM.map.txt", "libGLESv2.map.txt", "libGLESv3.map.txt",
        "GLES2/gl2_api.in", "GLES2/gl2ext_api.in", "EGL/eglApi.cpp")]
    print("BOUNDARY actual pinned source ownership metadata and generated rows; no GL backend execution", flush=True)
    for path in inputs:
        print("INPUT_SHA256 " + str(path) + " " + hashlib.sha256(path.read_bytes()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-gl-owners-") as temporary:
        output = Path(temporary) / "gl_thunks.inc"
        subprocess.run([sys.executable, str(generator), str(output), str(include), *handwritten],
                       check=True, timeout=30)
        text = output.read_text()
        checks = len(CASES)
        for name, mask in CASES.items():
            if not re.search(r'\{"' + re.escape(name) + r'", kLib\w+, ' + str(mask) + r'\}', text):
                raise AssertionError("generated name ownership differs: " + name)
            checks += 1
            if name not in handwritten:
                if f'reinterpret_cast<void*>(&T_{name}), {mask}}}' not in text:
                    raise AssertionError("generated thunk ownership differs: " + name)
                checks += 1
        print("GENERATED_SHA256 " + hashlib.sha256(output.read_bytes()).hexdigest(), flush=True)
        registration_contracts(Path(temporary), text, owners, args.cxx)
    print(f"GL provider ownership contracts: {checks} checks passed; {len(owners)} ownership names", flush=True)


if __name__ == "__main__":
    main()
