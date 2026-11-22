#!/usr/bin/env python3
# Copyright 2026 The Gesher Authors
# SPDX-License-Identifier: Apache-2.0
"""Run exact socket receive/registration/registry bodies on declared OS boundaries.

Real Linux socketpair endpoints and fcntl flags back the Win32/CRT/Winsock
adapter. This does not execute Windows, its descriptor broker, or process
startup. unix_rights_test.so separately exercises the public SCM path there.
--source-ref compiles immutable source, including its complete socket header.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def function(source, marker):
    if source.count(marker) != 1:
        raise ValueError('ambiguous production function: ' + marker)
    begin = source.index(marker)
    end = source.index('{', begin) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[begin:end]


def section(source, first, last):
    if source.count(first) != 1 or source.count(last) != 1:
        raise ValueError('ambiguous production section: ' + first)
    return source[source.index(first):source.index(last, source.index(first))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref')
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--linux-reference', action='store_true')
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
    parser.add_argument('--mutation', choices=['ignore-receiver', 'invert-default',
                                              'ignore-flag-failure', 'drop-socket-cleanup'])
    args = parser.parse_args()
    paths = ['compat/posix/src/fd.cc', 'compat/posix/src/socket.cc',
             'compat/posix/src/socket_internal.h', 'compat/posix/src/internal.h',
             'compat/posix/src/unix_rights.cc']
    sources = {path: subprocess.check_output(['git', 'show', args.source_ref + ':' + path],
                    cwd=ROOT).decode() if args.source_ref else (ROOT/path).read_text()
               for path in paths}
    originals = {path: hashlib.sha256(text.encode()).hexdigest() for path, text in sources.items()}
    mutations = {
        'ignore-receiver': (paths[4], 'AdoptSocket(reinterpret_cast<uintptr_t>(h), metadata, close_on_exec)',
                           'AdoptSocket(reinterpret_cast<uintptr_t>(h), metadata, true)'),
        'invert-default': (paths[3], 'int AdoptSocket(uintptr_t socket, uint64_t metadata, bool close_on_exec = true);',
                           'int AdoptSocket(uintptr_t socket, uint64_t metadata, bool close_on_exec = false);'),
        'ignore-flag-failure': (paths[0], '!SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)',
                               '(!SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT) && false)'),
        'drop-socket-cleanup': (paths[0], 'if (!state.description) ws::Close(state.socket);',
                                'if (!state.description) {}'),
    }
    if args.mutation:
        path, before, after = mutations[args.mutation]
        if sources[path].count(before) != 1:
            raise ValueError('mutation boundary changed')
        sources[path] = sources[path].replace(before, after, 1)
    fd, socket, header, internal, rights = [sources[path] for path in paths]
    declarations = []
    for marker in ['int ReserveFd(', 'int AdoptSocket(', 'bool TransferSocket(']:
        matches = [line for line in internal.splitlines() if line.startswith(marker)]
        if len(matches) != 1 or not matches[0].endswith(';'):
            raise ValueError('internal declaration changed: ' + marker)
        declarations.extend(matches)
    pieces = {
        'registry': section(fd, 'struct SocketRegistry {', '// Takes the socket out of the table;'),
        'remove': function(fd, 'bool Remove('),
        'insert': function(fd, 'void Insert('),
        'lookup': function(fd, 'bool LookupSocket('),
        'reserve': function(fd, 'int ReserveFd('),
        'register': function(fd, 'int RegisterSocket('),
        'new': function(socket, 'int RegisterNew('),
        'constants': section(socket, 'constexpr uint32_t kTransferUnixDatagram',
                             '}  // namespace\n\nbool gesher::TransferSocket('),
        'transfer': function(socket, 'bool gesher::TransferSocket('),
        'adopt': function(socket, 'int gesher::AdoptSocket('),
        'reply': section(rights, 'struct Reply {', '\nstd::string Device()'),
        'receive': function(rights, 'bool ReadDelivered('),
        'declarations': '\n'.join(declarations),
    }
    print('SOCKET_RECEIVE_SOURCE ' + str(args.source_ref or 'working-tree') + ' ' +
          ' '.join(path+'='+digest for path, digest in originals.items()), flush=True)
    print('SOCKET_RECEIVE_PIECES ' + ' '.join(name+'='+hashlib.sha256(body.encode()).hexdigest()
          for name, body in pieces.items()), flush=True)
    template = (ROOT/'tests/native/socket_receive_host.cc.in').read_text()
    groups = {
        '@DECLARATIONS@': pieces['declarations'],
        '@REGISTRY@': '\n'.join(pieces[k] for k in ['registry', 'remove', 'insert', 'lookup']),
        '@REGISTRATION@': '\n'.join(pieces[k] for k in ['reserve', 'register']),
        '@SOCKET@': '\n'.join(pieces[k] for k in ['new', 'constants']),
        '@ADOPTION@': pieces['transfer']+'\n'+pieces['adopt'],
        '@RECEIVE@': pieces['reply']+'\n'+pieces['receive'],
        '@FLAG_REGISTRATION@': '1' if 'bool close_on_exec' in pieces['register'] else '0',
    }
    for marker, value in groups.items():
        if template.count(marker) != 1:
            raise ValueError('fixture boundary changed: ' + marker)
        template = template.replace(marker, value)
    with tempfile.TemporaryDirectory(prefix='gesher-socket-receive-') as temporary:
        directory = Path(temporary)
        (directory/'socket_internal.h').write_text(header)
        source, binary = directory/'test.cc', directory/'test'
        source.write_text(template)
        command = shlex.split(args.cxx) + ['-std=c++20', '-O1', '-g', '-Wall', '-Wextra',
                  '-Werror', '-pthread', str(source), '-o', str(binary)]
        if args.sanitize:
            command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all',
                        '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, timeout=120)
        result = subprocess.run([str(binary)], timeout=30).returncode
        if args.linux_reference:
            public = (ROOT/'tests/native/unix_rights_test.c').read_text()
            helpers = '\n'.join(function(public, marker) for marker in
                                ['static ssize_t send_fds(', 'static ssize_t recv_fds_how('])
            reference = '''#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>
static int failures, checks;
static void check(const char* what,int good) {
  ++checks; if(!good){++failures;printf("FAIL Linux socket flags: %s (errno=%d)\\n",what,errno);}
}
''' + helpers + '\n#include "socket_receive_flags_test.inc"\n' + '''
int main(void) {
  test_socket_receive_flags();
  printf("SOCKET_RECEIVE_LINUX_REFERENCE checks=%d failures=%d cases=24\\n",checks,failures);
  return failures?1:0;
}
'''
            ref_source, ref_binary = directory/'reference.c', directory/'reference'
            ref_source.write_text(reference)
            ref_command = ['cc', '-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                           '-I', str(ROOT/'tests/native'), str(ref_source), '-o', str(ref_binary)]
            if args.sanitize:
                ref_command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all',
                                '-fno-omit-frame-pointer']
            subprocess.run(ref_command, check=True, timeout=120)
            result |= subprocess.run([str(ref_binary)], timeout=90).returncode
        return result


if __name__ == '__main__':
    raise SystemExit(main())
