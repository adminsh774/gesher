// Copyright 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
// Shared public contract: Windows runs this through the Android shim and real
// descriptor broker; the optional Linux reference runs the same assertions.
#include <poll.h>

static int socket_flags_ready(int fd) {
  struct pollfd ready = {fd, POLLIN, 0};
  return poll(&ready, 1, 3000) == 1 && (ready.revents & POLLIN) != 0;
}

static void test_socket_receive_flags(void) {
  const int carriers[] = {SOCK_STREAM, SOCK_DGRAM, SOCK_SEQPACKET};
  const int endpoints[] = {SOCK_STREAM, SOCK_DGRAM};
  int cases = 0;
  for (size_t transport = 0; transport < sizeof(carriers) / sizeof(carriers[0]); ++transport)
    for (size_t endpoint = 0; endpoint < sizeof(endpoints) / sizeof(endpoints[0]); ++endpoint)
      for (int sender = 0; sender < 2; ++sender) for (int receiver = 0; receiver < 2; ++receiver) {
        int carrier[2] = {-1, -1}, inner[2] = {-1, -1}, ordinary[2] = {-1, -1};
        check("receive flags: real carrier, socket endpoint and ordinary pipe",
              socketpair(AF_UNIX, carriers[transport], 0, carrier) == 0 &&
              socketpair(AF_UNIX, endpoints[endpoint], 0, inner) == 0 && pipe(ordinary) == 0);
        if (carrier[0] < 0 || inner[0] < 0 || ordinary[0] < 0) {
          for (int i = 0; i < 2; ++i) {
            if (carrier[i] >= 0) close(carrier[i]);
            if (inner[i] >= 0) close(inner[i]);
            if (ordinary[i] >= 0) close(ordinary[i]);
          }
          continue;
        }
        const int selected = sender ? FD_CLOEXEC : 0;
        check("receive flags: sender flags and logical socket status are explicit",
              fcntl(inner[1], F_SETFD, selected) == 0 &&
              fcntl(ordinary[0], F_SETFD, selected) == 0 &&
              fcntl(inner[1], F_SETFL, sender ? O_NONBLOCK : 0) == 0);
        const int outgoing[2] = {inner[1], ordinary[0]};
        const int sent = send_fds(carrier[0], "F", 1, outgoing, 2) == 1;
        check("receive flags: socket and ordinary descriptors enter one real SCM batch", sent);
        check("receive flags: sending preserves both source descriptor flags",
              fcntl(inner[1], F_GETFD) == selected && fcntl(ordinary[0], F_GETFD) == selected);
        close(inner[1]); close(ordinary[0]);
        int got[8]; size_t count = 0; int flags = 0; char byte = 0;
        const int ready = sent && socket_flags_ready(carrier[1]);
        check("receive flags: real SCM data arrives before the deadline", ready);
        ssize_t bytes = ready ? recv_fds_how(carrier[1], &byte, 1, got, 2, &count, &flags,
                                            receiver ? MSG_CMSG_CLOEXEC : 0) : -1;
        check("receive flags: complete mixed batch survives sender close",
              bytes == 1 && byte == 'F' && count == 2 && !(flags & MSG_CTRUNC));
        if (count == 2) {
          check("receive flags: MSG_CMSG_CLOEXEC alone selects the received socket flag",
                fcntl(got[0], F_GETFD) == (receiver ? FD_CLOEXEC : 0));
          check("receive flags: ordinary descriptor obeys the same receiver choice",
                fcntl(got[1], F_GETFD) == (receiver ? FD_CLOEXEC : 0));
          check("receive flags: socket open-description nonblocking status survives",
                !!(fcntl(got[0], F_GETFL) & O_NONBLOCK) == sender);
          struct ucred credentials = {0, 0, 0};
          socklen_t length = sizeof(credentials);
          check("receive flags: peer credentials still identify the original socketpair",
                getsockopt(got[0], SOL_SOCKET, SO_PEERCRED, &credentials, &length) == 0 &&
                length == sizeof(credentials) && credentials.pid == getpid() &&
                credentials.uid == getuid() && credentials.gid == getgid());
          check("receive flags: same socket endpoint receives after all sender copies close",
                send(inner[0], "S", 1, 0) == 1 && socket_flags_ready(got[0]) &&
                recv(got[0], &byte, 1, 0) == 1 && byte == 'S');
          check("receive flags: same socket endpoint sends in the reverse direction",
                send(got[0], "R", 1, 0) == 1 && socket_flags_ready(inner[0]) &&
                recv(inner[0], &byte, 1, 0) == 1 && byte == 'R');
          check("receive flags: mixed ordinary endpoint retains its actual pipe",
                write(ordinary[1], "P", 1) == 1 && socket_flags_ready(got[1]) &&
                read(got[1], &byte, 1) == 1 && byte == 'P');
        }
        for (size_t i = 0; i < count; ++i) close(got[i]);
        close(inner[0]); close(ordinary[1]); close(carrier[0]); close(carrier[1]);
        ++cases;
      }
  check("receive flags: all 24 transport, endpoint and sender/receiver combinations ran", cases == 24);
}
