/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
/*
 * Native Gesher-host dialogs. Match the Control Center's light, blue-accent
 * surfaces without changing Windows control classes, accessibility, tab order,
 * or the default-deny behavior of permission/account prompts.
 *
 * Only Gesher-owned chrome is styled. Android application windows, OS common
 * dialogs, and system high-contrast colors are left to their respective owners.
 * This header is shared by C (system-server JNI) and C++ (the shell launcher).
 */
#ifndef GESHER_HOST_DIALOG_THEME_H
#define GESHER_HOST_DIALOG_THEME_H

#include <windows.h>
#include <wchar.h>

enum { GESHER_HOST_MESSAGE_TEXT = 1901, GESHER_HOST_MESSAGE_HEADING = 1902 };

typedef struct {
  HBRUSH surface;
  HBRUSH input;
  BOOL high_contrast;
} GesherHostTheme;

static inline BOOL GesherHostHighContrast(void) {
  HIGHCONTRASTW contrast = {0};
  contrast.cbSize = sizeof(contrast);
  return SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof(contrast), &contrast, 0)
      && (contrast.dwFlags & HCF_HIGHCONTRASTON) != 0;
}

static inline COLORREF GesherHostTextColor(const GesherHostTheme* theme) {
  return theme->high_contrast ? GetSysColor(COLOR_WINDOWTEXT) : RGB(16, 24, 40);
}

static inline COLORREF GesherHostAccentColor(const GesherHostTheme* theme) {
  return theme->high_contrast ? GetSysColor(COLOR_HOTLIGHT) : RGB(21, 112, 239);
}

/* DWM attributes are optional: Windows 10 and non-composited desktops retain
 * the normal title bar, and high-contrast overrides are never painted over. */
static inline void GesherHostApplyCaption(HWND window) {
  if (GesherHostHighContrast()) return;
  HMODULE module = LoadLibraryW(L"dwmapi.dll");
  if (module == NULL) return;
  typedef HRESULT (WINAPI *SetAttribute)(HWND, DWORD, LPCVOID, DWORD);
  SetAttribute set_attribute = (SetAttribute)GetProcAddress(module, "DwmSetWindowAttribute");
  if (set_attribute != NULL) {
    const DWORD corner = 2; /* DWMWCP_ROUND, Windows 11; ignored elsewhere. */
    const COLORREF caption = RGB(255, 255, 255);
    const COLORREF text = RGB(16, 24, 40);
    set_attribute(window, 33, &corner, sizeof(corner)); /* DWMWA_WINDOW_CORNER_PREFERENCE */
    set_attribute(window, 35, &caption, sizeof(caption)); /* DWMWA_CAPTION_COLOR */
    set_attribute(window, 36, &text, sizeof(text)); /* DWMWA_TEXT_COLOR */
  }
  FreeLibrary(module);
}

static inline void GesherHostThemeInit(GesherHostTheme* theme, HWND window) {
  theme->high_contrast = GesherHostHighContrast();
  theme->surface = theme->high_contrast ? NULL : CreateSolidBrush(RGB(255, 255, 255));
  theme->input = theme->high_contrast ? NULL : CreateSolidBrush(RGB(249, 250, 251));
  GesherHostApplyCaption(window);
}

static inline void GesherHostThemeDispose(GesherHostTheme* theme) {
  if (theme->surface != NULL) DeleteObject(theme->surface);
  if (theme->input != NULL) DeleteObject(theme->input);
  theme->surface = NULL;
  theme->input = NULL;
}

static inline HBRUSH GesherHostSurface(const GesherHostTheme* theme) {
  return theme->surface != NULL ? theme->surface : GetSysColorBrush(COLOR_WINDOW);
}

static inline HBRUSH GesherHostInput(const GesherHostTheme* theme) {
  return theme->input != NULL ? theme->input : GetSysColorBrush(COLOR_WINDOW);
}

static inline HFONT GesherHostHeadingFont(HWND window) {
  LOGFONTW font = {0};
  HFONT original = (HFONT)SendMessageW(window, WM_GETFONT, 0, 0);
  if (original == NULL || GetObjectW(original, sizeof(font), &font) == 0) return NULL;
  font.lfHeight = MulDiv(font.lfHeight, 145, 100);
  font.lfWeight = FW_SEMIBOLD;
  return CreateFontIndirectW(&font);
}

static inline void GesherHostDrawButton(const DRAWITEMSTRUCT* item,
                                        const GesherHostTheme* theme) {
  if (item->CtlType != ODT_BUTTON) return;
  BOOL primary = item->CtlID == IDYES || item->CtlID == IDOK;
  BOOL disabled = (item->itemState & ODS_DISABLED) != 0;
  BOOL pressed = (item->itemState & ODS_SELECTED) != 0;
  COLORREF fill, border, ink;
  if (theme->high_contrast) {
    fill = GetSysColor(primary && !disabled ? COLOR_HIGHLIGHT : COLOR_BTNFACE);
    border = GetSysColor(COLOR_WINDOWFRAME);
    ink = GetSysColor(disabled ? COLOR_GRAYTEXT
                             : primary ? COLOR_HIGHLIGHTTEXT : COLOR_BTNTEXT);
  } else {
    fill = disabled ? RGB(242, 244, 247)
        : primary ? (pressed ? RGB(23, 92, 211) : RGB(21, 112, 239))
                  : (pressed ? RGB(242, 244, 247) : RGB(255, 255, 255));
    border = primary && !disabled ? fill : RGB(208, 213, 221);
    ink = disabled ? RGB(152, 162, 179)
        : primary ? RGB(255, 255, 255) : RGB(52, 64, 84);
  }
  HBRUSH brush = CreateSolidBrush(fill);
  HPEN pen = CreatePen(PS_SOLID, 1, border);
  if (brush != NULL && pen != NULL) {
    HGDIOBJ old_brush = SelectObject(item->hDC, brush);
    HGDIOBJ old_pen = SelectObject(item->hDC, pen);
    RECT bounds = item->rcItem;
    int radius = MulDiv(12, GetDeviceCaps(item->hDC, LOGPIXELSX), 96);
    RoundRect(item->hDC, bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius);
    SelectObject(item->hDC, old_brush);
    SelectObject(item->hDC, old_pen);
  }
  if (brush != NULL) DeleteObject(brush);
  if (pen != NULL) DeleteObject(pen);
  RECT label = item->rcItem;
  InflateRect(&label, -5, -2);
  WCHAR text[256] = {0};
  GetWindowTextW(item->hwndItem, text, sizeof(text) / sizeof(text[0]));
  SetBkMode(item->hDC, TRANSPARENT);
  SetTextColor(item->hDC, ink);
  UINT flags = DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX;
  if ((GetWindowLongPtrW(GetParent(item->hwndItem), GWL_EXSTYLE) & WS_EX_RTLREADING) != 0)
    flags |= DT_RTLREADING;
  DrawTextW(item->hDC, text, -1, &label, flags);
  if ((item->itemState & ODS_FOCUS) != 0) {
    RECT focus = item->rcItem;
    InflateRect(&focus, -4, -4);
    DrawFocusRect(item->hDC, &focus);
  }
}

/* Use only for the host's stock, owner-drawn buttons; Windows still provides
 * keyboard and UIA button behavior (including BM_CLICK and dialog mnemonics). */
static inline INT_PTR GesherHostThemeEvent(HWND dialog, const GesherHostTheme* theme,
                                           UINT message, WPARAM wparam, LPARAM lparam,
                                           int brand_id) {
  if (message == WM_ERASEBKGND) {
    RECT client;
    GetClientRect(dialog, &client);
    FillRect((HDC)wparam, &client, GesherHostSurface(theme));
    return TRUE;
  }
  if (message == WM_CTLCOLORDLG || message == WM_CTLCOLORSTATIC
      || message == WM_CTLCOLOREDIT || message == WM_CTLCOLORLISTBOX
      || message == WM_CTLCOLORBTN) {
    HDC dc = (HDC)wparam;
    BOOL input = message == WM_CTLCOLOREDIT || message == WM_CTLCOLORLISTBOX;
    SetTextColor(dc, message == WM_CTLCOLORSTATIC
        && GetDlgCtrlID((HWND)lparam) == brand_id
        ? GesherHostAccentColor(theme) : GesherHostTextColor(theme));
    SetBkColor(dc, theme->high_contrast ? GetSysColor(COLOR_WINDOW)
               : input ? RGB(249, 250, 251) : RGB(255, 255, 255));
    return (INT_PTR)(input ? GesherHostInput(theme) : GesherHostSurface(theme));
  }
  if (message == WM_DRAWITEM && lparam != 0) {
    const DRAWITEMSTRUCT* item = (const DRAWITEMSTRUCT*)lparam;
    if (item->CtlType == ODT_BUTTON) {
      GesherHostDrawButton(item, theme);
      return TRUE;
    }
  }
  return FALSE;
}

static inline HWND GesherHostControl(HWND dialog, DWORD extra, const WCHAR* klass,
                                      const WCHAR* text, DWORD style, int id,
                                      int x, int y, int width, int height) {
  RECT bounds = {x, y, x + width, y + height};
  if (!MapDialogRect(dialog, &bounds)) return NULL;
  HWND control = CreateWindowExW(extra, klass, text, WS_CHILD | WS_VISIBLE | style,
      bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top,
      dialog, (HMENU)(INT_PTR)id, GetModuleHandleW(NULL), NULL);
  if (control != NULL)
    SendMessageW(control, WM_SETFONT, SendMessageW(dialog, WM_GETFONT, 0, 0), FALSE);
  return control;
}

typedef struct {
  const WCHAR* title;
  const WCHAR* message;
  const WCHAR* positive;
  const WCHAR* negative;
  BOOL rtl;
  BOOL accepted;
  HFONT heading_font;
  GesherHostTheme theme;
} GesherHostPromptState;

/* Windows EDIT controls use CRLF line breaks; do not mutate input or embed it in
 * a resource template. The window copies this buffer synchronously at creation. */
static inline WCHAR* GesherHostEditCopy(const WCHAR* text) {
  SIZE_T length = wcslen(text), extra = 0;
  if (length > 32767) return NULL;
  for (SIZE_T i = 0; i < length; ++i)
    if (text[i] == L'\n' && (i == 0 || text[i - 1] != L'\r')) ++extra;
  WCHAR* buffer = (WCHAR*)HeapAlloc(GetProcessHeap(), 0,
      (length + extra + 1) * sizeof(WCHAR));
  if (buffer == NULL) return NULL;
  SIZE_T at = 0;
  for (SIZE_T i = 0; i < length; ++i) {
    if (text[i] == L'\n' && (i == 0 || text[i - 1] != L'\r')) buffer[at++] = L'\r';
    buffer[at++] = text[i];
  }
  buffer[at] = 0;
  return buffer;
}

static inline INT_PTR CALLBACK GesherHostPromptProc(HWND dialog, UINT message,
                                              WPARAM wparam, LPARAM lparam) {
  GesherHostPromptState* state = (GesherHostPromptState*)GetWindowLongPtrW(dialog, DWLP_USER);
  if (message == WM_INITDIALOG) {
    state = (GesherHostPromptState*)lparam;
    SetWindowLongPtrW(dialog, DWLP_USER, (LONG_PTR)state);
    GesherHostThemeInit(&state->theme, dialog);
    DWORD reading = state->rtl ? WS_EX_RTLREADING : 0;
    DWORD align = state->rtl ? SS_RIGHT : SS_LEFT;
    WCHAR* copy = GesherHostEditCopy(state->message);
    if (copy == NULL) { EndDialog(dialog, IDCANCEL); return FALSE; }
    HWND heading = GesherHostControl(dialog, reading, L"STATIC", state->title,
        align, GESHER_HOST_MESSAGE_HEADING, 16, 14, 300, 24);
    HWND details = GesherHostControl(dialog, reading, L"EDIT", copy,
        ES_READONLY | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL | WS_BORDER
        | WS_TABSTOP | (state->rtl ? ES_RIGHT : ES_LEFT),
        GESHER_HOST_MESSAGE_TEXT, 16, 44, 300, 124);
    SecureZeroMemory(copy, (wcslen(copy) + 1) * sizeof(WCHAR));
    HeapFree(GetProcessHeap(), 0, copy);
    HWND accept = GesherHostControl(dialog, 0, L"BUTTON", state->positive,
        WS_TABSTOP | BS_OWNERDRAW, state->negative ? IDYES : IDOK, 230, 182, 86, 24);
    HWND deny = state->negative != NULL
        ? GesherHostControl(dialog, 0, L"BUTTON", state->negative,
            WS_TABSTOP | BS_OWNERDRAW, IDCANCEL, 134, 182, 86, 24) : NULL;
    if (!heading || !details || !accept || (state->negative && !deny)) {
      EndDialog(dialog, IDCANCEL);
      return FALSE;
    }
    state->heading_font = GesherHostHeadingFont(dialog);
    if (state->heading_font != NULL)
      SendMessageW(heading, WM_SETFONT, (WPARAM)state->heading_font, TRUE);
    SendMessageW(dialog, DM_SETDEFID, deny ? IDCANCEL : IDOK, 0);
    // A host prompt may originate on a background system-server thread. Request
    // the foreground once, but never keep the dialog permanently topmost.
    if (!SetForegroundWindow(dialog)) {
      FLASHWINFO flash = {sizeof(flash), dialog, FLASHW_TRAY, 3, 0};
      FlashWindowEx(&flash);
    }
    SetFocus(deny ? deny : accept);
    return FALSE;
  }
  if (state == NULL) return FALSE;
  if (message == WM_DESTROY) {
    if (state->heading_font != NULL) DeleteObject(state->heading_font);
    GesherHostThemeDispose(&state->theme);
    SetWindowLongPtrW(dialog, DWLP_USER, 0);
    return TRUE;
  }
  if (message == WM_CLOSE) { EndDialog(dialog, IDCANCEL); return TRUE; }
  if (message == WM_COMMAND) {
    int command = LOWORD(wparam);
    if (command == IDCANCEL || command == IDYES || command == IDOK) {
      state->accepted = command == IDYES || (command == IDOK && state->negative == NULL);
      EndDialog(dialog, command);
      return TRUE;
    }
  }
  return GesherHostThemeEvent(dialog, &state->theme, message, wparam, lparam,
                              GESHER_HOST_MESSAGE_HEADING);
}

/* A no/deny result (including Esc, X, and creation failures) is always false.
 * For informational windows, the caller ignores the return value. */
static inline BOOL GesherHostPrompt(const WCHAR* title, const WCHAR* message,
                                     const WCHAR* positive, const WCHAR* negative,
                                     BOOL rtl, HWND owner) {
  if (title == NULL || message == NULL || positive == NULL) return FALSE;
  GesherHostPromptState state = {0};
  state.title = title;
  state.message = message;
  state.positive = positive;
  state.negative = negative;
  state.rtl = rtl;
  union { DWORD align; WORD words[256]; } buffer = {0};
  DLGTEMPLATE* layout = (DLGTEMPLATE*)buffer.words;
  layout->style = WS_POPUP | WS_CAPTION | WS_SYSMENU | DS_MODALFRAME | DS_CENTER | DS_SETFONT;
  layout->dwExtendedStyle = rtl ? WS_EX_RTLREADING : 0;
  layout->cx = 332;
  layout->cy = 218;
  WORD* at = (WORD*)(layout + 1);
  *at++ = 0;
  *at++ = 0;
  SIZE_T chars = wcslen(title) + 1;
  const WCHAR font[] = L"Segoe UI";
  if ((SIZE_T)(at - buffer.words) + chars + 1 + sizeof(font) / sizeof(font[0])
      > sizeof(buffer.words) / sizeof(buffer.words[0])) return FALSE;
  for (SIZE_T i = 0; i < chars; ++i) *at++ = title[i];
  *at++ = 9;
  for (SIZE_T i = 0; i < sizeof(font) / sizeof(font[0]); ++i) *at++ = font[i];
  DialogBoxIndirectParamW(GetModuleHandleW(NULL), layout, owner, GesherHostPromptProc,
                          (LPARAM)&state);
  return state.accepted;
}
#endif /* GESHER_HOST_DIALOG_THEME_H */
