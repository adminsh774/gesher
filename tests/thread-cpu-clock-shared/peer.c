/* A separately compiled image; every pthread and clock symbol imports the
   same provider DLL. No Gesher header forwarding or private clock API. */
#include "peer.h"
#include <windows.h>

__declspec(dllexport) const struct clock_peer_api* clock_peer_v1(void) {
  static const struct clock_peer_api api = {
      sizeof(api), pthread_self, pthread_create, pthread_join,
      pthread_getcpuclockid, clock_gettime, clock_getres};
  return &api;
}
