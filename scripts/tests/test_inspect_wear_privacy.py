"""APK privacy exclusions retain a single pinned public dependency resource."""
import base64
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('wear_apk', Path(__file__).parents[1] / 'inspect-wear-apk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

# Unmodified DebugProbesKt.bin from the official Maven Central JAR, version 1.8.1:
# https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar
# kotlinx.coroutines is Apache-2.0 licensed; this public fixture is not firmware.
OFFICIAL_PROBES = base64.b64decode(
    'yv66vgAAADQAPwEALGtvdGxpbi9jb3JvdXRpbmVzL2p2bS9pbnRlcm5hbC9EZWJ1Z1Byb2Jlc0t0BwABAQAQamF2YS9sYW5n'
    'L09iamVjdAcAAwEAFXByb2JlQ29yb3V0aW5lQ3JlYXRlZAEAQihMa290bGluL2Nvcm91dGluZXMvQ29udGludWF0aW9uOylM'
    'a290bGluL2Nvcm91dGluZXMvQ29udGludWF0aW9uOwEAYzxUOkxqYXZhL2xhbmcvT2JqZWN0Oz4oTGtvdGxpbi9jb3JvdXRp'
    'bmVzL0NvbnRpbnVhdGlvbjwtVFQ7PjspTGtvdGxpbi9jb3JvdXRpbmVzL0NvbnRpbnVhdGlvbjxUVDs+OwEAI0xvcmcvamV0'
    'YnJhaW5zL2Fubm90YXRpb25zL05vdE51bGw7AQAKY29tcGxldGlvbggACQEAHmtvdGxpbi9qdm0vaW50ZXJuYWwvSW50cmlu'
    'c2ljcwcACwEAFWNoZWNrTm90TnVsbFBhcmFtZXRlcgEAJyhMamF2YS9sYW5nL09iamVjdDtMamF2YS9sYW5nL1N0cmluZzsp'
    'VgwADQAOCgAMAA8BADFrb3RsaW54L2Nvcm91dGluZXMvZGVidWcvaW50ZXJuYWwvRGVidWdQcm9iZXNJbXBsBwARAQAISU5T'
    'VEFOQ0UBADNMa290bGlueC9jb3JvdXRpbmVzL2RlYnVnL2ludGVybmFsL0RlYnVnUHJvYmVzSW1wbDsMABMAFAkAEgAVAQAt'
    'cHJvYmVDb3JvdXRpbmVDcmVhdGVkJGtvdGxpbnhfY29yb3V0aW5lc19jb3JlDAAXAAYKABIAGAEAIExrb3RsaW4vY29yb3V0'
    'aW5lcy9Db250aW51YXRpb247AQAVcHJvYmVDb3JvdXRpbmVSZXN1bWVkAQAjKExrb3RsaW4vY29yb3V0aW5lcy9Db250aW51'
    'YXRpb247KVYBACYoTGtvdGxpbi9jb3JvdXRpbmVzL0NvbnRpbnVhdGlvbjwqPjspVgEABWZyYW1lCAAeAQAtcHJvYmVDb3Jv'
    'dXRpbmVSZXN1bWVkJGtvdGxpbnhfY29yb3V0aW5lc19jb3JlDAAgABwKABIAIQEAF3Byb2JlQ29yb3V0aW5lU3VzcGVuZGVk'
    'AQAvcHJvYmVDb3JvdXRpbmVTdXNwZW5kZWQka290bGlueF9jb3JvdXRpbmVzX2NvcmUMACQAHAoAEgAlAQARTGtvdGxpbi9N'
    'ZXRhZGF0YTsBAAJtdgMAAAABAwAAAAkDAAAAAAEAAWsDAAAAAgEAAnhpAwAAADABAAJkMQEAb8CAEgrAgAoCGAIKAggDCgIQ'
    'AgoCCAMaIhDAgBoIEgQSAkgCMAEiBAjAgBACMgwQAxoIEgQSAkgCMAFIwIAaFBAEGgIwBTIKEAYaBhICCAMwAUjAgBoUEAca'
    'AjAFMgoQBhoGEgIIAzABSMCAwqgGCAEAAmQyAQABVAEAAAEANWtvdGxpbngtY29yb3V0aW5lcy1pbnRlZ3JhdGlvbi10ZXN0'
    'aW5nX2RlYnVnQWdlbnRUZXN0AQAORGVidWdQcm9iZXMua3QBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQASTG9jYWxWYXJp'
    'YWJsZVRhYmxlAQAJU2lnbmF0dXJlAQAbUnVudGltZUludmlzaWJsZUFubm90YXRpb25zAQAkUnVudGltZUludmlzaWJsZVBh'
    'cmFtZXRlckFubm90YXRpb25zAQAKU291cmNlRmlsZQEAGVJ1bnRpbWVWaXNpYmxlQW5ub3RhdGlvbnMAMQACAAQAAAAAAAMA'
    'GQAFAAYABAA3AAAAOAACAAEAAAAOKhIKuAAQsgAWKrYAGbAAAAACADgAAAAGAAEABgAHADkAAAAMAAEAAAAOAAkAGgAAADoA'
    'AAACAAcAOwAAAAYAAQAIAAAAPAAAAAcBAAEACAAAABkAGwAcAAMANwAAADgAAgABAAAADioSH7gAELIAFiq2ACKxAAAAAgA4'
    'AAAABgABAAYACQA5AAAADAABAAAADgAeABoAAAA6AAAAAgAdADwAAAAHAQABAAgAAAAZACMAHAADADcAAAA4AAIAAQAAAA4q'
    'Eh+4ABCyABYqtgAmsQAAAAIAOAAAAAYAAQAGAAsAOQAAAAwAAQAAAA4AHgAaAAAAOgAAAAIAHQA8AAAABwEAAQAIAAAAAgA9'
    'AAAAAgA2AD4AAABGAAEAJwAFAChbAANJAClJACpJACsALEkALQAuSQAvADBbAAFzADEAMlsACXMABXMAGnMAM3MACXMAG3MA'
    'NHMAHnMAI3MANQ=='
)


class PrivateInputsContract(unittest.TestCase):
    def validate(self, entries):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            for name, content in entries:
                archive.writestr(name, content)
        data.seek(0)
        with zipfile.ZipFile(data) as archive:
            module.validate_private_inputs(archive)

    def test_official_exact_resource_is_accepted(self):
        self.validate([('DebugProbesKt.bin', OFFICIAL_PROBES)])

    def test_same_name_with_mutated_class_bytes_is_rejected(self):
        mutated = OFFICIAL_PROBES[:-1] + bytes([OFFICIAL_PROBES[-1] ^ 1])
        with self.assertRaisesRegex(AssertionError, 'DebugProbesKt.bin'):
            self.validate([('DebugProbesKt.bin', mutated)])

    def test_same_name_with_synthetic_private_payload_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'DebugProbesKt.bin'):
            self.validate([('DebugProbesKt.bin', b'synthetic private ROM')])

    def test_other_bin_names_and_paths_are_rejected(self):
        for name in ['other.bin', 'assets/DebugProbesKt.bin', 'debugprobeskt.bin']:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.validate([(name, OFFICIAL_PROBES)])

    def test_all_other_private_extensions_remain_rejected(self):
        for ext in ['rom', 'dump', 'state', 'sav', 'jks', 'keystore', 'ROM']:
            with self.subTest(ext=ext), self.assertRaisesRegex(AssertionError, 'private.'):
                self.validate([('private.' + ext, OFFICIAL_PROBES)])

    def test_public_resource_does_not_hide_another_private_entry(self):
        with self.assertRaisesRegex(AssertionError, 'pet.state'):
            self.validate([('DebugProbesKt.bin', OFFICIAL_PROBES), ('pet.state', b'synthetic save')])

    def test_additional_private_suffixes_and_backup_variants_are_rejected(self):
        for suffix in ['pcm', 'pem', 'key', 'p12', 'pfx', 'p8', 'state.old', 'sav.backup']:
            for name in ['private.' + suffix, 'assets/nested/Private.' + suffix.upper()]:
                with self.subTest(name=name), self.assertRaises(AssertionError):
                    self.validate([(name, b'synthetic private input')])

    def test_ordinary_entries_are_accepted(self):
        self.validate([('classes.dex', b'synthetic dex'), ('AndroidManifest.xml', b'synthetic manifest')])


if __name__ == '__main__':
    unittest.main()
