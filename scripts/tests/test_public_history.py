"""Publication history boundaries, exercised with real local Git objects."""
from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]
CHECKER = SCRIPTS / 'check-public-history.py'
ZERO = '0' * 40


class PublicHistoryContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Publication Test')
        self.git('config', 'user.email', 'publication@example.invalid')
        self.write('README.md', 'Public source\n')
        self.base = self.commit('Initial public source')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True,
                              capture_output=True, input=b'')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        self.git('add', '-f', '--', name)

    def commit(self, message):
        self.git('commit', '-q', '-m', message)
        return self.git('rev-parse', 'HEAD').stdout.decode().strip()

    def scan(self, tip=None, base=None, stdin=None, extra=(), cwd=None):
        args = ['--root', str(self.root)]
        if stdin is not None:
            args += ['--pre-push']
        else:
            args += ['--base', base or self.base, '--tip', tip or 'HEAD']
        return subprocess.run([sys.executable, str(CHECKER), *args, *extra],
                              input=stdin, text=True, capture_output=True, cwd=cwd)

    def assert_rejected_without_value(self, result, value):
        self.assertEqual(result.returncode, 1, 'Sensitive history was accepted')
        self.assertNotIn(value, result.stdout + result.stderr)

    def tuple(self, local, remote=ZERO, local_ref='refs/heads/main', remote_ref='refs/heads/main'):
        return f'{local_ref} {local} {remote_ref} {remote}\n'

    def test_clean_range_and_new_branch_pass_from_subdirectory_detached_head(self):
        self.write('src/main.txt', 'Public source\n')
        tip = self.commit('Add source')
        self.git('checkout', '-q', '--detach')
        result = self.scan(stdin=self.tuple(tip, local_ref='HEAD'), cwd=self.root / 'src')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_deleted_intermediate_secret_is_rejected(self):
        secret = 'gh' + 'p_' + 'a' * 36
        self.write('forgotten.txt', secret + '\n')
        bad = self.commit('Add temporary file')
        self.git('rm', '-q', 'forgotten.txt')
        tip = self.commit('Remove temporary file')
        result = self.scan(tip)
        self.assert_rejected_without_value(result, secret)
        self.assertIn(bad, result.stdout)
        self.assertIn('forgotten.txt', result.stdout)

    def test_remote_oid_cannot_hide_sensitive_ancestors(self):
        secret = 'gh' + 'p_' + 'b' * 36
        self.write('hidden.txt', secret + '\n')
        bad = self.commit('Add file')
        self.git('rm', '-q', 'hidden.txt')
        tip = self.commit('Remove file')
        result = self.scan(stdin=self.tuple(tip, bad))
        self.assert_rejected_without_value(result, secret)

    def test_commit_message_and_author_metadata_are_redacted(self):
        secret = 'gh' + 'p_' + 'c' * 36
        self.write('good.txt', 'Public source\n')
        tip = self.commit('note ' + secret)
        result = self.scan(tip)
        self.assert_rejected_without_value(result, secret)
        self.assertIn('commit-metadata', result.stdout)
        self.git('config', 'user.name', 'gh' + 'p_' + 'd' * 36)
        self.write('good.txt', 'Updated public source\n')
        tip = self.commit('Update source')
        result = self.scan(tip)
        self.assert_rejected_without_value(result, 'gh' + 'p_' + 'd' * 36)

    def test_annotated_tag_metadata_is_scanned(self):
        secret = 'gh' + 'p_' + 'e' * 36
        self.git('tag', '-a', 'release', '-m', 'Release ' + secret)
        oid = self.git('rev-parse', 'refs/tags/release').stdout.decode().strip()
        result = self.scan(stdin=self.tuple(oid, local_ref='refs/tags/release',
                                           remote_ref='refs/tags/release'))
        self.assert_rejected_without_value(result, secret)
        self.assertIn('tag-metadata', result.stdout)

    def test_every_ref_is_checked_including_secondary_new_branch(self):
        self.write('local.properties', 'synthetic\n')
        tip = self.commit('Local file')
        result = self.scan(stdin=self.tuple(self.base) + self.tuple(
            tip, local_ref='refs/heads/other', remote_ref='refs/heads/other'))
        self.assertEqual(result.returncode, 1, 'Secondary ref was skipped')
        self.assertIn('private-path', result.stdout)

    def test_invalid_ranges_and_missing_objects_fail_closed(self):
        for base, tip in [('missing', 'HEAD'), (self.base, 'missing'),
                          ('f' * 40, 'HEAD'), (self.base, 'HEAD~99')]:
            with self.subTest(case=(base, tip)):
                result = self.scan(base=base, tip=tip)
                self.assertEqual(result.returncode, 2, 'Invalid history input was accepted')

    def test_malformed_and_truncated_push_input_fail_closed(self):
        inputs = ['\n', ' ', ' \n', self.tuple(self.base).rstrip('\n'),
                  f'refs/heads/a {self.base} refs/heads/a\n',
                  self.tuple(self.base, local_ref='refs/heads/a..b'),
                  self.tuple(self.base, remote_ref='HEAD'),
                  self.tuple('f' * 40), self.tuple(self.base, remote='f' * 40),
                  self.tuple(self.base, local_ref='--all'),
                  self.tuple(ZERO), self.tuple(self.base, local_ref='(delete)')]
        for n, data in enumerate(inputs):
            with self.subTest(case=n):
                result = self.scan(stdin=data)
                self.assertEqual(result.returncode, 2, 'Invalid push input was accepted')

    def test_exact_empty_push_input_has_fixed_success_message(self):
        result = self.scan(stdin='')
        self.assertEqual(result.returncode, 0, 'No-op push was rejected')
        self.assertEqual(result.stdout, 'Public history: nothing to publish.\n')
        self.assertEqual(result.stderr, '')

    def test_raw_push_input_rejects_cr_nul_and_invalid_utf8(self):
        valid = self.tuple(self.base).encode()
        for data in [valid.replace(b'\n', b'\r\n'), valid.replace(b'heads', b'he\rads'),
                     valid.replace(b'heads', b'he\0ads'), valid.replace(b'heads', b'he\xffads')]:
            with self.subTest(case=data.find(b'\n')):
                result = subprocess.run([sys.executable, str(CHECKER), '--root',
                                         str(self.root), '--pre-push'], input=data, capture_output=True)
                self.assertEqual(result.returncode, 2, 'Invalid raw push framing was accepted')
                self.assertEqual(result.stdout, b'')

    def test_raw_utf8_ref_names_are_accepted(self):
        data = self.tuple(self.base, local_ref='refs/heads/caf\u00e9',
                          remote_ref='refs/heads/caf\u00e9').encode('utf-8')
        result = subprocess.run([sys.executable, str(CHECKER), '--root', str(self.root),
                                 '--pre-push'], input=data, capture_output=True)
        self.assertEqual(result.returncode, 0, 'Valid UTF-8 ref tuple was rejected')

    def docs_tip(self):
        for name in ['CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md', 'docs/WEAR_OS.md',
                     'docs/VERIFICATION.md', 'docs/RELEASE_NOTES.md']:
            self.write(name, '# Guide\n\nUse local firmware.\n')
        self.write('LICENSE', 'Public test license\n')
        return self.commit('Add public guides')

    def object_tip(self, parent, additions):
        # Raw tree input bypasses both filesystem and Git index Unicode normalization.
        nodes = {}
        entries = []
        for entry in self.git('ls-tree', '-rz', '--full-tree', parent).stdout.split(b'\0'):
            if entry:
                header, name = entry.split(b'\t', 1)
                entries.append((os.fsdecode(name), tuple(header.decode().split(' '))))
        for name, text in additions:
            blob = subprocess.run(['git', '-C', str(self.root), 'hash-object', '-w', '--stdin'],
                                  input=text.encode(), capture_output=True, check=True).stdout.decode().strip()
            entries.append((name, ('100644', 'blob', blob)))
        for name, value in entries:
            current = nodes
            parts = name.split('/')
            for part in parts[:-1]:
                current = current.setdefault(part, {})
            current[parts[-1]] = value

        def tree_object(children):
            records = []
            for name, value in children.items():
                mode, kind, oid = ('040000', 'tree', tree_object(value)) if isinstance(value, dict) else value
                records.append(f'{mode} {kind} {oid}\t'.encode() + os.fsencode(name) + b'\0')
            return subprocess.run(['git', '-C', str(self.root), 'mktree', '-z'],
                                  input=b''.join(records), capture_output=True,
                                  check=True).stdout.decode().strip()

        tree = tree_object(nodes)
        raw_names = [entry.split(b'\t', 1)[1] for entry in
                     self.git('ls-tree', '-rz', '--full-tree', tree).stdout.split(b'\0') if entry]
        self.assertTrue(all(os.fsencode(name) in raw_names for name, _ in additions),
                        'Git fixture did not preserve every supplied raw path')
        return self.git('commit-tree', tree, '-p', parent, '-m', 'Add export fixtures').stdout.decode().strip()

    def test_committed_export_rejects_case_unicode_and_directory_aliases(self):
        parent = self.docs_tip()
        for names in [('NOTES.md', 'notes.md'), ('Caf\u00e9.md', 'Cafe\u0301.md'),
                      ('CAF\u00c9.md', 'cafe\u0301.md'), ('Guides/a.md', 'guides/b.md')]:
            with self.subTest(case=names):
                tip = self.object_tip(parent, [(names[0], '# Note\n'), (names[1], '# Note\n')])
                result = self.scan(tip, extra=['--check-docs'])
                self.assertEqual(result.returncode, 2, 'Aliased committed paths were exported')
                self.assertNotIn(names[0], result.stdout + result.stderr)

    def test_windows_alias_paths_are_rejected_before_export(self):
        parent = self.docs_tip()
        for name in ['note.md.', 'note.md ', 'notes.md:extra', 'AUX.md', 'nested/COM1.txt']:
            with self.subTest(case=name):
                tip = self.object_tip(parent, [(name, '# Note\n')])
                result = self.scan(tip, extra=['--check-docs'])
                self.assertEqual(result.returncode, 2, 'Nonportable aliased path was exported')

    def test_alias_validation_precedes_export_creation_and_redacts_filenames(self):
        parent = self.docs_tip()
        planted = 'gh' + 'p_' + 'a' * 36
        tip = self.object_tip(parent, [(planted + '.md', '# Note\n'),
                                       (planted.upper() + '.md', '# Note\n')])
        spec = importlib.util.spec_from_file_location('history_export_contract', CHECKER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with patch.object(module.tempfile, 'TemporaryDirectory',
                          side_effect=AssertionError('Export creation reached')):
            try:
                module.check_docs(self.root, tip)
            except module.CheckError as error:
                self.assertNotIn(planted, str(error))
            except AssertionError:
                self.fail('Aliased tree reached export creation')
            else:
                self.fail('Aliased tree did not fail closed')

    def test_clean_committed_export_with_distinct_utf8_paths_passes(self):
        parent = self.docs_tip()
        tip = self.object_tip(parent, [('Caf\u00e9.md', '# Note\n'), ('notes.md', '# Note\n')])
        result = self.scan(tip, extra=['--check-docs'])
        self.assertEqual(result.returncode, 0, 'Clean committed documentation export was rejected')

    def test_ref_deletion_is_safe_and_requires_valid_tuple(self):
        result = self.scan(stdin=self.tuple(ZERO, self.base, local_ref='(delete)'))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_noncommit_target_and_submodule_fail_closed(self):
        blob = self.git('hash-object', '-w', '--stdin').stdout.decode().strip()
        result = self.scan(stdin=self.tuple(blob, local_ref='refs/tags/blob',
                                           remote_ref='refs/tags/blob'))
        self.assertEqual(result.returncode, 2, 'Noncommit publication target was accepted')
        self.git('update-index', '--add', '--cacheinfo', f'160000,{self.base},module')
        tip = self.commit('Add gitlink')
        result = self.scan(tip)
        self.assertEqual(result.returncode, 1, 'Unscanned gitlink was accepted')

    def test_escaping_symlink_is_rejected_from_git_object(self):
        (self.root / 'link').symlink_to('../../outside')
        self.git('add', 'link')
        tip = self.commit('Add link')
        (self.root / 'link').unlink()
        result = self.scan(tip)
        self.assertEqual(result.returncode, 1, 'Committed escaping symlink was accepted')

    def test_human_coauthor_is_allowed_but_tool_credit_is_rejected(self):
        self.write('good.txt', 'Public source\n')
        tip = self.commit('Update\n\nCo-authored-by: Human Contributor <human@example.invalid>\n'
                          'Co-authored-by: Claude Smith <claude@example.invalid>\n'
                          'Co-authored-by: Human Engineer <human@openai.com>')
        self.assertEqual(self.scan(tip).returncode, 0)
        self.write('good.txt', 'Updated public source\n')
        credit = 'Co-authored-by: ' + 'Claude' + ' <noreply@anthropic.com>'
        tip = self.commit('Update\n\n' + credit)
        result = self.scan(tip)
        self.assertEqual(result.returncode, 1, 'Tool attribution was accepted')
        self.assertIn('tool-credit', result.stdout)
        self.assertNotIn(credit, result.stdout + result.stderr)

    def test_shallow_history_and_missing_parent_fail_closed(self):
        self.write('good.txt', 'Public source\n')
        tip = self.commit('Update source')
        (self.root / '.git/shallow').write_text(tip + '\n')
        result = self.scan(tip)
        self.assertEqual(result.returncode, 2, 'Unscanned shallow ancestry was accepted')
        (self.root / '.git/shallow').unlink()
        raw = self.git('cat-file', 'commit', tip).stdout
        raw = raw.replace(self.base.encode(), ('f' * 40).encode(), 1)
        result = subprocess.run(['git', '-C', str(self.root), 'hash-object', '-t', 'commit', '-w', '--stdin'],
                                input=raw, capture_output=True, check=True)
        malformed = result.stdout.decode().strip()
        result = self.scan(malformed)
        self.assertEqual(result.returncode, 2, 'Missing ancestor object was accepted')

    def test_replace_refs_cannot_hide_bad_object_bytes(self):
        secret = 'gh' + 'p_' + 'r' * 36
        self.write('secret.txt', secret + '\n')
        tip = self.commit('Add file')
        self.git('replace', tip, self.base)
        result = self.scan(tip)
        self.assert_rejected_without_value(result, secret)


if __name__ == '__main__':
    unittest.main()
