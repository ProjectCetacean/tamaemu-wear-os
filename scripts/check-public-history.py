#!/usr/bin/env python3
"""Check publication Git objects, including deleted intermediate files and metadata.

Every reachable commit of every proposed nondeleted tip is scanned. --base and
remote OIDs are validated, never trusted as an audit baseline or used to exclude
ancestors. This deliberately rescans old ancestry; no baseline exemption exists.
--pre-push consumes Git's exact newline-terminated UTF-8 four-field ref tuples.
Exactly empty input is a successful no-op. Truncated, CR/NUL, invalid UTF-8,
malformed, shallow, missing-object and unsupported-object input fails closed.
A deletion publishes no objects. Annotated tag chains are checked too.

--check-docs additionally checks a temporary export of each prospective commit
tip using check-public-docs.py --export-root PATH. It does not read dirty files.
Findings are JSON lines containing only object IDs, redacted paths, lines, rules.
Git error messages and source/metadata snippets are never printed.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import sys
import tempfile
import unicodedata


spec = importlib.util.spec_from_file_location(
    'public_hygiene', Path(__file__).with_name('check-public-hygiene.py'))
hygiene = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hygiene)


class CheckError(Exception):
    pass


def git(root, *args):
    result = subprocess.run(['git', '--no-replace-objects', '-C', str(root), *args],
                            capture_output=True)
    if result.returncode:
        raise CheckError('git-object-read')
    return result.stdout


def resolve(root, revision):
    if not revision or revision.startswith('-'):
        raise CheckError('invalid-revision')
    value = git(root, 'rev-parse', '--verify', '--end-of-options',
                revision + '^{object}').decode('ascii').strip()
    if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', value):
        raise CheckError('invalid-object-id')
    return value


def ref_valid(root, name):
    if not name.startswith('refs/'):
        raise CheckError('invalid-ref')
    git(root, 'check-ref-format', name)


def push_tips(root, data, oid_length):
    if data == '':
        return []
    if '\r' in data or '\0' in data or not data.endswith('\n'):
        raise CheckError('invalid-push-framing')
    tips = []
    seen = set()
    for line in data.split('\n')[:-1]:
        fields = line.split(' ')
        if len(fields) != 4 or any(not field for field in fields):
            raise CheckError('invalid-push-tuple')
        local_ref, local, remote_ref, remote = fields
        ref_valid(root, remote_ref)
        if remote_ref in seen:
            raise CheckError('duplicate-push-ref')
        seen.add(remote_ref)
        for oid in [local, remote]:
            if not re.fullmatch('[0-9a-f]{' + str(oid_length) + '}', oid):
                raise CheckError('invalid-push-object-id')
            if set(oid) != {'0'}:
                resolve(root, oid)
        if set(local) == {'0'}:
            if local_ref != '(delete)' or set(remote) == {'0'}:
                raise CheckError('invalid-delete-tuple')
            continue
        if local_ref.startswith('refs/'):
            ref_valid(root, local_ref)
        elif resolve(root, local_ref) != local:
            # Git supplies HEAD/revision expressions verbatim for detached pushes.
            raise CheckError('local-revision-object-mismatch')
        tips.append(local)
    return sorted(set(tips))


def report(oid, path, line, rule):
    print(json.dumps({'object': oid, 'path': hygiene.display_path(path),
                      'line': line, 'rule': rule}, ensure_ascii=True, sort_keys=True))


def metadata(root, oid, kind):
    raw = git(root, 'cat-file', kind, oid)
    issues = list(hygiene.content_findings(raw))
    for number, line in enumerate(raw.decode('utf-8', errors='replace').splitlines(), 1):
        if re.search(r'(?i)^(?:co-authored-by|author|committer|tagger):?\s+'
                     r'(?:(?:Claude(?: Code)?|ChatGPT|Copilot|Codex)\s*(?:<|$)|'
                     r'.*<(?:noreply|claude|codex)@(?:anthropic|openai)\.com>)', line):
            issues.append((number, 'tool-credit'))
    for line, rule in issues:
        report(oid, kind + '-metadata', line, rule)
    return len(issues)


def commit_tip(root, tip):
    """Check every annotated tag object; return its final commit and issue count."""
    seen = set()
    count = 0
    while True:
        if tip in seen:
            raise CheckError('tag-cycle')
        seen.add(tip)
        kind = git(root, 'cat-file', '-t', tip).decode('ascii').strip()
        if kind == 'commit':
            return tip, count
        if kind != 'tag':
            raise CheckError('unsupported-publish-target')
        count += metadata(root, tip, kind)
        raw = git(root, 'cat-file', 'tag', tip)
        match = re.match(rb'object ([0-9a-f]{40}|[0-9a-f]{64})\n', raw)
        if not match:
            raise CheckError('invalid-tag-object')
        tip = resolve(root, match.group(1).decode('ascii'))


def entries(root, commit):
    raw = git(root, 'ls-tree', '-rz', '--full-tree', commit)
    if raw and not raw.endswith(b'\0'):
        raise CheckError('truncated-tree')
    for entry in raw.split(b'\0'):
        if not entry:
            continue
        header, name = entry.split(b'\t', 1)
        mode, kind, oid = header.decode('ascii').split(' ')
        name = os.fsdecode(name)
        if name.startswith('/') or any(part in {'', '.', '..'} for part in name.split('/')):
            raise CheckError('unsafe-tree-path')
        yield mode, kind, oid, name


def safe_symlink(name, data):
    target = os.fsdecode(data)
    combined = posixpath.normpath(posixpath.join(str(PurePosixPath(name).parent), target))
    return bool(target) and not target.startswith('/') and combined != '..' and not combined.startswith('../')


def snapshot(root, commit):
    count = 0
    for mode, kind, oid, name in entries(root, commit):
        if kind != 'blob' or mode not in {'100644', '100755', '120000'}:
            report(oid, name, 0, 'unsupported-git-entry')
            count += 1
            continue
        data = git(root, 'cat-file', 'blob', oid)
        if mode == '120000' and not safe_symlink(name, data):
            report(oid, name, 0, 'escaping-symlink')
            count += 1
        for line, rule in hygiene.data_findings(name, data):
            # Commit identifies where the blob was published; object identifies its bytes.
            report(commit, name, line, rule)
            count += 1
    return count


def validate_export_paths(tree):
    """Refuse aliases before any writes, including directory-prefix aliases."""
    aliases = {}
    leaves = set()
    for mode, kind, oid, name in tree:
        if kind != 'blob' or mode not in {'100644', '100755', '120000'}:
            raise CheckError('unsupported-export-entry')
        canonical = []
        original = []
        parts = name.split('/')
        for number, part in enumerate(parts):
            # Windows aliases/invalid components cannot represent this Git tree
            # faithfully, even if a Unix export would happen to preserve them.
            if (part.endswith((' ', '.')) or re.search(r'[<>:"\\|?*\x00-\x1f]', part)
                    or re.fullmatch(r'(?i)(?:CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3])',
                                    part.split('.', 1)[0])):
                raise CheckError('nonportable-export-path')
            original.append(part)
            canonical.append(unicodedata.normalize(
                'NFD', unicodedata.normalize('NFD', part).casefold()))
            key = tuple(canonical)
            spelling = tuple(original)
            if key in aliases and aliases[key] != spelling:
                raise CheckError('aliased-export-path')
            if key in leaves or (number == len(parts) - 1 and key in aliases):
                raise CheckError('aliased-export-path')
            aliases[key] = spelling
            if number == len(parts) - 1:
                leaves.add(key)


def check_docs(root, commit):
    checker = Path(__file__).with_name('check-public-docs.py')
    if not checker.is_file():
        raise CheckError('missing-docs-checker')
    tree = list(entries(root, commit))
    validate_export_paths(tree)
    with tempfile.TemporaryDirectory(prefix='publication-docs-') as directory:
        export = Path(directory)
        for mode, kind, oid, name in tree:
            data = git(root, 'cat-file', 'blob', oid)
            # Never follow a Git-controlled directory symlink while exporting.
            target = export / name
            for parent in target.parents:
                if parent == export:
                    break
                if parent.is_symlink():
                    raise CheckError('symlink-export-parent')
            target.parent.mkdir(parents=True, exist_ok=True)
            if mode == '120000':
                if not safe_symlink(name, data):
                    raise CheckError('escaping-export-symlink')
                target.symlink_to(os.fsdecode(data))
            else:
                target.write_bytes(data)
        result = subprocess.run([sys.executable, str(checker), '--export-root', str(export)],
                                capture_output=True)
        if result.returncode:
            # Checker diagnostics are intentionally suppressed at this boundary.
            report(commit, 'committed-documents', 0, 'docs-check-failed')
            return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--tip', help='Commit/tag revision (default: HEAD)')
    parser.add_argument('--base', help='Validate this object; does not exclude its ancestry')
    parser.add_argument('--pre-push', action='store_true', help='Read all Git pre-push tuples from stdin')
    parser.add_argument('--check-docs', action='store_true', help='Also lint committed tip exports')
    args = parser.parse_args()
    try:
        if args.pre_push and (args.tip or args.base):
            raise CheckError('incompatible-modes')
        root = Path(os.fsdecode(git(args.root, 'rev-parse', '--show-toplevel')).rstrip('\n'))
        object_format = git(root, 'rev-parse', '--show-object-format').strip()
        if object_format not in {b'sha1', b'sha256'}:
            raise CheckError('unsupported-object-format')
        length = 40 if object_format == b'sha1' else 64
        empty_push = False
        if args.pre_push:
            data = sys.stdin.buffer.read()
            empty_push = data == b''
            tips = push_tips(root, data.decode('utf-8', errors='strict'), length)
        else:
            if args.base:
                resolve(root, args.base)
            tips = [resolve(root, args.tip or 'HEAD')]
        if not tips:
            print('Public history: nothing to publish.' if empty_push else
                  'Public history: deletion only; no objects published.')
            return 0
        if git(root, 'rev-parse', '--is-shallow-repository').strip() != b'false':
            raise CheckError('shallow-history')
        graft_file = Path(os.fsdecode(git(root, 'rev-parse', '--git-path', 'info/grafts')).rstrip('\n'))
        if not graft_file.is_absolute():
            graft_file = root / graft_file
        if graft_file.exists() and graft_file.stat().st_size:
            raise CheckError('grafted-history')
        commits = set()
        tip_commits = set()
        count = 0
        for tip in tips:
            commit, issues = commit_tip(root, tip)
            tip_commits.add(commit)
            count += issues
            commits.update(git(root, 'rev-list', commit).decode('ascii').splitlines())
        for commit in sorted(commits):
            count += metadata(root, commit, 'commit')
            count += snapshot(root, commit)
        # Privacy failures must never be handed to another checker that may echo content.
        if args.check_docs and not count:
            for commit in sorted(tip_commits):
                count += check_docs(root, commit)
        print(f'Public history: {len(tips)} tips, {len(commits)} commits, {count} findings; full ancestry scanned.')
        return 1 if count else 0
    except (CheckError, OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        print('Public history: unable to validate complete publication objects/input.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
