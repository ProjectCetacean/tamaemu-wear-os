"""Launch checks fail closed on incomplete GitHub REST evidence.

Fixtures use documented REST response fields, independent of checker helpers.
Mutations exercise lost protection, incorrect identity, and unavailable evidence.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'check-github-publication-policy.py'


def evidence():
    return {
        'repository': {'status': 200, 'body': {
            'full_name': 'sample/public', 'private': False, 'visibility': 'public',
            'fork': True, 'parent': {'full_name': 'maragotchi/tamaemu'},
            'default_branch': 'main', 'archived': False, 'disabled': False,
            'security_and_analysis': {
                'secret_scanning': {'status': 'enabled'},
                'secret_scanning_push_protection': {'status': 'enabled'},
            },
        }},
        'branch': 'main',
        'protection': {'status': 200, 'body': {
            'required_status_checks': {
                'strict': True, 'contexts': ['Public hygiene'],
                'checks': [{'context': 'Public hygiene', 'app_id': 15368}],
            },
            'enforce_admins': {'enabled': True},
            'allow_force_pushes': {'enabled': False},
            'allow_deletions': {'enabled': False},
            'required_pull_request_reviews': {
                'required_approving_review_count': 0,
                'bypass_pull_request_allowances': {'users': [], 'teams': [], 'apps': []},
            },
        }},
        'workflow_permissions': {'status': 200, 'body': {
            'default_workflow_permissions': 'read',
            'can_approve_pull_request_reviews': False,
        }},
    }


class PublicationPolicyTests(unittest.TestCase):
    def run_fixture(self, data, *extra):
        self.assertTrue(SCRIPT.is_file(), 'Read-only publication preflight is missing')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.json'
            path.write_text(json.dumps(data))
            result = subprocess.run(
                [sys.executable, str(SCRIPT), '--repo', 'sample/public',
                 '--fixture', str(path), *extra], capture_output=True, text=True)
        self.assertEqual(result.stderr, '')
        return result, json.loads(result.stdout)

    def assert_blocked(self, data, rule):
        result, report = self.run_fixture(data)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(report['launch_ready'])
        self.assertIn(rule, report['failures'])
        return report

    def test_complete_offline_evidence_is_planning_only(self):
        result, report = self.run_fixture(evidence())
        self.assertEqual(result.returncode, 0)
        self.assertTrue(report['policy_matches'])
        self.assertFalse(report['launch_ready'])
        self.assertEqual(report['evidence'], 'fixture-unverified')

    def test_missing_required_check_blocks(self):
        data = evidence()
        data['protection']['body']['required_status_checks']['checks'] = []
        self.assert_blocked(data, 'branch.required_checks')

    def test_check_from_any_app_cannot_satisfy_required_gate(self):
        data = evidence()
        data['protection']['body']['required_status_checks']['checks'][0]['app_id'] = -1
        self.assert_blocked(data, 'branch.required_checks')

    def test_branch_bypass_blocks(self):
        data = evidence()
        data['protection']['body']['required_pull_request_reviews']['bypass_pull_request_allowances']['users'] = [{'login': 'example'}]
        self.assert_blocked(data, 'branch.no_bypass')

    def test_missing_pull_request_policy_evidence_blocks(self):
        data = evidence()
        del data['protection']['body']['required_pull_request_reviews']['required_approving_review_count']
        self.assert_blocked(data, 'branch.require_pull_request')

    def test_missing_bypass_evidence_blocks(self):
        data = evidence()
        del data['protection']['body']['required_pull_request_reviews']['bypass_pull_request_allowances']
        self.assert_blocked(data, 'branch.no_bypass')

    def test_private_preview_never_matches_public_policy(self):
        data = evidence()
        data['repository']['body'].update(private=True, visibility='private')
        self.assert_blocked(data, 'repository.public')

    def test_wrong_parent_blocks(self):
        data = evidence()
        data['repository']['body']['parent']['full_name'] = 'sample/unrelated'
        self.assert_blocked(data, 'repository.parent')

    def test_wrong_repository_blocks(self):
        data = evidence()
        data['repository']['body']['full_name'] = 'sample/other'
        self.assert_blocked(data, 'repository.identity')

    def test_unavailable_api_evidence_is_never_treated_as_enabled(self):
        for key in ('repository', 'protection', 'workflow_permissions'):
            for status in (403, 404, 429, 500):
                with self.subTest(endpoint=key, status=status):
                    data = evidence()
                    data[key] = {'status': status, 'body': {'message': 'sensitive payload'}}
                    report = self.assert_blocked(data, key + '.unverified')
                    self.assertNotIn('sensitive payload', json.dumps(report))

    def test_disabled_native_push_protection_blocks(self):
        data = evidence()
        data['repository']['body']['security_and_analysis']['secret_scanning_push_protection']['status'] = 'disabled'
        self.assert_blocked(data, 'native.secret_scanning_push_protection')

    def test_missing_native_settings_blocks(self):
        data = evidence()
        data['repository']['body']['security_and_analysis'] = None
        self.assert_blocked(data, 'native.secret_scanning')

    def test_each_branch_and_actions_protection_is_required(self):
        mutations = [
            ('enforce_admins', True, False, 'branch.enforce_admins'),
            ('allow_force_pushes', False, True, 'branch.no_force_pushes'),
            ('allow_deletions', False, True, 'branch.no_deletions'),
        ]
        for key, _, changed, rule in mutations:
            with self.subTest(key=key):
                data = evidence()
                data['protection']['body'][key]['enabled'] = changed
                self.assert_blocked(data, rule)
        data = evidence()
        data['protection']['body']['required_status_checks']['strict'] = False
        self.assert_blocked(data, 'branch.strict_checks')
        data = evidence()
        data['workflow_permissions']['body']['default_workflow_permissions'] = 'write'
        self.assert_blocked(data, 'actions.read_only')
        data = evidence()
        data['workflow_permissions']['body']['can_approve_pull_request_reviews'] = True
        self.assert_blocked(data, 'actions.no_pr_approval')

    def test_branch_evidence_must_match_actual_default(self):
        data = evidence()
        data['branch'] = 'different'
        self.assert_blocked(data, 'branch.identity')

    def test_malformed_evidence_has_sanitized_json_report(self):
        for data in (None, [], {'repository': 'bad'}, {'repository': {'status': 200, 'body': []}}):
            with self.subTest(data=data):
                result, report = self.run_fixture(data)
                self.assertEqual(result.returncode, 1)
                self.assertFalse(report['launch_ready'])
                self.assertFalse(report['policy_matches'])

    def test_live_transport_uses_only_read_only_requests(self):
        self.assertTrue(SCRIPT.is_file(), 'Read-only publication preflight is missing')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'responses.json').write_text(json.dumps(evidence()))
            log = root / 'calls.jsonl'
            gh = root / 'gh'
            gh.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ['TEST_CALL_LOG']).open('a') as f:
    f.write(json.dumps(args) + '\\n')
e = json.loads(Path(os.environ['TEST_RESPONSES']).read_text())
endpoint = args[-1]
key = 'protection' if endpoint.endswith('/protection') else 'workflow_permissions' if endpoint.endswith('/actions/permissions/workflow') else 'repository'
print(json.dumps(e[key]['body']))
''')
            gh.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       TEST_CALL_LOG=str(log), TEST_RESPONSES=str(root / 'responses.json'))
            result = subprocess.run([sys.executable, str(SCRIPT), '--repo', 'sample/public'],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(json.loads(result.stdout)['launch_ready'])
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([call[-1] for call in calls], [
                '/repos/sample/public', '/repos/sample/public/branches/main/protection',
                '/repos/sample/public/actions/permissions/workflow'])
            for call in calls:
                self.assertEqual(call[0], 'api')
                self.assertEqual(call[call.index('--method') + 1], 'GET')
                self.assertEqual(call[call.index('--hostname') + 1], 'github.com')
                self.assertNotIn('--input', call)


class PublicHistoryWorkflowTests(unittest.TestCase):
    def run_history_step(self, private, ref, sha):
        # Execute the actual YAML block with modeled GitHub runner variables.
        # Removing tag selection must fail the annotated-tag regression below.
        workflow = SCRIPT.parents[1] / '.github/workflows/public-hygiene.yml'
        lines = workflow.read_text().splitlines()
        start = lines.index('      - name: Check complete public ancestry')
        run = lines.index('        run: |', start) + 1
        script = []
        for line in lines[run:]:
            if line.strip() and not line.startswith('          '):
                break
            script.append(line[10:])
        self.assertTrue(script, 'The history step must contain an executable command')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            # Substitute only the external history-checker boundary. Real Bash
            # evaluates the shipped step, so wrong refs/visibility branches fail.
            (root / 'scripts/check-public-history.py').write_text(
                'import json, sys\nfrom pathlib import Path\n'
                'Path("called.json").write_text(json.dumps(sys.argv[1:]))\n')
            result = subprocess.run(['bash', '--noprofile', '--norc', '-eo', 'pipefail', '-c', '\n'.join(script)],
                                    cwd=root, env=dict(os.environ, REPOSITORY_PRIVATE=private,
                                                       GITHUB_REF=ref, GITHUB_SHA=sha),
                                    capture_output=True, text=True)
            called = root / 'called.json'
            return result, json.loads(called.read_text()) if called.exists() else None

    def test_annotated_tag_object_is_passed_to_history_checker(self):
        result, called = self.run_history_step('false', 'refs/tags/v1', 'a' * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(called, ['--root', '.', '--tip', 'refs/tags/v1'])

    def test_pull_request_merge_commit_is_passed_to_history_checker(self):
        result, called = self.run_history_step('false', 'refs/pull/5/merge', 'b' * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(called, ['--root', '.', '--tip', 'b' * 40])

    def test_private_preview_does_not_clear_ancestry(self):
        result, called = self.run_history_step('true', 'refs/heads/main', 'c' * 40)
        self.assertEqual(result.returncode, 0)
        self.assertIsNone(called)
        self.assertIn('NOT cleared', result.stdout)

    def test_unknown_visibility_fails_closed(self):
        result, called = self.run_history_step('null', 'refs/heads/main', 'd' * 40)
        self.assertEqual(result.returncode, 1)
        self.assertIsNone(called)


if __name__ == '__main__':
    unittest.main()
