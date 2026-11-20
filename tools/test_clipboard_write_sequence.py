#!/usr/bin/env python3
"""Run exact native clipboard writers against real Win32 data, races and bounded failures.

The event-window accessor alone supplies an owned test HWND. The full current
writer/readback bodies and receipt class are extracted unchanged; API wrappers
delegate to real Windows except explicitly named failure/race controls.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def generate(source_ref=None):
    paths = ['compat/devsvc/devsvc_win32.cc', 'compat/windows/clipboard_bridge.cc',
             'compat/windows/clipboard_write_receipt.h']
    inputs = {}
    for path in paths:
        if source_ref and path.endswith('clipboard_write_receipt.h'):
            inputs[path] = b''
        elif source_ref:
            inputs[path] = subprocess.check_output(['git', 'show', source_ref + ':' + path], cwd=ROOT)
        else:
            inputs[path] = (ROOT / path).read_bytes()
    devsvc, rich, receipt = (inputs[p].decode() for p in paths)
    common = ['const wchar_t* W(', 'std::u16string U16(const wchar_t* s, size_t n)',
              'std::string Utf8(', 'std::u16string FromUtf8(', 'bool OpenClipboardRetrying(',
              'UINT HtmlFormat()', 'long HeaderNumber(']
    simple_names = common + ['std::string CfHtml(', 'bool ClipboardText(', 'bool ClipboardHtml(', 'bool SetClipboard(']
    rich_names = common + ['std::string BuildCfHtml(', 'bool ReadClipboardText(', 'bool ReadClipboardHtml(',
                           'bool ReadClipboardImage(', 'bool WriteRichClipboard(']
    simple_bodies = '\n\n'.join(method(devsvc, name) for name in simple_names)
    rich_bodies = rich[rich.index('#pragma pack(push, 2)'):rich.index('const wchar_t* W(')]
    rich_bodies += '\n\n'.join(method(rich, name) for name in rich_names)
    template = (ROOT / 'tests/native/clipboard_write_sequence_test.cc.in').read_text()
    code = template.replace('@RECEIPT@', receipt).replace('@DEV_WRITERS@', simple_bodies).replace('@RICH_WRITERS@', rich_bodies)
    manifest = {'source_ref': source_ref, 'checkout': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'source_sha256': {p: hashlib.sha256(b).hexdigest() for p, b in inputs.items()},
                'simple_bodies_sha256': hashlib.sha256(simple_bodies.encode()).hexdigest(),
                'rich_bodies_sha256': hashlib.sha256(rich_bodies.encode()).hexdigest(),
                'generated_sha256': hashlib.sha256(code.encode()).hexdigest(),
                'simple_signatures': simple_names, 'rich_signatures': rich_names,
                'boundary': 'Exact production bodies and receipt; StartEvents supplies the owned HWND. Real Win32 calls unless a named race/error control is armed. Child writer is a real separate process.'}
    return code, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--source-ref', help='Exact old production writer negative control')
    parser.add_argument('--save-generated', type=Path)
    args = parser.parse_args()
    if args.compile_only and args.output is None:
        parser.error('--compile-only requires --output')
    code, manifest = generate(args.source_ref)
    with tempfile.TemporaryDirectory(prefix='gesher-clipboard-sequence-') as temporary:
        work = Path(temporary)
        source = work / 'clipboard.cc'
        source.write_text(code)
        output = args.output.resolve() if args.output else work / 'clipboard.exe'
        output.parent.mkdir(parents=True, exist_ok=True)
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-fno-exceptions',
                  '-static', '-pthread', '-D_WIN32_WINNT=0x0A00', str(source), '-luser32', '-lole32', '-o', str(output)]
        subprocess.run(command, check=True, timeout=90)
        manifest['compile_command'] = command
        manifest['binary_sha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
        if args.save_generated:
            args.save_generated.mkdir(parents=True, exist_ok=True)
            (args.save_generated / 'clipboard.cc').write_text(code)
            (args.save_generated / 'source-receipt.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps(manifest), flush=True)
        if not args.compile_only:
            subprocess.run([str(output)] + (['--old-control'] if args.source_ref else []), check=True, timeout=60)


if __name__ == '__main__':
    main()
