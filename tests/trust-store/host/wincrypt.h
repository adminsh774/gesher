#pragma once
#include <windows.h>
using HCERTSTORE = void*;
struct CRYPT_DATA_BLOB { DWORD cbData; BYTE* pbData; };
struct CERT_INFO { CRYPT_DATA_BLOB Subject, Issuer; };
struct CERT_CONTEXT { DWORD dwCertEncodingType; BYTE* pbCertEncoded; DWORD cbCertEncoded; CERT_INFO* pCertInfo; };
using PCCERT_CONTEXT = const CERT_CONTEXT*;
struct CERT_ENHKEY_USAGE { DWORD cUsageIdentifier; char** rgpszUsageIdentifier; };
using PCERT_ENHKEY_USAGE = CERT_ENHKEY_USAGE*;
#define CERT_STORE_PROV_SYSTEM_W reinterpret_cast<LPCSTR>(10)
constexpr DWORD CERT_SYSTEM_STORE_CURRENT_USER = 0x10000, CERT_SYSTEM_STORE_LOCAL_MACHINE = 0x20000,
                CERT_STORE_OPEN_EXISTING_FLAG = 0x4000, CERT_STORE_READONLY_FLAG = 0x8000,
                CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG = 4, X509_ASN_ENCODING = 1;
constexpr DWORD CRYPT_E_NOT_FOUND = 0x80092004;
inline constexpr const char* szOID_PKIX_KP_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
HCERTSTORE CertOpenStore(LPCSTR, DWORD, HCRYPTPROV_LEGACY, DWORD, const void*);
PCCERT_CONTEXT CertEnumCertificatesInStore(HCERTSTORE, PCCERT_CONTEXT);
BOOL CertGetEnhancedKeyUsage(PCCERT_CONTEXT, DWORD, PCERT_ENHKEY_USAGE, DWORD*);
BOOL CertCompareCertificateName(DWORD, const CRYPT_DATA_BLOB*, const CRYPT_DATA_BLOB*);
BOOL CertFreeCertificateContext(PCCERT_CONTEXT);
BOOL CertCloseStore(HCERTSTORE, DWORD);
