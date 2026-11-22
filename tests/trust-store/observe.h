// Forced into the production translation unit only. Every operation delegates
// to real APIs on Windows, except system-store selection: test certificates live
// in isolated memory stores loaded from this fixture's directory, never ROOT.
#pragma once
#include <windows.h>
#include <wincrypt.h>
#undef X509_NAME
#undef X509_EXTENSIONS
#undef X509_CERT_PAIR
#undef PKCS7_ISSUER_AND_SERIAL
#undef PKCS7_SIGNER_INFO
#undef OCSP_REQUEST
#undef OCSP_RESPONSE
#include <openssl/pem.h>
#include <openssl/x509.h>
HCERTSTORE WINAPI TrustTestOpenStore(LPCSTR, DWORD, HCRYPTPROV_LEGACY, DWORD, const void*);
PCCERT_CONTEXT WINAPI TrustTestEnum(HCERTSTORE, PCCERT_CONTEXT);
BOOL WINAPI TrustTestUsage(PCCERT_CONTEXT, DWORD, PCERT_ENHKEY_USAGE, DWORD*);
DWORD WINAPI TrustTestWait(HANDLE, DWORD);
BOOL WINAPI TrustTestWrite(HANDLE, LPCVOID, DWORD, LPDWORD, LPOVERLAPPED);
BOOL WINAPI TrustTestRename(LPCWSTR, LPCWSTR, DWORD);
BOOL WINAPI TrustTestDelete(LPCWSTR);
DWORD WINAPI TrustTestModule(HANDLE, wchar_t*, DWORD);
HANDLE WINAPI TrustTestFile(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
BOOL WINAPI TrustTestRead(HANDLE, LPVOID, DWORD, LPDWORD, LPOVERLAPPED);
X509* TrustTestDecode(X509**, const uint8_t**, long);
int TrustTestEncode(BIO*, X509*);
X509* TrustTestReadPem(BIO*, X509**, pem_password_cb*, void*);
#define CertOpenStore TrustTestOpenStore
#define CertEnumCertificatesInStore TrustTestEnum
#define CertGetEnhancedKeyUsage TrustTestUsage
#define WaitForSingleObject TrustTestWait
#define WriteFile TrustTestWrite
#define MoveFileExW TrustTestRename
#define DeleteFileW TrustTestDelete
#define GetModuleFileNameW TrustTestModule
#define CreateFileW TrustTestFile
#define ReadFile TrustTestRead
#define d2i_X509 TrustTestDecode
#define PEM_write_bio_X509 TrustTestEncode
#define PEM_read_bio_X509 TrustTestReadPem
