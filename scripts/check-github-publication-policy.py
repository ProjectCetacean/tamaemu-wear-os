#!/usr/bin/env python3
"""Read-only GitHub launch preflight; never changes repository settings.

The checked-in JSON is a desired plan, not evidence of enforcement. By default,
GET current github.com REST responses through an already-authenticated gh CLI.
A --fixture JSON holds repository/protection/workflow_permissions envelopes
{status: HTTP_STATUS, body: REST_RESPONSE}, plus branch: DEFAULT_BRANCH.
Fixtures are offline planning evidence and can never report launch_ready=true.
Reports contain fixed rule IDs only, never raw API responses or stderr.
This verifies classic default-branch protection; ruleset-only configurations are
unverified and fail closed. It does not attest source/history or any APK.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


POLICY_PATH = Path(__file__).resolve().parents[1] / '.github/publication-policy.json'
# These minimums cannot be weakened by editing a desired plan accidentally.
MINIMUM = {
    'schema_version': 1,
    'expected_parent': 'maragotchi/tamaemu',
    'visibility': 'public',
    'native_security': {'secret_scanning': 'enabled',
                        'secret_scanning_push_protection': 'enabled'},
    'branch_protection': {
        'required_checks': ['Public hygiene'], 'check_app_id': 15368,
        'strict': True, 'enforce_admins': True, 'allow_force_pushes': False,
        'allow_deletions': False, 'require_pull_request': True,
        'required_approving_review_count': 0, 'allow_bypass': False,
    },
    'actions': {'default_workflow_permissions': 'read',
                'can_approve_pull_request_reviews': False},
}


def obj(value):
    return value if isinstance(value, dict) else {}


def api_get(endpoint):
    """GET only; unavailable, malformed, and denied responses stay unverified."""
    try:
        result = subprocess.run(
            ['gh', 'api', '--method', 'GET', '--hostname', 'github.com',
             '-H', 'Accept: application/vnd.github+json',
             '-H', 'X-GitHub-Api-Version: 2026-03-10', endpoint],
            capture_output=True, text=True, timeout=30, check=False)
        if result.returncode:
            return {'status': None, 'body': None}
        body = json.loads(result.stdout)
        return {'status': 200, 'body': body}
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return {'status': None, 'body': None}


def live_evidence(repo):
    data = {'repository': api_get('/repos/' + repo)}
    branch = obj(obj(data['repository']).get('body')).get('default_branch')
    if isinstance(branch, str) and branch:
        data['branch'] = branch
        data['protection'] = api_get('/repos/' + repo + '/branches/'
                                     + quote(branch, safe='') + '/protection')
    data['workflow_permissions'] = api_get('/repos/' + repo + '/actions/permissions/workflow')
    return data


def evaluate(data, repo, policy):
    """Compare documented response fields with the mandatory launch plan."""
    failures = []

    def require(ok, rule):
        if not ok:
            failures.append(rule)

    data = obj(data)

    def response(key):
        envelope = obj(data.get(key))
        body = envelope.get('body')
        valid = type(envelope.get('status')) is int and envelope['status'] == 200 and isinstance(body, dict)
        require(valid, key + '.unverified')
        return body if valid else {}

    repository = response('repository')
    branch = response('protection')
    actions = response('workflow_permissions')
    full_name = repository.get('full_name')
    require(isinstance(full_name, str) and full_name.lower() == repo.lower(), 'repository.identity')
    require(repository.get('private') is False and repository.get('visibility') == policy['visibility'],
            'repository.public')
    require(repository.get('fork') is True and
            obj(repository.get('parent')).get('full_name') == policy['expected_parent'],
            'repository.parent')
    require(repository.get('archived') is False and repository.get('disabled') is False,
            'repository.active')
    default_branch = repository.get('default_branch')
    require(isinstance(default_branch, str) and bool(default_branch) and data.get('branch') == default_branch,
            'branch.identity')
    native = obj(repository.get('security_and_analysis'))
    for feature, expected in policy['native_security'].items():
        require(obj(native.get(feature)).get('status') == expected, 'native.' + feature)
    desired = policy['branch_protection']
    checks = obj(branch.get('required_status_checks'))
    required = checks.get('checks')
    present = set()
    if isinstance(required, list):
        for check in required:
            check = obj(check)
            if check.get('app_id') == desired['check_app_id'] and isinstance(check.get('context'), str):
                present.add(check['context'])
    require(set(desired['required_checks']).issubset(present), 'branch.required_checks')
    require(checks.get('strict') is True, 'branch.strict_checks')
    for key, expected, rule in (
        ('enforce_admins', True, 'branch.enforce_admins'),
        ('allow_force_pushes', False, 'branch.no_force_pushes'),
        ('allow_deletions', False, 'branch.no_deletions'),
    ):
        require(obj(branch.get(key)).get('enabled') is expected, rule)
    reviews = branch.get('required_pull_request_reviews')
    count = obj(reviews).get('required_approving_review_count')
    require(isinstance(reviews, dict) and type(count) is int and count >= desired['required_approving_review_count'],
            'branch.require_pull_request')
    bypass = obj(reviews).get('bypass_pull_request_allowances')
    require(isinstance(bypass, dict) and
            all(bypass.get(key) == [] for key in ('users', 'teams', 'apps')), 'branch.no_bypass')
    require(actions.get('default_workflow_permissions') == policy['actions']['default_workflow_permissions'],
            'actions.read_only')
    require(actions.get('can_approve_pull_request_reviews') is False, 'actions.no_pr_approval')
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, help='Explicit future public OWNER/REPO on github.com')
    parser.add_argument('--fixture', type=Path, help='Offline REST evidence; never live launch clearance')
    args = parser.parse_args(argv)
    report = {'evidence': 'fixture-unverified' if args.fixture else 'live-github-api',
              'policy_matches': False, 'launch_ready': False, 'failures': []}
    # Keep user-supplied repository strings, fixture contents, and paths out of output.
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}', args.repo):
        report['failures'] = ['input.repository']
    else:
        try:
            policy = json.loads(POLICY_PATH.read_text())
            if policy != MINIMUM:
                raise ValueError('invalid policy')
        except (OSError, ValueError):
            report['failures'] = ['policy.invalid']
        else:
            try:
                data = json.loads(args.fixture.read_text()) if args.fixture else live_evidence(args.repo)
                report['failures'] = evaluate(data, args.repo, policy)
            except (OSError, ValueError):
                report['failures'] = ['evidence.unreadable']
    report['policy_matches'] = not report['failures']
    report['launch_ready'] = report['policy_matches'] and not args.fixture
    print(json.dumps(report, sort_keys=True))
    return 0 if report['policy_matches'] else 1


if __name__ == '__main__':
    sys.exit(main())
