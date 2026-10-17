"""Install and execute publication hooks in disposable real Git repositories."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2]
INSTALLER = SOURCE / 'scripts/install-public-hooks.py'
ZERO = '0' * 40


class PublicHooksContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Publication Test')
        self.git('config', 'user.email', 'publication@example.invalid')
        (self.root / 'scripts').mkdir()
        (self.root / '.githooks').mkdir()
        # Guard implementations are real, never replaced with return-code doubles.
        for name in ['check-public-hygiene.py', 'check-public-history.py',
                     'check-public-docs.py']:
            path = SOURCE / 'scripts' / name
            if path.exists():
                shutil.copyfile(path, self.root / 'scripts' / name)
        if (SOURCE / '.githooks/pre-push').exists():
            shutil.copyfile(SOURCE / '.githooks/pre-push', self.root / '.githooks/pre-push')
        self.write('README.md', '# Public source\n\nSee [license](LICENSE).\n')
        self.write('LICENSE', 'Public test license\n')
        for name in ['CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md', 'docs/WEAR_OS.md',
                     'docs/VERIFICATION.md', 'docs/RELEASE_NOTES.md']:
            self.write(name, '# Guide\n\nUse local firmware.\n')
        self.base = self.commit('Initial source')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True,
                              capture_output=True)

    def write(self, name, value):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)
        self.git('add', '-f', '--', name)

    def commit(self, message):
        self.git('commit', '-q', '-m', message)
        return self.git('rev-parse', 'HEAD').stdout.decode().strip()

    def install(self):
        return subprocess.run([sys.executable, str(INSTALLER), '--root', str(self.root)],
                              text=True, capture_output=True)

    def run_hook(self, tip=None, data=None, cwd=None):
        if data is None:
            data = f'refs/heads/main {tip or self.base} refs/heads/main {ZERO}\n'
        return subprocess.run([str(self.root / '.git/hooks/pre-push'), 'origin',
                               'https://private-host.invalid/not-to-print'],
                              input=data, text=True, capture_output=True, cwd=cwd or self.root)

    def test_explicit_install_creates_executable_without_hooks_path_config(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        hook = self.root / '.git/hooks/pre-push'
        self.assertTrue(os.access(hook, os.X_OK), 'Installed hook is not executable')
        config = subprocess.run(['git', '-C', str(self.root), 'config', '--local', '--get',
                                 'core.hooksPath'], capture_output=True)
        self.assertEqual(config.returncode, 1, 'Installer silently changed hooksPath')
        nested = self.root / 'nested'
        nested.mkdir()
        result = self.run_hook(cwd=nested)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('private-host', result.stdout + result.stderr)

    def test_existing_hook_or_hooks_path_is_never_overwritten(self):
        hook = self.root / '.git/hooks/pre-push'
        hook.write_text('original local hook\n')
        result = self.install()
        self.assertNotEqual(result.returncode, 0, 'Existing hook was overwritten')
        self.assertEqual(hook.read_text(), 'original local hook\n')
        hook.unlink()
        self.git('config', 'core.hooksPath', 'my-existing-hooks')
        result = self.install()
        self.assertNotEqual(result.returncode, 0, 'Existing hooksPath was overridden')
        self.assertEqual(self.git('config', '--get', 'core.hooksPath').stdout,
                         b'my-existing-hooks\n')
        self.assertFalse(hook.exists())

    def test_dangling_hook_symlink_is_not_replaced(self):
        hook = self.root / '.git/hooks/pre-push'
        hook.symlink_to('absent-hook')
        result = self.install()
        self.assertNotEqual(result.returncode, 0, 'Existing symlink was replaced')
        self.assertTrue(hook.is_symlink())

    def test_hook_blocks_committed_secret_hidden_by_dirty_worktree(self):
        secret = 'gh' + 'p_' + 'a' * 36
        self.write('config.txt', secret + '\n')
        tip = self.commit('Add config')
        (self.root / 'config.txt').write_text('public placeholder\n')
        self.assertEqual(self.install().returncode, 0)
        result = self.run_hook(tip)
        self.assertNotEqual(result.returncode, 0, 'Committed secret escaped hook')
        self.assertNotIn(secret, result.stdout + result.stderr)

    def test_hook_blocks_committed_bad_docs_hidden_by_dirty_worktree(self):
        self.write('README.md', '# Public source\n\nSee [missing](not-here.md).\n')
        tip = self.commit('Update docs')
        (self.root / 'README.md').write_text('# Public source\n\nSee [license](LICENSE).\n')
        self.assertEqual(self.install().returncode, 0)
        result = self.run_hook(tip)
        self.assertNotEqual(result.returncode, 0, 'Dirty good docs hid committed broken docs')
        self.assertIn('docs-check-failed', result.stdout)

    def test_hook_blocks_dirty_tracked_private_content(self):
        (self.root / 'README.md').write_text('pass' + 'word = "' + 'synthetic-' + 'value-12345678"\n')
        self.assertEqual(self.install().returncode, 0)
        result = self.run_hook()
        self.assertNotEqual(result.returncode, 0, 'Current tracked tree was not checked')
        self.assertIn('assigned-credential', result.stdout)

    def test_empty_hook_input_skips_dirty_worktree_checks(self):
        self.assertEqual(self.install().returncode, 0)
        planted = 'gh' + 'p_' + 'a' * 36
        (self.root / 'README.md').write_text(planted + '\n')
        result = self.run_hook(data='')
        self.assertEqual(result.returncode, 0, 'No-op push inspected dirty worktree')
        self.assertEqual(result.stdout, 'Public history: nothing to publish.\n')
        self.assertNotIn(planted, result.stdout + result.stderr)

    def test_hook_rejects_missing_checker(self):
        self.assertEqual(self.install().returncode, 0)
        (self.root / 'scripts/check-public-docs.py').unlink()
        result = self.run_hook()
        self.assertNotEqual(result.returncode, 0, 'Missing docs guard was accepted')
        self.assertNotIn('private-host', result.stdout + result.stderr)

    def test_real_detached_head_push_uses_supplied_local_revision(self):
        self.assertEqual(self.install().returncode, 0)
        self.git('checkout', '-q', '--detach')
        with tempfile.TemporaryDirectory() as remote:
            subprocess.run(['git', 'init', '--bare', '-q', remote], check=True,
                           capture_output=True)
            result = subprocess.run(['git', '-C', str(self.root), 'push', remote,
                                     'HEAD:refs/heads/detached'], capture_output=True)
            self.assertEqual(result.returncode, 0, 'Actual detached HEAD push was rejected')
            (self.root / 'README.md').write_text('gh' + 'p_' + 'a' * 36 + '\n')
            result = subprocess.run(['git', '-C', str(self.root), 'push', remote,
                                     'HEAD:refs/heads/detached'], capture_output=True)
            self.assertEqual(result.returncode, 0, 'Identical second push was rejected')
            published = subprocess.check_output(['git', '-C', remote, 'rev-parse',
                                                  'refs/heads/detached']).decode().strip()
            self.assertEqual(published, self.base)


if __name__ == '__main__':
    unittest.main()
