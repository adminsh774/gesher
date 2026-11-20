# Isolated clipboard OLE identity measurement

This diagnostic is not a product repair. It adds an independent native API probe;
every original production file and existing clipboard regression remains identical
to parent 0cbdafa973064dc715a2c30ccbbf23b0564797a8. The diagnostic branch must not
be merged into a product PR.

The local execution environment was unavailable during preparation. No local
compilation or Windows execution is claimed. The pinned compiler job is the
first executable gate; both Windows matrix outcomes must be read before another
diagnostic update.

## Question and boundary

The existing original writer samples a sequence before CloseClipboard adds
formats. The rejected owner/public-GUID reconciliation accepts a different
foreign payload when a real process borrows the original HWND and copies the
GUID. Both mechanisms have already failed on Windows 2022 and 2025.

This probe measures whether the exact IDataObject passed to OleSetClipboard
provides a usable publication identity. No HWND exclusion, sequence stability
or OLE identity behavior is assumed. S_OK and S_FALSE are distinguished exactly.

The immutable data object has text, HTML, a real two-pixel DIB and a full public
GUID format. Its private identity is a native fixture canary for data absent
from that projection. It is not an execution of Android ClipData, URI or Intent
handling. A future production design would still require the existing ART
source/typed-data/notification/permission/lifecycle gates.

## Actual controls

- A pumped STA publishes and reads its own plain, HTML, rich and empty text data.
  Explicit clear is separate and must leave zero formats.
- Separate child processes perform normal and borrowed-OLE-HWND writes.
- Every enumerated clipboard format is read and duplicated for replay; failure
  to copy a format is reported as an incomplete control, never called a pass.
- EmptyClipboard replacements include exact same projection, changed text,
  changed HTML and changed preferred DIBV5/DIB pixels.
- In-place SetClipboardData changes text, HTML or preferred image without
  EmptyClipboard. A same-text in-place write is recorded separately and is not
  rejected solely because the writer is external.
- Different observable content or a full replacement must not retain the old
  private publication identity. Failed Win32 setup remains a fatal failure.
- GetData allocates actual HGLOBAL media with consumer-owned release. OLE object
  and enumerator lifetimes, child identity/creation time and owned resources
  are observed. Normal OleFlushClipboard/OleUninitialize must leave real native
  data readable and release the private object.

The unchanged old writer negative and rejected writer race/resource fixture
also run. Their fatal assertions remain fatal. The overall diagnostic may
therefore remain red even if an individual OLE observation is promising.
That is evidence gathering, not product readiness.

## Primary API references

- [OleIsCurrentClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleiscurrentclipboard)
  specifies the original data-object pointer, not one obtained by a reader.
- [OleSetClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-olesetclipboard)
  describes ownership, delayed rendering and the data-object reference.
- [OleFlushClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleflushclipboard)
  describes rendering/releasing data for shutdown persistence.
- [OleInitialize](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleinitialize)
  establishes OLE's STA requirement.
- [IDataObject::GetData](https://learn.microsoft.com/en-us/windows/win32/api/objidl/nf-objidl-idataobject-getdata)
  defines format validation and medium ownership.
- [OleDuplicateData](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleduplicatedata)
  duplicates actual clipboard data, including its documented GDI special cases.
