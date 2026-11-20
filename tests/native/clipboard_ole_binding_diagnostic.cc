/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Isolated private-publication binding candidate, not a production repair.
 * The separate API-only OLE probe and both prior Win32 controls are unchanged.
 * Full actual preferred-format bytes, exact original object and a stable native
 * sequence must agree before a completion receipt is accepted.
 */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <ole2.h>
#include <objidl.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <new>
#include <string>
#include <utility>
#include <vector>
#include <array>
#include <atomic>
#include <deque>
#include <mutex>
#include <memory>
#include <thread>
#include "clipboard_dib_layout_diagnostic.h"
#include "clipboard_legacy_image_probe_generated.h"
#include "clipboard_wic_decode_diagnostic.h"

namespace {
int checks = 0;
int failures = 0;
LONG live_objects = 0;
LONG live_enumerators = 0;
LONG data_calls = 0;
unsigned case_number = 0;

void Check(bool ok, const char* what) {
  ++checks;
  if (!ok) {
    ++failures;
    std::printf("FAIL OLE_BINDING case=%u %s error=%lu\n",
                case_number, what, static_cast<unsigned long>(GetLastError()));
  }
}
void Hr(const char* phase, HRESULT result) {
  std::printf("OLE_HRESULT case=%u phase=%s hr=%08lx sequence=%lu owner=%p\n",
              case_number, phase, static_cast<unsigned long>(result),
              static_cast<unsigned long>(GetClipboardSequenceNumber()),
              static_cast<void*>(GetClipboardOwner()));
}
UINT HtmlFormat() {
  static UINT value = RegisterClipboardFormatW(L"HTML Format");
  return value;
}
UINT MarkerFormat() {
  static UINT value = RegisterClipboardFormatW(L"Gesher.Clipboard.OleIdentityProbe.v1");
  return value;
}
std::string Html(const std::string& text) {
  const char* header =
      "Version:0.9\r\nStartHTML:%010zu\r\nEndHTML:%010zu\r\n"
      "StartFragment:%010zu\r\nEndFragment:%010zu\r\n";
  const std::string before = "<html><body>\r\n<!--StartFragment-->";
  const std::string after = "<!--EndFragment-->\r\n</body>\r\n</html>";
  char line[256];
  const size_t n = static_cast<size_t>(std::snprintf(
      line, sizeof(line), header, size_t{0}, size_t{0}, size_t{0}, size_t{0}));
  const size_t start = n + before.size();
  const size_t end = start + text.size();
  std::snprintf(line, sizeof(line), header, n, end + after.size(), start, end);
  return std::string(line) + before + text + after;
}
std::vector<uint8_t> Bytes(const void* data, size_t size) {
  const auto* first = static_cast<const uint8_t*>(data);
  return std::vector<uint8_t>(first, first + size);
}
std::vector<uint8_t> WideBytes(const std::wstring& value) {
  return Bytes(value.c_str(), (value.size() + 1) * sizeof(wchar_t));
}
std::vector<uint8_t> NarrowBytes(const std::string& value) {
  return Bytes(value.c_str(), value.size() + 1);
}
const wchar_t* OwnText() { return L"Android OLE publication \x05D0\x05D1"; }
const wchar_t* OtherText() { return L"external different content"; }
const char* OwnHtml() { return "<b>Android OLE publication</b>"; }
const char* OtherHtml() { return "<i>external different HTML</i>"; }
std::vector<uint8_t> Dib() {
  BITMAPINFOHEADER header{};
  header.biSize = sizeof(header);
  header.biWidth = 2;
  header.biHeight = 1;
  header.biPlanes = 1;
  header.biBitCount = 32;
  header.biCompression = BI_RGB;
  header.biSizeImage = 8;
  std::vector<uint8_t> out(sizeof(header) + 8);
  std::memcpy(out.data(), &header, sizeof(header));
  const uint8_t pixels[8] = {0x31, 0x52, 0x73, 0, 0x97, 0xb9, 0xdb, 0};
  std::memcpy(out.data() + sizeof(header), pixels, sizeof(pixels));
  return out;
}
struct FormatBytes {
  CLIPFORMAT format;
  std::vector<uint8_t> bytes;
};

class FormatEnumerator final : public IEnumFORMATETC {
 public:
  explicit FormatEnumerator(const std::vector<FORMATETC>& formats, size_t position = 0)
      : formats_(formats), position_(position) { InterlockedIncrement(&live_enumerators); }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (iid == IID_IUnknown || iid == IID_IEnumFORMATETC) {
      *out = static_cast<IEnumFORMATETC*>(this); AddRef(); return S_OK;
    }
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&references_); }
  ULONG STDMETHODCALLTYPE Release() override {
    ULONG left = InterlockedDecrement(&references_);
    if (!left) delete this;
    return left;
  }
  HRESULT STDMETHODCALLTYPE Next(ULONG count, FORMATETC* values, ULONG* fetched) override {
    if (fetched) *fetched = 0;
    if (!values || (!fetched && count != 1)) return E_POINTER;
    ULONG n = 0;
    while (n < count && position_ < formats_.size()) values[n++] = formats_[position_++];
    if (fetched) *fetched = n;
    return n == count ? S_OK : S_FALSE;
  }
  HRESULT STDMETHODCALLTYPE Skip(ULONG count) override {
    size_t left = formats_.size() - position_;
    size_t n = (std::min)(static_cast<size_t>(count), left);
    position_ += n;
    return n == count ? S_OK : S_FALSE;
  }
  HRESULT STDMETHODCALLTYPE Reset() override { position_ = 0; return S_OK; }
  HRESULT STDMETHODCALLTYPE Clone(IEnumFORMATETC** out) override {
    if (!out) return E_POINTER;
    *out = new (std::nothrow) FormatEnumerator(formats_, position_);
    return *out ? S_OK : E_OUTOFMEMORY;
  }
 private:
  ~FormatEnumerator() { InterlockedDecrement(&live_enumerators); }
  LONG references_ = 1;
  std::vector<FORMATETC> formats_;
  size_t position_;
};

class Publication final : public IDataObject {
 public:
  // Private state represents data absent from the Windows projection. This is
  // a native identity canary, not an ART/ClipData execution claim.
  Publication(int kind, bool empty_text,
              const std::vector<uint8_t>* image = nullptr)
      : private_identity_(++next_identity_) {
    marker_result_ = CoCreateGuid(&marker_);
    formats_.push_back({CF_UNICODETEXT, WideBytes(empty_text ? L"" : OwnText())});
    if (kind >= 1) formats_.push_back({
        static_cast<CLIPFORMAT>(HtmlFormat()), NarrowBytes(Html(OwnHtml()))});
    if (kind >= 2) {
      const std::vector<uint8_t> input = image ? *image : Dib();
      formats_.push_back({CF_DIB, input});
      std::vector<uint8_t> preferred;
      layout_ok_ = gesher::clipboard_diagnostic::ToPackedV5(input, &preferred);
      if (layout_ok_) formats_.push_back({CF_DIBV5, std::move(preferred)});
    }
    formats_.push_back({static_cast<CLIPFORMAT>(MarkerFormat()), Bytes(&marker_, sizeof(marker_))});
    // Establish the complete expected native extents from our own allocation,
    // before OleSetClipboard. Never learn expected bytes from the clipboard.
    for (auto& format : formats_) {
      HGLOBAL allocation = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, format.bytes.size());
      const SIZE_T extent = allocation ? GlobalSize(allocation) : 0;
      if (allocation) GlobalFree(allocation);
      if (extent < format.bytes.size() || extent > 64u * 1024u * 1024u) {
        allocations_ok_ = false;
      } else {
        format.bytes.resize(extent, 0);
      }
    }
    InterlockedIncrement(&live_objects);
  }
  const GUID& marker() const { return marker_; }
  HRESULT marker_result() const { return marker_result_; }
  uint64_t private_identity() const { return private_identity_; }
  bool ready() const { return marker_result_ == S_OK && layout_ok_ && allocations_ok_; }
  const std::vector<uint8_t>* expected(CLIPFORMAT format) const {
    const auto* value = Find(format);
    return value ? &value->bytes : nullptr;
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (iid == IID_IUnknown || iid == IID_IDataObject) {
      *out = static_cast<IDataObject*>(this); AddRef(); return S_OK;
    }
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&references_); }
  ULONG STDMETHODCALLTYPE Release() override {
    ULONG left = InterlockedDecrement(&references_);
    if (!left) delete this;
    return left;
  }
  HRESULT STDMETHODCALLTYPE QueryGetData(FORMATETC* request) override {
    if (!request) return E_POINTER;
    if (request->dwAspect != DVASPECT_CONTENT) return DV_E_DVASPECT;
    if (request->lindex != -1) return DV_E_LINDEX;
    if (request->ptd) return DV_E_DVTARGETDEVICE;
    if (!(request->tymed & TYMED_HGLOBAL)) return DV_E_TYMED;
    return Find(request->cfFormat) ? S_OK : DV_E_FORMATETC;
  }
  HRESULT STDMETHODCALLTYPE GetData(FORMATETC* request, STGMEDIUM* medium) override {
    if (!medium) return E_POINTER;
    std::memset(medium, 0, sizeof(*medium));
    HRESULT result = QueryGetData(request);
    if (result != S_OK) return result;
    const auto* format = Find(request->cfFormat);
    if (!ready()) return E_INVALIDARG;
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, format->bytes.size());
    if (!memory) return STG_E_MEDIUMFULL;
    if (GlobalSize(memory) != format->bytes.size()) {
      GlobalFree(memory);
      return STG_E_MEDIUMFULL;
    }
    void* target = GlobalLock(memory);
    if (!target) { GlobalFree(memory); return STG_E_MEDIUMFULL; }
    std::memcpy(target, format->bytes.data(), format->bytes.size());
    GlobalUnlock(memory);
    medium->tymed = TYMED_HGLOBAL;
    medium->hGlobal = memory;
    medium->pUnkForRelease = nullptr; // The receiving consumer owns ReleaseStgMedium.
    InterlockedIncrement(&data_calls);
    std::printf("OLE_GETDATA case=%u identity=%llu format=%u bytes=%zu calls=%ld\n",
                case_number, static_cast<unsigned long long>(private_identity_),
                static_cast<unsigned>(request->cfFormat), format->bytes.size(),
                static_cast<long>(data_calls));
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE GetDataHere(FORMATETC*, STGMEDIUM*) override { return E_NOTIMPL; }
  HRESULT STDMETHODCALLTYPE GetCanonicalFormatEtc(FORMATETC* in, FORMATETC* out) override {
    if (!in || !out) return E_POINTER;
    *out = *in; out->ptd = nullptr;
    return DATA_S_SAMEFORMATETC;
  }
  HRESULT STDMETHODCALLTYPE SetData(FORMATETC*, STGMEDIUM*, BOOL) override { return E_NOTIMPL; }
  HRESULT STDMETHODCALLTYPE EnumFormatEtc(DWORD direction, IEnumFORMATETC** out) override {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (direction != DATADIR_GET) return E_NOTIMPL;
    std::vector<FORMATETC> values;
    for (const auto& format : formats_)
      values.push_back({format.format, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL});
    *out = new (std::nothrow) FormatEnumerator(values);
    return *out ? S_OK : E_OUTOFMEMORY;
  }
  HRESULT STDMETHODCALLTYPE DAdvise(FORMATETC*, DWORD, IAdviseSink*, DWORD* connection) override {
    if (connection) *connection = 0;
    return OLE_E_ADVISENOTSUPPORTED;
  }
  HRESULT STDMETHODCALLTYPE DUnadvise(DWORD) override { return OLE_E_ADVISENOTSUPPORTED; }
  HRESULT STDMETHODCALLTYPE EnumDAdvise(IEnumSTATDATA** out) override {
    if (out) *out = nullptr;
    return OLE_E_ADVISENOTSUPPORTED;
  }
 private:
  ~Publication() {
    std::printf("OLE_RELEASE case=%u identity=%llu\n", case_number,
                static_cast<unsigned long long>(private_identity_));
    InterlockedDecrement(&live_objects);
  }
  const FormatBytes* Find(CLIPFORMAT format) const {
    for (const auto& value : formats_) if (value.format == format) return &value;
    return nullptr;
  }
  inline static uint64_t next_identity_ = 0;
  LONG references_ = 1;
  GUID marker_{};
  HRESULT marker_result_ = E_FAIL;
  uint64_t private_identity_;
  bool layout_ok_ = true, allocations_ok_ = true;
  std::vector<FormatBytes> formats_;
};

bool PumpWait(HANDLE event, DWORD timeout) {
  ULONGLONG started = GetTickCount64();
  for (;;) {
    ULONGLONG elapsed = GetTickCount64() - started;
    if (elapsed >= timeout) return WaitForSingleObject(event, 0) == WAIT_OBJECT_0;
    DWORD result = MsgWaitForMultipleObjects(1, &event, FALSE,
        timeout - static_cast<DWORD>(elapsed), QS_ALLINPUT);
    if (result == WAIT_OBJECT_0) return true;
    if (result != WAIT_OBJECT_0 + 1) return false;
    MSG message;
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
      if (message.message == WM_QUIT) return false;
      TranslateMessage(&message);
      DispatchMessageW(&message);
    }
  }
}
void PumpPending() {
  MSG message;
  while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
    TranslateMessage(&message); DispatchMessageW(&message);
  }
}
bool Open(HWND owner) {
  for (int attempt = 0; attempt != 20; ++attempt) {
    if (OpenClipboard(owner)) return true;
    Sleep(10);
  }
  return false;
}
std::vector<uint8_t> ReadGlobal(UINT format) {
  HANDLE value = GetClipboardData(format);
  if (!value) return {};
  const SIZE_T size = GlobalSize(value);
  if (!size || size > 64u * 1024u * 1024u) return {};
  const void* memory = GlobalLock(value);
  if (!memory) return {};
  auto result = Bytes(memory, size);
  GlobalUnlock(value);
  return result;
}
uint32_t FirstPixel(const std::vector<uint8_t>& bytes, bool* valid) {
  *valid = false;
  if (bytes.size() < sizeof(BITMAPINFOHEADER)) return 0;
  BITMAPINFOHEADER header{};
  std::memcpy(&header, bytes.data(), sizeof(header));
  if (header.biSize < sizeof(header) || header.biSize > bytes.size()
      || header.biWidth != 2 || (header.biHeight != 1 && header.biHeight != -1)
      || header.biPlanes != 1 || header.biBitCount != 32
      || (header.biCompression != BI_RGB && header.biCompression != BI_BITFIELDS)) return 0;
  size_t offset = header.biSize;
  if (header.biSize == sizeof(header) && header.biCompression == BI_BITFIELDS) offset += 12;
  if (offset > bytes.size() || bytes.size() - offset < 8) return 0;
  *valid = true;
  return static_cast<uint32_t>(bytes[offset]) |
      (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
      (static_cast<uint32_t>(bytes[offset + 2]) << 16);
}
struct Projection {
  bool opened = false, closed = false, text_present = false, html_present = false;
  bool image_present = false, marker_present = false;
  bool text_available = false, html_available = false;
  std::vector<uint8_t> raw_text, raw_html, raw_image;
  std::wstring text;
  std::string html;
  GUID marker{};
  UINT image_format = 0;
  uint32_t pixel = 0;
  DWORD before = 0, locked = 0, after = 0;
};
Projection ReadProjection(const char* phase) {
  Projection out;
  out.before = GetClipboardSequenceNumber();
  out.opened = Open(nullptr);
  if (out.opened) {
    out.text_available = IsClipboardFormatAvailable(CF_UNICODETEXT) != 0;
    auto text = ReadGlobal(CF_UNICODETEXT);
    out.raw_text = text;
    if (text.size() >= sizeof(wchar_t) && text.size() % sizeof(wchar_t) == 0) {
      const auto* data = reinterpret_cast<const wchar_t*>(text.data());
      size_t count = 0, maximum = text.size() / sizeof(wchar_t);
      while (count < maximum && data[count]) ++count;
      out.text_present = count < maximum;
      if (out.text_present) out.text.assign(data, count);
    }
    out.html_available = IsClipboardFormatAvailable(HtmlFormat()) != 0;
    if (out.html_available) {
      auto html = ReadGlobal(HtmlFormat());
      out.raw_html = html;
      auto end = std::find(html.begin(), html.end(), uint8_t{0});
      out.html_present = end != html.end();
      if (out.html_present) out.html.assign(html.begin(), end);
    }
    out.image_format = IsClipboardFormatAvailable(CF_DIBV5) ? CF_DIBV5
        : (IsClipboardFormatAvailable(CF_DIB) ? CF_DIB : 0);
    if (out.image_format) {
      out.raw_image = ReadGlobal(out.image_format);
      out.pixel = FirstPixel(out.raw_image, &out.image_present);
    }
    if (IsClipboardFormatAvailable(MarkerFormat())) {
      auto marker = ReadGlobal(MarkerFormat());
      out.marker_present = marker.size() == sizeof(GUID);
      if (out.marker_present) std::memcpy(&out.marker, marker.data(), sizeof(GUID));
    }
    out.locked = GetClipboardSequenceNumber();
    out.closed = CloseClipboard() != 0;
  }
  out.after = GetClipboardSequenceNumber();
  std::printf("OLE_PROJECTION case=%u phase=%s opened=%d closed=%d seq_before=%lu "
      "seq_locked=%lu seq_after=%lu text=%d html=%d image=%d image_format=%u "
      "pixel=%06x marker=%d text_length=%zu html_length=%zu\n", case_number, phase,
      out.opened, out.closed, static_cast<unsigned long>(out.before),
      static_cast<unsigned long>(out.locked), static_cast<unsigned long>(out.after),
      out.text_present, out.html_present, out.image_present, out.image_format,
      out.pixel, out.marker_present, out.text.size(), out.html.size());
  return out;
}
struct Binding {
  HRESULT before = E_FAIL, after = E_FAIL;
  Projection projection;
  bool formats = false, stable = false;
  DWORD sequence = 0;
};
bool SameBytes(const std::vector<uint8_t>* expected, const std::vector<uint8_t>& actual) {
  return expected && *expected == actual;
}
Binding ReadBinding(Publication* object, const char* phase) {
  Binding out;
  out.before = OleIsCurrentClipboard(object);
  out.projection = ReadProjection(phase);
  out.after = OleIsCurrentClipboard(object);
  const auto& value = out.projection;
  const auto* html = object->expected(static_cast<CLIPFORMAT>(HtmlFormat()));
  const auto* image = object->expected(CF_DIBV5);
  out.formats = object->ready() && value.text_available
      && SameBytes(object->expected(CF_UNICODETEXT), value.raw_text)
      && value.html_available == (html != nullptr)
      && (html ? SameBytes(html, value.raw_html) : value.raw_html.empty())
      && (image ? value.image_format == CF_DIBV5 && SameBytes(image, value.raw_image)
                : value.image_format == 0 && value.raw_image.empty());
  out.stable = value.opened && value.closed && value.before != 0
      && value.before == value.locked && value.locked == value.after;
  // A subsequent write is a new sequence and must still be imported as external
  // by the Android consumer. This is only the current write's completion proof.
  if (out.before == S_OK && out.after == S_OK && out.formats && out.stable)
    out.sequence = value.after;
  std::printf("OLE_BINDING case=%u phase=%s object=%llu before=%08lx after=%08lx "
      "formats=%d stable=%d receipt=%lu text_bytes=%zu html_bytes=%zu image_bytes=%zu\n",
      case_number, phase, static_cast<unsigned long long>(object->private_identity()),
      static_cast<unsigned long>(out.before), static_cast<unsigned long>(out.after),
      out.formats, out.stable, static_cast<unsigned long>(out.sequence),
      value.raw_text.size(), value.raw_html.size(), value.raw_image.size());
  return out;
}

void CheckOwn(const Projection& value, int kind, bool empty_text, const GUID& marker) {
  Check(value.opened && value.closed, "own read opens and closes actual clipboard");
  Check(value.text_present && value.text == (empty_text ? L"" : OwnText()), "own text exact including empty");
  Check(value.html_present == (kind >= 1), "own HTML presence exact");
  if (kind >= 1) Check(value.html == Html(OwnHtml()), "own HTML data exact");
  Check(value.image_present == (kind >= 2), "own image presence exact");
  if (kind >= 2) Check(value.pixel == 0x735231u, "actual preferred image pixels exact");
  Check(value.marker_present && IsEqualGUID(value.marker, marker), "full public marker round trip");
}
void ClearFixture() {
  HRESULT result = OleSetClipboard(nullptr);
  Hr("fixture-clear", result);
  Check(result == S_OK, "actual fixture clear succeeds");
  PumpPending();
}
void OwnCase(int kind, bool empty_text) {
  ++case_number;
  ClearFixture();
  auto* object = new Publication(kind, empty_text);
  const GUID marker = object->marker();
  Check(object->ready(), "own immutable preferred formats prepared before publication");
  Check(object->marker_result() == S_OK, "own full marker was generated by COM");
  HRESULT published = OleSetClipboard(object);
  Hr("own-set", published);
  Check(published == S_OK, "own actual OLE publication succeeds");
  if (published == S_OK) {
    HRESULT current = OleIsCurrentClipboard(object);
    Hr("own-current-before", current);
    Check(current == S_OK, "exact original IDataObject is current");
    auto first_binding = ReadBinding(object, "own-first-read");
    auto first = first_binding.projection;
    Check(first_binding.sequence != 0 && first_binding.sequence == GetClipboardSequenceNumber(),
          "own full preferred bytes and original object yield exact current receipt");
    CheckOwn(first, kind, empty_text, marker);
    current = OleIsCurrentClipboard(object);
    Hr("own-current-after", current);
    Check(current == S_OK, "real native rendering preserves original IDataObject");
    auto second_binding = ReadBinding(object, "own-second-read");
    auto second = second_binding.projection;
    Check(second_binding.sequence == first_binding.sequence && second_binding.sequence != 0,
          "read-only paths preserve full publication receipt and private metadata identity");
    CheckOwn(second, kind, empty_text, marker);
    // Raw sequence transitions are observations, not assumed lock guarantees.
    Check(first.text == second.text && first.html == second.html && first.pixel == second.pixel,
          "read-only paths preserve app-visible native values");
  }
  ClearFixture();
  Check(CountClipboardFormats() == 0, "explicit clear leaves zero native formats");
  Check(OleIsCurrentClipboard(object) == S_FALSE, "clear retires exact original IDataObject");
  object->Release();
  PumpPending();
  Check(live_objects == 0 && live_enumerators == 0, "own objects and format enumerators retire");
}

void ReleaseFormat(UINT format, HANDLE value) {
  if (!value) return;
  STGMEDIUM medium{};
  if (format == CF_BITMAP || format == CF_PALETTE) {
    medium.tymed = TYMED_GDI; medium.hBitmap = static_cast<HBITMAP>(value);
  } else if (format == CF_ENHMETAFILE) {
    medium.tymed = TYMED_ENHMF; medium.hEnhMetaFile = static_cast<HENHMETAFILE>(value);
  } else if (format == CF_METAFILEPICT) {
    medium.tymed = TYMED_MFPICT; medium.hMetaFilePict = static_cast<HMETAFILEPICT>(value);
  } else {
    medium.tymed = TYMED_HGLOBAL; medium.hGlobal = static_cast<HGLOBAL>(value);
  }
  ReleaseStgMedium(&medium);
}
struct OwnedFormat {
  UINT format = 0;
  HANDLE value = nullptr;
  OwnedFormat(UINT f, HANDLE v) : format(f), value(v) {}
  OwnedFormat(const OwnedFormat&) = delete;
  OwnedFormat& operator=(const OwnedFormat&) = delete;
  OwnedFormat(OwnedFormat&& other) noexcept : format(other.format), value(other.value) { other.value = nullptr; }
  OwnedFormat& operator=(OwnedFormat&& other) noexcept {
    if (this != &other) {
      ReleaseFormat(format, value); format = other.format; value = other.value; other.value = nullptr;
    }
    return *this;
  }
  ~OwnedFormat() { ReleaseFormat(format, value); }
};
HANDLE MakeGlobal(const std::vector<uint8_t>& bytes) {
  HGLOBAL value = GlobalAlloc(GMEM_MOVEABLE, bytes.size());
  if (!value) return nullptr;
  void* data = GlobalLock(value);
  if (!data) { GlobalFree(value); return nullptr; }
  std::memcpy(data, bytes.data(), bytes.size());
  GlobalUnlock(value);
  return value;
}
struct Observation {
  DWORD pid = 0, error = 0;
  LONG ready = 0, opened = 0, emptied = 0, published = 0, closed = 0;
  LONG read_opened = 0, read_closed = 0;
  LONG formats_seen = 0, formats_copied = 0, copy_failed = 0, formats_set = 0;
  unsigned long long owner_before = 0, owner_after = 0;
  UINT format_ids[128]{};
  DWORD format_errors[128]{};
  LONG format_duplicated[128]{};
};
void FirstError(Observation* result) {
  if (!result->error) result->error = GetLastError() ? GetLastError() : ERROR_GEN_FAILURE;
}
bool CopyFormats(std::vector<OwnedFormat>* formats, Observation* result) {
  if (!Open(nullptr)) { FirstError(result); return false; }
  result->read_opened = 1;
  bool ok = true;
  UINT format = 0;
  for (;;) {
    SetLastError(ERROR_SUCCESS);
    format = EnumClipboardFormats(format);
    if (!format) {
      if (GetLastError() != ERROR_SUCCESS) { FirstError(result); ok = false; }
      break;
    }
    ++result->formats_seen;
    if (result->formats_seen > 128) { SetLastError(ERROR_INVALID_DATA); FirstError(result); ok = false; break; }
    SetLastError(ERROR_SUCCESS);
    HANDLE original = GetClipboardData(format);
    const DWORD read_error = GetLastError();
    SetLastError(ERROR_SUCCESS);
    HANDLE copy = original ? OleDuplicateData(original, static_cast<CLIPFORMAT>(format), 0) : nullptr;
    const DWORD duplicate_error = GetLastError();
    const auto index = static_cast<size_t>(result->formats_seen - 1);
    result->format_ids[index] = format;
    result->format_errors[index] = original ? duplicate_error : read_error;
    result->format_duplicated[index] = copy != nullptr;
    wchar_t name[256]{};
    GetClipboardFormatNameW(format, name, 256);
    std::printf("OLE_REPLAY_FORMAT format=%u available=%d duplicate=%d error=%lu\n",
                format, original != nullptr, copy != nullptr,
                static_cast<unsigned long>(result->format_errors[index]));
    if (!copy) {
      ++result->copy_failed; SetLastError(result->format_errors[index]);
      FirstError(result); ok = false;
    }
    else { formats->emplace_back(format, copy); ++result->formats_copied; }
  }
  result->read_closed = CloseClipboard() != 0;
  if (!result->read_closed) { FirstError(result); ok = false; }
  return ok;
}
bool ReplaceBytes(std::vector<OwnedFormat>* formats, UINT format, const std::vector<uint8_t>& bytes) {
  HANDLE value = MakeGlobal(bytes);
  if (!value) return false;
  for (auto& current : *formats) {
    if (current.format == format) {
      ReleaseFormat(current.format, current.value); current.value = value; return true;
    }
  }
  formats->emplace_back(format, value);
  return true;
}
bool MutatePreferredImage(std::vector<OwnedFormat>* formats, int target) {
  UINT preferred = 0;
  for (const auto& value : *formats) {
    if (value.format == CF_DIBV5) preferred = CF_DIBV5;
    else if (!preferred && value.format == CF_DIB) preferred = CF_DIB;
  }
  for (auto& value : *formats) {
    if (value.format != preferred) continue;
    SIZE_T size = GlobalSize(value.value);
    auto* data = static_cast<uint8_t*>(GlobalLock(value.value));
    if (!data || size < sizeof(BITMAPINFOHEADER)) { if (data) GlobalUnlock(value.value); return false; }
    BITMAPINFOHEADER header{};
    std::memcpy(&header, data, sizeof(header));
    size_t offset = header.biSize;
    if (header.biSize == sizeof(header) && header.biCompression == BI_BITFIELDS) offset += 12;
    bool ok = header.biSize >= sizeof(header) && offset <= size && size - offset >= 8;
    if (ok && target == 3) { data[offset] = 0xaf; data[offset + 1] = 0x01; data[offset + 2] = 0xfe; }
    if (ok && target == 5) {
      LONG resolution = 424242; std::memcpy(data + 24, &resolution, sizeof(resolution));
    }
    if (ok && target == 6) {
      ok = header.biSize >= sizeof(BITMAPV5HEADER);
      if (ok) { DWORD alpha = 0xff000000u; std::memcpy(data + 52, &alpha, sizeof(alpha)); }
    }
    if (ok && target == 7) data[offset + 4] ^= 0x7f;
    if (ok && target == 8) {
      ok = header.biSize >= sizeof(BITMAPV5HEADER);
      if (ok) { DWORD intent = LCS_GM_GRAPHICS; std::memcpy(data + 108, &intent, sizeof(intent)); }
    }
    GlobalUnlock(value.value);
    return ok;
  }
  return false;
}

int Writer(int argc, wchar_t** argv) {
  if (argc != 8) return 2;
  HRESULT initialized = OleInitialize(nullptr);
  std::printf("OLE_CHILD_INITIALIZE hr=%08lx\n", static_cast<unsigned long>(initialized));
  if (FAILED(initialized)) return 2;
  const std::wstring prefix = argv[2];
  HWND borrowed = reinterpret_cast<HWND>(static_cast<uintptr_t>(_wcstoui64(argv[3], nullptr, 10)));
  const bool use_borrowed = _wtoi(argv[4]) != 0;
  const bool empty = _wtoi(argv[5]) != 0;
  const bool replay = _wtoi(argv[6]) != 0;
  const int target = _wtoi(argv[7]); // 0=replay, 1=text, 2=HTML, 3=pixel, 4=same text, 5..8=image metadata.
  HANDLE ready = OpenEventW(EVENT_MODIFY_STATE, FALSE, (prefix + L"-ready").c_str());
  HANDLE stop = OpenEventW(SYNCHRONIZE, FALSE, (prefix + L"-stop").c_str());
  HANDLE mapping = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, (prefix + L"-map").c_str());
  auto* result = mapping ? static_cast<Observation*>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Observation))) : nullptr;
  if (!ready || !stop || !result) {
    if (result) UnmapViewOfFile(result);
    if (mapping) CloseHandle(mapping);
    if (ready) CloseHandle(ready);
    if (stop) CloseHandle(stop);
    OleUninitialize();
    return 2;
  }
  HWND own = CreateWindowExW(WS_EX_TOOLWINDOW, L"STATIC", L"OLE identity external writer",
      WS_POPUP, 0, 0, 1, 1, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
  result->pid = GetCurrentProcessId();
  result->owner_before = reinterpret_cast<uintptr_t>(GetClipboardOwner());
  bool ok = own != nullptr;
  std::vector<OwnedFormat> copies;
  const bool image_target = target == 3 || (target >= 5 && target <= 8);
  if (ok && (replay || image_target || target == 11)) ok = CopyFormats(&copies, result);
  if (ok && target == 1) ok = ReplaceBytes(&copies, CF_UNICODETEXT, WideBytes(OtherText()));
  if (ok && target == 2) ok = ReplaceBytes(&copies, HtmlFormat(), NarrowBytes(Html(OtherHtml())));
  if (ok && image_target) ok = MutatePreferredImage(&copies, target);
  if (ok && target == 4) ok = ReplaceBytes(&copies, CF_UNICODETEXT, WideBytes(OwnText()));
  if (ok && target == 9) {
    copies.erase(std::remove_if(copies.begin(), copies.end(),
        [](const OwnedFormat& value) { return value.format == CF_DIBV5; }), copies.end());
  }
  if (ok && target == 10) ok = ReplaceBytes(&copies, CF_DIBV5, std::vector<uint8_t>{0});
  if (ok && !replay && image_target) {
    UINT keep = 0;
    for (const auto& value : copies)
      if (value.format == CF_DIBV5 || (!keep && value.format == CF_DIB)) keep = value.format;
    copies.erase(std::remove_if(copies.begin(), copies.end(),
        [keep](const OwnedFormat& value) { return value.format != keep; }), copies.end());
  }
  if (ok && target != 11) {
    result->opened = Open(use_borrowed ? borrowed : own);
    ok = result->opened != 0;
    if (!ok) FirstError(result);
  }
  if (ok && target != 11) {
    if (empty) {
      result->emptied = EmptyClipboard() != 0;
      ok = result->emptied != 0;
      if (!ok) FirstError(result);
    }
    if (ok) {
      for (auto& value : copies) {
        if (!SetClipboardData(value.format, value.value)) { FirstError(result); ok = false; break; }
        value.value = nullptr; // Windows owns it after a successful SetClipboardData.
        ++result->formats_set;
      }
      result->published = ok && !copies.empty();
    }
    result->closed = CloseClipboard() != 0;
    if (!result->closed) { FirstError(result); ok = false; }
  }
  result->owner_after = reinterpret_cast<uintptr_t>(GetClipboardOwner());
  result->ready = 1;
  SetEvent(ready);
  const bool stopped = PumpWait(stop, 20000);
  if (own) DestroyWindow(own);
  UnmapViewOfFile(result);
  CloseHandle(mapping); CloseHandle(ready); CloseHandle(stop);
  copies.clear();
  OleUninitialize();
  return ok && stopped ? 0 : 2;
}

struct ForeignCase {
  const char* name;
  bool borrowed, empty, replay;
  int target;
};
void Replacement(const ForeignCase& test) {
  ++case_number;
  ClearFixture();
  auto* object = new Publication(2, false);
  GUID marker = object->marker();
  Check(object->marker_result() == S_OK, "full marker was generated by COM");
  HRESULT set = OleSetClipboard(object);
  Hr(test.name, set);
  Check(set == S_OK, "replacement control begins with actual own publication");
  if (set != S_OK) { ClearFixture(); object->Release(); return; }
  auto original_binding = ReadBinding(object, "before-foreign");
  CheckOwn(original_binding.projection, 2, false, marker);
  Check(original_binding.sequence != 0, "foreign control begins with a valid own completion proof");
  Check(OleIsCurrentClipboard(object) == S_OK, "original identity current before foreign process");

  GUID nonce{};
  HRESULT nonce_result = CoCreateGuid(&nonce);
  Check(nonce_result == S_OK, "fresh child IPC identity");
  if (nonce_result != S_OK) { ClearFixture(); object->Release(); return; }
  wchar_t guid[40]{};
  StringFromGUID2(nonce, guid, 40);
  const std::wstring prefix = std::wstring(L"Local\\Gesher-OLE-Identity-") + guid;
  HANDLE ready = CreateEventW(nullptr, TRUE, FALSE, (prefix + L"-ready").c_str());
  HANDLE stop = CreateEventW(nullptr, TRUE, FALSE, (prefix + L"-stop").c_str());
  HANDLE mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
      sizeof(Observation), (prefix + L"-map").c_str());
  auto* result = mapping ? static_cast<Observation*>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Observation))) : nullptr;
  Check(ready && stop && mapping && result, "own child synchronization resources created");
  PROCESS_INFORMATION process{};
  STARTUPINFOW startup{};
  startup.cb = sizeof(startup);
  wchar_t executable[32768]{};
  DWORD length = GetModuleFileNameW(nullptr, executable, 32768);
  bool started = false;
  if (ready && stop && result && length && length < 32768) {
    *result = Observation{};
    const auto owner = static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(GetClipboardOwner()));
    std::wstring command = L"\"" + std::wstring(executable) + L"\" --writer \"" + prefix + L"\" "
        + std::to_wstring(owner) + L" " + std::to_wstring(test.borrowed) + L" "
        + std::to_wstring(test.empty) + L" " + std::to_wstring(test.replay) + L" "
        + std::to_wstring(test.target);
    started = CreateProcessW(executable, command.data(), nullptr, nullptr, FALSE, 0,
                             nullptr, nullptr, &startup, &process) != 0;
  }
  Check(started, "actual separate writer process started");
  if (started) {
    FILETIME created{}, exited{}, kernel{}, user{};
    Check(GetProcessTimes(process.hProcess, &created, &exited, &kernel, &user) != 0
          && (created.dwHighDateTime || created.dwLowDateTime), "real child process generation");
    bool observed = PumpWait(ready, 15000);
    Check(observed, "STA pumps until foreign publication completes");
    if (observed) {
      Check(result->pid == process.dwProcessId && result->pid != GetCurrentProcessId(),
            "observed writer is the actual separate process");
      std::printf("OLE_FOREIGN case=%u name=%s borrowed=%d empty=%d replay=%d target=%d "
          "pid=%lu opened=%ld emptied=%ld published=%ld closed=%ld formats_seen=%ld "
          "formats_copied=%ld copy_failed=%ld formats_set=%ld error=%lu "
          "owner_before=%llu owner_after=%llu\n", case_number, test.name, test.borrowed,
          test.empty, test.replay, test.target, static_cast<unsigned long>(result->pid),
          static_cast<long>(result->opened), static_cast<long>(result->emptied),
          static_cast<long>(result->published), static_cast<long>(result->closed),
          static_cast<long>(result->formats_seen), static_cast<long>(result->formats_copied),
          static_cast<long>(result->copy_failed), static_cast<long>(result->formats_set),
          static_cast<unsigned long>(result->error), result->owner_before, result->owner_after);
      for (LONG index = 0; index < result->formats_seen && index < 128; ++index) {
        std::printf("OLE_REPLAY_RECEIPT case=%u format=%u duplicated=%ld error=%lu\n",
            case_number, result->format_ids[index],
            static_cast<long>(result->format_duplicated[index]),
            static_cast<unsigned long>(result->format_errors[index]));
      }
      bool wrote = test.target == 11
          ? result->read_opened && result->read_closed && result->formats_seen > 0
              && result->formats_seen == result->formats_copied && result->copy_failed == 0
              && !result->published && result->error == 0
          : result->opened && (!test.empty || result->emptied)
              && result->published && result->closed && result->error == 0;
      Check(wrote, "foreign operation completed its actual Win32 calls");
      if (test.replay) Check(result->copy_failed == 0
          && result->formats_seen > 0 && result->formats_seen == result->formats_copied,
          "all enumerated native formats were actually duplicated");
      HRESULT current_before = OleIsCurrentClipboard(object);
      Hr("current-after-foreign-before-read", current_before);
      auto foreign_binding = ReadBinding(object, "after-foreign");
      const auto& value = foreign_binding.projection;
      HRESULT current_after = OleIsCurrentClipboard(object);
      Hr("current-after-foreign-after-read", current_after);
      Check(value.opened && value.closed, "read actual foreign clipboard data");
      if (wrote) {
        if (test.target == 1) Check(value.text_present && value.text == OtherText(),
                                   "different external text is really observable");
        if (test.target == 2) Check(value.html_present && value.html == Html(OtherHtml()),
                                   "different external HTML is really observable");
        if (test.target == 3) Check(value.image_present && value.pixel == 0xfe01afu,
                                   "different preferred external image is really observable");
        if (test.target >= 5 && test.target <= 8) {
          Check(value.image_format == CF_DIBV5 && !value.raw_image.empty()
                && value.raw_image != original_binding.projection.raw_image,
                "full preferred image metadata or second pixel changed in actual native data");
          Check(value.pixel == original_binding.projection.pixel,
                "metadata and second-pixel controls preserve the first pixel");
        }
        if (test.target == 10) {
          Check(value.image_format == CF_DIBV5 && value.raw_image.size() < sizeof(BITMAPINFOHEADER)
                && !value.image_present,
                "invalid preferred format is observed instead of silently authorizing matching fallback DIB");
        }
        if (test.target == 0 || test.target == 4 || test.target == 9 || test.target == 11) {
          Check(value.text_present && value.text == OwnText(), "same-looking external text preserved");
          if (test.replay) {
            Check(value.html_present && value.html == Html(OwnHtml()), "replayed HTML projection exact");
            Check(value.image_present && value.pixel == 0x735231u, "replayed image projection exact");
          }
        }
        if (test.replay) Check(value.marker_present && IsEqualGUID(value.marker, marker),
                               "foreign process copied the full available public marker");
        // EmptyClipboard is an actual replacement even if all public projection
        // bytes are copied: richer private state must not be revived. In-place
        // changes of observable text/HTML/image must also invalidate the binding.
        // A same-value in-place write is recorded, not rejected merely by actor.
        if (test.target == 11) {
          Check(foreign_binding.sequence == original_binding.sequence && foreign_binding.sequence != 0
                && current_before == S_OK && current_after == S_OK,
                "real foreign read of every available native format preserves the own receipt");
          Check(value.raw_text == original_binding.projection.raw_text
                && value.raw_html == original_binding.projection.raw_html
                && value.raw_image == original_binding.projection.raw_image,
                "read-only native consumer preserves every complete preferred-format byte");
          std::printf("OLE_READ_ONLY case=%u copied=%ld opened=%ld closed=%ld before=%lu after=%lu\n",
              case_number, static_cast<long>(result->formats_copied),
              static_cast<long>(result->read_opened), static_cast<long>(result->read_closed),
              static_cast<unsigned long>(original_binding.sequence),
              static_cast<unsigned long>(foreign_binding.sequence));
        }
        if (test.empty || (test.target >= 1 && test.target <= 10 && test.target != 4)) {
          Check(foreign_binding.sequence == 0,
                "replacement cannot retain the prior private publication binding");
        }
        // Keep the API-only result visible; its original fatal assertions remain
        // in the separate byte-identical baseline executable.
        std::printf("OLE_BINDING_IDENTITY_ONLY case=%u before=%08lx after=%08lx\n",
                    case_number, static_cast<unsigned long>(current_before),
                    static_cast<unsigned long>(current_after));
      }
    }
    SetEvent(stop);
    bool ended = PumpWait(process.hProcess, 5000);
    Check(ended, "owned foreign writer retires after stop");
    if (!ended) {
      // Bounded cleanup of this exact process created by this fixture only.
      TerminateProcess(process.hProcess, 3);
      WaitForSingleObject(process.hProcess, 5000);
    }
    DWORD code = STILL_ACTIVE;
    GetExitCodeProcess(process.hProcess, &code);
    Check(code == 0, "foreign writer reports complete resource cleanup");
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
  }
  if (result) UnmapViewOfFile(result);
  if (mapping) CloseHandle(mapping);
  if (ready) CloseHandle(ready);
  if (stop) CloseHandle(stop);
  ClearFixture();
  object->Release();
  PumpPending();
  Check(live_objects == 0 && live_enumerators == 0, "replacement releases all owned OLE objects");
}



class SerialPublisher {
 public:
  struct Result { DWORD sequence = 0; uint64_t identity = 0; bool formats = false, stable = false; };
  SerialPublisher() {
    work_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    stop_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    ready_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (work_ && stop_ && ready_)
      thread_ = CreateThread(nullptr, 0, &Run, this, 0, nullptr);
  }
  bool Ready() {
    return thread_ && WaitForSingleObject(ready_, 10000) == WAIT_OBJECT_0
        && SUCCEEDED(initialized_.load(std::memory_order_acquire));
  }
  bool Submit(int kind, Result* output) {
    auto request = std::make_shared<Request>(kind);
    if (!request->done) return false;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (stopping_) return false;
      requests_.push_back(request);
      if (!SetEvent(work_)) { requests_.pop_back(); return false; }
    }
    // The diagnostic process has an external 180-second watchdog. It retains
    // each request until the actual STA operation completes, so no timeout can
    // leave a dangling event, caller stack or falsely completed publication.
    if (WaitForSingleObject(request->done, INFINITE) != WAIT_OBJECT_0
        || !request->complete.load(std::memory_order_acquire)) return false;
    if (output) *output = request->result;
    return request->result.sequence != 0;
  }
  void Close() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      stopping_ = true;
    }
    if (thread_) {
      SetEvent(stop_);
      if (!PumpWait(thread_, 120000)) {
        std::printf("FAIL OLE_BINDING case=%u private STA did not retire within diagnostic bound\n", case_number);
        ExitProcess(3);
      }
      CloseHandle(thread_); thread_ = nullptr;
    }
    if (work_) { CloseHandle(work_); work_ = nullptr; }
    if (stop_) { CloseHandle(stop_); stop_ = nullptr; }
    if (ready_) { CloseHandle(ready_); ready_ = nullptr; }
  }
  HRESULT flush_result() const { return flushed_.load(std::memory_order_acquire); }
  bool matches_flushed(const Projection& value) const {
    return flush_result() == S_OK && value.opened && value.closed
        && value.raw_text == last_text_ && value.html_available == last_has_html_
        && value.raw_html == last_html_
        && (last_has_image_ ? value.image_format == CF_DIBV5 : value.image_format == 0)
        && value.raw_image == last_image_;
  }
  ~SerialPublisher() { Close(); }
 private:
  struct Request {
    explicit Request(int k) : kind(k), done(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {
      InterlockedIncrement(&live_requests);
    }
    ~Request() { if (done) CloseHandle(done); InterlockedDecrement(&live_requests); }
    int kind;
    HANDLE done;
    Result result;
    std::atomic<bool> complete{false};
  };
  static DWORD WINAPI Run(void* parameter) {
    auto* self = static_cast<SerialPublisher*>(parameter);
    HRESULT init = OleInitialize(nullptr);
    self->initialized_.store(init, std::memory_order_release);
    SetEvent(self->ready_);
    if (FAILED(init)) return 1;
    std::printf("OLE_SERIAL_STA thread=%lu initialize=%08lx\n",
                static_cast<unsigned long>(GetCurrentThreadId()), static_cast<unsigned long>(init));
    Publication* current = nullptr;
    HANDLE events[] = {self->stop_, self->work_};
    bool running = true;
    while (running) {
      DWORD ready = MsgWaitForMultipleObjects(2, events, FALSE, INFINITE, QS_ALLINPUT);
      if (ready == WAIT_OBJECT_0) break;
      if (ready == WAIT_OBJECT_0 + 1) {
        std::shared_ptr<Request> request;
        {
          std::lock_guard<std::mutex> lock(self->mutex_);
          if (!self->requests_.empty()) {
            request = self->requests_.front();
            self->requests_.pop_front();
          }
          if (self->requests_.empty()) ResetEvent(self->work_);
        }
        if (request) {
          auto* next = new (std::nothrow) Publication(request->kind, false);
          if (next && next->ready() && OleSetClipboard(next) == S_OK) {
            if (current) current->Release();
            current = next;
            const Binding bound = ReadBinding(current, "serialized-own-write");
            request->result = {bound.sequence, current->private_identity(), bound.formats, bound.stable};
          } else if (next) {
            next->Release();
          }
          request->complete.store(true, std::memory_order_release);
          SetEvent(request->done);
          PumpPending();
        }
      } else if (ready == WAIT_OBJECT_0 + 2) {
        MSG message;
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
          if (message.message == WM_QUIT) { running = false; break; }
          TranslateMessage(&message); DispatchMessageW(&message);
        }
      } else {
        running = false;
      }
    }
    // Flush only the exact object still owned by this STA. A foreign replacement
    // is left untouched; no global Clear is used to disguise a lifetime failure.
    HRESULT flush = S_FALSE;
    if (current) {
      const auto* text = current->expected(CF_UNICODETEXT);
      const auto* html = current->expected(static_cast<CLIPFORMAT>(HtmlFormat()));
      const auto* image = current->expected(CF_DIBV5);
      if (text) self->last_text_ = *text;
      self->last_has_html_ = html != nullptr;
      self->last_has_image_ = image != nullptr;
      if (html) self->last_html_ = *html;
      if (image) self->last_image_ = *image;
      const HRESULT own = OleIsCurrentClipboard(current);
      flush = own == S_OK ? OleFlushClipboard() : own;
      current->Release();
    }
    self->flushed_.store(flush, std::memory_order_release);
    {
      std::lock_guard<std::mutex> lock(self->mutex_);
      for (const auto& request : self->requests_) {
        request->complete.store(true, std::memory_order_release);
        SetEvent(request->done);
      }
      self->requests_.clear();
    }
    OleUninitialize();
    std::printf("OLE_SERIAL_STA teardown flush=%08lx live_objects=%ld live_enumerators=%ld\n",
                static_cast<unsigned long>(flush), static_cast<long>(live_objects),
                static_cast<long>(live_enumerators));
    return 0;
  }
 public:
  inline static LONG live_requests = 0;
 private:
  std::mutex mutex_;
  std::deque<std::shared_ptr<Request>> requests_;
  bool stopping_ = false;
  HANDLE work_ = nullptr, stop_ = nullptr, ready_ = nullptr, thread_ = nullptr;
  std::atomic<HRESULT> initialized_{E_PENDING}, flushed_{E_PENDING};
  std::vector<uint8_t> last_text_, last_html_, last_image_;
  bool last_has_html_ = false, last_has_image_ = false;
};
void ConcurrentCase() {
  ++case_number;
  ClearFixture();
  SerialPublisher publisher;
  const bool ready = publisher.Ready();
  Check(ready, "one real pumped STA starts for all plain HTML and rich publications");
  constexpr size_t workers = 6, writes = 8;
  std::array<std::array<SerialPublisher::Result, writes>, workers> results{};
  std::array<std::array<bool, writes>, workers> accepted{};
  std::vector<std::thread> threads;
  HANDLE completed = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  std::atomic<size_t> completed_workers{0};
  Check(completed != nullptr, "concurrent caller completion uses an owned event");
  if (ready && completed) {
    for (size_t worker = 0; worker < workers; ++worker) {
      threads.emplace_back([worker, completed, &completed_workers, &publisher, &results, &accepted]() {
        for (size_t index = 0; index < writes; ++index)
          accepted[worker][index] = publisher.Submit(
              static_cast<int>((worker + index) % 3u), &results[worker][index]);
        if (completed_workers.fetch_add(1, std::memory_order_acq_rel) + 1 == workers)
          SetEvent(completed);
      });
    }
  }
  if (!threads.empty() && !PumpWait(completed, 120000)) {
    std::printf("FAIL OLE_BINDING case=%u actual concurrent callers exceeded diagnostic bound\n", case_number);
    ExitProcess(3);
  }
  for (auto& thread : threads) thread.join();
  if (completed) CloseHandle(completed);
  std::vector<uint64_t> identities;
  std::vector<DWORD> sequences;
  for (size_t worker = 0; worker < workers; ++worker) {
    for (size_t index = 0; index < writes; ++index) {
      const auto& result = results[worker][index];
      Check(accepted[worker][index] && result.sequence && result.formats && result.stable,
            "concurrent caller completes its actual full-format original-object publication");
      identities.push_back(result.identity);
      sequences.push_back(result.sequence);
    }
  }
  std::sort(identities.begin(), identities.end());
  std::sort(sequences.begin(), sequences.end());
  Check(identities.front() != 0 && std::adjacent_find(identities.begin(), identities.end()) == identities.end(),
        "every concurrent request retains a distinct private publication identity");
  Check(sequences.front() != 0 && std::adjacent_find(sequences.begin(), sequences.end()) == sequences.end(),
        "every serialized native write has its own observed nonzero completion sequence");
  publisher.Close();
  Check(publisher.flush_result() == S_OK, "normal STA shutdown flushes only its current private publication");
  auto after = ReadProjection("after-serialized-shutdown");
  Check(after.text_present && after.text == OwnText() && publisher.matches_flushed(after),
        "serialized publisher shutdown preserves all complete preferred native formats");
  Check(live_objects == 0 && live_enumerators == 0 && SerialPublisher::live_requests == 0,
        "concurrent objects enumerators requests and STA ownership retire");
  std::printf("OLE_SERIAL_SUMMARY workers=%zu writes=%zu requests=%zu live_requests=%ld\n",
              workers, writes, workers * writes, static_cast<long>(SerialPublisher::live_requests));
  ClearFixture();
}

unsigned layout_exact_differences = 0;
unsigned layout_conversion_failures = 0;
unsigned layout_old_available = 0;
unsigned layout_semantics_required = 0;
unsigned layout_extended_unresolved = 0;
struct ImageInput { const char* name; std::vector<uint8_t> bytes; };
std::vector<uint8_t> ImageFixture(DWORD header_size, WORD bits, DWORD compression,
                                bool top_down = false, bool profile = false) {
  const bool core = header_size == sizeof(BITMAPCOREHEADER);
  const DWORD colors = bits <= 8 ? (DWORD{1} << bits) : 0;
  const size_t masks = header_size == 40 && (compression == BI_BITFIELDS || compression == 6)
      ? (compression == 6 ? 16u : 12u) : 0;
  const size_t palette = static_cast<size_t>(colors) * (core ? 3u : 4u);
  const size_t row = ((size_t{2} * bits + 31u) / 32u) * 4u;
  const std::vector<uint8_t> rle8 = {2, 1, 0, 0, 2, 2, 0, 0, 0, 1};
  const std::vector<uint8_t> rle4 = {2, 0x12, 0, 0, 2, 0x23, 0, 0, 0, 1};
  const size_t pixels = compression == BI_RLE8 ? rle8.size()
      : (compression == BI_RLE4 ? rle4.size() : row * 2u);
  const size_t pixel_offset = static_cast<size_t>(header_size) + masks + palette;
  const size_t profile_size = profile ? 132u : 0u;
  std::vector<uint8_t> data(pixel_offset + pixels + profile_size, 0);
  BITMAPV5HEADER h{};
  h.bV5Size = header_size;
  h.bV5Width = 2; h.bV5Height = top_down ? -2 : 2;
  h.bV5Planes = 1; h.bV5BitCount = bits; h.bV5Compression = compression;
  h.bV5SizeImage = static_cast<DWORD>(pixels);
  h.bV5XPelsPerMeter = 3780; h.bV5YPelsPerMeter = 7559;
  h.bV5ClrUsed = colors; h.bV5ClrImportant = colors;
  if (compression == BI_BITFIELDS || compression == 6) {
    h.bV5RedMask = bits == 16 ? 0xf800u : 0x00ff0000u;
    h.bV5GreenMask = bits == 16 ? 0x07e0u : 0x0000ff00u;
    h.bV5BlueMask = bits == 16 ? 0x001fu : 0x000000ffu;
    if (header_size >= 56 || compression == 6) h.bV5AlphaMask = 0xff000000u;
  }
  h.bV5CSType = profile ? PROFILE_EMBEDDED : LCS_sRGB;
  h.bV5GammaRed = 0x00018000u;
  h.bV5GammaGreen = 0x00020000u;
  h.bV5GammaBlue = 0x00028000u;
  h.bV5Intent = LCS_GM_IMAGES;
  if (profile) {
    h.bV5ProfileData = static_cast<DWORD>(pixel_offset + pixels);
    h.bV5ProfileSize = static_cast<DWORD>(profile_size);
  }
  if (core) {
    BITMAPCOREHEADER c{};
    c.bcSize = sizeof(c); c.bcWidth = 2; c.bcHeight = 2;
    c.bcPlanes = 1; c.bcBitCount = bits;
    std::memcpy(data.data(), &c, sizeof(c));
  } else {
    std::memcpy(data.data(), &h, header_size);
  }
  if (masks) std::memcpy(data.data() + header_size, &h.bV5RedMask, masks);
  for (DWORD index = 0; index < colors; ++index) {
    const size_t offset = static_cast<size_t>(header_size) + masks
        + static_cast<size_t>(index) * (core ? 3u : 4u);
    data[offset] = static_cast<uint8_t>(index * 31u);
    data[offset + 1] = static_cast<uint8_t>(index * 53u);
    data[offset + 2] = static_cast<uint8_t>(index * 79u);
  }
  if (compression == BI_RLE8) {
    std::copy(rle8.begin(), rle8.end(), data.begin() + static_cast<ptrdiff_t>(pixel_offset));
  } else if (compression == BI_RLE4) {
    std::copy(rle4.begin(), rle4.end(), data.begin() + static_cast<ptrdiff_t>(pixel_offset));
  } else {
    for (size_t index = 0; index < pixels; ++index)
      data[pixel_offset + index] = static_cast<uint8_t>((index * 37u + 17u) & 0xffu);
  }
  if (profile) {
    // A complete, bounded embedded-profile payload used for byte/layout
    // preservation. This probe does not assert a color-engine interpretation.
    uint8_t* p = data.data() + h.bV5ProfileData;
    p[3] = 132; p[8] = 4; p[9] = 0x30;
    std::memcpy(p + 12, "mntrRGB XYZ ", 12);
    std::memcpy(p + 36, "acsp", 4);
    std::memcpy(p + 40, "MSFT", 4);
    p[68] = 0; p[69] = 0; p[70] = 0xf6; p[71] = 0xd6;
    p[72] = 0; p[73] = 1; p[74] = 0; p[75] = 0;
    p[76] = 0; p[77] = 0; p[78] = 0xd3; p[79] = 0x2d;
  }
  return data;
}
void HexImage(const char* name, const char* phase, const std::vector<uint8_t>& bytes) {
  std::printf("OLE_IMAGE_BYTES case=%u name=%s phase=%s size=%zu hex=", case_number, name, phase, bytes.size());
  for (uint8_t value : bytes) std::printf("%02x", static_cast<unsigned>(value));
  std::printf("\n");
}

// Wrap only the fixture's original packed bytes in a file header. This does
// not call the candidate converter or learn offsets from its output. Windows'
// actual BMP decoder independently interprets the requested source file.
std::vector<uint8_t> FixtureBmp(const std::vector<uint8_t>& dib) {
  if (dib.size() < 12) return {};
  DWORD size = 0;
  std::memcpy(&size, dib.data(), sizeof(size));
  size_t pixel_offset = 0;
  if (size == 12) {
    BITMAPCOREHEADER h{};
    std::memcpy(&h, dib.data(), sizeof(h));
    pixel_offset = 12u + (h.bcBitCount <= 8 ? (size_t{1} << h.bcBitCount) * 3u : 0u);
  } else {
    if (dib.size() < sizeof(BITMAPINFOHEADER)) return {};
    BITMAPINFOHEADER h{};
    std::memcpy(&h, dib.data(), sizeof(h));
    const size_t colors = h.biClrUsed ? h.biClrUsed
        : (h.biBitCount <= 8 ? size_t{1} << h.biBitCount : 0u);
    const size_t masks = size == 40 && h.biCompression == BI_BITFIELDS ? 12u
        : (size == 40 && h.biCompression == 6 ? 16u : 0u);
    pixel_offset = static_cast<size_t>(size) + masks + colors * 4u;
  }
  if (pixel_offset >= dib.size() || dib.size() > 64u * 1024u * 1024u) return {};
  static_assert(sizeof(BITMAPFILEHEADER) == 14);
  BITMAPFILEHEADER file{};
  file.bfType = 0x4d42;
  file.bfSize = static_cast<DWORD>(14u + dib.size());
  file.bfOffBits = static_cast<DWORD>(14u + pixel_offset);
  std::vector<uint8_t> result(14u + dib.size());
  std::memcpy(result.data(), &file, sizeof(file));
  std::memcpy(result.data() + 14u, dib.data(), dib.size());
  return result;
}

bool RecordedOldImageAvailable(const char* name) {
  // Exact old107 observations on both Windows 2022/2025 in 44727512461 a1.
  // This is a named regression matrix, not a general image acceptance policy.
  const char* const available[] = {"info-1", "info-4", "info-8", "info-16-rgb",
      "info-16-masks", "info-24", "info-24-top-down", "info-32-rgb",
      "info-32-masks", "info-rle8", "info-rle4"};
  for (const char* value : available) if (std::strcmp(name, value) == 0) return true;
  return false;
}

void ImageLayoutCase(const ImageInput& input) {
  ++case_number;
  ClearFixture();
  uint32_t old_receipt = 0;
  const bool old_set = old_image::WriteRichClipboard(
      u"", u"", input.bytes.data(), input.bytes.size(), &old_receipt);
  Check(old_set, "exact original production CF_DIB body really publishes the input");
  std::vector<uint8_t> old_bmp;
  const bool old_read = old_image::ReadClipboardImage(&old_bmp);
  const auto old_projection = ReadProjection("image-existing-preferred");
  const bool old_has_image = old_read && old_bmp.size() >= 14u + sizeof(BITMAPINFOHEADER)
      && old_projection.opened && old_projection.closed && old_projection.image_format != 0
      && std::vector<uint8_t>(old_bmp.begin() + 14, old_bmp.end()) == old_projection.raw_image;
  const bool expected_old_image = RecordedOldImageAvailable(input.name);
  if (old_has_image) ++layout_old_available;
  HexImage(input.name, "input-dib", input.bytes);
  HexImage(input.name, "old-reader-bmp", old_bmp);
  ClearFixture();
  auto* candidate = new Publication(2, false, &input.bytes);
  HRESULT new_set = candidate->ready() ? OleSetClipboard(candidate) : E_INVALIDARG;
  Binding current;
  if (new_set == S_OK) current = ReadBinding(candidate, "image-candidate-preferred");
  else ++layout_conversion_failures;
  std::vector<uint8_t> new_bmp;
  const bool new_read = old_image::ReadClipboardImage(&new_bmp);
  HexImage(input.name, "new-reader-bmp", new_bmp);
  const auto source_bmp = FixtureBmp(input.bytes);
  HexImage(input.name, "source-bmp", source_bmp);
  const auto source_picture = gesher::wic_diagnostic::Decode(source_bmp);
  const auto old_picture = gesher::wic_diagnostic::Decode(old_bmp);
  const auto new_picture = gesher::wic_diagnostic::Decode(new_bmp);
  gesher::wic_diagnostic::Print(input.name, "source", source_picture);
  gesher::wic_diagnostic::Print(input.name, "old-reader", old_picture);
  gesher::wic_diagnostic::Print(input.name, "new-reader", new_picture);
  const bool source_new_pixels = source_picture.pixels_equal(new_picture);
  const bool source_old_pixels = source_picture.pixels_equal(old_picture);
  const bool source_new_resolution = source_picture.resolution_result == S_OK
      && new_picture.resolution_result == S_OK
      && source_picture.dpi_x == new_picture.dpi_x && source_picture.dpi_y == new_picture.dpi_y;
  const bool source_new_contexts = source_picture.color_result == new_picture.color_result
      && source_picture.colors == new_picture.colors;
  std::printf("OLE_IMAGE_SEMANTICS name=%s old_expected=%d source_old_pixels=%d "
      "source_new_pixels=%d source_new_resolution=%d source_new_contexts=%d\n",
      input.name, expected_old_image, source_old_pixels, source_new_pixels,
      source_new_resolution, source_new_contexts);
  const bool exact = old_read == new_read && old_bmp == new_bmp;
  if (!exact) ++layout_exact_differences;
  std::printf("OLE_IMAGE_LAYOUT case=%u name=%s old_set=%d new_set=%08lx old_image=%d "
      "old_format=%u new_format=%u exact=%d receipt=%lu old_receipt=%lu input_bytes=%zu "
      "old_bytes=%zu new_bytes=%zu\n", case_number, input.name, old_set,
      static_cast<unsigned long>(new_set), old_has_image, old_projection.image_format,
      current.projection.image_format, exact, static_cast<unsigned long>(current.sequence),
      static_cast<unsigned long>(old_receipt), input.bytes.size(), old_bmp.size(), new_bmp.size());
  // All complete-byte differences remain explicit evidence for independent
  // compatibility review. A positive binding is not a byte-exact migration or
  // product approval; no image decoder or metadata compatibility is presumed.
  Check(old_has_image == expected_old_image,
        "exact old reader reproduces its independently observed 11 available and 11 unavailable layouts");
  Check(new_set == S_OK && current.sequence != 0 && new_read
        && new_bmp.size() >= 14u + sizeof(BITMAPINFOHEADER)
        && std::vector<uint8_t>(new_bmp.begin() + 14, new_bmp.end()) == current.projection.raw_image,
        "every observed old image input retains its complete actual preferred-reader binding");
  Check(!source_bmp.empty(), "original fixture has an independent complete BMP input for Windows decoding");
  if (expected_old_image) {
    ++layout_semantics_required;
    Check(source_picture.result == S_OK && new_picture.result == S_OK && source_new_pixels,
          "every actually supported old input preserves all requested decoded pixels and alpha");
    Check(source_new_resolution,
          "every actually supported old input preserves its actual horizontal and vertical resolution");
    Check(source_new_contexts,
          "every actually supported old input preserves its actual color-context result and bytes");
  } else {
    // A decoder error, normalized alpha or profile difference here remains an
    // explicit unresolved extension, never a compatibility PASS for the product.
    if (!source_new_pixels || !source_new_resolution || !source_new_contexts)
      ++layout_extended_unresolved;
  }
  ClearFixture();
  candidate->Release();
  PumpPending();
  Check(live_objects == 0 && live_enumerators == 0, "image-layout objects and enumerators retire");
}

void InvalidLayouts() {
  ++case_number;
  std::vector<std::vector<uint8_t>> invalid;
  invalid.emplace_back();
  invalid.push_back({0, 0, 0});
  auto truncated = Dib(); truncated.resize(39); invalid.push_back(truncated);
  auto bad_header = Dib(); DWORD unknown = 63; std::memcpy(bad_header.data(), &unknown, 4); invalid.push_back(bad_header);
  auto bad_width = Dib(); LONG negative = -2; std::memcpy(bad_width.data() + 4, &negative, 4); invalid.push_back(bad_width);
  auto bad_height = Dib(); LONG zero = 0; std::memcpy(bad_height.data() + 8, &zero, 4); invalid.push_back(bad_height);
  auto short_pixels = Dib(); short_pixels.pop_back(); invalid.push_back(short_pixels);
  auto bad_palette = ImageFixture(40, 8, BI_RGB); DWORD many = 0xffffffffu;
  std::memcpy(bad_palette.data() + 32, &many, 4); invalid.push_back(bad_palette);
  auto bad_profile = ImageFixture(124, 32, BI_BITFIELDS, false, true);
  DWORD outside = static_cast<DWORD>(bad_profile.size() + 1);
  std::memcpy(bad_profile.data() + 112, &outside, 4); invalid.push_back(bad_profile);
  auto overlapping = ImageFixture(124, 32, BI_BITFIELDS, false, true);
  DWORD overlap = 124; std::memcpy(overlapping.data() + 112, &overlap, 4); invalid.push_back(overlapping);
  auto bad_stride = Dib(); LONG huge = 0x7fffffff;
  std::memcpy(bad_stride.data() + 4, &huge, 4); invalid.push_back(bad_stride);
  const DWORD before = GetClipboardSequenceNumber();
  for (const auto& value : invalid) {
    std::vector<uint8_t> output = {0x5a};
    Check(!gesher::clipboard_diagnostic::ToPackedV5(value, &output) && output.empty(),
          "invalid layout returns an explicit failure without fabricated image bytes");
  }
  Check(GetClipboardSequenceNumber() == before, "conversion errors never publish or clear the clipboard");
  for (DWORD intent : {LCS_GM_BUSINESS, LCS_GM_GRAPHICS, LCS_GM_IMAGES, LCS_GM_ABS_COLORIMETRIC}) {
    auto explicit_v5 = ImageFixture(124, 24, BI_RGB);
    std::memcpy(explicit_v5.data() + 108, &intent, sizeof(intent));
    std::vector<uint8_t> output;
    const bool converted = gesher::clipboard_diagnostic::ToPackedV5(explicit_v5, &output);
    DWORD retained = 0;
    if (converted && output.size() >= 112) std::memcpy(&retained, output.data() + 108, 4);
    Check(converted && retained == intent, "every explicit V5 rendering intent remains caller-owned");
  }
  std::printf("OLE_IMAGE_INVALID case=%u inputs=%zu\n", case_number, invalid.size());
}

void ShutdownCase() {
  ++case_number;
  ClearFixture();
  auto* object = new Publication(2, false);
  GUID marker = object->marker();
  Check(object->marker_result() == S_OK, "full marker was generated by COM");
  HRESULT set = OleSetClipboard(object);
  Hr("shutdown-set", set);
  HRESULT current = OleIsCurrentClipboard(object);
  Hr("shutdown-current", current);
  Check(set == S_OK && current == S_OK, "shutdown owns exact publication");
  HRESULT flush = set == S_OK && current == S_OK ? OleFlushClipboard() : E_ABORT;
  Hr("shutdown-flush", flush);
  Check(flush == S_OK, "actual OLE shutdown rendering succeeds");
  const auto shutdown_image = *object->expected(CF_DIBV5);
  const auto shutdown_text = *object->expected(CF_UNICODETEXT);
  const auto shutdown_html = *object->expected(static_cast<CLIPFORMAT>(HtmlFormat()));
  object->Release();
  OleUninitialize();
  auto after = ReadProjection("after-ole-uninitialize");
  CheckOwn(after, 2, false, marker);
  Check(after.image_format == CF_DIBV5 && after.raw_image == shutdown_image
        && after.raw_text == shutdown_text && after.raw_html == shutdown_html,
        "shutdown retains complete native text HTML and preferred-image bytes");
  Check(live_objects == 0 && live_enumerators == 0, "shutdown releases private data and enumerators");
  if (Open(nullptr)) { Check(EmptyClipboard() != 0, "final fixture-only clear"); Check(CloseClipboard() != 0, "final clear closes"); }
  else Check(false, "final fixture-only clipboard opens");
  Check(CountClipboardFormats() == 0, "final owned cleanup leaves no formats");
}
}  // namespace

int wmain(int argc, wchar_t** argv) {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  if (argc > 1 && std::wstring(argv[1]) == L"--writer") return Writer(argc, argv);
  HRESULT init = OleInitialize(nullptr);
  Hr("initialize", init);
  Check(init == S_OK || init == S_FALSE, "real STA OLE initialization");
  if (FAILED(init)) return 1;
  Check(HtmlFormat() != 0 && MarkerFormat() != 0, "actual clipboard formats register");
  OwnCase(0, false);
  OwnCase(1, false);
  OwnCase(2, false);
  OwnCase(0, true);
  const ForeignCase cases[] = {
      {"normal-different-text", false, true, false, 1},
      {"borrowed-replay-different-text", true, true, true, 1},
      {"normal-replay-same-projection", false, true, true, 0},
      {"borrowed-replay-same-projection", true, true, true, 0},
      {"normal-inplace-text", false, false, false, 1},
      {"borrowed-inplace-text", true, false, false, 1},
      {"borrowed-replay-different-html", true, true, true, 2},
      {"borrowed-replay-different-image", true, true, true, 3},
      {"normal-inplace-html", false, false, false, 2},
      {"borrowed-inplace-image", true, false, false, 3},
      {"borrowed-inplace-same-text-observation", true, false, false, 4},
      {"borrowed-inplace-image-resolution", true, false, false, 5},
      {"normal-inplace-image-alpha-mask", false, false, false, 6},
      {"borrowed-inplace-image-second-pixel", true, false, false, 7},
      {"normal-inplace-image-color-intent", false, false, false, 8},
      {"borrowed-replay-without-preferred-v5", true, true, true, 9},
      {"normal-inplace-invalid-preferred-v5", false, false, false, 10},
      {"normal-read-only-all-native-formats", false, false, true, 11},
  };
  for (const auto& value : cases) Replacement(value);
  const ImageInput images[] = {
      {"core-1", ImageFixture(12, 1, BI_RGB)},
      {"core-4", ImageFixture(12, 4, BI_RGB)},
      {"core-8", ImageFixture(12, 8, BI_RGB)},
      {"core-24", ImageFixture(12, 24, BI_RGB)},
      {"info-1", ImageFixture(40, 1, BI_RGB)},
      {"info-4", ImageFixture(40, 4, BI_RGB)},
      {"info-8", ImageFixture(40, 8, BI_RGB)},
      {"info-16-rgb", ImageFixture(40, 16, BI_RGB)},
      {"info-16-masks", ImageFixture(40, 16, BI_BITFIELDS)},
      {"info-24", ImageFixture(40, 24, BI_RGB)},
      {"info-24-top-down", ImageFixture(40, 24, BI_RGB, true)},
      {"info-32-rgb", ImageFixture(40, 32, BI_RGB)},
      {"info-32-masks", ImageFixture(40, 32, BI_BITFIELDS)},
      {"v2-32-masks", ImageFixture(52, 32, BI_BITFIELDS)},
      {"v3-32-alpha", ImageFixture(56, 32, BI_BITFIELDS)},
      {"v4-24-srgb-gamma", ImageFixture(108, 24, BI_RGB)},
      {"v4-32-alpha", ImageFixture(108, 32, BI_BITFIELDS)},
      {"v5-32-alpha", ImageFixture(124, 32, BI_BITFIELDS)},
      {"v5-24-top-down", ImageFixture(124, 24, BI_RGB, true)},
      {"v5-32-embedded-profile-bytes", ImageFixture(124, 32, BI_BITFIELDS, false, true)},
      {"info-rle8", ImageFixture(40, 8, BI_RLE8)},
      {"info-rle4", ImageFixture(40, 4, BI_RLE4)},
  };
  for (const auto& input : images) ImageLayoutCase(input);
  InvalidLayouts();
  ConcurrentCase();
  ShutdownCase(); // Balances the one successful OleInitialize on this STA.
  std::printf("OLE_BINDING completed cases=%u checks=%d failures=%d live_objects=%ld "
      "live_enumerators=%ld data_calls=%ld\n", case_number, checks, failures,
      static_cast<long>(live_objects), static_cast<long>(live_enumerators),
      static_cast<long>(data_calls));
  std::printf("OLE_IMAGE_LAYOUT_SUMMARY inputs=%zu exact_differences=%u conversion_failures=%u\n",
      sizeof(images) / sizeof(images[0]), layout_exact_differences, layout_conversion_failures);
  std::printf("OLE_IMAGE_SEMANTICS_SUMMARY old_available=%u required_inputs=%u extended_unresolved=%u\n",
      layout_old_available, layout_semantics_required, layout_extended_unresolved);
  return failures ? 1 : 0;
}
