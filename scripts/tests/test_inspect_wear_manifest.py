"""Manifest component ownership tests, independent of SDK/device/firmware."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('wear_apk', Path(__file__).parents[1] / 'inspect-wear-apk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
MANIFEST = '''E: manifest (line=1)
  E: uses-permission (line=1)
    A: android:name(0x01010003)="android.permission.RECEIVE_BOOT_COMPLETED"
  E: application (line=2)
    A: android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0xffffffff
    E: meta-data (line=3)
      A: android:name(0x01010003)="com.google.android.wearable.standalone"
      A: android:value(0x01010024)=(type 0x12)0xffffffff
    E: activity (line=4)
      A: android:name(0x01010003)="io.github.projectcetacean.tamaemu.MainActivity"
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
      A: android:launchMode(0x0101001d)=(type 0x10)0x2
    E: service (line=5)
      A: android:name(0x01010003)="io.github.projectcetacean.tamaemu.PetTileService"
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
      A: android:permission(0x01010006)="com.google.android.wearable.permission.BIND_TILE_PROVIDER"
      E: intent-filter (line=6)
        E: action (line=7)
          A: android:name(0x01010003)="androidx.wear.tiles.action.BIND_TILE_PROVIDER"
      E: meta-data (line=8)
        A: android:name(0x01010003)="androidx.wear.tiles.PREVIEW"
        A: android:resource(0x01010025)=@0x7f010003
    E: service (line=9)
      A: android:name(0x01010003)="io.github.projectcetacean.tamaemu.PetAgingJobService"
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
      A: android:permission(0x01010006)="android.permission.BIND_JOB_SERVICE"
'''


PROBE = MANIFEST.replace('PetTileService', 'TileTransportProbeService').replace(
    '  E: application (line=2)\n',
    '  E: application (line=2)\n'
    '    A: android:name="android.app.Application"\n'
    '    A: android:debuggable=(type 0x12)0xffffffff\n') + '''    E: activity
      A: android:name="io.github.projectcetacean.tamaemu.TileTransportProbeActivity"
      A: android:exported=(type 0x12)0xffffffff
    E: service
      A: android:name="io.github.projectcetacean.tamaemu.TileTransportProbeJob"
      A: android:exported=(type 0x12)0xffffffff
      A: android:permission="android.permission.BIND_JOB_SERVICE"
    E: receiver
      A: android:name="io.github.projectcetacean.tamaemu.TileTransportProbeReceiver"
      A: android:exported=(type 0x12)0xffffffff
      A: android:permission="android.permission.DUMP"
'''


AUDIOQA = MANIFEST.replace('  E: application (line=2)\n',
    '  E: application (line=2)\n'
    '    A: android:name="io.github.projectcetacean.tamaemu.TamaemuApplication"\n'
    '    A: android:debuggable=(type 0x12)0xffffffff\n') + '''    E: provider
      A: android:name="io.github.projectcetacean.tamaemu.AudioQaInputProvider"
      A: android:authorities="io.github.projectcetacean.tamaemu.qoltest.audioqa"
      A: android:exported=(type 0x12)0x0
    E: receiver
      A: android:name="io.github.projectcetacean.tamaemu.AudioQaInputReceiver"
      A: android:exported=(type 0x12)0xffffffff
      A: android:permission="android.permission.DUMP"
'''


class ManifestContract(unittest.TestCase):

    def validate_version(self, badging, package):
        try:
            return module.validate_package_version(badging, package)
        except AttributeError:
            self.fail('Inspector version gate is not independently callable')

    def test_current_release_version_is_accepted_for_production_and_qa(self):
        for package in ['io.github.projectcetacean.tamaemu', module.QA_PACKAGE]:
            with self.subTest(package=package):
                self.validate_version(f"package: name='{package}' versionCode='11' versionName='0.2.0-wear.11'\n", package)

    def test_stale_and_mixed_release_versions_are_rejected(self):
        package='io.github.projectcetacean.tamaemu'
        for code,name in [('10','0.2.0-wear.10'),('10','0.2.0-wear.11'),
                          ('11','0.2.0-wear.10'),('110','0.2.0-wear.11'),('11','0.2.0-wear.110')]:
            with self.subTest(code=code,name=name), self.assertRaisesRegex(AssertionError,'version'):
                self.validate_version(f"package: name='{package}' versionCode='{code}' versionName='{name}'\n",package)

    def test_current_version_does_not_admit_the_wrong_package(self):
        with self.assertRaisesRegex(AssertionError,'package'):
            self.validate_version("package: name='io.github.projectcetacean.tamaemu.qoltest' versionCode='11' versionName='0.2.0-wear.11'\n",
                                  'io.github.projectcetacean.tamaemu')

    def test_required_tile_probe_rejects_valid_regular_qa(self):
        with self.assertRaisesRegex(AssertionError, 'required QA artifact kind'):
            module.validate_required_kind(module.QA_PACKAGE, module.validate_manifest(AUDIOQA, module.QA_PACKAGE), 'tile-probe')

    def test_required_audioqa_rejects_valid_tile_probe(self):
        with self.assertRaisesRegex(AssertionError, 'required QA artifact kind'):
            module.validate_required_kind(module.QA_PACKAGE, module.validate_manifest(PROBE, module.QA_PACKAGE), 'audioqa')

    def test_required_qa_kinds_match_actual_manifest(self):
        for manifest, kind in [(AUDIOQA, 'audioqa'), (PROBE, 'tile-probe')]:
            module.validate_required_kind(module.QA_PACKAGE, module.validate_manifest(manifest, module.QA_PACKAGE), kind)

    def test_required_qa_kind_rejects_production(self):
        for kind in ['audioqa', 'tile-probe']:
            with self.subTest(kind=kind), self.assertRaisesRegex(AssertionError, 'exact test package'):
                module.validate_required_kind('io.github.projectcetacean.tamaemu', False, kind)


    def test_regular_qa_remains_allowed(self):
        module.validate_manifest(AUDIOQA, 'io.github.projectcetacean.tamaemu.qoltest')

    def test_audioqa_regular_qa_requires_exact_components_and_application(self):
        for invalid in [MANIFEST, AUDIOQA.replace('AudioQaInputProvider', 'MissingProvider'),
                        AUDIOQA.replace('TamaemuApplication', 'android.app.Application'),
                        AUDIOQA.replace('android:debuggable=(type 0x12)0xffffffff', 'android:debuggable=(type 0x12)0x0'),
                        AUDIOQA.replace('qoltest.audioqa', 'audioqa'),
                        AUDIOQA.replace('android:exported=(type 0x12)0x0', 'android:exported=(type 0x12)0xffffffff'),
                        AUDIOQA.replace('android:exported=(type 0x12)0x0', 'android:exported=(type 0x12)0x01'),
                        AUDIOQA.replace('      A: android:exported=(type 0x12)0x0\n', ''),
                        AUDIOQA.replace('android.permission.DUMP', 'android.permission.BIND_JOB_SERVICE'),
                        AUDIOQA + '      E: intent-filter\n',
                        AUDIOQA.replace('AudioQaInputReceiver"\n      A: android:exported=(type 0x12)0xffffffff', 'AudioQaInputReceiver"\n      A: android:exported=(type 0x12)0x0')]:
            with self.subTest(invalid=invalid), self.assertRaisesRegex(AssertionError, 'audioqa'):
                module.validate_manifest(invalid, module.QA_PACKAGE)

    def test_audioqa_components_excluded_from_production_and_probe(self):
        extra=AUDIOQA[AUDIOQA.index('    E: provider'):]
        for manifest,package in [(MANIFEST+extra, 'io.github.projectcetacean.tamaemu'), (PROBE+extra,module.QA_PACKAGE),
                                 (AUDIOQA,module.QA_PACKAGE+'.extra')]:
            with self.subTest(package=package), self.assertRaisesRegex(AssertionError, 'audioqa'):
                module.validate_manifest(manifest,package)

    def test_each_audioqa_component_spelling_is_excluded_from_production(self):
        for tag,name in [('provider','AudioQaInputProvider'),('receiver','AudioQaInputReceiver')]:
            for spelling in [name,'.'+name,'io.github.projectcetacean.tamaemu.'+name]:
                invalid=MANIFEST+f'    E: {tag}\n      A: android:name="{spelling}"\n'
                with self.subTest(spelling=spelling), self.assertRaisesRegex(AssertionError,'audioqa'):
                    module.validate_manifest(invalid,'io.github.projectcetacean.tamaemu')

    def test_audioqa_dex_contract_complete_only_for_regular_qa(self):
        import io, zipfile
        for suffixes in [[], ['Provider'], ['Receiver'], ['Driver'], ['Provider','Receiver','Driver']]:
            data=io.BytesIO()
            with zipfile.ZipFile(data,'w') as z:
                z.writestr('classes2.dex', b''.join(('Lio/github/projectcetacean/tamaemu/AudioQaInput'+s+';').encode() for s in suffixes))
            data.seek(0)
            with zipfile.ZipFile(data) as z:
                if len(suffixes)==3:
                    module.validate_audioqa_classes(z,module.QA_PACKAGE,True)
                else:
                    with self.assertRaisesRegex(AssertionError,'audioqa'):
                        module.validate_audioqa_classes(z,module.QA_PACKAGE,True)
                for package in ['io.github.projectcetacean.tamaemu',module.QA_PACKAGE]:
                    if suffixes:
                        with self.assertRaisesRegex(AssertionError,'audioqa'):
                            module.validate_audioqa_classes(z,package,False)
                    else:
                        module.validate_audioqa_classes(z,package,False)

    def test_probe_requires_plain_application_and_debuggable(self):
        for invalid in [PROBE.replace('android.app.Application', '.TamaemuApplication'),
                        PROBE.replace('android:debuggable=(type 0x12)0xffffffff', 'android:debuggable=(type 0x12)0x0'),
                        PROBE.replace('    A: android:debuggable=(type 0x12)0xffffffff\n', '')]:
            with self.subTest(invalid=invalid), self.assertRaisesRegex(AssertionError, 'probe'):
                module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu.qoltest')

    def test_each_probe_component_rejected_in_production_even_with_pet_tile(self):
        for tag, name in [('receiver', 'TileTransportProbeReceiver'), ('activity', 'TileTransportProbeActivity'),
                          ('service', 'TileTransportProbeJob'), ('service', 'TileTransportProbeService')]:
            for spelling in ['.' + name, 'io.github.projectcetacean.tamaemu.' + name]:
                invalid = MANIFEST + f'    E: {tag}\n      A: android:name="{spelling}"\n'
                with self.subTest(name=spelling), self.assertRaisesRegex(AssertionError, 'probe'):
                    module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_probe_receiver_requires_dump_export_and_explicit_only(self):
        for invalid in [PROBE.replace('android.permission.DUMP', 'android.permission.INTERNET'),
                        PROBE.replace('android.permission.DUMP', 'android.permission.BIND_JOB_SERVICE'),
                        PROBE + '      E: intent-filter\n',
                        PROBE.replace('android:name="io.github.projectcetacean.tamaemu.TileTransportProbeReceiver"\n      A: android:exported=(type 0x12)0xffffffff', 'android:name="io.github.projectcetacean.tamaemu.TileTransportProbeReceiver"\n      A: android:exported=(type 0x12)0x0')]:
            with self.subTest(invalid=invalid), self.assertRaises(AssertionError):
                module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu.qoltest')

    def test_probe_rejects_qa_lookalike_package(self):
        with self.assertRaisesRegex(AssertionError, 'probe'):
            module.validate_manifest(PROBE, 'io.github.projectcetacean.tamaemu.qoltest.extra')

    def test_probe_components_must_be_complete(self):
        for name in ['TileTransportProbeReceiver', 'TileTransportProbeJob', 'TileTransportProbeActivity']:
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, 'probe'):
                module.validate_manifest(PROBE.replace(name, 'MissingComponent'), 'io.github.projectcetacean.tamaemu.qoltest')

    def test_probe_classes_are_excluded_from_production_and_regular_qa(self):
        import io
        import zipfile
        # DEX string-table descriptors are independent fixtures, not Gradle text.
        names = ['Activity', 'Job', 'Receiver', 'Service', 'State']
        for suffix in names:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('classes2.dex', ('Lio/github/projectcetacean/tamaemu/TileTransportProbe' + suffix + ';').encode())
            data.seek(0)
            with zipfile.ZipFile(data) as archive:
                for package in ['io.github.projectcetacean.tamaemu', 'io.github.projectcetacean.tamaemu.qoltest']:
                    with self.subTest(suffix=suffix, package=package), self.assertRaisesRegex(AssertionError, 'probe'):
                        module.validate_probe_classes(archive, package, False)

    def test_probe_apk_requires_all_five_compiled_classes(self):
        import io
        import zipfile
        names = ['Activity', 'Job', 'Receiver', 'Service', 'State']
        for missing in [None] + names:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('classes.dex', b''.join(('Lio/github/projectcetacean/tamaemu/TileTransportProbe' + n + ';').encode()
                                                       for n in names if n != missing))
            data.seek(0)
            with zipfile.ZipFile(data) as archive:
                if missing is None:
                    module.validate_probe_classes(archive, 'io.github.projectcetacean.tamaemu.qoltest', True)
                    with self.assertRaisesRegex(AssertionError, 'probe'):
                        module.validate_probe_classes(archive, 'io.github.projectcetacean.tamaemu', True)
                else:
                    with self.subTest(missing=missing), self.assertRaisesRegex(AssertionError, 'probe'):
                        module.validate_probe_classes(archive, 'io.github.projectcetacean.tamaemu.qoltest', True)

    def test_transport_probe_allowed_only_in_exact_qa_package(self):
        probe = PROBE
        module.validate_manifest(probe, 'io.github.projectcetacean.tamaemu.qoltest')
        with self.assertRaisesRegex(AssertionError, 'Tile|probe'):
            module.validate_manifest(probe, 'io.github.projectcetacean.tamaemu')

    def test_transport_probe_still_requires_binding_permission(self):
        probe = PROBE.replace(
            'com.google.android.wearable.permission.BIND_TILE_PROVIDER', 'android.permission.BIND_JOB_SERVICE')
        with self.assertRaisesRegex(AssertionError, 'Tile binding permission'):
            module.validate_manifest(probe, 'io.github.projectcetacean.tamaemu.qoltest')

    def test_valid_tile_declaration(self):
        module.validate_manifest(MANIFEST, 'io.github.projectcetacean.tamaemu')

    def test_application_back_opt_in_must_be_present(self):
        invalid = MANIFEST.replace('    A: android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0xffffffff\n', '')
        with self.assertRaisesRegex(AssertionError, 'Application.*Back'):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_application_back_opt_in_cannot_be_false(self):
        invalid = MANIFEST.replace('android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0xffffffff',
                                   'android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0x0')
        with self.assertRaisesRegex(AssertionError, 'Application.*Back'):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_application_cannot_borrow_activity_back_opt_in(self):
        invalid = MANIFEST.replace('    A: android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0xffffffff\n', '')
        invalid = invalid.replace('    E: activity (line=4)\n', '    E: activity (line=4)\n      A: android:enableOnBackInvokedCallback(0x0101066c)=(type 0x12)0xffffffff\n')
        with self.assertRaisesRegex(AssertionError, 'Application.*Back'):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_exported_service_cannot_borrow_binding_permission_from_activity(self):
        # Put permission on the activity rather than its sibling service.
        invalid = MANIFEST.replace('      A: android:permission(0x01010006)="com.google.android.wearable.permission.BIND_TILE_PROVIDER"\n', '')
        invalid = invalid.replace('    E: service', '      A: android:permission(0x01010006)="com.google.android.wearable.permission.BIND_TILE_PROVIDER"\n    E: service')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_empty_task_affinity_rejected(self):
        invalid = MANIFEST.replace('    E: service', '      A: android:taskAffinity(0x01010012)=""\n    E: service')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_non_exported_tile_rejected(self):
        invalid = MANIFEST.replace('      A: android:exported(0x01010010)=(type 0x12)0xffffffff', '      A: android:exported(0x01010010)=(type 0x12)0x0')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_persisted_aging_needs_boot_permission(self):
        invalid = MANIFEST.replace('  E: uses-permission (line=1)\n    A: android:name(0x01010003)="android.permission.RECEIVE_BOOT_COMPLETED"\n', '')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_aging_service_cannot_borrow_tile_permission(self):
        invalid = MANIFEST.replace('"android.permission.BIND_JOB_SERVICE"', '"com.google.android.wearable.permission.BIND_TILE_PROVIDER"')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_non_exported_aging_service_rejected(self):
        prefix, service = MANIFEST.split('    E: service (line=9)')
        invalid = prefix + '    E: service (line=9)' + service.replace('(type 0x12)0xffffffff', '(type 0x12)0x0')
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')

    def test_missing_aging_service_rejected(self):
        invalid = MANIFEST.split('    E: service (line=9)')[0]
        with self.assertRaises(AssertionError):
            module.validate_manifest(invalid, 'io.github.projectcetacean.tamaemu')
