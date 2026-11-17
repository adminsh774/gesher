// Gesher: lossless CPU readback layout for GLES child surfaces.
// Intentionally independent of GLES/Windows headers so the production geometry
// and color conversion can be checked against an independent pixel oracle.
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace gesher::bionic::gl {

constexpr int kRgba8888 = 1;
constexpr int kRgbx8888 = 2;
constexpr int kRgb565 = 4;
constexpr int kBgra8888 = 5;

enum class DirectReadFormat { kNone, kRgba, kBgra };

// Respect whole extension tokens, never names with shared prefixes.
inline bool HasGlExtension(const char* list, const char* name) {
  if (list == nullptr || name == nullptr || *name == '\0') return false;
  const size_t length = std::strlen(name);
  for (const char* p = list; (p = std::strstr(p, name)) != nullptr; p += length) {
    if ((p == list || p[-1] == ' ') && (p[length] == ' ' || p[length] == '\0'))
      return true;
  }
  return false;
}

// Only the extension names relevant to the readback path are retained.
// GLES2 exposes a space-separated list; GLES3 uses indexed glGetStringi,
// so never depend on the legacy extension string for an ES3 context.
struct ReadbackExtensions {
  bool reverse_rows = false;
  bool bgra_read = false;
  bool es2_pack_buffer = false;
  bool es2_pack_subimage = false;
};

inline void RecordReadbackExtension(ReadbackExtensions* result, const char* name) {
  if (name == nullptr) return;
  if (std::strcmp(name, "GL_ANGLE_pack_reverse_row_order") == 0) result->reverse_rows = true;
  if (std::strcmp(name, "GL_EXT_read_format_bgra") == 0) result->bgra_read = true;
  if (std::strcmp(name, "GL_NV_pixel_buffer_object") == 0 ||
      std::strcmp(name, "GL_EXT_pixel_buffer_object") == 0) result->es2_pack_buffer = true;
  if (std::strcmp(name, "GL_NV_pack_subimage") == 0) result->es2_pack_subimage = true;
}

template <typename Legacy, typename Count, typename Indexed>
inline ReadbackExtensions QueryReadbackExtensions(int client_version, Legacy legacy,
                                                  Count count, Indexed indexed) {
  ReadbackExtensions result;
  if (client_version < 2) return result;
  if (client_version < 3) {
    const char* extensions = legacy();
    result.reverse_rows = HasGlExtension(extensions, "GL_ANGLE_pack_reverse_row_order");
    result.bgra_read = HasGlExtension(extensions, "GL_EXT_read_format_bgra");
    result.es2_pack_buffer = HasGlExtension(extensions, "GL_NV_pixel_buffer_object") ||
                             HasGlExtension(extensions, "GL_EXT_pixel_buffer_object");
    result.es2_pack_subimage = HasGlExtension(extensions, "GL_NV_pack_subimage");
    return result;
  }
  // Bound an invalid driver-reported count rather than looping indefinitely.
  const int size = count();
  if (size < 0 || size > 16384) return result;
  for (int i = 0; i < size; ++i) RecordReadbackExtension(&result, indexed(i));
  return result;
}

struct ReadbackLayout {
  int width = 0;             // Clipped source/target intersection.
  int height = 0;
  int source_y = 0;          // GL lower-left origin: top of source after reversal.
  int target_stride = 0;     // In pixels, not bytes.
  int target_format = 0;
  std::size_t pixels = 0;
  DirectReadFormat direct = DirectReadFormat::kNone;

  bool valid() const { return width > 0 && height > 0; }
};

inline ReadbackLayout PlanChildReadback(int source_width, int source_height,
                                       int target_width, int target_height,
                                       int target_stride, int target_format,
                                       bool reverse_rows_supported,
                                       bool bgra_read_supported, bool es3_row_length) {
  ReadbackLayout result;
  if (source_width <= 0 || source_height <= 0 || target_width <= 0 || target_height <= 0 ||
      target_stride < target_width) {
    return result;
  }
  const int width = std::min(source_width, target_width);
  const int height = std::min(source_height, target_height);
  const auto max_pixels = std::numeric_limits<std::size_t>::max() / sizeof(uint32_t);
  if (static_cast<std::size_t>(width) > max_pixels / static_cast<std::size_t>(height) ||
      static_cast<std::size_t>(target_stride) >
          max_pixels / static_cast<std::size_t>(target_height)) {
    return result;
  }
  result.width = width;
  result.height = height;
  result.source_y = source_height - height;
  result.target_stride = target_stride;
  result.target_format = target_format;
  result.pixels = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
  // GLES2 cannot describe padded rows; GLES3 can set GL_PACK_ROW_LENGTH.
  if (reverse_rows_supported && (target_stride == width || es3_row_length)) {
    if (target_format == kRgba8888) result.direct = DirectReadFormat::kRgba;
    if (target_format == kBgra8888 && bgra_read_supported)
      result.direct = DirectReadFormat::kBgra;
  }
  // RGBX needs opaque alpha, RGB565 needs quantization, and unknown formats
  // preserve the old RGBA interpretation; all require conversion/copyback.
  return result;
}

// The fallback read uses GL_RGBA / GL_UNSIGNED_BYTE with a tightly packed,
// bottom-up rectangle of layout.width x layout.height. Preserve padding and
// write only the clipped top-left rectangle, matching the old full readback.
inline void CopyChildReadback(const uint32_t* bottom_up, void* target,
                              const ReadbackLayout& layout) {
  if (!layout.valid() || bottom_up == nullptr || target == nullptr) return;
  auto* out = static_cast<uint8_t*>(target);
  const int bytes_per_pixel = layout.target_format == kRgb565 ? 2 : 4;
  for (int y = 0; y < layout.height; ++y) {
    const auto* src = bottom_up + static_cast<std::size_t>(layout.height - 1 - y) * layout.width;
    auto* dst = out + static_cast<std::size_t>(y) * layout.target_stride * bytes_per_pixel;
    if (layout.target_format == kBgra8888) {
      auto* d = reinterpret_cast<uint32_t*>(dst);
      for (int x = 0; x < layout.width; ++x) {
        const uint32_t p = src[x];
        d[x] = (p & 0xff00ff00u) | ((p & 0xffu) << 16) | ((p >> 16) & 0xffu);
      }
    } else if (layout.target_format == kRgb565) {
      auto* d = reinterpret_cast<uint16_t*>(dst);
      for (int x = 0; x < layout.width; ++x) {
        const uint32_t p = src[x];
        const uint32_t r = p & 0xffu, g = (p >> 8) & 0xffu, b = (p >> 16) & 0xffu;
        d[x] = static_cast<uint16_t>(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
      }
    } else if (layout.target_format == kRgbx8888) {
      auto* d = reinterpret_cast<uint32_t*>(dst);
      for (int x = 0; x < layout.width; ++x) d[x] = src[x] | 0xff000000u;
    } else {
      std::memcpy(dst, src, static_cast<std::size_t>(layout.width) * 4);
    }
  }
}

}  // namespace gesher::bionic::gl
