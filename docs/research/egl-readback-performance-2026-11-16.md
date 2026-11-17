# EGL child readback: direct destination and bounded fallback

Date: 2026-11-16. This patch targets the GPU-to-CPU path used when Android
apps render GLES into a SurfaceView, SurfaceTexture or ImageReader without a
native HWND. It also reuses the per-frame scratch of translucent HWUI popups.
No code is copied from the closed-source Microsoft WSA graphics runtime.

## Before and after

Previously the EGL child surface did glReadPixels of the whole source frame
into an RGBA scratch array; only afterwards did it lock the native window
and vertically flip/copy or convert into the consumer buffer. At 1920 x 1080,
the staging image is 8,294,400 bytes. An additional full-frame CPU copy moves
497,664,000 bytes/s at 60 fps. These are analytical byte counts, not timings.

Now the child:

1. Locks the destination before a synchronous GPU read. If that fails, it
   avoids the unnecessary read altogether.
2. Calculates the clipped top-left intersection; GL readback starts at the
   bottom-left Y coordinate (source height minus clipped height).
3. When GL_ANGLE_pack_reverse_row_order is supported, writes RGBA_8888
   directly to the destination with top-down rows. GLES3 pack row length
   supports padded native buffers.
4. When GL_EXT_read_format_bgra is also available, BGRA_8888 likewise
   reads directly with correct channel order.
5. Otherwise reuses a clipped staging buffer and the prior color
   conversions for RGB565, RGBX (forced alpha), and unknown formats.
6. Saves/restores GL framebuffer and pack state, including PBOs, row
   length, skips, alignment and the reverse-row flag, before callbacks.
7. Keeps the child and its native window alive during in-flight swaps
   while EGL surface teardown retires them.

The RGBA staging and converted ARGB scratch for translucent layered
popups now persist per popup rather than being allocated every present.
They are freed with the popup. This does not remove popup readback or
conversion.

## Driver compatibility and diagnostics

ANGLE already implements the reverse-row packing extension
(https://chromium.googlesource.com/angle/angle/+/refs/heads/main/extensions/ANGLE_pack_reverse_row_order.txt).
Hardware Intel UHD/Iris Xe/Arc and software D3D11 WARP can use the
extension-gated route when the current context advertises support; otherwise
Gesher keeps the previous staged conversion. The patch requires no new
libraries, vendor driver assumptions, or proprietary Microsoft WSA code.

Set GESHER_EGL_DIRECT_READBACK=0 in the launched app environment to
force the staging fallback and compare the same workloads. It has no
effect on a regular top-level Win32/ANGLE window swap chain.

## Verification and limitations

Run: python3 tools/test_egl_readback_layout.py --sanitize.

The host test compiles the production layout/conversion code and checks
12,000 deterministic cases against an independent full-frame oracle:
cropping, stride padding, untouched guard bytes, RGBA/BGRA/RGBX/RGB565,
GLES2/GLES3, extension presence, alpha and color conversion, invalid
dimensions. Sanitizers test the same operations, not physical GPU behavior.

The Windows graphics suite continues to run GlBindingsTest and
ImageReaderEglTest against real ART/ANGLE, including a GLES3 ImageReader
case that checks indexed extension-query compatibility and unchanged GL
error/pack state. It also reruns ImageReaderEglTest with the optimized path
forced off. The ImageReader
fixture deliberately leaves nondefault GL_PACK_ALIGNMENT and ANGLE
reverse-row pack state active across swaps and, when GLES2 advertises a
pixel pack buffer extension, binds a PBO. It checks that pixels remain
correct, every state value is restored and no GLES error is introduced.
Both the optimized and forced-staging paths must pass.
Full Windows CI and physical Intel GPU performance tests are still required.

For performance acceptance, alternate default and forced staging runs
on Intel iGPU hardware and a discrete GPU with the same APK, resolution
and warmed workloads. Record driver, extensions, time to first frame,
frame p50/p95, CPU/GPU utilization, stutter, and working-set memory.
Exercise resize, frame queue overflow, surface close and detach.

The GPU-to-CPU transfer and its possible synchronization stall remain.
Completely eliminating it requires a larger GPU-backed consumer path
with correct Android ImageReader and SurfaceTexture semantics. This PR
does not alter ART, JNI, regular HWND swap chains or publication order.
It is independent of the separate automatic HWUI GPU-selection PR #55.


### GLES2 vs GLES3 extension discovery

The implementation now uses the GLES2 legacy extension string only in
GLES2 contexts. GLES3 uses GL_NUM_EXTENSIONS and glGetStringi, through
an optional ANGLE export. If unavailable, optimization is disabled for
that context and the staged path remains valid. This avoids depending
on legacy extension-string behavior for GLES3 and is covered by bounded
indexed-list and state tests in the host oracle.
