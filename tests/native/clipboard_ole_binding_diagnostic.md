# Isolated private clipboard publication binding

This is an isolated native diagnostic. It does not update PR 213 or implement a
production clipboard backend. Every existing leaf file, including the API-only
OLE probe, the rejected Win32 writer and its fatal regressions, stays identical
to parent 0c62948de98daf24b5acb8ee0626e511bb9c7903. This diagnostic ancestry must
not be merged into a product PR.

The executor recovered during preparation. The new component was compiled with
the existing pinned LLVM/MinGW using warnings as errors; that is cross-compilation,
not Windows execution. Both actual Windows outcomes, including every primary
failure and all source/artifact receipts, must be captured before another update.

## Requested behavior and demonstrated failures

An Android write must preserve its original typed ClipData and source while its
own native publication remains valid. A real native replacement must not return
stale Android data or the old Android source. Ordinary reads must not destroy the
valid write, and normal shutdown must leave real native data available.

The exact 107 writer samples a sequence before CloseClipboard adds formats. The
rejected public-HWND/GUID design admits different content from a foreign process
that borrows the HWND and copies public data. The API-only OLE diagnostic proves
that full replacement changes the original IDataObject identity, but in-place
text, HTML and preferred-image changes can retain S_OK. All these prior failures
remain independently observable and fatal in their unchanged fixture binaries.

## Candidate binding boundary

The candidate prepares immutable text, HTML and image buffers before publication,
including actual zero-initialized HGLOBAL extents established from its own
allocation. It never learns an expected buffer from the current clipboard.

A completion receipt requires all of the following:

- The exact original IDataObject is current before and after the native read.
- The complete preferred text/HTML/image buffers and format presence agree with
  that object's private expected buffers. A preferred CF_DIBV5 must not be
  replaced by an apparently matching fallback DIB.
- The real nonzero sequence is unchanged before the read, while open, and after
  the actual CloseClipboard call.

This is a completion proof for this write only. The Android consumer must still
import every later sequence change as unknown/external, without trying to revive
a prior typed clip from public payload equality. The native private identity is
a fixture canary; no Android URI, Intent, permission, foreground or ART execution
is claimed by this standalone probe.

The candidate keeps a legitimate empty CF_UNICODETEXT clip distinct from a real
clear, which must leave zero formats. Its private queue serializes plain, HTML
and rich publications on one pumped STA. Requests use owned in-process events
and queue entries, not raw pointers accepted through public window messages.
The parent STA pumps while the actual callers run. The diagnostic process keeps
the existing 180-second external watchdog; a timeout is fatal, never a successful
publication or a claim of resource retirement.

## Actual cases and negative controls

The new executable has 47 cases. It includes all original replacement shapes,
complete available-format replay by actual child processes, in-place changes,
full preferred-image header/alpha/color-intent/second-pixel changes, invalid
preferred-format precedence, and replay with the preferred V5 omitted. A genuine
read-only child copies every available native format and must preserve both the
full buffers and the original sequence. Child PID, creation time, native API
results, copied-format completeness and actual readback remain required.

Six concurrent callers perform 48 plain/HTML/rich operations through the private
STA queue. Every completion must have its own private identity and nonzero
observed sequence. Object/enumerator/request retirement and normal flush/uninit
are checked; the complete final buffers must remain pasteable after shutdown.
No actual Win32 failure or original fatal assertion is converted into success.

The separate byte-identical API-only executable must still reproduce its four
specific in-place failures (cases 9, 10, 13, 14; 16 cases and 391 checks). It is an
explicit negative control. The original 107 close-transition fixture and rejected
Win32 replay/concurrency fixture also remain unchanged. The rejected fixture's
fatal failures can keep the overall workflow red even if the new binding passes;
this run is evidence gathering, not product readiness.

## Image representation and compatibility limits

The original production writer exports CF_DIB while the production reader prefers
CF_DIBV5. Comparing one pixel or a different source format cannot bind the complete
actual preferred image. This candidate supplies an explicit packed V5 projection
alongside the original DIB, with no image decoder, color transformation or profile
loader. It preserves input pixel streams, palettes, masks, resolution and existing
V4/V5 metadata within the parsed layout.

The image matrix extracts the exact 107 WriteRichClipboard and ReadClipboardImage
bodies plus their original helpers and BMP header. Their source and generated
header are pinned by SHA256 and included in the producer artifact. All operations
use actual Win32 APIs. For 22 CORE/INFO/V2/V3/V4/V5, palette, mask, top-down, alpha,
embedded-profile-byte and RLE inputs, the run records the full input and complete
old/new BMP buffers returned by the original reader.

A new binding pass is not a compatibility verdict for different BMP bytes.
Every difference is counted and retained for independent interpretation of
dimensions, stride/padding, masks, alpha, color/profile metadata and offsets.
The embedded-profile fixture measures bounded metadata preservation; it does not
claim a valid color-engine interpretation. Inputs not represented in this matrix
remain an explicit coverage limit. The diagnostic parser's recognized-header
list is not approved as a blanket production acceptance restriction.

Eleven malformed/overflow/truncated/profile-range inputs must return an explicit
conversion failure without fabricated image bytes or any clipboard mutation.
A future production repair must preserve all currently working inputs or expose
a real conversion error; it may not silently reinterpret or discard metadata.

## Provenance and remaining product gates

The workflow uses the digest-pinned 20261006 compiler, an exact current checkout,
the current producer/run/attempt and the existing artifact-receipt verifier.
Consumer jobs verify the archive and every staged source/binary/receipt digest.
All source hashes and the complete old/new observations are saved.

This does not replace the actual current-product Android ClipData/source,
permission/focus/session, listener/reentry, image ownership and shutdown tests.
Source/integration QA and full current-head CI remain required before product
publication or merge.

## Primary API references

- [OleIsCurrentClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleiscurrentclipboard)
- [OleSetClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-olesetclipboard)
- [OleFlushClipboard](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleflushclipboard)
- [OleInitialize](https://learn.microsoft.com/en-us/windows/win32/api/ole2/nf-ole2-oleinitialize)
- [IDataObject::GetData](https://learn.microsoft.com/en-us/windows/win32/api/objidl/nf-objidl-idataobject-getdata)
- [BITMAPV5HEADER](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-bitmapv5header)
