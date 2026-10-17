"""Exercise publication checks against real temporary Git repositories and exports."""
from pathlib import Path
import base64
import hashlib
import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
import zlib


CHECKER = Path(__file__).resolve().parents[1] / 'check-public-hygiene.py'
SOURCE_ROOT = CHECKER.parent.parent

# Unmodified public maragotchi/tamaemu ICO blob, retaining the upstream project license.
# https://api.github.com/repos/maragotchi/tamaemu/git/blobs/b9e39d850308fd8ef3ce7311a341e0c735df3aa1
# Verified against the official GitHub blob API; compressed only for test storage.
HISTORICAL_TAMAPS = zlib.decompress(base64.b64decode(
    'eNqVVwVYVF23PjPE0DN0x4A0Soh0DC3SAhIiHYJ0I90pEkpINyKpgKKkkhKCSsgQEjLAiIAgQ3PB+O597of3f+5+1pnZ6+xY717v'
    'u86cAQAQAAFgMOC0sQBjIADwPu3R0PzyoWAAUD+9WFh++d9P+zexAUBA4JdPigUAGhAAQCB++RKnvikBAISE/PJ/nPoZxMDvxgKk'
    '4QDAPVIAiNXWVCEmoDudCRCrXlW8fvp9hgCGh3v6ybx1YgEAoBxVRTk932wv3bprb7QppKvg4VQF2nAxioiQwnDWeb1HL69Kjjc0'
    'ekBWIFcf4crmey4vJkmsaLvyhQknE5EQUPKU43mlkHTxKMLwlZ2vXUvhgFyCDr3CTGFmmvjDEBQlKTQ1+0ZKTOMhaYtLrK2v1VUT'
    'fJ7N80a9yEr+UouldNz52VvTv/W9UKfSNL58kJx7nKSQLBtCao166BuOBLtbQhL/E176SnMf2D5NIXMAL0DJEf/ykxwfqULFofZo'
    'VhINzwzZO7uUx6nMel8hRb0LLR9j0BKdclY0d8OubPiGGWQU+FPT6dD4Qi96xuW8UIQ0b4BajAYE6h8zHH1yac66qSiEIeP67Ok9'
    '9iM7dPRINHZkUgNJAs4rN7QAv8zG7aRHMq5q1nUG4zPRQ+630fkxRtGkrfWsNXzipvbivL3mckU9hK/0G8VXKXqR2IgUL+dEMOcI'
    'sZX154z+cSnODEQstkYBc2p2mvWrzypaB6HwMoWqkm93PZ7hboeN2jdrDTo98UyuXw7XmLBcWtwfrsjKjob6YkF27HRC01bny2Ms'
    '355g9Xtnyqjgufmc8aqqpKlYLW8edg6rZzqi+cnqZx/p3VNWx36x2nBD8OobbVggd0ef3PMEAkYCAltXvnpnWkYC7zvKK9oMjBfs'
    '5aMTScgYFsYK5HtYqSSsPWOGOzoKxGxgXlyaHb1HIleShfcafshdCz758u24xQfDG0LokNTqQhjvTeUMNJRWOKG3NUZydacxg1KO'
    'W9MZ1c6PGEAOgwVtT1696BbayJWqx4xOxLobshWHRWwlD5KpEyNKYm2DOXiCSXiU70i/KxaOo+WmZdAGpwcF7zvLydcdl0tSTXBj'
    'X7/aExwZDakknI7C7nGnhbKSuo8mhVtOx9PqcKBDrjInepmLpTHw6vpaMzEoyEH7dsBuw3TY+7Yrif1GKVswCxjGG2unL1lOOhkL'
    '+9akvwUdSV4fGrt5eTBbWSuJv0XnWyWr/Hsu7YaLvcaQJyyOL42YtVFKzDLZSvtWOjWVU31CmqhCFr/3Jm+JEOG8AYLEi/V8ifC5'
    'pKp4r3T+dgl49ern9+tFb1drLuxoERQ4CGz71H7t5Uga4C8mINe6FusYutr/pstF3S+jQjA1/KlY1J0bTQ1a7iUjbwY7hmflkM2o'
    'VvLl1/br40voBHLsKKeg/MLAwi9emUQrz311cZXvbHT1stP3pBiZmnw6DFFWnZQlfMn6zPEYfxZn/dkV57tS0v9BDmePEZafcvC4'
    'e3cbAMBNv+TQld52B4mg6GkTilY1gVa5yXlyMQpHlGoTr9kmqVC//AGttdOivfwpcXlYMHWl3JFxH+Jb33FT4kWSPK5Cx3B+RzGT'
    'Iq6Vtl+ZjJJ38e6Q1xRUahvtuX7wTROTS3RbZx1Qvlnl5AvO9hprEPJkEDW+L1cahhoqriK3LBdzqc+9ipJpVdXt6KS3R0fQ8Azd'
    'qap7uiAcZh+Se8diIjBQRZFEMt/wUlhYuXDb81DljACOp2ZPDHKg9yvSPO4BBt+HfLVTlLWjkYhDr7TGqxVa0LLElYR07GHtCjzz'
    'OgrAqDUpXcU8wiL9UEkX7pv7QUIwx6zOI0xC+1521/WDYmDLNcxinCOYrz3GYJcHdHvXZe62nnkdepJwX0rKZ6zx0dFH3OJsdWr9'
    'urCl1x7ocvWqj/a+e5eSStVo2e11TdReqSAi9KtLNRVaBaIvLe4CN/g+ky2ok7JKKKLNI9YCjBkCv0upIRJFoo7ncZR3E245Xl0K'
    'jV0ghDbD8YOWzfYf8yetyCtoDgsPi5uVWShGf1lLLrDfG5dKwsE5XNEP9wi1m7ry1nSfIda4+SaZZ26zKXs0JERXsEPSLi2GwRtR'
    'Ih6n+zQq5Fki6limIF2MjlKTWrrJVm+2f06WjalQ9y3o+DOB2TQYbJgxO5DMr4jxUsk/2eoJQDxuiLEzBqujWDW/PBjgyqsFjKFv'
    'qi+iFa0CfZCPCT/sAPtT+mPtykVJuBpKQndflh2amUseOFVDnxFkHDB4xCqj+lYNHcnwAy1lbI3BvRwodvUhDbr9F7iUFTkTQ0wO'
    'VfS7NEUoZcyN6Mtyo947jEyCxhHvZpX7LpG5xrfjbuxci+cmq93vUdBtRNWaU/WzH0V9lLw3pjNh5vn6QyQtkxnLwNvCvh0zqvU3'
    '94MEK4XXBKoORT2V+E4Ydcf0nGp2njd7HANZohaXzDSwbf+D4s9+KAV+Kt4AvBd9qviJX4pfM2o5U3ywECvFakEEUaEuFh3RUH6H'
    'lRxdCwoa0/2e24oDOfhgJHVl+RAUmkB3cWOiI5JCCttYwp6rQCEFTmMyo8qZZx1osn7h0/WDcBRU1eelD2ZmvcW/DAEXowdxKWzu'
    'oLWAcbbktLip/MCMspqqhW+h3JMfMC781f5bwkiTOQNlykBuyYWeaaxaiEuE/yPTY5HpLDtvsDJ7TUhLbtVM5InUrrwiBd12jLbh'
    'KnTpm7BHwsVy8Qdt/lOmC5xDx2AZhcyNNKa9Vom6yzkBLgYI9G3FS99KN44XxuE5zZNc2gKEe1JajKLOMkMlmhGWze75ljhDfJ7H'
    'CaheadrSvtct/lcjCmVfQIOf35/Mz4JaTy6R0/mLHCMM2udji214PgvwZhSHjB3hb8XK9fTymNcI4m1iFyq99QqAH7znGQnjEN8s'
    'tHaOJruhbWGEK54O6X2nP785zkYqW670Ib60hMzbsj6+6HbueohmxDwVwzfKmE7QW0WbERsyifw3pAzOt26C87QU5wF9845PL/A8'
    'MzKp9czJId5d8n7t5lHyqkur2BrTokW2XSD+3o4nxe9uurrnZEiSLEpnp927QeyyEpnwkYL8JZONAdgaLYMUMzhYlOQu/Sw9Y/hs'
    'i3I4H+LkS98U5acTkPrREEdvQtcEOzu4ejLz0+qQAYIqmyA/VYuyMqOAZkNuv2u5eOGhwr6CLunalplR6FHkSzQowYBEEK6m1L3C'
    'OcOvDarUSXHfrZVdpmz0GR6EVEsLOjvAtmJJE5VeNpvamC4qEbQSq5bd1PbgmtcQtcN5yins1zv5UY23vfBq2wbrTm7QlJ4/+XUb'
    'SXONXgL3wwsy6aqrS+Rs+UMpWLT9XwSHvHXGF2+8HRYwA8XFMD8sBRrmzJdsV6LHRXTa+dGOkMsumUea+JwkkFp9A0vRaT9Us72K'
    'YYNsyaISiDepcQWVuSeWEtfPenQI0YuSlZ0SuoKvwzD6Hwri7E0R8bMgKuzdZgAAC/S7ILIEtegFKXpOgp7S8FyoqMCyiiYiIqKl'
    'Sb5k1R2EJXQjhyYTPNg5wW1JNJvPZnmtJIFRSACbW6GMh0y/qz3mwerXjM6eW+6CIj5z01pPLxlK0H9az8072SHHeztCy6J59BxJ'
    '3mrMKqCy61R9peHYoc2gS5R4D5mlBMtgCxgKONlM1Zfs4NcP0O2hdqGSM08MaEofn72r7t7IvAGBX9354DiLPj6WKUflPzmpmeAK'
    '7Q9q18q3yTyGUXOl9N0uzEUdVBkNbtO72uOyReA8iF+7j1z12/Uqz/VZyOlea3gapa6W5PsGS2B5rAh5hxQ0qf1hBi6gEnW4UyNx'
    'RZ9q79g3Uug0Zi+B1Vatg4iCUXud45LDZVOqBaHlMX1VwhS8gWQjC0em+prB0k9MT67bnvp9cYVFTVK7Jqqn/W5ekeIdHE5RiVzT'
    '6OmWW4482Eu9e1pO+KiD4wXQbEAUI+QwxP/k9CafGxvVZXl8uuqvBY31QRGbariI7MnVIUNKXCzXBzx3Hjrt3Jq4+7xEOMx4e53b'
    'OaffxpibzCa1YcwtSPVpdsEnik/umd77Z/tY1CWAkjLnHGj54lnezwJtddckOAVUXubUVrtcrOZ762AEf57cayLvDva4IILnwZO9'
    'LW67dIsvtJ0hVrSzT0NL+r4N73MuNfNET/+LMt64d3P8G8IdG6F6TjFIlk3l7gHLmiJRR5mDVOealRy9mg+PnArekITMDHaVVqUr'
    '5B2RZiiYk4SKk9FH4LvSaZW9WQGrUuJRBuq4Gz5dc2OPvXgaBuwRMbvkaAZbfeppWiEpr8SpXsyuGWECz52l7H4r+eb0CLB7HIEI'
    'PgcRW8mmyuWLEQKWs1D16HaGPbocvji6KD93hWkyo7RUqdf86jA36ispJHdkgH5yemHlEEGSK7fWsxII6EMesz8cLYSWK0HGnMif'
    'Z2q5yHLfY0e20q8fYpe65SJOHqbJ+YsL989aIrYDHrv37anNBjQVD5zKLa6fcSf5JMtL2egL8Q09FuYT0FChZbXJNn/lf6ics/9U'
    'IT8r542MRf9p5TT/rpwcuAedHEUPc04qKSVpb1xMDJ4MQtHauk0uiVbPmgKuk1yuzr6H+zD5bAxPUUkJh7UwLNZcSCE6IypyZqZj'
    'pq7/BJMlB3vtNnC4tGaFWUNGsWv42/YJ3DaMCJjcIW73lgPcYIhsrp6SwoWDBSS1WmTqV4MH4mBSBAM2DALq7OOjG7rLnNfwKq9l'
    'nQYJVYv0xrBgfnQMzmPMBgNDT1RF8E3U9+Mu+GrtDF4vsEHRc8SOXogjAs2zhIjhsWiyjQnKlmy2EHQXbb6fdb4/2eEBZj0bIAHH'
    'kI8owgKK/SaE0/tRHa+3kGw+HZUBmoifQU8xPX0QjDGzLmuqyfMObpX9fAL7eRd2hooTOeZM39CVS6j1ejLYn0tafDGP5tGtg4qY'
    'GOB32EZNQVu8XLBJUe8L/L0/K7Mre79lPfD+3HlN9n1A5E8M4cC8tTN8TrVRjN91as+pJ+eLBoEXhZz8j/8Rza5LcdjyulizJJp4'
    '3diggpy9YNdhDwc+6yQ5m/dnE994edTa5FSakwNOTkHPAev6Y15Olp8jp3iIzXS4cOA/FoFL6EXA2a5u7hb6KFn0ncKfkzYrFXwY'
    'f6xhytkh6+sS/M77YfJFH3bx+vD367EqIdTYE/ogGvDvE3Q3SkQMM7XeXWrKympruoiK/X3gxOdcwgw/FqkEPQF0jc5nunk69D+J'
    'FBMqLbWyDIys/5bRVvHsNqUf6A+1cEjMg4Uhd/7R6KyuR2GwnjkWhDz4BaE4HNsc7/dihwHQd+SIdGsO5yuspnX14ezKjM7YHwek'
    '/50eyJ/uGJjHXH+u+/sUYu8SK0UEwvUf1HHeoKrwE6IFQypty0gRN226qP+1NjtmCXTIqLTMl3byanZazFLklcirdSMSxT98P2Zo'
    '77nE6HPZuxszanW4xDjzh+jT7Kfq6de6sN5u+u4dXLWKNGwKYHqgTnD0D+fF7iEwSbtDQq5w5UpCo4VknOg/nCk20+gFbcVIVlOJ'
    'iwzySLLaPfPnb6Sp32sh/7O2cwm0pJR4QjN12LS/G/CVYUQmMKdV9ns1KbpFy/TRzB08jlD4EGsl74rawgztr8OeVTkiqc1tnCrI'
    '6jJH2P9d9CDg7PpZ9Cbu5acvmzi3fhf9NNz5phFFiIXPNpWOoRhLvhoeUTY7NhWVLX5oaKSRvIV5mjYMJjYcXytcVFF8uR1jFOT2'
    'FRPM3PDFsHoffW19eQpKP9O3Jhu0XyIdtJHFtHQfaxZaGQMgMv5tHbE+dPN+M4ESO2y7eeiVdZyg1J6PJ/icAB75v0xNestklfaE'
    '7N1GsGMr4xHt4ThqML0oOAU9wRdKDkTgn2e3BtpfP8YNXCLao5m3R7Y6Y1qDgr38Bd/IawIwjn+ZSAuGOkRta/PdfX+zNn8/hqDu'
    'o9HbmwP2S9jZAIv6eeZoE47omV31qLBhNivLKpJdsMDU9/oU9OMDr7H/ZaUluTVmBOl3K8ebBm4EMPARZT4Ttm4WsM4sgZ8L5wzR'
    'xTrFhZTDiWxIv5QY5NxZjb1dLGmOriQDNxiGzg17Fln8ebyJ1kF61FSLo+gdQdQNakPCc3Oc1QSzPqlsPxrddU9Ws3jc+WX/jofH'
    'tl3ArJTTtb/mYIll+FguDzVdNsf5QjrGg2bbaN4lwGykNulcVjSK7A49crf8u6H9pggPzHexw4RN1tzNEtm/5sAD3huEHF+zvYhF'
    'k10zwkoiisV4/kmjCLGCaPmJmyjSsmqpkB2Hg0ejaxuX/45bPmmTmA1lIY2lduWUvTl/Jm+tzHU3nr8BaewdRLgS5Ima7bQeW22t'
    'LbdtClj12gnzeaqGn68nWxKAxBPz/QXmR82HXeVcj0xKV3CIAOgcq31IDL7nIvAyd2eWabsZ2eZp+biLnEGGxkPasHNu9pvr3yD9'
    'zRqfRrI8SI9KoIo6FEJJ94in3wxeOjd27ZwSnfQBP8neih/Gfh19iWTO8DTVG9IYg+cqZg8WsAEL8P/HqMKaO94njbxOyuMjtkeS'
    'UAUlpAcO3NwVnP/bRrihM8s7+Va0HMYHsHdI6N7A3LkzP/gHh14xnqc9/uF5eYF/0A9a5n2qnIB3Qs/APR5/yalAOMJu1QKzvRTP'
    'q+mHpbGiXLqGXrIUA53zHJrIlJpdu8UgdNdWGuX3Q4haL7v/8jH77jT0y99w89KBjWaH326OCS8+o2g7WD9PZP80c4+OY5zFkOEq'
    '6ENS9//5VP4voAOnog=='
))


class PublicHygieneContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True,
                              capture_output=True)

    def write(self, name, value, tracked=True):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value if isinstance(value, bytes) else value.encode())
        if tracked:
            self.git('add', '-f', '--', name)

    def scan(self, export=False):
        args = ['--export-root', str(self.root)] if export else ['--root', str(self.root)]
        return subprocess.run([sys.executable, str(CHECKER), *args],
                              text=True, capture_output=True)

    def test_public_sources_and_exact_upstream_binaries_pass(self):
        self.write('main.java', 'String token = "placeholder"; // https://example.com\n')
        self.write('.env.example', 'TOKEN=replace-me\n')
        self.write('cert.crt', 'public certificate\n')
        self.write('tone.wav', 'intentional public audio\n')
        self.write('hash.txt', 'a' * 64 + '\n/opt/java/Contents/Home/bin/java\n')
        for name in ['packaging/launcher.ico', 'packaging/tamaemu.ico',
                     'wear/gradle/wrapper/gradle-wrapper.jar']:
            self.write(name, (SOURCE_ROOT / name).read_bytes())
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_historical_icon_hash_policy_is_path_specific(self):
        spec = importlib.util.spec_from_file_location('icon_policy_contract', CHECKER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        policy = getattr(module, 'expected_binary', None)
        self.assertTrue(callable(policy), 'Exact binary digest policy is unavailable')
        for path, digest in [
            ('packaging/launcher.ico', '98265af9f98fe79e4b0342ab770864b925f9e889ab366316209f079532468734'),
            ('packaging/tamaps.ico', '97c9d18755728ebc10b253dc45e55e4831e93609edd5d76e51f5a814751b4558')]:
            with self.subTest(path=path):
                self.assertTrue(policy(path, digest))
                for wrong in ['assets/' + Path(path).name, path.upper(), 'packaging/tamaemu.ico']:
                    self.assertFalse(policy(wrong, digest), 'Historical digest accepted at wrong path')
                self.assertFalse(policy(path, hashlib.sha256(HISTORICAL_TAMAPS + b'changed').hexdigest()))

    def test_historical_icon_bytes_pass_only_exact_path_and_hash(self):
        self.write('packaging/tamaps.ico', HISTORICAL_TAMAPS)
        result = self.scan()
        self.assertEqual(result.returncode, 0, 'Official historical ICO was rejected')
        self.write('packaging/tamaps.ico', HISTORICAL_TAMAPS[:-1] + bytes([HISTORICAL_TAMAPS[-1] ^ 1]))
        self.assertEqual(self.scan().returncode, 1, 'Mutated historical ICO was accepted')
        self.git('rm', '-q', '-f', '--', 'packaging/tamaps.ico')
        for wrong in ['assets/tamaps.ico', 'packaging/launcher.ico', 'packaging/TAMAPS.ICO']:
            with self.subTest(path=wrong):
                self.write(wrong, HISTORICAL_TAMAPS)
                self.assertEqual(self.scan().returncode, 1, 'Historical ICO accepted at wrong path')
                self.git('rm', '-q', '-f', '--', wrong)

    def test_force_tracked_private_paths_and_suffixes_fail(self):
        for name in ['private/input.txt', 'nested/build/log.txt', '.worktrees/a/readme',
                     '.tooling/log.txt', 'nested/private/session.txt', '.env', '.env.local',
                     'nested/pet.state.old', 'pet.sav.copy', 'capture.PCM', 'key.PEM',
                     'x.key', 'x.p12', 'x.pfx', 'x.p8', 'x.rom', 'x.bin']:
            with self.subTest(name=name):
                self.write(name, 'synthetic')
                result = self.scan()
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('private-path', result.stdout)
                self.git('rm', '-q', '-f', '--', name)

    def test_credentials_keys_and_home_paths_are_redacted(self):
        examples = [
            ('credential', 'gh' + 'p_' + 'a' * 36),
            ('credential', 'github_' + 'pat_' + 'a' * 82),
            ('credential', 'AK' + 'IA' + 'A' * 16),
            ('credential', 'AIza' + 'b' * 35),
            ('credential', 'xox' + 'b-' + '1234567890-' * 2 + 'a' * 24),
            ('private-key', '-----BEGIN ' + 'RSA PRIVATE KEY-----'),
            ('home-path', '/' + 'Users/' + 'synthetic-person/secrets'),
            ('home-path', '/' + 'home/' + 'synthetic-person/secrets'),
            ('home-path', 'C:' + '\\Users\\' + 'synthetic-person\\secrets'),
        ]
        for rule, planted in examples:
            with self.subTest(rule=rule):
                self.write('notes.txt', 'safe first line\n' + planted + '\n')
                result = self.scan()
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode, 1, output)
                self.assertIn(rule, output)
                self.assertIn(':2:', output)
                self.assertNotIn(planted, output)
                self.assertNotIn('synthetic-person', output)

    def test_untracked_files_and_deleted_tracked_paths_are_excluded(self):
        self.write('good.txt', 'public\n')
        self.write('deleted.txt', 'public\n')
        (self.root / 'deleted.txt').unlink()
        self.write('private/local.txt', 'local only', tracked=False)
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Untracked files are excluded', result.stdout)

    def test_armored_pgp_private_key_header_is_rejected_without_echo(self):
        header = '-----BEGIN ' + 'PGP PRIVATE ' + 'KEY BLOCK-----'
        self.write('notes.txt', 'public line\n' + header + '\n')
        result = self.scan()
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, 'PGP private key header was not rejected')
        self.assertIn(':2:private-key', output)
        self.assertNotIn(header, output)

    def test_subdirectory_entry_scans_whole_git_worktree(self):
        planted = 'gh' + 'p_' + 'a' * 36
        self.write('notes.txt', planted + '\n')
        self.write('nested/public.txt', 'public\n')
        for explicit in [True, False]:
            with self.subTest(explicit_root=explicit):
                args = ['--root', str(self.root / 'nested')] if explicit else []
                result = subprocess.run([sys.executable, str(CHECKER), *args],
                                        cwd=self.root / 'nested', text=True, capture_output=True)
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode, 1, 'Repository-level credential was missed')
                self.assertIn('notes.txt', output)
                self.assertIn(':1:credential', output)
                self.assertNotIn(planted, output)

    def test_escaping_symlink_fails_without_reading_target(self):
        (self.root / 'link').symlink_to(self.root.parent / 'outside-does-not-exist')
        self.git('add', 'link')
        result = self.scan()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('escaping-symlink', result.stdout)

    def test_internal_symlink_is_allowed(self):
        self.write('good.txt', 'public\n')
        (self.root / 'link').symlink_to('good.txt')
        self.git('add', 'link')
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_unknown_binary_and_changed_allowed_binary_fail(self):
        for name in ['blob.dat', 'wear/gradle/wrapper/gradle-wrapper.jar']:
            with self.subTest(name=name):
                self.write(name, b'\x00synthetic binary')
                result = self.scan()
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn('unexpected-binary', result.stdout)

    def test_export_scans_all_files_without_git(self):
        shutil.rmtree(self.root / '.git')
        self.write('README.md', 'public\n', tracked=False)
        result = self.scan(export=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.write('nested/pet.SAV.backup', 'synthetic', tracked=False)
        result = self.scan(export=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('private-path', result.stdout)

    def test_missing_export_root_fails_closed(self):
        result = subprocess.run([sys.executable, str(CHECKER), '--export-root',
                                 str(self.root / 'missing')], text=True, capture_output=True)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_assigned_credentials_and_url_passwords_are_redacted(self):
        examples = [
            'pass' + 'word = "' + 'synthetic-' + 'password-value-83' + '"',
            'API_TOKEN=' + 'synthetic-' + 'token-value-123456',
            'client_secret: ' + 'synthetic-' + 'secret-value-123456',
            'https://person:' + 'synthetic-' + 'password@host.invalid/path',
            'sk-' + 'proj-' + 'a' * 48,
            'glpat-' + 'b' * 24,
            'npm_' + 'c' * 36,
        ]
        for planted in examples:
            with self.subTest(kind=examples.index(planted)):
                self.write('config.txt', planted + '\n')
                result = self.scan()
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode, 1, 'Assigned credential was accepted')
                self.assertNotIn(planted, output)

    def test_operational_dotfiles_and_archives_fail(self):
        for name in ['.DS_Store', '.claude/settings.json', '.codex/log.txt',
                     '.git-credentials', '.netrc', 'backup.zip', 'dump.tar.gz',
                     'nested/.env.example.local', 'AGENTS.md', 'nested/CLAUDE.md',
                     'RESUME-STATE.md', 'working-context/notes.md',
                     'RUN-MANIFEST.json', 'CARRY-MANIFEST.json']:
            with self.subTest(name=name):
                self.write(name, 'synthetic')
                result = self.scan()
                self.assertEqual(result.returncode, 1, 'Private operational path was accepted')
                self.assertIn('private-path', result.stdout)
                self.git('rm', '-q', '-f', '--', name)

    def test_credential_placeholders_and_program_expressions_pass(self):
        self.write('config.py', 'password = os.environ["PASSWORD"]\n'
                   'token = "placeholder"\nsecret = "replace-me"\n'
                   'password = "${PASSWORD}"\ntoken = args.token\n'
                   'password = "your-password-here"\n'
                   'token = epoch.incrementAndGet();\n'
                   'password = Files.readString(Path.of(args[2]));\n'
                   'h->token=next_token++;\n')
        result = self.scan()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_sensitive_filename_is_rejected_without_echo(self):
        marker = 'gh' + 'p_' + 'z' * 36
        self.write(marker + '.txt', 'public\n')
        result = self.scan()
        self.assertEqual(result.returncode, 1, 'Sensitive filename was accepted')
        self.assertNotIn(marker, result.stdout + result.stderr)

    def test_symlinked_parent_cannot_expose_outside_files(self):
        self.write('nested/visible.txt', 'public\n')
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'visible.txt').write_text('must not be read\n')
        (self.root / 'nested/visible.txt').unlink()
        (self.root / 'nested').rmdir()
        (self.root / 'nested').symlink_to(outside)
        # Move the target beyond the checkout after creating a tracked child.
        with tempfile.TemporaryDirectory() as moved:
            (self.root / 'nested').unlink()
            (self.root / 'nested').symlink_to(moved)
            Path(moved, 'visible.txt').write_text('must not be read\n')
            result = self.scan()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('escaping-symlink', result.stdout)


if __name__ == '__main__':
    unittest.main()
