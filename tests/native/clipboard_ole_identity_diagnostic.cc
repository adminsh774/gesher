/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Isolated Win32/OLE mechanism measurement, not a production clipboard repair.
 * The production writers and their previously failing replay/resource controls
 * are kept unchanged and run separately by the diagnostic workflow.
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
    std::printf("FAIL OLE_IDENTITY case=%u %s error=%lu\n",
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
  Publication(int kind, bool empty_text) : private_identity_(++next_identity_) {
    marker_result_ = CoCreateGuid(&marker_);
    formats_.push_back({CF_UNICODETEXT, WideBytes(empty_text ? L"" : OwnText())});
    if (kind >= 1) formats_.push_back({
        static_cast<CLIPFORMAT>(HtmlFormat()), NarrowBytes(Html(OwnHtml()))});
    if (kind >= 2) formats_.push_back({CF_DIB, Dib()});
    formats_.push_back({static_cast<CLIPFORMAT>(MarkerFormat()), Bytes(&marker_, sizeof(marker_))});
    InterlockedIncrement(&live_objects);
  }
  const GUID& marker() const { return marker_; }
  HRESULT marker_result() const { return marker_result_; }
  uint64_t private_identity() const { return private_identity_; }
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
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, format->bytes.size());
    if (!memory) return STG_E_MEDIUMFULL;
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
  if (!size || size > 16 * 1024 * 1024) return {};
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
    auto text = ReadGlobal(CF_UNICODETEXT);
    if (text.size() >= sizeof(wchar_t) && text.size() % sizeof(wchar_t) == 0) {
      const auto* data = reinterpret_cast<const wchar_t*>(text.data());
      size_t count = 0, maximum = text.size() / sizeof(wchar_t);
      while (count < maximum && data[count]) ++count;
      out.text_present = count < maximum;
      if (out.text_present) out.text.assign(data, count);
    }
    if (IsClipboardFormatAvailable(HtmlFormat())) {
      auto html = ReadGlobal(HtmlFormat());
      auto end = std::find(html.begin(), html.end(), uint8_t{0});
      out.html_present = end != html.end();
      if (out.html_present) out.html.assign(html.begin(), end);
    }
    out.image_format = IsClipboardFormatAvailable(CF_DIBV5) ? CF_DIBV5
        : (IsClipboardFormatAvailable(CF_DIB) ? CF_DIB : 0);
    if (out.image_format) out.pixel = FirstPixel(ReadGlobal(out.image_format), &out.image_present);
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
  Check(object->marker_result() == S_OK, "own full marker was generated by COM");
  HRESULT published = OleSetClipboard(object);
  Hr("own-set", published);
  Check(published == S_OK, "own actual OLE publication succeeds");
  if (published == S_OK) {
    HRESULT current = OleIsCurrentClipboard(object);
    Hr("own-current-before", current);
    Check(current == S_OK, "exact original IDataObject is current");
    auto first = ReadProjection("own-first-read");
    CheckOwn(first, kind, empty_text, marker);
    current = OleIsCurrentClipboard(object);
    Hr("own-current-after", current);
    Check(current == S_OK, "real native rendering preserves original IDataObject");
    auto second = ReadProjection("own-second-read");
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
  if (!CloseClipboard()) { FirstError(result); ok = false; }
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
bool MutatePreferredImage(std::vector<OwnedFormat>* formats) {
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
    if (ok) { data[offset] = 0xaf; data[offset + 1] = 0x01; data[offset + 2] = 0xfe; }
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
  const int target = _wtoi(argv[7]); // 0=same replay, 1=text, 2=HTML, 3=image, 4=same text.
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
  if (ok && (replay || target == 3)) ok = CopyFormats(&copies, result);
  if (ok && target == 1) ok = ReplaceBytes(&copies, CF_UNICODETEXT, WideBytes(OtherText()));
  if (ok && target == 2) ok = ReplaceBytes(&copies, HtmlFormat(), NarrowBytes(Html(OtherHtml())));
  if (ok && target == 3) ok = MutatePreferredImage(&copies);
  if (ok && target == 4) ok = ReplaceBytes(&copies, CF_UNICODETEXT, WideBytes(OwnText()));
  if (ok && !replay && target == 3) {
    UINT keep = 0;
    for (const auto& value : copies)
      if (value.format == CF_DIBV5 || (!keep && value.format == CF_DIB)) keep = value.format;
    copies.erase(std::remove_if(copies.begin(), copies.end(),
        [keep](const OwnedFormat& value) { return value.format != keep; }), copies.end());
  }
  if (ok) {
    result->opened = Open(use_borrowed ? borrowed : own);
    ok = result->opened != 0;
    if (!ok) FirstError(result);
  }
  if (ok) {
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
  CheckOwn(ReadProjection("before-foreign"), 2, false, marker);
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
      bool wrote = result->opened && (!test.empty || result->emptied)
          && result->published && result->closed && result->error == 0;
      Check(wrote, "foreign modification completed real Win32 calls");
      if (test.replay) Check(result->copy_failed == 0
          && result->formats_seen > 0 && result->formats_seen == result->formats_copied,
          "all enumerated native formats were actually duplicated");
      HRESULT current_before = OleIsCurrentClipboard(object);
      Hr("current-after-foreign-before-read", current_before);
      auto value = ReadProjection("after-foreign");
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
        if (test.target == 0 || test.target == 4) {
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
        if (test.empty || (test.target >= 1 && test.target <= 3)) {
          Check(current_before == S_FALSE && current_after == S_FALSE,
                "replacement cannot retain the prior private publication identity");
        }
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
  object->Release();
  OleUninitialize();
  auto after = ReadProjection("after-ole-uninitialize");
  CheckOwn(after, 2, false, marker);
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
  };
  for (const auto& value : cases) Replacement(value);
  ShutdownCase(); // Balances the one successful OleInitialize on this STA.
  std::printf("OLE_IDENTITY completed cases=%u checks=%d failures=%d live_objects=%ld "
      "live_enumerators=%ld data_calls=%ld\n", case_number, checks, failures,
      static_cast<long>(live_objects), static_cast<long>(live_enumerators),
      static_cast<long>(data_calls));
  return failures ? 1 : 0;
}
