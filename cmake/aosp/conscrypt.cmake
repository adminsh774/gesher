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

# com.gesher.net.WindowsNetworks's natives (the built-in library libgesher_net.so):
# Windows' networks for the connectivity service, and the INTERNET permission's
# switch (<gesher/inet.h>), plus the libgesher_nsd.so multicast query transport.
add_library(gesher_net_natives OBJECT ${_net}/windows_networks.cc ${_net}/windows_nsd.cc
  ${_net}/windows_wifi.cc)
target_include_directories(gesher_net_natives PRIVATE ${_net}/include)
target_compile_options(gesher_net_natives PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(gesher_net_natives PUBLIC nativehelper iphlpapi ole32 oleaut32 ws2_32)
target_link_libraries(dalvikvm PRIVATE gesher_net_natives)
