"""Resolve Tile PREVIEW by manifest ID and resource table, including shortened paths."""
import importlib.util
import io
from pathlib import Path
import struct
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('wear_apk', Path(__file__).parents[1] / 'inspect-wear-apk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
PACKAGE = 'io.github.projectcetacean.tamaemu'
MANIFEST = 'E: manifest\n  E: application\n    E: service\n      A: android:name="io.github.projectcetacean.tamaemu.PetTileService"\n      E: meta-data\n        A: android:name="androidx.wear.tiles.PREVIEW"\n        A: android:resource(0x01010025)=@0x7f040015\n'
TABLE = '      spec resource 0x7f040015 io.github.projectcetacean.tamaemu:drawable/pet_tile_preview: flags=0x00000000\n      config (default):\n        resource 0x7f040015 io.github.projectcetacean.tamaemu:drawable/pet_tile_preview: t=0x03 d=0x00000012 (s=0x0008 r=0x00)\n          (string8) "res/iZ.xml"\n'
BINARY_XML = struct.pack('<HHI', 3, 8, 8)


class TilePreviewContract(unittest.TestCase):
    def resolve(self, manifest=MANIFEST, table=TABLE, entries=None, package=PACKAGE):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            for name, content in (entries if entries is not None else [('res/iZ.xml', BINARY_XML)]):
                archive.writestr(name, content)
        data.seek(0)
        with zipfile.ZipFile(data) as archive:
            return module.validate_tile_preview(manifest, table, package, archive)

    def test_transport_probe_preview_resolves_only_for_exact_qa_package(self):
        probe = MANIFEST.replace('PetTileService', 'TileTransportProbeService')
        qa = PACKAGE + '.qoltest'
        self.assertEqual(['res/iZ.xml'], self.resolve(manifest=probe, table=TABLE.replace(PACKAGE, qa), package=qa))
        with self.assertRaisesRegex(AssertionError, 'Tile|probe'):
            self.resolve(manifest=probe)

    def test_probe_cannot_substitute_launcher_or_other_package_preview(self):
        probe = MANIFEST.replace('PetTileService', 'TileTransportProbeService')
        qa = PACKAGE + '.qoltest'
        for table in [TABLE.replace(PACKAGE, qa).replace('drawable/pet_tile_preview', 'drawable/ic_launcher'), TABLE]:
            with self.subTest(table=table), self.assertRaisesRegex(AssertionError, 'authored drawable'):
                self.resolve(manifest=probe, table=table, package=qa)

    def test_release_shortened_path_resolves(self):
        self.assertEqual(['res/iZ.xml'], self.resolve())

    def test_debug_authored_path_resolves(self):
        path = 'res/drawable/pet_tile_preview.xml'
        self.assertEqual([path], self.resolve(table=TABLE.replace('res/iZ.xml', path), entries=[(path, BINARY_XML)]))

    def test_wrong_metadata_id_rejected_even_if_authored_file_exists(self):
        with self.assertRaisesRegex(AssertionError, 'resource table'):
            self.resolve(manifest=MANIFEST.replace('@0x7f040015', '@0x7f040014'))

    def test_wrong_resource_name_or_package_rejected(self):
        for table in [TABLE.replace('drawable/pet_tile_preview', 'drawable/other'), TABLE.replace(PACKAGE, 'other.package')]:
            with self.subTest(table=table), self.assertRaisesRegex(AssertionError, 'authored drawable'):
                self.resolve(table=table)

    def test_metadata_on_other_service_cannot_satisfy_tile(self):
        with self.assertRaisesRegex(AssertionError, 'Tile service'):
            self.resolve(manifest=MANIFEST.replace('PetTileService', 'AnotherService'))

    def test_missing_resolved_path_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'missing or ambiguous'):
            self.resolve(entries=[('res/unrelated.xml', BINARY_XML)])

    def test_plain_xml_and_invalid_binary_xml_rejected(self):
        for payload in [b'<vector/>', struct.pack('<HHI', 3, 8, 100), struct.pack('<HHI', 2, 8, 8)]:
            with self.subTest(payload=payload), self.assertRaisesRegex(AssertionError, 'binary XML'):
                self.resolve(entries=[('res/iZ.xml', payload)])

    def test_non_file_resource_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'file resource'):
            self.resolve(table=TABLE.replace('t=0x03', 't=0x01'))


if __name__ == '__main__':
    unittest.main()
