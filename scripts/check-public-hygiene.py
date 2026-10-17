#!/usr/bin/env python3
"""Check tracked working-tree files, or every file in an explicit publication export.

Git mode uses the index's path list and current file bytes, skips deleted paths,
and excludes untracked files until staged. CI checks the checked-out tracked tree.
The current directory or --root may be a subdirectory; Git mode always resolves
and scans the entire containing worktree.
Use --export-root for a prepared Git-free export. Findings show only path, line
and rule, never matched content. This catches selected high-confidence mistakes;
it is not a proof that renamed, encoded or unknown private material is absent.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


EXPECTED_BINARIES = {
    'wear/gradle/wrapper/gradle-wrapper.jar':
        {'81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'},
    'packaging/launcher.ico': {
        'e490492cd41d535790785525a26ee175ad0558fc2a0fcf1df29f913cd893c520',
        # Public upstream blob 2e812b377694e78d7d59aaeb03d0f6319c01864d.
        '98265af9f98fe79e4b0342ab770864b925f9e889ab366316209f079532468734',
    },
    'packaging/tamaemu.ico':
        {'e490492cd41d535790785525a26ee175ad0558fc2a0fcf1df29f913cd893c520'},
    # Public upstream blob b9e39d850308fd8ef3ce7311a341e0c735df3aa1.
    'packaging/tamaps.ico':
        {'97c9d18755728ebc10b253dc45e55e4831e93609edd5d76e51f5a814751b4558'},
}
PRIVATE_DIRS = {
    'private', 'build', 'artifacts', 'backups', 'captures', 'recordings',
    '.worktrees', '.tooling', '.gradle', '.cxx',
    '.externalnativebuild',
    '.codex', '.claude', '.agents', '.ssh', '.aws', 'working-context',
    'private-export', 'private-exports',
}
PRIVATE_NAMES = {
    'local.properties', 'rom_path.txt', '.ds_store', '.netrc', '.git-credentials',
    'agents.md', 'claude.md', 'resume.md', 'resume-state.md',
    'run-manifest.json', 'carry-manifest.json', 'review.md', 'audit-results.json',
}
PRIVATE_SUFFIX = re.compile(
    r'\.(?:bin|rom|dump|state|sav|pcm|pem|key|p12|pfx|p8|jks|keystore|apk|aab)'
    r'$|\.(?:state|sav)\.|\.(?:zip|tar|tgz|gz|bz2|xz|7z|rar)$', re.IGNORECASE)
CONTENT_RULES = {
    'private-key': re.compile(r'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----'),
    'credential': re.compile(
        r'\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{82,255}'
        r'|(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{35}'
        r'|xox[baprs]-[A-Za-z0-9-]{20,255}'
        r'|sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{48,255}'
        r'|glpat-[A-Za-z0-9_-]{20,255}|npm_[A-Za-z0-9]{36}'
        r'|pypi-[A-Za-z0-9_-]{50,255})\b'),
    'url-credential': re.compile(
        r'\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s/:@]+:[^\s/@]+@'),
    # Case-sensitive Unix paths avoid matching portable JDK Contents/Home paths.
    'home-path': re.compile(
        r'/(?:Users|home)/[A-Za-z0-9_.-]+(?:/|\b)'
        r'|[A-Za-z]:[\\/][Uu]sers[\\/][A-Za-z0-9_.-]+(?:[\\/]|\b)'),
}
ASSIGNMENT = re.compile(
    r'\b(?:password|passwd|pwd|(?:api|access|auth|client|refresh)[_-]?(?:token|key|secret)'
    r'|token|secret)[\"\']?\s*[:=]\s*'
    r'(?:\"([^\"\r\n]{10,})\"|\'([^\'\r\n]{10,})\'|([A-Za-z0-9_+/@=-]{12,})(?![A-Za-z0-9_.]))',
    re.IGNORECASE)
PLACEHOLDERS = re.compile(
    r'^(?:placeholder|replace[-_ ]?me|changeme|example|dummy|redacted|'
    r'your[-_ ].*|<.*>|\$\{.*\}|\$[A-Za-z_][A-Za-z0-9_]*)$', re.IGNORECASE)


def content_findings(data):
    """Yield line/rule only; shared by worktree and Git-object guards."""
    try:
        text = data.decode('utf-8')
        if '\0' in text:
            raise UnicodeError('binary')
    except UnicodeError:
        yield 0, 'unexpected-binary'
        return
    for number, line in enumerate(text.splitlines(), 1):
        for rule, pattern in CONTENT_RULES.items():
            if pattern.search(line):
                yield number, rule
        for match in ASSIGNMENT.finditer(line):
            value = next(group for group in match.groups() if group is not None)
            # Bare literals need letters and digits; ordinary source identifiers
            # and method/property expressions are not credential assignments.
            if match.group(3) and not (re.search(r'[A-Za-z]', value) and re.search(r'[0-9]', value)):
                continue
            if not PLACEHOLDERS.fullmatch(value):
                yield number, 'assigned-credential'
                break


def display_path(name):
    """A credential-shaped filename must not become a diagnostic leak."""
    if any(content_findings(os.fsencode(name))):
        return '<redacted-path:' + hashlib.sha256(os.fsencode(name)).hexdigest() + '>'
    return name


def expected_binary(name, digest):
    """Accept only an exact public binary digest at its verified Git path."""
    return digest in EXPECTED_BINARIES.get(name, ())


def data_findings(name, data):
    if any(content_findings(os.fsencode(name))):
        yield 0, 'sensitive-path'
    if private_path(name):
        yield 0, 'private-path'
        return
    if name in EXPECTED_BINARIES:
        if not expected_binary(name, hashlib.sha256(data).hexdigest()):
            yield 0, 'unexpected-binary'
        return
    yield from content_findings(data)


def private_path(name):
    path = PurePosixPath(name)
    return (any(part.lower() in PRIVATE_DIRS for part in path.parts)
            or (path.name.lower().startswith('.env')
                and path.name != '.env.example'
                and (path.name.lower() == '.env' or path.name.lower().startswith('.env.')))
            or path.name.lower() in PRIVATE_NAMES
            or PRIVATE_SUFFIX.search(path.name) is not None)


def paths_to_check(root, export):
    if not export:
        result = subprocess.run(['git', '-C', str(root), 'ls-files', '-z'],
                                check=True, capture_output=True)
        return sorted(set(os.fsdecode(name) for name in result.stdout.split(b'\0') if name))
    paths = []
    for current, directories, files in os.walk(root, followlinks=False):
        for name in directories[:]:
            path = Path(current) / name
            if path.is_symlink():
                files.append(name)
                directories.remove(name)
        paths.extend((Path(current) / name).relative_to(root).as_posix() for name in files)
    return sorted(paths)


def findings(root, names):
    for name in names:
        path = root / name
        # A concurrent/staged deletion is absent from the effective publication.
        if not path.exists() and not path.is_symlink():
            continue
        if private_path(name):
            yield name, 0, 'private-path'
            continue
        try:
            path.resolve().relative_to(root)
        except (ValueError, RuntimeError):
            yield name, 0, 'escaping-symlink'
            continue
        if path.is_symlink():
            data = os.fsencode(os.readlink(path))
        elif path.is_file():
            data = path.read_bytes()
        else:
            yield name, 0, 'unsupported-file'
            continue
        for number, rule in data_findings(name, data):
            yield name, number, rule


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--root', type=Path, default=Path.cwd(),
                      help='Git checkout or subdirectory; scan the entire containing worktree')
    mode.add_argument('--export-root', type=Path, help='Scan every file in a prepared export')
    args = parser.parse_args()
    export = args.export_root is not None
    root = (args.export_root if export else args.root).resolve()
    if not root.is_dir():
        print('Publication check requires an existing directory.', file=sys.stderr)
        return 2
    try:
        if not export:
            result = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                                    check=True, capture_output=True)
            root = Path(os.fsdecode(result.stdout).rstrip('\n')).resolve()
        names = paths_to_check(root, export)
        issues = list(findings(root, names))
    except (OSError, subprocess.CalledProcessError):
        print('Publication check could not read the tree; use a Git checkout or --export-root.',
              file=sys.stderr)
        return 2
    for name, line, rule in issues:
        print(f'{json.dumps(display_path(name), ensure_ascii=True)}:{line}:{rule}')
    print(f'Public hygiene: {len(names)} listed files, {len(issues)} findings.')
    if not export:
        print('Untracked files are excluded until staged; deleted tracked paths are skipped.')
    return 1 if issues else 0


if __name__ == '__main__':
    sys.exit(main())
