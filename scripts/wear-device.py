#!/usr/bin/env python3
"""Explicit-device local helpers; no uninstall, save replacement, or firmware upload."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import zipfile

PKG = 'io.github.projectcetacean.tamaemu'
ROOT = Path(__file__).resolve().parents[1]

def adb(args, *, data=None, check=True, reject_stderr=False):
    result = subprocess.run([ADB, '-s', options.serial, *args], input=data,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)
    if reject_stderr and result.stderr.strip():
        raise SystemExit('Device preflight produced diagnostics; refusing to infer absence: '+
                         result.stderr.decode(errors='replace').strip())
    return result.stdout

def shell(command, **kwargs):
    return adb(['shell', '-T', command], **kwargs)

def probe():
    info = {key: shell('getprop '+prop).decode().strip() for key, prop in
            [('model','ro.product.model'), ('manufacturer','ro.product.manufacturer'),
             ('api','ro.build.version.sdk'), ('abis','ro.product.cpu.abilist')]}
    info['watch'] = 'android.hardware.type.watch' in shell('pm list features').decode()
    info['display'] = shell('wm size').decode().strip()
    if not info['watch'] or not info['api'].isdigit() or int(info['api']) < 26:
        raise SystemExit('Requires a watch with Android API 26 or newer: '+json.dumps(info))
    print(json.dumps(info, indent=2))
    return info

def backup():
    # Probe existence first so empty/truncated transfers cannot masquerade as a backup.
    # Unlike `pm path`, list packages exits successfully for an absent app.
    # A failed/denied query must never be converted into a first installation.
    packages = shell(f'pm list packages {PKG}', reject_stderr=True).splitlines()
    if any(not re.fullmatch(rb'package:[A-Za-z0-9_.]+', line.strip()) for line in packages):
        raise SystemExit('Unexpected package query output; refusing to infer absence.')
    if ('package:'+PKG).encode() not in [line.strip() for line in packages]:
        print('App is not installed; no save exists to back up.')
        return
    present = shell(f'run-as {PKG} sh -c \'if test -f files/pet.state; then echo yes; else echo no; fi\'', reject_stderr=True).strip()
    if present == b'no':
        print('No existing app save found; nothing backed up.')
        return
    if present != b'yes':
        raise SystemExit('Could not establish save existence; refusing to continue.')
    state = adb(['exec-out', 'run-as', PKG, 'cat', 'files/pet.state'])
    if not state:
        raise SystemExit('Existing save was empty; refusing to continue.')
    folder = Path(options.backup_dir).resolve()
    folder.mkdir(parents=True, exist_ok=True)
    os.chmod(folder, 0o700)
    target = folder / ('pet-'+uuid.uuid4().hex+'.state')
    with target.open('xb') as out:
        os.chmod(target, 0o600)
        out.write(state)
    print('Save backup created: '+str(target))

def expected_rom_digest(value):
    if not re.fullmatch(r'[0-9a-fA-F]{64}', value):
        raise argparse.ArgumentTypeError('expected ROM SHA-256 must contain exactly 64 hexadecimal characters')
    return value.lower()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Explicit adb device serial')
parser.add_argument('--backup-dir', default=str(ROOT/'backups'))
sub = parser.add_subparsers(dest='command', required=True)
sub.add_parser('probe')
sub.add_parser('backup')
p = sub.add_parser('install'); p.add_argument('apk', type=Path)
p = sub.add_parser('import-rom'); p.add_argument('rom', type=Path)
p.add_argument('--expected-rom-sha256', required=True, type=expected_rom_digest,
               help='Expected local input identity; does not certify firmware compatibility')
options = parser.parse_args()
ADB = shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME',''))/'platform-tools'/'adb')
try:
    if options.command == 'import-rom':
        # Verify explicit local input identity before contacting any device.
        # A matching hash and size do not certify firmware compatibility.
        rom = options.rom.read_bytes()
        if len(rom) != 8388608 or hashlib.sha256(rom).hexdigest() != options.expected_rom_sha256:
            raise SystemExit('ROM size or expected input identity differs; import refused.')
    info = probe()
    if options.command == 'backup':
        backup()
    elif options.command == 'install':
        # Only the durable local certificate may become a device update identity.
        subprocess.run([sys.executable, str(ROOT/'scripts/sign-wear-apk.py'), 'verify',
                        str(options.apk.resolve())], check=True)
        with zipfile.ZipFile(options.apk) as z:
            abis = {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('/libtamaemu.so')}
        if not abis.intersection(info['abis'].split(',')):
            raise SystemExit('APK has no native ABI supported by this device.')
        backup()
        result = adb(['install', '-r', str(options.apk.resolve())]).decode()
        if 'Success' not in result:
            raise SystemExit('Install did not report success: '+result)
        print('Installed update while preserving app data.')
    elif options.command == 'import-rom':
        processes = shell('ps -A -o NAME', reject_stderr=True).decode().splitlines()
        if not processes or processes[0].strip() != 'NAME':
            raise SystemExit('Could not establish app process state; import refused.')
        if any(name.strip() == PKG or name.strip().startswith(PKG+':') for name in processes[1:]):
            raise SystemExit('Force-stop the app before import and leave it stopped until verification finishes.')
        backup()
        # Android run-as SELinux can prohibit hard links. mksh set -C opens an
        # absent destination with O_EXCL. Guard special files/symlinks as well;
        # never unlink the target, even after an interrupted/invalid transfer.
        command = (f"run-as {PKG} sh -c 'umask 077; mkdir -p files || exit 1; "
                   "if test -e files/firmware.bin || test -L files/firmware.bin; then "
                   "echo Existing-firmware-refused >&2; exit 1; fi; "
                   "set -C; exec 3> files/firmware.bin; cat >&3; result=$?; exec 3>&-; exit $result'")
        try:
            shell(command, data=rom, reject_stderr=True)
            imported = adb(['exec-out', 'run-as', PKG, 'cat', 'files/firmware.bin'], reject_stderr=True)
            if len(imported) != len(rom) or hashlib.sha256(imported).hexdigest() != hashlib.sha256(rom).hexdigest():
                raise SystemExit('Firmware readback size/hash verification failed.')
        except (subprocess.CalledProcessError, SystemExit) as error:
            detail = (error.stderr or b'').decode(errors='replace').strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
            raise SystemExit('Firmware import failed; any existing or partial target was retained for review. '+detail)
        print('Imported local firmware; full readback size and SHA-256 verified. Existing saves were not replaced.')
except subprocess.CalledProcessError as error:
    detail = error.stderr.decode(errors='replace').strip() if isinstance(error.stderr, bytes) else (error.stderr or '')
    print('Device/install preflight failed: '+detail, file=sys.stderr)
    sys.exit(1)
