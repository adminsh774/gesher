/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
#ifndef GESHER_WINDOWS_CLIPBOARD_WRITE_RECEIPT_H_
#define GESHER_WINDOWS_CLIPBOARD_WRITE_RECEIPT_H_

#include <windows.h>
#include <objbase.h>
#include <cstdint>
#include <cstring>

namespace gesher {
namespace windows {

// Windows synthesizes additional text formats during the publishing CloseClipboard.
// Its final sequence therefore cannot be captured before that close. A unique,
// non-delayed private format binds the later observation to this exact write.
// It is not a permission or caller identity and never authorizes Android access.
class ClipboardWriteReceipt {
 public:
  bool Publish() {
    if (CoCreateGuid(&token_) != S_OK) return false;
    format_ = RegisterClipboardFormatW(L"Gesher.Clipboard.WriteReceipt.v1");
    if (format_ == 0) return false;
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, sizeof(token_));
    if (memory == nullptr) return false;
    void* data = GlobalLock(memory);
    if (data == nullptr) { GlobalFree(memory); return false; }
    std::memcpy(data, &token_, sizeof(token_));
    GlobalUnlock(memory);
    if (SetClipboardData(format_, memory) == nullptr) {
      GlobalFree(memory);
      return false;
    }
    // Windows owns memory after successful publication.
    published_ = true;
    return true;
  }

  uint32_t SequenceAfterClose(HWND expected_owner) const {
    if (!published_ || expected_owner == nullptr) return 0;
    bool opened = false;
    for (int i = 0; i < 20; ++i) {
      if (OpenClipboard(nullptr)) { opened = true; break; }
      Sleep(10);
    }
    if (!opened) return 0;
    uint32_t sequence = 0;
    // The clipboard lock makes the owner, token and sequence one observation.
    // Another writer, including one using the same HWND, invalidates this receipt.
    if (GetClipboardOwner() == expected_owner && IsClipboardFormatAvailable(format_)) {
      HANDLE memory = GetClipboardData(format_);
      if (memory != nullptr && GlobalSize(memory) == sizeof(token_)) {
        const void* data = GlobalLock(memory);
        if (data != nullptr) {
          if (std::memcmp(data, &token_, sizeof(token_)) == 0) sequence = GetClipboardSequenceNumber();
          GlobalUnlock(memory);
        }
      }
    }
    // This open only reads our already-rendered metadata. A later writer changes
    // the sequence and is imported normally by ClipboardService under its lock.
    if (!CloseClipboard()) return 0;
    return sequence;
  }

 private:
  GUID token_{};
  UINT format_ = 0;
  bool published_ = false;
};

}  // namespace windows
}  // namespace gesher
#endif
