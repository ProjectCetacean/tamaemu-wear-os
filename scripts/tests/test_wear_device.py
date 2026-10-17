"""Exercise the real helper through an isolated adb/signing process boundary.

No device writes occur. The fake process reports the watch's observed absent
`pm path` status, transport failures and denied run-as access.
"""
import contextlib
import io
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HELPER = Path(__file__).resolve().parents[1] / 'wear-device.py'
PKG = 'io.github.projectcetacean.tamaemu'

class DeviceHelperTest(unittest.TestCase):
    def execute(self, mode):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            apk = folder/'app.apk'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('lib/armeabi-v7a/libtamaemu.so',b'test library')
            calls = []
            def process(command, *, input=None, stdout=None, stderr=None, check=False, **kwargs):
                calls.append(command)
                rc, out, err = 0, b'', b''
                if str(command[0]) == sys.executable:
                    # Certificate cryptography is tested separately; this test
                    # controls only the process boundary, without private keys.
                    self.assertEqual(command[2], 'verify')
                elif command[3:5] == ['shell','-T']:
                    query=command[5]
                    props={'ro.product.model':'Pixel Watch 5','ro.product.manufacturer':'Google',
                           'ro.build.version.sdk':'37','ro.product.cpu.abilist':'armeabi-v7a,armeabi'}
                    if query.startswith('getprop '):out=props[query.split()[1]].encode()
                    elif query=='pm list features':out=b'feature:android.hardware.type.watch\n'
                    elif query=='wm size':out=b'Physical size: 480x480\n'
                    elif query==f'pm path {PKG}':
                        # Observed Android behavior: absent package is exit1
                        # without diagnostic output, unlike list packages.
                        if mode in ('absent','package-disconnect','package-denied','package-denied-zero','similar'):rc=1
                        else:out=b'package:/data/app/tamaemu/base.apk\n'
                    elif query==f'pm list packages {PKG}':
                        if mode=='package-disconnect':rc,err=1,b'error: device offline'
                        elif mode=='package-denied':rc,err=1,b'Security exception: package access denied'
                        elif mode=='package-denied-zero':err=b'Security exception: package access denied'
                        elif mode=='similar':out=('package:'+PKG+'.other\n').encode()
                        elif mode!='absent':out=('package:'+PKG+'\n').encode()
                    elif query.startswith('run-as '):
                        if mode=='save-denied':rc,err=1,b'run-as: permission denied'
                        elif mode=='save-denied-zero':out,err=b'no\n',b'run-as: permission denied'
                        elif mode=='save-present':out=b'yes\n'
                        else:out=b'no\n'
                    else:self.fail('Unexpected shell command: '+query)
                elif command[3:5]==['exec-out','run-as']:
                    out=b'last durable pet state'
                elif command[3:5]==['install','-r']:
                    if mode=='save-present':
                        self.assertEqual([p.read_bytes() for p in (folder/'backup').glob('*.state')], [b'last durable pet state'])
                    out=b'Success\n'
                else:self.fail('Unexpected process: '+str(command))
                result=subprocess.CompletedProcess(command,rc,out,err)
                if check:result.check_returncode()
                return result
            argv=[str(HELPER),'--serial','test-watch','--backup-dir',str(folder/'backup'),'install',str(apk)]
            output=io.StringIO()
            status=0
            with patch('subprocess.run',side_effect=process),patch('shutil.which',return_value='/isolated/adb'),patch.object(sys,'argv',argv),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
                try:runpy.run_path(str(HELPER),run_name='__main__')
                except SystemExit as error:
                    status=error.code or 0
                    if isinstance(error.code,str): print(error.code,file=sys.stderr)
            backups=list((folder/'backup').glob('*.state'))
            backup_bytes=[path.read_bytes() for path in backups]
            installed=any(command[3:5]==['install','-r'] for command in calls)
            return status,installed,backup_bytes,output.getvalue()

    def test_absent_app_proceeds_to_install(self):
        status,installed,backups,output=self.execute('absent')
        self.assertEqual(status,0,output)
        self.assertTrue(installed)
        self.assertEqual(backups,[])

    def test_similarly_named_package_does_not_mean_app_installed(self):
        status,installed,backups,output=self.execute('similar')
        self.assertEqual(status,0,output)
        self.assertTrue(installed)
        self.assertEqual(backups,[])

    def test_package_probe_failures_never_become_absence(self):
        for mode in ('package-disconnect','package-denied','package-denied-zero'):
            with self.subTest(mode=mode):
                status,installed,_,output=self.execute(mode)
                self.assertNotEqual(status,0,output)
                self.assertFalse(installed,output)

    def test_denied_save_never_becomes_absence(self):
        for mode in ('save-denied','save-denied-zero'):
            with self.subTest(mode=mode):
                status,installed,_,output=self.execute(mode)
                self.assertNotEqual(status,0,output)
                self.assertFalse(installed,output)

    def test_existing_save_backed_up_before_install(self):
        status,installed,backups,output=self.execute('save-present')
        self.assertEqual(status,0,output)
        self.assertTrue(installed)
        self.assertEqual(backups,[b'last durable pet state'])


class RomImportTest(unittest.TestCase):
    """Real local shell/file semantics, with Android's denied hardlinks."""
    def execute_import(self, mode):
        import hashlib
        import os
        import shlex
        synthetic = b'CI-only synthetic firmware\0' * (8388608 // 26 + 1)
        synthetic = (synthetic + bytes(8388608))[:8388608]
        expected_digest = hashlib.sha256(synthetic).hexdigest()
        real_run = subprocess.run
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder); app=folder/'app'; (app/'files').mkdir(parents=True)
            source=folder/'synthetic.bin'; source.write_bytes(synthetic[:-1] if mode=='wrong-size' else synthetic)
            target=app/'files/firmware.bin'
            if mode=='existing':target.write_bytes(b'preexisting owner firmware')
            tools=folder/'tools';tools.mkdir()
            denied=tools/'ln';denied.write_text('#!/bin/sh\necho "ln: Permission denied" >&2\nexit 1\n');denied.chmod(0o755)
            if mode=='partial':
                cat=tools/'cat';cat.write_text('#!/usr/bin/env python3\nimport sys\ndata=sys.stdin.buffer.read()\nsys.stdout.buffer.write(data[:4096])\nsys.exit(1)\n');cat.chmod(0o755)
            env=dict(os.environ);env['PATH']=str(tools)+os.pathsep+env['PATH']
            def process(command, *, input=None, stdout=None, stderr=None, check=False, **kwargs):
                if mode in ('missing-digest', 'malformed-digest', 'wrong-digest', 'wrong-size'):
                    self.fail('Invalid input must be refused before any adb call')
                rc,out,err=0,b'',b''
                if command[3:5]==['shell','-T']:
                    query=command[5]
                    props={'ro.product.model':'Pixel Watch 5','ro.product.manufacturer':'Google',
                           'ro.build.version.sdk':'37','ro.product.cpu.abilist':'armeabi-v7a,armeabi'}
                    if query.startswith('getprop '):out=props[query.split()[1]].encode()
                    elif query=='pm list features':out=b'feature:android.hardware.type.watch\n'
                    elif query=='wm size':out=b'Physical size: 480x480\n'
                    elif query.startswith('pm list packages '):out=('package:'+PKG+'\n').encode()
                    elif query=='ps -A -o NAME':out=b'NAME\n'+(PKG.encode()+b'\n' if mode=='running' else b'')
                    elif query.startswith('run-as '):
                        parts=shlex.split(query)
                        self.assertEqual(parts[:4],['run-as',PKG,'sh','-c'])
                        result=real_run(['/bin/sh','-c',parts[4]],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,cwd=app,env=env)
                        rc,out,err=result.returncode,result.stdout,result.stderr
                    else:self.fail('Unexpected import shell: '+query)
                elif command[3:]==['exec-out','run-as',PKG,'cat','files/firmware.bin']:
                    if mode=='verify-transport':rc,err=1,b'error: device offline'
                    else:out=target.read_bytes() if mode!='hash-mismatch' else b'wrong transfer'
                else:self.fail('Unexpected import command: '+str(command))
                result=subprocess.CompletedProcess(command,rc,out,err)
                if check:result.check_returncode()
                return result
            argv=[str(HELPER),'--serial','test-watch','--backup-dir',str(folder/'backup'),'import-rom',str(source)]
            if mode != 'missing-digest':
                supplied = 'invalid-digest' if mode == 'malformed-digest' else (
                    '0' * 64 if mode == 'wrong-digest' else expected_digest.upper())
                argv += ['--expected-rom-sha256', supplied]
            status=0;output=io.StringIO()
            with patch('subprocess.run',side_effect=process),patch('shutil.which',return_value='/isolated/adb'),patch.object(sys,'argv',argv),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
                try:runpy.run_path(str(HELPER),run_name='__main__')
                except SystemExit as error:
                    status=error.code or 0
                    if isinstance(error.code,str): print(error.code,file=sys.stderr)
            self.assertNotIn(expected_digest.lower(), output.getvalue().lower())
            return status,target.read_bytes() if target.exists() else None,synthetic,output.getvalue()

    def test_hardlink_denied_device_can_import_exclusively(self):
        status,actual,expected,output=self.execute_import('hardlink-denied')
        self.assertEqual(status,0,output)
        self.assertEqual(actual,expected)
        self.assertIn('verified',output.lower())

    def test_import_requires_explicit_well_formed_expected_digest(self):
        for mode in ('missing-digest', 'malformed-digest'):
            with self.subTest(mode=mode):
                status, actual, _, output = self.execute_import(mode)
                self.assertEqual(status, 2, output)
                self.assertIsNone(actual)
                self.assertIn('--expected-rom-sha256', output)
                self.assertNotIn('invalid-digest', output)

    def test_wrong_digest_or_size_is_refused_before_adb(self):
        for mode in ('wrong-digest', 'wrong-size'):
            with self.subTest(mode=mode):
                status, actual, _, output = self.execute_import(mode)
                self.assertNotEqual(status, 0, output)
                self.assertIsNone(actual)
                self.assertIn('refused', output)
                self.assertNotIn('0' * 64, output)

    def test_existing_firmware_is_never_replaced(self):
        status,actual,_,output=self.execute_import('existing')
        self.assertNotEqual(status,0,output)
        self.assertEqual(actual,b'preexisting owner firmware')

    def test_partial_transfer_preserves_evidence_and_fails(self):
        status,actual,expected,output=self.execute_import('partial')
        self.assertNotEqual(status,0,output)
        self.assertEqual(actual,expected[:4096])
        self.assertIn('retained',output.lower())

    def test_readback_hash_or_transport_failure_never_reports_success(self):
        for mode in ('hash-mismatch','verify-transport'):
            with self.subTest(mode=mode):
                status,actual,expected,output=self.execute_import(mode)
                self.assertNotEqual(status,0,output)
                self.assertEqual(actual,expected)
                self.assertIn('retained',output.lower())

    def test_running_app_refuses_import_before_file_creation(self):
        status,actual,_,output=self.execute_import('running')
        self.assertNotEqual(status,0,output)
        self.assertIsNone(actual)
        self.assertIn('stop',output.lower())

if __name__=='__main__':unittest.main()
