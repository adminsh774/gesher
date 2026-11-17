// Source/target differential tests for the real production readback geometry.
// No GPU assumptions: simulated GL reads are checked against a separate
// old-pipeline oracle (full-frame read, then vertical flip + PutRow).
#include "../../compat/bionic/egl_readback_layout.h"

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <random>
#include <vector>

using gesher::bionic::gl::DirectReadFormat;
using gesher::bionic::gl::PlanChildReadback;
using gesher::bionic::gl::CopyChildReadback;

[[noreturn]] void Fail(const char* msg) { std::fprintf(stderr, "FAIL: %s\n", msg); std::exit(1); }
void Check(bool ok, const char* msg) { if (!ok) Fail(msg); }

// Logical pixels are stored top-down in 'frame'. The output is the previously
// implemented conversion, independent of the new production helper.
void OldOracle(const std::vector<uint32_t>& frame, int source_w, int source_h,
               std::vector<uint8_t>& target, int target_w, int target_h,
               int stride, int format) {
  const int rows = std::min(source_h, target_h);
  const int cols = std::min(source_w, target_w);
  const int bpp = format == 4 ? 2 : 4;
  for (int y = 0; y < rows; ++y) {
    auto* out = target.data() + static_cast<std::size_t>(y) * stride * bpp;
    for (int x = 0; x < cols; ++x) {
      uint32_t p = frame[static_cast<std::size_t>(y) * source_w + x];
      if (format == 4) {
        uint32_t r = p & 255u, g = (p >> 8) & 255u, b = (p >> 16) & 255u;
        uint16_t v = static_cast<uint16_t>(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
        std::memcpy(out + x * 2, &v, 2);
      } else {
        if (format == 5) {
          p = (p & 0xff00ff00u) | ((p & 255u) << 16) | ((p >> 16) & 255u);
        } else if (format == 2) {
          p |= 0xff000000u;
        }
        std::memcpy(out + x * 4, &p, 4);
      }
    }
  }
}

void TestOne(std::mt19937& rng, int sw, int sh, int tw, int th, int stride,
             int fmt, bool reverse, bool bgra, bool es3) {
  auto plan = PlanChildReadback(sw, sh, tw, th, stride, fmt, reverse, bgra, es3);
  Check(plan.valid(), "valid geometry");
  int bpp = fmt == 4 ? 2 : 4;
  std::vector<uint32_t> frame(static_cast<std::size_t>(sw) * sh);
  for (auto& p : frame) p = rng();
  const size_t bytes = static_cast<size_t>(stride) * th * bpp;
  std::vector<uint8_t> actual(bytes + 64, 0xa5), expected = actual;
  OldOracle(frame, sw, sh, expected, tw, th, stride, fmt);
  if (plan.direct != DirectReadFormat::kNone) {
    // Mock GL_PACK_REVERSE_ROW_ORDER_ANGLE and GL_PACK_ROW_LENGTH. GL writes
    // RGBA or BGRA bytes directly into the consumer's locked buffer.
    for (int y = 0; y < plan.height; ++y) {
      for (int x = 0; x < plan.width; ++x) {
        uint32_t p = frame[static_cast<size_t>(y) * sw + x];
        if (plan.direct == DirectReadFormat::kBgra)
          p = (p & 0xff00ff00u) | ((p & 255u) << 16) | ((p >> 16) & 255u);
        std::memcpy(actual.data() + (static_cast<size_t>(y) * stride + x) * 4, &p, 4);
      }
    }
  } else {
    // GL read rectangle starts at source_h - clipped_height and is returned
    // bottom-up, never reading unrelated rows or columns.
    std::vector<uint32_t> read(plan.pixels);
    for (int y = 0; y < plan.height; ++y) {
      for (int x = 0; x < plan.width; ++x) {
        read[static_cast<size_t>(y) * plan.width + x] =
            frame[static_cast<size_t>(plan.height - 1 - y) * sw + x];
      }
    }
    CopyChildReadback(read.data(), actual.data(), plan);
  }
  if (actual != expected) Fail("layout differs from full-frame reference or modifies padding");
  Check(plan.source_y == sh - plan.height, "GL read origin retains top of source");
  if (!reverse || (fmt != 1 && fmt != 5) ||
      (fmt == 5 && !bgra) || (!es3 && stride != plan.width))
    Check(plan.direct == DirectReadFormat::kNone, "safe direct eligibility");
}
int main() {
  using namespace gesher::bionic::gl;
  Check(HasGlExtension("GL_ANGLE_pack_reverse_row_order GL_OTHER",
                       "GL_ANGLE_pack_reverse_row_order"), "exact extension token");
  Check(HasGlExtension("GL_A GL_EXT_read_format_bgra GL_Z",
                       "GL_EXT_read_format_bgra"), "middle token");
  Check(!HasGlExtension("GL_EXT_read_format_bgra_FAKE",
                        "GL_EXT_read_format_bgra"), "not a prefix");
  Check(!HasGlExtension("MYGL_EXT_read_format_bgra",
                        "GL_EXT_read_format_bgra"), "not a suffix");
  Check(!HasGlExtension(nullptr, "GL_A"), "null extension list");
  Check(!HasGlExtension("GL_A", ""), "empty name");
  // Ensure ES3 indexed extension queries are used instead of a potentially
  // unavailable legacy extension string; the two paths never mix.
  int legacy = 0, counts = 0, indexed = 0;
  auto es3 = QueryReadbackExtensions(3,
      [&]() { ++legacy; return "GL_A"; },
      [&]() { ++counts; return 4; },
      [&](int i) -> const char* {
        ++indexed;
        static const char* names[] = {"GL_EXT_foo", "GL_EXT_read_format_bgra",
            "GL_NV_pixel_buffer_object", "GL_ANGLE_pack_reverse_row_order"};
        return names[i];
      });
  Check(legacy == 0 && counts == 1 && indexed == 4, "ES3 uses indexed extensions only");
  Check(es3.reverse_rows && es3.bgra_read && es3.es2_pack_buffer, "ES3 indexed capabilities");
  auto es2 = QueryReadbackExtensions(2,
      [&]() { ++legacy; return "GL_ANGLE_pack_reverse_row_order GL_EXT_read_format_bgra"; },
      [&]() { ++counts; return 1; },
      [&](int) -> const char* { ++indexed; return "GL_NV_pixel_buffer_object"; });
  Check(legacy == 1 && counts == 1 && indexed == 4, "ES2 uses legacy list only");
  Check(es2.reverse_rows && es2.bgra_read && !es2.es2_pack_buffer, "ES2 capabilities");
  auto pack_layout = QueryReadbackExtensions(2,
      []() { return "GL_NV_pack_subimage GL_NV_pixel_buffer_object"; },
      []() { return 0; }, [](int) -> const char* { return nullptr; });
  Check(pack_layout.es2_pack_subimage && pack_layout.es2_pack_buffer,
        "ES2 pack layout and PBO extensions recognized");
  auto similar_pack_layout = QueryReadbackExtensions(2,
      []() { return "GL_NV_pack_subimage_extra PREFIX_GL_NV_pack_subimage"; },
      []() { return 0; }, [](int) -> const char* { return nullptr; });
  Check(!similar_pack_layout.es2_pack_subimage, "ES2 pack layout requires exact extension token");
  ReadbackExtensions indexed_pack_layout;
  RecordReadbackExtension(&indexed_pack_layout, "GL_NV_pack_subimage");
  Check(indexed_pack_layout.es2_pack_subimage, "indexed pack layout extension recognized");
  auto malformed = QueryReadbackExtensions(3,
      [&]() { ++legacy; return "GL_ANGLE_pack_reverse_row_order"; },
      [&]() { ++counts; return 65536; },
      [&](int) -> const char* { ++indexed; return "GL_ANGLE_pack_reverse_row_order"; });
  Check(!malformed.reverse_rows && indexed == 4, "invalid extension count bounded");
  auto unknown = QueryReadbackExtensions(0,
      [&]() { ++legacy; return "GL_ANGLE_pack_reverse_row_order"; },
      [&]() { ++counts; return 1; },
      [&](int) -> const char* { ++indexed; return "GL_EXT_read_format_bgra"; });
  Check(!unknown.reverse_rows && legacy == 1 && counts == 2 && indexed == 4,
        "unknown GL version avoids invalid extension queries");
  std::mt19937 rng(0x61cafe42);
  std::array<int, 5> fmts = {1, 2, 4, 5, 99};
  int cases = 0;
  for (int iter = 0; iter < 12000; ++iter) {
    int sw = static_cast<int>(rng() % 39 + 1);
    int sh = static_cast<int>(rng() % 31 + 1);
    int tw = static_cast<int>(rng() % 41 + 1);
    int th = static_cast<int>(rng() % 35 + 1);
    int stride = tw + static_cast<int>(rng() % 8);
    int format = fmts[rng() % fmts.size()];
    bool reverse = (rng() & 1) != 0;
    bool bgra = (rng() & 1) != 0;
    bool es3 = (rng() & 1) != 0;
    TestOne(rng, sw, sh, tw, th, stride, format, reverse, bgra, es3);
    ++cases;
  }
  Check(!PlanChildReadback(100, 100, 10, 10, 9, 1, true, true, true).valid(), "short stride");
  Check(!PlanChildReadback(100, 100, 10, 0, 10, 1, true, true, true).valid(), "zero height");
  Check(!PlanChildReadback(-1, 100, 10, 10, 10, 1, true, true, true).valid(), "negative source");
  std::printf("readback layout: %d full-frame oracle comparisons passed\n", cases);
}
