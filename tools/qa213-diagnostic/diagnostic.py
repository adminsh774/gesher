"""Isolated, source-bound Windows controls; never part of the product PR."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = '1071a0ea72120bdb5afe602517e3ff2676e9f3b9'
REPAIR_TREE = '815dc1be1ca74e794822e56932b6a7b1ff788296'
TOOLCHAIN_SHA = '5f9c6ed95b2d4bdb2869a488c5fd5857fbdabcf288a0aa3eb1da43f6a08d8ab4'
HEADER = 'compat/arm64/dll/numeric_fenv.h'
TEST = 'tests/native/arm64_numeric_fenv_test.cc'
WINDOW = 'tests/native/window_promotion_test.c'
READER = 'tests/windows/run-window-promotion.ps1'
DIAGNOSTIC_PATHS = [
    '.github/workflows/qa213-native-diagnostic.yml',
    'tools/qa213-diagnostic/diagnostic.py',
    'tools/qa213-diagnostic/run.ps1',
]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def source(ref, path):
    return git('show', f'{ref}:{path}')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity():
    head = git('rev-parse', 'HEAD').decode().strip()
    parent = git('rev-parse', 'HEAD^').decode().strip()
    assert git('rev-parse', f'{parent}^').decode().strip() == BASE
    assert git('rev-parse', f'{parent}^{{tree}}').decode().strip() == REPAIR_TREE
    assert sorted(git('diff', '--name-only', parent, head).decode().splitlines()) == sorted(DIAGNOSTIC_PATHS)
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        assert os.environ['GITHUB_SHA'] == head
    return head, parent


def writer_source(raw):
    # These are the actual three fixture open expressions, in marker order.
    calls = re.findall(r'CreateFileW\(argv\[4\],[^;]+\)', raw.decode())
    assert len(calls) == 3
    calls = [call.replace('argv[4]', 'argv[2]') for call in calls]
    selectors = '\n'.join(f'    case {n}: file = {call}; break;' for n, call in enumerate(calls))
    return ('''#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
int wmain(int argc, wchar_t** argv) {
  if (argc != 3) return 2;
  unsigned stage = (unsigned)wcstoul(argv[1], NULL, 10);
  HANDLE file = INVALID_HANDLE_VALUE;
  switch (stage) {
''' + selectors + '''
    default: return 2;
  }
  if (file == INVALID_HANDLE_VALUE) {
    printf("OPEN_FAILED error=%lu\\n", GetLastError());
    return 3;
  }
  const char* markers[] = {"PASS\\n", "CONTENT_PASS\\n", "BACKGROUND_PASS\\n"};
  const DWORD sizes[] = {5, 13, 16};
  DWORD written = 0;
  if (!WriteFile(file, markers[stage], sizes[stage], &written, NULL) || written != sizes[stage]
      || !FlushFileBuffers(file)) { CloseHandle(file); return 4; }
  puts("WRITER_HELD");
  fflush(stdout);
  char release = 0;
  DWORD received = 0;
  BOOL acknowledged = ReadFile(GetStdHandle(STD_INPUT_HANDLE), &release, 1, &received, NULL);
  BOOL closed = CloseHandle(file);
  if (!acknowledged || received != 1 || release != 'R' || !closed) return 5;
  puts("WRITER_CLOSED");
  return 0;
}
''').encode()


def run(command, cwd, out, label):
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (out / (label + '.log')).write_bytes(result.stdout)
    if result.returncode:
        raise RuntimeError(f'{label} failed: {result.returncode}\n{result.stdout.decode(errors="replace")}')
    return command


def build(args):
    head, parent = identity()
    assert digest(args.archive.read_bytes()) == TOOLCHAIN_SHA
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    toolchain = args.toolchain.resolve() / 'bin'
    cxx = str(toolchain / 'x86_64-w64-mingw32-clang++')
    cc = str(toolchain / 'x86_64-w64-mingw32-clang')
    commands = {}
    header = source(parent, HEADER)
    assert header.count(b'    _mm_setcsr(_MM_MASK_MASK);') == 1
    variants = {
        'old': source(BASE, HEADER),
        'fixed': header,
        'clear-only': header.replace(b'    _mm_setcsr(_MM_MASK_MASK);',
                                    b'    _mm_setcsr(_mm_getcsr() & ~_MM_EXCEPT_MASK);'),
    }
    # One identical enhanced fixture evaluates actual old/fixed scope bodies.
    fixture = source(parent, TEST)
    common = ['-std=gnu++20', '-O2', '-g', '-DNDEBUG', '-mno-ms-bitfields',
              '-fno-exceptions', '-fsized-deallocation', '-Wall', '-Wextra', '-Werror', '-frounding-math']
    with tempfile.TemporaryDirectory(prefix='qa213-native-') as temporary:
        temp = Path(temporary)
        for variant, body in variants.items():
            root = temp / variant
            (root / HEADER).parent.mkdir(parents=True)
            (root / TEST).parent.mkdir(parents=True)
            (root / HEADER).write_bytes(body)
            (root / TEST).write_bytes(fixture)
            (out / (variant + '-numeric-header.h')).write_bytes(body)
            command = [cxx, *common, TEST, '-static', '-o', str(out / (variant + '-numeric.exe'))]
            commands[variant + '-numeric'] = run(command, root, out, variant + '-numeric-build')
        for variant, ref in [('old', BASE), ('fixed', parent)]:
            body = source(ref, WINDOW)
            (out / (variant + '-window-source.c')).write_bytes(body)
            generated = out / (variant + '-held-writer.c')
            generated.write_bytes(writer_source(body))
            command = [cc, '-std=gnu17', '-O2', '-g', '-DNDEBUG', '-Wall', '-Wextra', '-Werror',
                       '-municode', '-static', str(generated), '-o', str(out / (variant + '-held-writer.exe'))]
            commands[variant + '-writer'] = run(command, ROOT, out, variant + '-writer-build')
    commands['actual-guest-libc'] = run([
        cxx, '-std=gnu++20', '-O3', '-DNDEBUG', '-DFMT_USE_CONSTEVAL=0',
        '-Wno-deprecated-literal-operator', '-Wno-deprecated-declarations', '-Wall', '-Wextra', '-Werror',
        '-mcx16', '-Icompat/arm64/include', '-c', 'compat/arm64/dll/guest_libc.cc',
        '-o', str(out / 'guest_libc-fixed.obj')], ROOT, out, 'guest-libc-build')
    commands['actual-window-controller'] = run([
        cc, '-std=gnu17', '-O2', '-g', '-DNDEBUG', '-mno-ms-bitfields', '-fno-exceptions',
        '-Wall', '-Wextra', '-Werror', '-municode', '-static', WINDOW,
        '-o', str(out / 'window-promotion-fixed.exe'), '-lkernel32', '-luser32'],
        ROOT, out, 'window-controller-build')
    (out / 'numeric-fixture.cc').write_bytes(fixture)
    (out / 'actual-window-reader.ps1').write_bytes(source(parent, READER))
    (out / 'compiler-version.txt').write_bytes(subprocess.check_output([cxx, '--version']))
    paths = [HEADER, TEST, WINDOW, READER, 'tests/windows/tracks/46-native3.ps1',
             'compat/arm64/dll/guest_libc.cc', 'compat/arm64/dll/translator.h',
             'compat/arm64/dll/guest_setjmp.h', 'compat/arm64/include/gesher/arm64_translator.h',
             'compat/arm64/include/gesher/symbol_table.h', *DIAGNOSTIC_PATHS]
    manifest = {
        'source': head, 'repair_parent': parent, 'repair_tree': REPAIR_TREE, 'baseline': BASE,
        'repository': os.environ.get('GITHUB_REPOSITORY'), 'repository_id': os.environ.get('GITHUB_REPOSITORY_ID'),
        'run': os.environ.get('GITHUB_RUN_ID'), 'attempt': os.environ.get('GITHUB_RUN_ATTEMPT'), 'producer': 'build',
        'toolchain_sha256': TOOLCHAIN_SHA, 'commands': commands,
        'source_files': {path: digest(source(head, path)) for path in paths},
        'variants': {name: {'header_sha256': digest(body), 'fixture_sha256': digest(fixture)}
                     for name, body in variants.items()},
        'outputs': {path.name: digest(path.read_bytes()) for path in out.iterdir() if path.is_file()},
    }
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'source': head, 'repair_parent': parent, 'repair_tree': REPAIR_TREE,
                      'manifest_sha256': digest((out / 'manifest.json').read_bytes()), 'outputs': manifest['outputs']}))


def verify(args):
    head, parent = identity()
    out = args.out.resolve()
    manifest = json.loads((out / 'manifest.json').read_text())
    assert manifest['source'] == head and manifest['repair_parent'] == parent
    assert manifest['repair_tree'] == REPAIR_TREE and manifest['baseline'] == BASE
    assert manifest['producer'] == 'build' and manifest['toolchain_sha256'] == TOOLCHAIN_SHA
    for key, environment in [('repository', 'GITHUB_REPOSITORY'), ('repository_id', 'GITHUB_REPOSITORY_ID'),
                             ('run', 'GITHUB_RUN_ID'), ('attempt', 'GITHUB_RUN_ATTEMPT')]:
        assert manifest[key] == os.environ[environment]
    assert {p.name for p in out.iterdir()} == set(manifest['outputs']) | {'manifest.json'}
    for path, expected in manifest['source_files'].items():
        assert digest(source(head, path)) == expected, path
    for name, expected in manifest['outputs'].items():
        assert digest((out / name).read_bytes()) == expected, name
    assert (out / 'numeric-fixture.cc').read_bytes() == source(parent, TEST)
    assert (out / 'actual-window-reader.ps1').read_bytes() == source(parent, READER)
    assert (out / 'old-numeric-header.h').read_bytes() == source(BASE, HEADER)
    assert (out / 'fixed-numeric-header.h').read_bytes() == source(parent, HEADER)
    for variant, ref in [('old', BASE), ('fixed', parent)]:
        assert (out / (variant + '-held-writer.c')).read_bytes() == writer_source(source(ref, WINDOW))
    print(f'QA213_DIAGNOSTIC_BOUND source={head} repair={parent} tree={REPAIR_TREE} '
          f'run={manifest["run"]} attempt={manifest["attempt"]} producer=build '
          f'manifest_sha256={digest((out / "manifest.json").read_bytes())}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['build', 'verify'])
    parser.add_argument('--toolchain', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    (build if args.mode == 'build' else verify)(args)
