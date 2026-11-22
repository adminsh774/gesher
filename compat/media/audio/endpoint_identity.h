// Persistent identity of the actual MMDevice bound to a WASAPI output client.
#pragma once

#include <mmdeviceapi.h>
#include <objbase.h>

#include <memory>
#include <string>

namespace gesher::media {

inline HRESULT ReadEndpointId(IMMDevice* device, std::wstring* id) {
  if (device == nullptr || id == nullptr) return E_POINTER;
  LPWSTR value = nullptr;
  const HRESULT result = device->GetId(&value);
  std::unique_ptr<wchar_t, decltype(&CoTaskMemFree)> owner(value, CoTaskMemFree);
  if (FAILED(result)) return result;
  if (value == nullptr || *value == 0) return E_UNEXPECTED;
  *id = value;
  return S_OK;
}

}  // namespace gesher::media
