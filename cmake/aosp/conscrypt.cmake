# Conscrypt's native library, libjavacrypto (external/conscrypt's
# common/src/jni, as Android.bp's libjavacrypto builds it), over BoringSSL
# (cmake/aosp/boringssl.cmake), and Gesher's networking pieces in compat/net
# (docs/network.md, ADR 0037), linked into dalvikvm.
#
# compat/net/javacrypto.cc registers it as the built-in library
# libjavacrypto.so, which Conscrypt's NativeCryptoJni loads; its JNI_OnLoad
# first brings the system CA certificates up to date from Windows' store
# (compat/net/trust_store.cc). Conscrypt's sources are patched to take the
# POSIX side of their #ifdefs (gesher-base) rather than their own Windows
# ports, and to register their natives through System V thunks
# (patches/external/conscrypt).

set(_cs "${AOSP_DIR}/external/conscrypt")
set(_net "${CMAKE_SOURCE_DIR}/compat/net")
file(GLOB _cs_srcs "${_cs}/common/src/jni/main/cpp/conscrypt/*.cc")
list(SORT _cs_srcs)

add_library(conscrypt_jni OBJECT ${_cs_srcs}
  ${_net}/javacrypto.cc ${_net}/trust_store.cc ${_net}/fd_bio.cc)
target_include_directories(conscrypt_jni PRIVATE ${_cs}/common/src/jni/main/include
  ${_net}/include ${_net})
# STATIC_LIB: JNI_OnLoad_conscrypt instead of JNI_OnLoad; JNI_JARJAR_PREFIX:
# the platform's com.android.org.conscrypt classes, as Android.bp sets it.
target_compile_definitions(conscrypt_jni PRIVATE STATIC_LIB JNI_JARJAR_PREFIX=com/android/)
target_compile_options(conscrypt_jni PRIVATE -Wall -Wextra -Wno-unused-parameter
  -Wno-missing-field-initializers)
target_link_libraries(conscrypt_jni PUBLIC nativehelper log boringssl_ssl crypt32)

target_link_libraries(dalvikvm PRIVATE conscrypt_jni)

# Isolated native trust-store contract: the production synchronizer/JNI wrapper,
# real BoringSSL, CryptoAPI memory stores, and real Windows files/named mutexes.
# Only this observer target redirects source selection and injects explicit faults.
set(_trust_tests "${CMAKE_SOURCE_DIR}/tests/trust-store")
add_library(trust_store_observed OBJECT ${_net}/trust_store.cc)
target_compile_options(trust_store_observed PRIVATE -include "${_trust_tests}/observe.h")
target_link_libraries(trust_store_observed PRIVATE boringssl_crypto)
add_executable(trust_store_contract_test ${_trust_tests}/contracts.cc ${_trust_tests}/boundary.cc
  ${_net}/javacrypto.cc $<TARGET_OBJECTS:trust_store_observed>)
target_include_directories(trust_store_contract_test PRIVATE ${_net} ${_trust_tests}/include)
target_compile_definitions(trust_store_contract_test PRIVATE TRUST_SYNC_RETURNS_BOOL=1)
target_compile_options(trust_store_contract_test PRIVATE -Wall -Wextra -Wno-deprecated-declarations)
target_link_libraries(trust_store_contract_test PRIVATE boringssl_crypto crypt32)

# Both measurement variants inherit the same production compiler settings,
# POSIX headers/gesher_posix dependency graph and BoringSSL archive. The archive
# itself uses Gesher's errno, error-string and pthread-key adapters; linking it
# in a standalone command without those real dependencies is not equivalent.
# Keep the existing correctness executable above as its own mandatory gate.
set(_trust_baseline "a6be01164ff09de1a87382aad864a6253264b77b")
set(_trust_baseline_dir "${CMAKE_CURRENT_BINARY_DIR}/trust-source/${_trust_baseline}")
set(_trust_baseline_files "${_trust_baseline_dir}/trust_store.cc"
  "${_trust_baseline_dir}/trust_store.h" "${_trust_baseline_dir}/javacrypto.cc")
add_custom_command(OUTPUT ${_trust_baseline_files}
  COMMAND "${Python3_EXECUTABLE}" "${CMAKE_SOURCE_DIR}/tools/test_trust_store.py"
    --source-ref "${_trust_baseline}" --stage-source "${_trust_baseline_dir}"
  DEPENDS "${CMAKE_SOURCE_DIR}/tools/test_trust_store.py"
  WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}"
  COMMENT "Stage exact original trust-store sources for paired measurements"
  VERBATIM)
add_custom_target(trust_store_baseline_source DEPENDS ${_trust_baseline_files})
function(_gesher_trust_measurement variant source_dir returns_bool)
  set(observed "trust_store_${variant}_observed")
  set(executable "trust_store_${variant}_test")
  add_library(${observed} OBJECT EXCLUDE_FROM_ALL "${source_dir}/trust_store.cc")
  target_compile_options(${observed} PRIVATE -include "${_trust_tests}/observe.h")
  target_link_libraries(${observed} PRIVATE boringssl_crypto)
  add_executable(${executable} EXCLUDE_FROM_ALL
    ${_trust_tests}/contracts.cc ${_trust_tests}/boundary.cc "${source_dir}/javacrypto.cc"
    $<TARGET_OBJECTS:${observed}>)
  target_include_directories(${executable} PRIVATE "${source_dir}" ${_trust_tests}/include)
  target_compile_definitions(${executable} PRIVATE TRUST_SYNC_RETURNS_BOOL=${returns_bool})
  target_compile_options(${executable} PRIVATE -Wall -Wextra -Wno-deprecated-declarations)
  target_link_libraries(${executable} PRIVATE boringssl_crypto crypt32)
endfunction()
_gesher_trust_measurement(baseline "${_trust_baseline_dir}" 0)
_gesher_trust_measurement(candidate "${_net}" 1)
add_dependencies(trust_store_baseline_observed trust_store_baseline_source)
add_dependencies(trust_store_baseline_test trust_store_baseline_source)

# com.gesher.net.WindowsNetworks's natives (the built-in library libgesher_net.so):
# Windows' networks for the connectivity service, and the INTERNET permission's
# switch (<gesher/inet.h>), plus the libgesher_nsd.so multicast query transport.
add_library(gesher_net_natives OBJECT ${_net}/windows_networks.cc ${_net}/windows_nsd.cc
  ${_net}/windows_wifi.cc)
target_include_directories(gesher_net_natives PRIVATE ${_net}/include)
target_compile_options(gesher_net_natives PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(gesher_net_natives PUBLIC nativehelper iphlpapi ole32 oleaut32 ws2_32)
target_link_libraries(dalvikvm PRIVATE gesher_net_natives)
