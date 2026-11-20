#!/usr/bin/env python3
"""Extract unchanged production clipboard bodies; trace only actual Win32 boundary calls."""
import argparse, hashlib, json, os, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = '1071a0ea72120bdb5afe602517e3ff2676e9f3b9'
SOURCE = 'compat/devsvc/devsvc_win32.cc'

def body(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    level = 1; end = brace + 1
    while level:
        level += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[start:end]

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('out', type=pathlib.Path); args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    original = subprocess.check_output(['git', 'show', BASE + ':' + SOURCE], cwd=ROOT)
    if (ROOT / SOURCE).read_bytes() != original: raise SystemExit('Production source differs from exact107')
    text = original.decode()
    names = ['const wchar_t* W(', 'std::u16string U16(const wchar_t* s, size_t n)',
             'std::string Utf8(', 'std::u16string FromUtf8(', 'bool OpenClipboardRetrying()',
             'bool ClipboardText(', 'UINT HtmlFormat()', 'long HeaderNumber(', 'std::string CfHtml(',
             'bool ClipboardHtml(', 'bool SetClipboard(']
    bodies = '\n\n'.join(body(text, name) for name in names)
    code = r'''#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <cstdint>
static HWND g_window;
static const char* phase;
static BOOL TraceClose() {
  DWORD before = GetClipboardSequenceNumber();
  BOOL result = CloseClipboard();
  DWORD error = GetLastError(), after = GetClipboardSequenceNumber();
  std::printf("CLIP_NATIVE phase=%s CloseClipboard=%d error=%lu before=%lu after=%lu owner=%p\n", phase, result, error, before, after, (void*)GetClipboardOwner());
  return result;
}
#define CloseClipboard TraceClose
''' + bodies + r'''
#undef CloseClipboard
int main() {
  g_window = CreateWindowExW(0, L"STATIC", L"Gesher clipboard source diagnostic", 0,
      0, 0, 0, 0, HWND_MESSAGE, nullptr, GetModuleHandleW(nullptr), nullptr);
  if (!g_window) return 10;
  std::printf("CLIP_NATIVE process=%lu thread=%lu ownerWindow=%p\n", GetCurrentProcessId(), GetCurrentThreadId(), (void*)g_window);
  int errors = 0;
  for (int i = 0; i < 3; ++i) {
    std::u16string text = i == 2 ? u"" : u"clipboard actual Windows sequence";
    std::u16string html = i == 1 ? u"<b>clipboard actual Windows sequence</b>" : u"";
    phase = i == 0 ? "plain-write" : i == 1 ? "html-write" : "clear";
    uint32_t written = 0;
    bool ok = SetClipboard(text, html, &written);
    DWORD after = GetClipboardSequenceNumber();
    std::printf("CLIP_NATIVE phase=%s success=%d captured=%lu after=%lu\n", phase, ok, (unsigned long)written, after);
    if (!ok || !written) ++errors;
    phase = "read-text";
    std::u16string readText; bool hasText = ClipboardText(&readText);
    phase = "read-html";
    std::u16string readHtml; bool hasHtml = ClipboardHtml(&readHtml);
    std::printf("CLIP_NATIVE case=%d textPresent=%d textMatches=%d htmlPresent=%d htmlMatches=%d afterReads=%lu\n", i, hasText, readText == text, hasHtml, readHtml == html, GetClipboardSequenceNumber());
    if (i < 2 && (!hasText || readText != text)) ++errors;
    if (i == 1 && (!hasHtml || readHtml != html)) ++errors;
  }
  if (!DestroyWindow(g_window)) ++errors;
  std::printf("CLIP_NATIVE complete errors=%d\n", errors);
  return errors ? 1 : 0;
}
'''
    target = args.out / 'clipboard-sequence.cc'; target.write_text(code)
    receipt = {'base': BASE, 'checkout': subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'production_path': SOURCE, 'production_sha256': hashlib.sha256(original).hexdigest(),
        'extracted_bodies_sha256': hashlib.sha256(bodies.encode()).hexdigest(),
        'generated_sha256': hashlib.sha256(code.encode()).hexdigest(), 'signatures': names,
        'boundary': 'Only CloseClipboard is wrapped to log actual before/after; every extracted production body is unchanged.'}
    (args.out / 'native-source-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(receipt))
if __name__ == '__main__': main()
