#!/usr/bin/env python3
"""Inspect APK ABI/JNI, Tile manifest, private-input exclusion and alignment."""
import argparse
import hashlib
import os
import re
from pathlib import Path
import struct
import subprocess
import zipfile


def manifest_nodes(dump):
    """Retain parent/child ownership, so another component cannot satisfy a check."""
    root = {'tag': '#root', 'attrs': [], 'children': []}
    stack = [(-1, root)]
    for line in dump.splitlines():
        element = re.match(r'(\s*)E: ([^\s(]+)', line)
        if element:
            indent = len(element[1])
            while stack[-1][0] >= indent:
                stack.pop()
            node = {'tag': element[2], 'attrs': [], 'children': []}
            stack[-1][1]['children'].append(node)
            stack.append((indent, node))
        elif re.match(r'\s*A:', line):
            stack[-1][1]['attrs'].append(line.strip())
    return root


def attr(node, name):
    return next((a for a in node['attrs'] if re.match(r'A: '+re.escape(name)+r'(?:\(|=)', a)), '')


def text_attr(node, name):
    a = attr(node, name)
    m = re.search(r'="([^"]*)"', a)
    return m[1] if m else None


def children(node, tag):
    return [n for n in node['children'] if n['tag'] == tag]


QA_PACKAGE = 'io.github.projectcetacean.tamaemu.qoltest'
PROBE_PREFIX = 'io.github.projectcetacean.tamaemu.TileTransportProbe'
PROBE_SUFFIXES = {'Activity', 'Job', 'Receiver', 'Service', 'State'}


def component_name(node):
    name = text_attr(node, 'android:name') or ''
    if name.startswith('.'):
        return 'io.github.projectcetacean.tamaemu' + name
    if '.' not in name:
        return 'io.github.projectcetacean.tamaemu.' + name
    return name


def validate_probe_manifest(app, package):
    components = [n for n in app['children'] if component_name(n).startswith(PROBE_PREFIX)]
    if not components:
        return False
    assert package == QA_PACKAGE, 'QA probe component in production package'
    assert text_attr(app, 'android:name') == 'android.app.Application', 'QA probe requires plain Application'
    assert '(type 0x12)0xffffffff' in attr(app, 'android:debuggable'), 'QA probe must be debuggable'
    expected = {PROBE_PREFIX + suffix: tag for suffix, tag in
                [('Activity', 'activity'), ('Service', 'service'), ('Job', 'service'), ('Receiver', 'receiver')]}
    assert len(components) == len(expected) and {component_name(n): n['tag'] for n in components} == expected, 'QA probe components incomplete or unexpected'
    receiver = next(n for n in components if n['tag'] == 'receiver')
    assert '(type 0x12)0xffffffff' in attr(receiver, 'android:exported'), 'QA probe receiver must be exported'
    assert text_attr(receiver, 'android:permission') == 'android.permission.DUMP', 'QA probe receiver requires DUMP'
    assert not children(receiver, 'intent-filter'), 'QA probe receiver must be explicit only'
    job = next(n for n in components if component_name(n) == PROBE_PREFIX + 'Job')
    assert '(type 0x12)0xffffffff' in attr(job, 'android:exported'), 'QA probe job must be exported'
    assert text_attr(job, 'android:permission') == 'android.permission.BIND_JOB_SERVICE', 'QA probe job requires binding permission'
    return True


def validate_probe_classes(archive, package, probe):
    dex = [archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
    prefix = ('L' + PROBE_PREFIX.replace('.', '/')).encode()
    present = {suffix for suffix in PROBE_SUFFIXES if any(prefix + suffix.encode() + b';' in data for data in dex)}
    if probe:
        assert package == QA_PACKAGE, 'QA probe classes in production package'
        assert present == PROBE_SUFFIXES, 'QA probe APK must compile all five probe classes'
    else:
        assert not any(prefix in data for data in dex), 'QA probe classes outside explicit probe APK'


AUDIOQA_PREFIX = 'io.github.projectcetacean.tamaemu.AudioQaInput'
AUDIOQA_SUFFIXES = {'Provider', 'Receiver', 'Driver'}


def validate_audioqa_manifest(app, package, probe):
    components = [n for n in app['children'] if component_name(n).startswith(AUDIOQA_PREFIX)]
    regular_qa = package == QA_PACKAGE and not probe
    if not regular_qa:
        assert not components, 'audioqa components outside regular QA debug'
        return False
    assert '(type 0x12)0xffffffff' in attr(app, 'android:debuggable'), 'audioqa requires debuggable QA'
    assert component_name(app) == 'io.github.projectcetacean.tamaemu.TamaemuApplication', 'audioqa requires regular TamaemuApplication'
    expected = {AUDIOQA_PREFIX + 'Provider': 'provider', AUDIOQA_PREFIX + 'Receiver': 'receiver'}
    assert len(components) == 2 and {component_name(n): n['tag'] for n in components} == expected, 'audioqa components incomplete or unexpected'
    provider = next(n for n in components if n['tag'] == 'provider')
    assert re.search(r'=\(type 0x12\)0x0$', attr(provider, 'android:exported')), 'audioqa provider must be exported false'
    assert text_attr(provider, 'android:authorities') == QA_PACKAGE + '.audioqa', 'audioqa authority must match exact QA package'
    assert not children(provider, 'intent-filter'), 'audioqa provider must have no filters'
    receiver = next(n for n in components if n['tag'] == 'receiver')
    assert '(type 0x12)0xffffffff' in attr(receiver, 'android:exported'), 'audioqa receiver must be exported'
    assert text_attr(receiver, 'android:permission') == 'android.permission.DUMP', 'audioqa receiver requires DUMP'
    assert not children(receiver, 'intent-filter'), 'audioqa receiver must be explicit only'
    tiles = [n for n in children(app, 'service') if component_name(n) == 'io.github.projectcetacean.tamaemu.PetTileService']
    assert len(tiles) == 1, 'audioqa requires original PetTileService'
    return True


def validate_audioqa_classes(archive, package, audioqa):
    dex = [archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
    prefix = ('L' + AUDIOQA_PREFIX.replace('.', '/')).encode()
    if audioqa:
        assert package == QA_PACKAGE, 'audioqa classes in production package'
        present = {suffix for suffix in AUDIOQA_SUFFIXES if any(prefix + suffix.encode() + b';' in data for data in dex)}
        assert present == AUDIOQA_SUFFIXES, 'audioqa APK must compile all three input classes'
    else:
        assert not any(prefix in data for data in dex), 'audioqa classes outside regular QA debug'


def tile_service(app, package):
    names = {'.PetTileService', 'io.github.projectcetacean.tamaemu.PetTileService'}
    probe = {'.TileTransportProbeService', 'io.github.projectcetacean.tamaemu.TileTransportProbeService'}
    services = children(app, 'service')
    if package == 'io.github.projectcetacean.tamaemu.qoltest':
        names |= probe
    else:
        assert not any(text_attr(n, 'android:name') in probe for n in services), 'QA Tile probe in production package'
    selected = [n for n in services if text_attr(n, 'android:name') in names]
    assert len(selected) == 1, 'Exactly one supported Tile service required'
    return selected[0]


def validate_manifest(dump, package):
    manifest = children(manifest_nodes(dump), 'manifest')[0]
    app = children(manifest, 'application')[0]
    probe = validate_probe_manifest(app, package)
    validate_audioqa_manifest(app, package, probe)
    assert '(type 0x12)0xffffffff' in attr(app, 'android:enableOnBackInvokedCallback'), 'Application Back callback opt-in must be boolean true'
    metadata = children(app, 'meta-data')
    assert any(text_attr(n, 'android:name') == 'com.google.android.wearable.standalone' and
               '(type 0x12)0xffffffff' in attr(n, 'android:value') for n in metadata), 'Standalone metadata must be boolean true'
    assert 'android.permission.INTERNET' not in dump
    assert any(text_attr(n, 'android:name') == 'android.permission.RECEIVE_BOOT_COMPLETED'
               for n in children(manifest, 'uses-permission')), 'Persisted aging requires boot permission'
    activity = next(n for n in children(app, 'activity') if text_attr(n, 'android:name') in
                    {'.MainActivity', 'io.github.projectcetacean.tamaemu.MainActivity'})
    assert re.search(r'(?:\(type 0x10\)|\(type 0x11\))0x2\b', attr(activity, 'android:launchMode')), 'Activity must be singleTask'
    assert text_attr(activity, 'android:taskAffinity') in {None, package}, 'Activity must use application task affinity'
    assert '(type 0x12)0xffffffff' in attr(activity, 'android:exported')
    service = tile_service(app, package)
    assert '(type 0x12)0xffffffff' in attr(service, 'android:exported'), 'Tile service must be exported'
    assert text_attr(service, 'android:permission') == 'com.google.android.wearable.permission.BIND_TILE_PROVIDER', 'Tile binding permission missing'
    actions = [a for f in children(service, 'intent-filter') for a in children(f, 'action')]
    assert any(text_attr(a, 'android:name') == 'androidx.wear.tiles.action.BIND_TILE_PROVIDER' for a in actions), 'Tile binding action missing'
    preview = next(n for n in children(service, 'meta-data') if text_attr(n, 'android:name') == 'androidx.wear.tiles.PREVIEW')
    assert attr(preview, 'android:resource'), 'Tile preview resource missing'
    aging = next((n for n in children(app, 'service') if text_attr(n, 'android:name') in
                  {'.PetAgingJobService', 'io.github.projectcetacean.tamaemu.PetAgingJobService'}), None)
    assert aging is not None, 'Aging JobService missing'
    assert '(type 0x12)0xffffffff' in attr(aging, 'android:exported'), 'Aging JobService must be exported'
    assert text_attr(aging, 'android:permission') == 'android.permission.BIND_JOB_SERVICE', 'Aging JobService binding permission missing'
    return probe



# Exact public resource from kotlinx-coroutines-core-jvm 1.8.1, verified against
# Maven Central JAR and its published .sha1 on 2026-10-15:
# https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar
# JAR SHA256: f3d4f5de1c391bbcc20f3b3435ccbac013521e76b6902d7d59635ec15c1f797e
PUBLIC_DEBUG_PROBES_NAME = 'DebugProbesKt.bin'
PUBLIC_DEBUG_PROBES_SHA256 = '86fecd0235d45839845081c1f91c9a58b40b0ae787f242bdba8f9150da45d0eb'


def validate_private_inputs(archive):
    """Reject private-input extensions except one exact, pinned public resource."""
    blocked = []
    for entry in archive.infolist():
        name = entry.filename
        lower = name.lower()
        if not (lower.endswith(('.bin', '.rom', '.dump', '.state', '.sav', '.jks', '.keystore',
                                '.pcm', '.pem', '.key', '.p12', '.pfx', '.p8'))
                or re.search(r'\.(?:state|sav)\.', lower.rsplit('/', 1)[-1])):
            continue
        if (name == PUBLIC_DEBUG_PROBES_NAME and
                hashlib.sha256(archive.read(entry)).hexdigest() == PUBLIC_DEBUG_PROBES_SHA256):
            continue
        blocked.append(name)
    assert not blocked, f'Private input packaged: {blocked!r}'



def validate_tile_preview(manifest_dump, resources_dump, package, archive):
    """Follow Tile metadata through aapt's resource table, including renamed paths."""
    manifest = children(manifest_nodes(manifest_dump), 'manifest')[0]
    app = children(manifest, 'application')[0]
    service = tile_service(app, package)
    preview = next((n for n in children(service, 'meta-data') if
                    text_attr(n, 'android:name') == 'androidx.wear.tiles.PREVIEW'), None)
    assert preview is not None, 'Tile preview metadata missing'
    reference = re.search(r'=@(0x[0-9a-fA-F]+)\b', attr(preview, 'android:resource'))
    assert reference, 'Tile preview must reference a resource ID'
    resource_id = int(reference[1], 16)
    lines = resources_dump.splitlines()
    paths = set()
    for i, line in enumerate(lines):
        resource = re.fullmatch(r'\s*resource (0x[0-9a-fA-F]+) (\S+): (.*)', line)
        if resource is None or int(resource[1], 16) != resource_id:
            continue
        assert resource[2] == package + ':drawable/pet_tile_preview', 'Tile preview ID must name authored drawable'
        assert resource[3].startswith('t=0x03 '), 'Tile preview must be a file resource'
        value = re.fullmatch(r'\s*\(string(?:8|16)\) "([^"]+)"', lines[i + 1] if i + 1 < len(lines) else '')
        assert value, 'Tile preview file path missing from resource table'
        path = value[1]
        assert path.startswith('res/') and path.endswith('.xml') and '..' not in Path(path).parts, 'Tile preview path must be packaged XML'
        paths.add(path)
    assert paths, 'Tile preview ID missing from resource table'
    for path in paths:
        entries = [entry for entry in archive.infolist() if entry.filename == path]
        assert len(entries) == 1, 'Resolved Tile preview XML missing or ambiguous: ' + path
        content = archive.read(entries[0])
        assert len(content) >= 8, 'Tile preview must be binary XML: ' + path
        kind, header_size, size = struct.unpack_from('<HHI', content)
        assert kind == 3 and header_size == 8 and size == len(content), 'Tile preview must be binary XML: ' + path
    return sorted(paths)


def validate_required_kind(package, probe, required_kind=None):
    if required_kind is None:
        return
    assert package == QA_PACKAGE, 'Required QA artifact kind needs exact test package'
    assert required_kind in {'tile-probe', 'audioqa'}, 'Unknown required QA artifact kind'
    assert probe == (required_kind == 'tile-probe'), 'APK is not the required QA artifact kind: ' + required_kind


def validate_package_version(badging, package):
    assert f"package: name='{package}'" in badging, 'APK package differs from requested package'
    assert "versionCode='9'" in badging and "versionName='0.2.0-wear.9'" in badging, 'APK version must be 9 / 0.2.0-wear.9'


def inspect(apk, tools, package, unsigned, required_kind=None):
    expected = {'armeabi-v7a', 'arm64-v8a', 'x86_64'}
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        assert 'classes.dex' in names and 'AndroidManifest.xml' in names
        assert any(b'PetTileService' in z.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n)), 'Tile class missing'
        assert any(b'PetAgingJobService' in z.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n)), 'Aging class missing'
        libs = {n.split('/')[1]: n for n in names if n.startswith('lib/') and n.endswith('/libtamaemu.so')}
        assert set(libs) == expected, f'Native ABIs differ: {set(libs)}'
        validate_private_inputs(z)
        symbols = ['open', 'close', 'width', 'height', 'advance', 'setButton', 'save',
                   'advanceHeadless', 'readLife', 'writeLife', 'committedPayloadHash', 'setAudioEnabled', 'drainAudio']
        for abi, name in libs.items():
            elf = z.read(name)
            assert elf[:4] == b'\x7fELF'
            assert all(('Java_io_github_projectcetacean_tamaemu_NativeBridge_' + symbol).encode() in elf for symbol in symbols), f'Missing JNI symbol: {abi}'
            if abi in {'arm64-v8a', 'x86_64'}:
                assert elf[4:6] == b'\x02\x01', 'Expected little endian ELF64'
                offset, = struct.unpack_from('<Q', elf, 32)
                entry_size, count = struct.unpack_from('<HH', elf, 54)
                aligns = [struct.unpack_from('<Q', elf, offset + i*entry_size + 48)[0]
                          for i in range(count) if struct.unpack_from('<I', elf, offset+i*entry_size)[0] == 1]
                assert aligns and min(aligns) >= 16384, f'ELF alignment below 16KB: {abi}'
            print(f'{abi}: JNI symbols and ELF alignment passed')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    validate_package_version(badging, package)
    assert "launchable-activity: name='io.github.projectcetacean.tamaemu.MainActivity'" in badging
    assert "uses-feature: name='android.hardware.type.watch'" in badging
    manifest = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
    probe = validate_manifest(manifest, package)
    validate_required_kind(package, probe, required_kind)
    with zipfile.ZipFile(apk) as archive:
        validate_probe_classes(archive, package, probe)
        validate_audioqa_classes(archive, package, package == QA_PACKAGE and not probe)
    resources = subprocess.check_output([str(tools / 'aapt'), 'dump', '--values', 'resources', str(apk)], text=True)
    with zipfile.ZipFile(apk) as archive:
        preview_paths = validate_tile_preview(manifest, resources, package, archive)
    for path in preview_paths:
        preview_dump = subprocess.check_output([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), path], text=True)
        assert children(manifest_nodes(preview_dump), 'vector'), 'Authored Tile preview must decode as a vector'
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    if not unsigned:
        subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], check=True)
    print('Tile, watch launcher, standalone metadata, private exclusion and APK alignment passed' + (' (unsigned release)' if unsigned else ', signature passed'))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk', type=Path)
    p.add_argument('--test-package', action='store_true')
    kind = p.add_mutually_exclusive_group()
    kind.add_argument('--require-tile-probe', dest='required_kind', action='store_const', const='tile-probe')
    kind.add_argument('--require-audioqa', dest='required_kind', action='store_const', const='audioqa')
    p.add_argument('--unsigned', action='store_true', help='Allow the intentionally unsigned release build')
    args = p.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    package = 'io.github.projectcetacean.tamaemu' + ('.qoltest' if args.test_package else '')
    inspect(args.apk.resolve(), sdk / 'build-tools' / '35.0.0', package, args.unsigned, args.required_kind)


if __name__ == '__main__':
    main()
