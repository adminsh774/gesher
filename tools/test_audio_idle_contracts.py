#!/usr/bin/env python3
"""Run actual audio demand/lifecycle code with isolated endpoint transport.

Windows uses real Win32 events, timers and thread priorities. Linux substitutes
only that transport. Neither run is physical audio or an idle-CPU benchmark.
"""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def generate(actual_routing: bool = False) -> str:
    source = (ROOT / 'compat/media/audio/engine.cc').read_text()
    header = (ROOT / 'compat/media/audio/engine.h').read_text().replace('#pragma once', '')
    sink = (ROOT / 'compat/media/audio/sink.h').read_text().replace('#pragma once', '')
    shared = (ROOT / 'compat/media/audio/shared_state.h').read_text().replace('#pragma once', '')
    # Visibility only; complete production Engine/Track bodies remain unchanged.
    body = source[source.index('namespace gesher::media {'):].replace('private:', 'public:')
    template = (ROOT / 'tests/media/audio_idle_engine.cc.in').read_text()
    if actual_routing:
        route_source = (ROOT / 'compat/media/audio/sink_wasapi.cc').read_text()
        watcher = (ROOT / 'tests/media/audio_idle_route_watcher.cc.in').read_text()
        route = between(route_source, 'class RenderRoute {', 'class DeviceWatcher :')
        watcher = watcher.replace('@RENDER_ROUTE@', route.replace('IMMDeviceEnumerator*', 'RouteEnumerator*'))
        source += route_source
    else:
        watcher = '''class FixtureWatcher : public OutputDeviceWatcher {
 public:
  uint64_t generation() const override { return fixture::device_generation.load(); }
  uint64_t render_generation() override { return fixture::render_generation.load(); }
  bool matches_render_endpoint(const std::wstring& id) const override {
    return id == (fixture::no_endpoint ? L"" : fixture::EndpointId(fixture::route));
  }
};'''
    replacements = {'@HEADER@': header, '@SINK@': sink + shared, '@ENGINE@': body,
                    '@WATCHER@': watcher, '@ROUTING_CASES@':
                    (ROOT / 'tests/media/audio_idle_routing_cases.cc.in').read_text() if actual_routing else ''}
    for marker, value in replacements.items():
        if template.count(marker) != 1:
            raise ValueError('test section boundary changed: ' + marker)
        template = template.replace(marker, value)
    print('audio idle source SHA256 ' + hashlib.sha256((source + header + sink + shared).encode()).hexdigest(), flush=True)
    return template


def between(source: str, begin: str, end: str) -> str:
    start = source.index(begin)
    return source[start:source.index(end, start)]


def feeder_source() -> str:
    source = (ROOT / 'compat/media/audio/wasapi_sink.cc').read_text()
    header = (ROOT / 'compat/media/audio/wasapi_sink.h').read_text()
    header = '\n'.join(line for line in header.splitlines() if not line.startswith(('#include', '#pragma', '#undef')))
    for name, boundary in {'IAudioClient': 'fixture::FakeClient', 'IAudioRenderClient': 'fixture::FakeRender',
                           'ISimpleAudioVolume': 'fixture::FakeVolume', 'IAudioSessionControl': 'fixture::FakeSession',
                           'IMMDeviceEnumerator': 'fixture::FakeUnknown', 'IMMDevice': 'fixture::FakeUnknown'}.items():
        header = header.replace(name + '*', boundary + '*')
    header = header.replace('private:', 'public:')
    legacy = (ROOT / 'compat/media/audio/sink_wasapi.cc').read_text()
    idle = between(legacy, '  void SetIdle(bool idle) override {', '  ~WasapiSink()').replace(' override', '')
    template = (ROOT / 'tests/media/audio_idle_feeder.cc.in').read_text()
    replacements = {
        '@ENGINE_HEADER@': (ROOT / 'compat/media/audio/engine.h').read_text().replace('#pragma once', ''),
        '@SINK@': (ROOT / 'compat/media/audio/sink.h').read_text().replace('#pragma once', ''),
        '@WASAPI_HEADER@': header,
        '@PREPARE@': between(source, 'WasapiAudioSink::WasapiAudioSink()', 'bool WasapiAudioSink::InitAudioClient'),
        '@CONTROLS@': between(source, 'void WasapiAudioSink::Close()', 'void WasapiAudioSink::SetAudioTrackVolume') +
                     between(source, 'bool WasapiAudioSink::EnqueueOpenSLBuffer', 'bool WasapiAudioSink::Tick'),
        '@TICK@': between(source, 'bool WasapiAudioSink::Tick', 'void WasapiAudioSink::ApplyVolume'),
        '@FEED@': between(source, 'void WasapiAudioSink::FeedLoop()', 'std::unique_ptr<WasapiAudioSink> CreateWasapiAudioSink'),
        '@LEGACY_IDLE@': idle,
    }
    for marker, value in replacements.items():
        if template.count(marker) != 1:
            raise ValueError('test section boundary changed: ' + marker)
        template = template.replace(marker, value)
    print('audio feeder source SHA256 ' + hashlib.sha256((source + header + legacy).encode()).hexdigest(), flush=True)
    return template


def clock_source() -> str:
    source = (ROOT / 'compat/media/audio/sink_file.cc').read_text()
    template = (ROOT / 'tests/media/audio_idle_clock.cc.in').read_text()
    replacements = {
        '@ENGINE_HEADER@': (ROOT / 'compat/media/audio/engine.h').read_text().replace('#pragma once', ''),
        '@SINK@': (ROOT / 'compat/media/audio/sink.h').read_text().replace('#pragma once', ''),
        '@CLOCK@': between(source, 'namespace gesher::media {', 'std::string Narrow('),
    }
    for marker, value in replacements.items():
        if template.count(marker) != 1:
            raise ValueError('test section boundary changed: ' + marker)
        template = template.replace(marker, value)
    print('audio clock source SHA256 ' + hashlib.sha256(source.encode()).hexdigest(), flush=True)
    return template


def notification_source() -> str:
    source = (ROOT / 'compat/media/audio/sink_wasapi.cc').read_text()
    identity = (ROOT / 'compat/media/audio/endpoint_identity.h').read_text()
    identity_body = '\n'.join(line for line in identity.splitlines() if not line.startswith(('#include', '#pragma')))
    identity_body = identity_body.replace('IMMDevice*', 'fixture::Device*').replace('CoTaskMemFree', 'fixture::FreeId')
    route = between(source, 'bool ReadDefaultRenderId(', 'class DeviceWatcher :')
    route = route.replace('IMMDeviceEnumerator*', 'fixture::Enumerator*').replace('IMMDevice*', 'fixture::Device*')
    template = (ROOT / 'tests/media/audio_idle_notifications.cc.in').read_text()
    replacements = {
        '@ENDPOINT_ID@': identity_body,
        '@IID@': between(source, 'const IID kIidIMMNotificationClient', 'const GUID kSubtypeFloat'),
        '@NOTIFICATIONS@': between(source, 'class DeviceEvents :', '// Return a known current route'),
        '@ROUTE@': route,
    }
    for marker, value in replacements.items():
        if template.count(marker) != 1:
            raise ValueError('test section boundary changed: ' + marker)
        template = template.replace(marker, value)
    print('audio notification source SHA256 ' + hashlib.sha256((source + identity).encode()).hexdigest(), flush=True)
    return template


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cxx', default=os.environ.get('CXX', 'cl' if os.name == 'nt' else 'c++'))
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--component', choices=['engine', 'feeder', 'clock', 'notifications', 'routing'])
    args = parser.parse_args()
    if args.component is None:
        for component in ('engine', 'feeder', 'clock', 'notifications', 'routing'):
            command = [sys.executable, __file__, '--component', component, '--cxx', args.cxx]
            if args.sanitize:
                command.append('--sanitize')
            subprocess.run(command, check=True, timeout=180)
        return 0
    with tempfile.TemporaryDirectory(prefix='gesher-audio-idle-') as tmp:
        work = Path(tmp)
        src = work / 'audio-idle.cc'
        src.write_text({'engine': generate, 'feeder': feeder_source, 'clock': clock_source, 'notifications': notification_source, 'routing': lambda: generate(True)}[args.component]())
        exe = work / ('audio-idle.exe' if os.name == 'nt' else 'audio-idle')
        command = shlex.split(args.cxx)
        if Path(command[0]).name.lower() in ('cl', 'cl.exe'):
            command += ['/nologo', '/std:c++20', '/EHsc', '/W4', '/WX', '/D_WIN32_WINNT=0x0A00',
                        '/D_USE_MATH_DEFINES', '/DNOMINMAX', '/D_CRT_SECURE_NO_WARNINGS', '/wd4100', '/wd4244', '/wd4267', '/I' + str(ROOT / 'tests/media'),
                        str(src), '/Fe:' + str(exe), 'ole32.lib', 'uuid.lib']
        else:
            command += ['-std=c++20', '-O1', '-g', '-fno-exceptions', '-Wall', '-Wextra', '-Werror', '-pthread',
                        '-Wno-unused-const-variable',
                        '-I' + str(ROOT / 'tests/media'), str(src), '-o', str(exe)]
            if args.sanitize:
                command += ['-fsanitize=address,undefined', '-fno-sanitize-recover=all', '-fno-omit-frame-pointer']
        subprocess.run(command, check=True, cwd=work, timeout=120)
        env = os.environ.copy()
        for key in ('GESHER_AUDIO', 'GESHER_AUDIO_CAPTURE', 'GESHER_TEST_NO_WATCHER',
                    'GESHER_TEST_TIMER_FAILURE', 'GESHER_TEST_EVENT_FAILURE', 'GESHER_TEST_OUTPUT_TIMING',
                    'GESHER_TEST_ROUTE_SCENARIO', 'GESHER_TEST_THREAD_FAILURE', 'GESHER_TEST_IDLE_OBSERVATION'):
            env.pop(key, None)
        if args.component == 'routing':
            for scenario in ('same-endpoint', 'changed-endpoint', 'changed-without-notification',
                             'switch-during-open', 'notification-during-lookup', 'repeated-failures',
                             'no-endpoint', 'no-endpoint-lookup-failure', 'open-failure'):
                subprocess.run([str(exe)], check=True, env={**env, 'GESHER_TEST_ROUTE_SCENARIO': scenario}, timeout=15)
            return 0
        subprocess.run([str(exe)], check=True, env=env, timeout=30)
        for key, value in ([] if args.component != 'engine' else [
                ('GESHER_AUDIO_CAPTURE', str(work / 'existing.wav')), ('GESHER_TEST_NO_WATCHER', '1'),
                ('GESHER_TEST_TIMER_FAILURE', '1'), ('GESHER_TEST_EVENT_FAILURE', '1'),
                ('GESHER_TEST_OUTPUT_TIMING', '1'), ('GESHER_TEST_THREAD_FAILURE', '1'),
                ('GESHER_TEST_IDLE_OBSERVATION', '1')]):
            sentinel = work / 'existing.wav'; sentinel.write_bytes(b'existing capture must not change')
            subprocess.run([str(exe), 'query'], check=True, env={**env, key: value}, timeout=10)
            if sentinel.read_bytes() != b'existing capture must not change':
                raise AssertionError('metadata query changed existing capture')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
