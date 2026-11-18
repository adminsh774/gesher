#!/usr/bin/env python3
"""Check unchanged production mask entry points against Android mask contracts.

Self and DeliverAll are explicit host fixture boundaries. This gate executes
mask arithmetic and error/output rules, not Windows signal delivery or thread
creation. The shared native guest fixture separately exercises the real APIs.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def function(source: str, signature: str) -> str:
    if source.count(signature) != 1:
        raise ValueError("missing or ambiguous function: " + signature)
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", help="Run the same cases against a historical implementation")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    path = "compat/bionic/signal_bionic.cc"
    source = (subprocess.check_output(["git", "show", args.source_ref + ":" + path],
                                     cwd=ROOT, text=True) if args.source_ref else
              (ROOT / path).read_text())
    declarations = []
    for key in ("kSigrtMin", "kSigBlock", "kSigkill"):
        matches = re.findall(r"^constexpr int " + key + r"[^\n]+", source, re.M)
        if len(matches) != 1:
            raise ValueError("missing or ambiguous constant: " + key)
        declarations.extend(matches)
    bodies = [function(source, "uint64_t Bit("),
              function(source, "int BIONIC_SYSV PthreadSigmask("),
              function(source, "int BIONIC_SYSV Sigprocmask(")]
    fixture = r'''
#include <cstdint>
#include <cstdio>
#include <cerrno>
#define BIONIC_SYSV __attribute__((sysv_abi))
constexpr int kEINVAL=22;
struct ThreadState { uint64_t mask; };
thread_local ThreadState state;
static int deliveries;
ThreadState* Self() { return &state; }
void DeliverAll(ThreadState*) { ++deliveries; }
'''
    fixture += "\n".join(declarations + bodies)
    fixture += r'''
static int checks, failures;
static void check(const char* name, bool ok) {
  ++checks;
  if (!ok) { ++failures; std::fprintf(stderr, "FAIL %s\n", name); }
}
int main() {
  constexpr uint64_t timer=UINT64_C(1)<<31;
  constexpr uint64_t runtime=UINT64_C(0x1ff)<<32;
  constexpr uint64_t public_pair=(UINT64_C(1)<<9)|(UINT64_C(1)<<63);
  constexpr uint64_t unmaskable=(UINT64_C(1)<<8)|(UINT64_C(1)<<18);
  struct Case { const char* name; int how; uint64_t initial, input, expected; bool query; };
  const Case cases[] = {
    {"empty SETMASK",2,timer,0,timer,false},
    {"reserved SETMASK",2,0,timer|runtime,timer,false},
    {"full SETMASK",2,0,UINT64_MAX,UINT64_MAX&~runtime&~unmaskable,false},
    {"empty BLOCK",0,timer,0,timer,false},
    {"reserved BLOCK",0,timer,timer|runtime,timer,false},
    {"public BLOCK including signal64",0,timer,public_pair,timer|public_pair,false},
    {"timer UNBLOCK",1,timer|public_pair,timer,timer|public_pair,false},
    {"reserved UNBLOCK",1,timer|public_pair,timer|runtime,timer|public_pair,false},
    {"public UNBLOCK including signal64",1,timer|public_pair,public_pair,timer,false},
    {"null query ignores how",12345,timer|public_pair,0,timer|public_pair,true},
    // AOSP filters the requested set, not the final mask. These pre-existing
    // raw states distinguish those two implementations without delivering a
    // reserved signal or claiming a public API can create every raw state.
    {"BLOCK preserves a previously blocked runtime bit",0,UINT64_C(1)<<32,0,timer|(UINT64_C(1)<<32),false},
    {"UNBLOCK does not add a previously absent timer bit",1,0,0,0,false},
    {"UNBLOCK removes previously blocked runtime bits",1,timer|runtime,0,timer,false},
  };
  for (const Case& c : cases) {
    state.mask=c.initial;
    uint64_t input=c.input, old=UINT64_MAX;
    errno=ERANGE;
    const int rc=PthreadSigmask(c.how,c.query?nullptr:&input,&old);
    check(c.name,rc==0&&state.mask==c.expected&&old==c.initial);
    check("input remains unchanged",input==c.input);
    check("pthread_sigmask preserves errno",errno==ERANGE);
  }
  state.mask=timer|public_pair;
  uint64_t input=0, old=UINT64_MAX;
  const int before=deliveries;
  errno=ERANGE;
  check("invalid pthread operation returns EINVAL",PthreadSigmask(12345,&input,&old)==22);
  check("invalid pthread operation preserves errno",errno==ERANGE);
  check("invalid operation preserves state/output",state.mask==(timer|public_pair)&&old==UINT64_MAX);
  check("invalid operation does not deliver",deliveries==before);
  errno=0;
  check("sigprocmask translates failure",Sigprocmask(12345,&input,&old)==-1&&errno==EINVAL);
  check("sigprocmask retains failed output",old==UINT64_MAX&&state.mask==(timer|public_pair));
  check("sigprocmask uses the same mask contract",Sigprocmask(2,&input,&old)==0&&state.mask==timer);
  std::printf("Bionic mask entry points: %d checks, %d failures; explicit state/delivery fixture\n",checks,failures);
  return failures?1:0;
}
'''
    print("production source SHA256 " + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    print("production entry bodies SHA256 " + hashlib.sha256("\n".join(bodies).encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-bionic-masks-") as temporary:
        directory = Path(temporary)
        cpp, executable = directory / "mask.cc", directory / "mask"
        cpp.write_text(fixture)
        command = shlex.split(args.cxx) + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra",
                   "-Werror", "-Wno-unused-const-variable", str(cpp), "-o", str(executable)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True, timeout=60)
        return subprocess.run([str(executable)], timeout=10).returncode


if __name__ == "__main__":
    raise SystemExit(main())
