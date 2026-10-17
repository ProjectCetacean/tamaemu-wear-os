#!/usr/bin/env python3
"""Bounded public Markdown checks. No network requests or repository writes."""
import argparse
import hashlib
import html
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

REQUIRED = ('README.md', 'CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md',
            'docs/WEAR_OS.md', 'docs/VERIFICATION.md', 'docs/RELEASE_NOTES.md')
# Exact inherited desktop documents: edits lose the prose exemption. Links and
# UTF-8/path checks still apply. License texts are outside Markdown discovery.
UPSTREAM = {
    'docs/device-map.md': frozenset({'824233ebeff4167bad778c51d60ecfbbd68fe5ce25d828baeef9741f6f37cdc0'}),
    'packaging/README.txt': frozenset({
        '17aa6e37a2af3f9f74172dbb4795ffb1ba5e2fdd6ab6f13fe5c3d3822ab579e3',
        # Verified upstream 79e19b6, Git blob 946ec15990ff6e35d5282d03c0546193330b38a7.
        'd71c092a6455df26347b3f1d087b4aac6cf16c7c5a091d1bceca9c2efb4372ee',
    }),
}
WORDS = ('delve landscape evolving insight nuanced paradigm comprehensive framework '
         'facet dynamic intricacies holistic iterative synergy confluence pivotal nuance '
         'robust transformative underpinning spectrum trajectory leverage').split()
PHRASES = ('in fact', 'indeed', 'absolutely', 'clearly', 'first and foremost',
           'as a result', 'therefore', 'in other words', 'to put it simply', 'for example',
           'although', 'in summary', 'in conclusion', 'imagine if', 'what if',
           'have you ever wondered', 'on one hand', 'the challenge is', 'the question remains')
RULES = {
    'em-dash': re.compile('—'),
    'filler': re.compile(r'\b(?:' + '|'.join(map(re.escape, WORDS + list(PHRASES))) + r')\b', re.I),
    'placeholder': re.compile(r'\b(?:TODO|FIXME|TBD|PLACEHOLDER|lorem ipsum)\b|<insert\b', re.I),
    'tool-credit': re.compile(r'co[- ]authored[- ]by\s*:|(?:generated|written|built|assisted)\s+(?:with|by)\s+(?:AI|ChatGPT|Claude|Codex|Copilot)\b', re.I),
    'unsupported-claim': re.compile(r'\b(?:production[- ]ready|release[- ]ready|zero[- ]latency|'
        r'no\s+(?:audio\s+)?(?:latency|lag)|(?:100%|fully|completely)\s+(?:safe|secure|tested)|'
        r'no\s+data\s+loss(?:\s+is\s+possible)?|guarantee[sd]?\s+(?:safety|security|no\s+data\s+loss))\b', re.I),
}
PRIVATE = re.compile(r'^(?:AGENTS|CLAUDE|RESUME(?:-STATE)?|MEMORY|HANDOFF|TASK[-_]SPEC|REVIEW[-_]REPORT)\.md$', re.I)
INLINE_CODE = re.compile(r'(`+)(.+?)\1', re.S)
LINK = re.compile(r'!?\[([^\]\n]*)\]\(\s*(<[^>\n]*>|[^\s\n]+?)(?:\s+["\'][^\n]*?["\'])?\s*\)')
REF = re.compile(r'^ {0,3}\[([^\]]+)\]:\s*(<[^>]+>|\S+)', re.M)
REF_USE = re.compile(r'!?\[([^\]\n]+)\]\[([^\]\n]*)\]')


def without_fences(text):
    output, fence = [], None
    for line in text.splitlines(keepends=True):
        match = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if fence:
            if re.match(r'^ {0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*$', line):
                fence = None
            output.append('\n' if line.endswith('\n') else '')
        elif match:
            fence = match.group(1)
            output.append('\n' if line.endswith('\n') else '')
        elif line.startswith('    ') or line.startswith('\t'):
            output.append('\n' if line.endswith('\n') else '')
        else:
            output.append(line)
    return ''.join(output)


def anchors(text):
    used, found = set(), set()
    lines = without_fences(text).splitlines()
    for index, line in enumerate(lines):
        match = re.match(r'^ {0,3}#{1,6}\s+(.+?)(?:\s+#+)?\s*$', line)
        title = match.group(1) if match else None
        if title is None and index + 1 < len(lines) and re.fullmatch(r' {0,3}(?:=+|-+)\s*', lines[index + 1]) and line.strip():
            title = line.strip()
        if title is None:
            continue
        title = re.sub(r'!?\[([^]]*)\]\([^)]*\)', r'\1', title)
        title = html.unescape(re.sub(r'<[^>]*>', '', title)).lower()
        slug = ''.join(char for char in title if char.isalnum() or char in '_- ')
        slug = slug.replace(' ', '-')
        candidate, suffix = slug, 0
        while candidate in used:
            suffix += 1
            candidate = slug + '-' + str(suffix)
        used.add(candidate)
        found.add(candidate)
    found.update(re.findall(r'<a\s+(?:name|id)=["\']([^"\']+)["\']', text, re.I))
    return found


def safe_path(root, name):
    path = root / name
    if not path.resolve().is_relative_to(root):
        raise ValueError('link: path escapes root')
    relative = path.relative_to(root)
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('symlink: document or link crosses a symlink')
    return path


def check(root, names):
    problems, texts = [], {}
    def fail(name, rule, detail):
        problems.append(f'{name}: {rule}: {detail}')
    docs = sorted(name for name in names if name.lower().endswith(('.md', '.markdown')) or name in UPSTREAM)
    if not docs:
        fail('.', 'no-documents', 'no Markdown documents discovered')
    for name in REQUIRED:
        if name not in names:
            fail(name, 'required', 'required public document is missing')
    for name in docs:
        if PRIVATE.match(PurePosixPath(name).name) or any(part.lower() in ('private', 'plans', 'memory', 'rollout_summaries') for part in PurePosixPath(name).parts):
            fail(name, 'private-document', 'internal process document must stay outside the public tree')
        try:
            path = safe_path(root, name)
            if not path.stat().st_mode & 0o444:
                raise ValueError('read: document has no read permission')
            data = path.read_bytes()
            text = data.decode('utf-8')
            texts[name] = text
        except (OSError, ValueError) as error:
            fail(name, 'read', str(error))
            continue
        visible = without_fences(text)
        links = [(m.group(2).strip('<>'), m.start()) for m in LINK.finditer(INLINE_CODE.sub('', visible))]
        links.extend((m.group(2), m.start()) for m in re.finditer(r'''<(?:a|img)\b[^>]*?\b(?:href|src)\s*=\s*(["'])(.*?)\1''', INLINE_CODE.sub('', visible), re.I))
        references = {m.group(1).casefold(): m.group(2).strip('<>') for m in REF.finditer(visible)}
        for match in REF_USE.finditer(INLINE_CODE.sub('', visible)):
            key = (match.group(2) or match.group(1)).casefold()
            if key not in references:
                fail(name, 'link', 'undefined reference: ' + key)
            else:
                links.append((references[key], match.start()))
        links.extend((target, 0) for target in references.values())
        for target, position in links:
            try:
                decoded = unquote(html.unescape(target))
                parsed = urlsplit(decoded)
                if parsed.scheme in ('https', 'http', 'mailto'):
                    continue
                if parsed.scheme or parsed.netloc or '\\' in decoded or any(ord(c) < 32 for c in decoded):
                    raise ValueError('link: unsupported or unsafe destination')
                dest = parsed.path.lstrip('/') if parsed.path.startswith('/') else str(PurePosixPath(name).parent / parsed.path) if parsed.path else name
                dest = os.path.normpath(dest)
                path = safe_path(root, dest)
                if dest not in names and not any(item.startswith(dest.rstrip('/') + '/') for item in names):
                    raise ValueError('link: target is absent from checked tree')
                if not path.exists():
                    raise ValueError('link: target does not exist')
                if parsed.fragment:
                    if not path.is_file() or path.suffix.lower() not in ('.md', '.markdown'):
                        raise ValueError('anchor: local fragments require a Markdown target')
                    if parsed.fragment not in anchors(path.read_text(encoding='utf-8')):
                        raise ValueError('anchor: missing heading ' + parsed.fragment)
            except (OSError, ValueError) as error:
                fail(name, 'link', f'line {visible.count(chr(10), 0, position) + 1}: {error}')
        if hashlib.sha256(data).hexdigest() in UPSTREAM.get(name, ()):
            continue
        prose = INLINE_CODE.sub('', visible)
        prose = REF.sub('', prose)
        prose = LINK.sub(lambda m: m.group(1), prose)
        prose = re.sub(r'https?://[^\s<>]+|<[^>]+>', '', prose)
        for rule, pattern in RULES.items():
            for match in pattern.finditer(prose):
                fail(name, rule, f'line {prose.count(chr(10), 0, match.start()) + 1}: revise authored prose')
    return problems, len(docs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--root', type=Path, help='Git top-level; tracked/staged paths, current file bytes')
    group.add_argument('--export-root', type=Path, help='complete source export; no Git commands')
    args = parser.parse_args()
    try:
        if args.export_root:
            root = args.export_root.resolve(strict=True)
            names = set()
            def walk_error(error):
                raise error
            for directory, dirs, files in os.walk(root, followlinks=False, onerror=walk_error):
                if '.git' in dirs:
                    dirs.remove('.git')
                for name in dirs + files:
                    path = Path(directory) / name
                    if path.is_symlink():
                        raise ValueError('symlink: export contains a symlink')
                names.update(str((Path(directory) / name).relative_to(root)) for name in files)
        else:
            requested = (args.root or Path.cwd()).resolve(strict=True)
            root = Path(subprocess.check_output(['git', '-C', str(requested), 'rev-parse', '--show-toplevel'], text=True).strip()).resolve()
            if args.root and root != requested:
                raise ValueError('--root must name the Git top-level')
            entries = subprocess.check_output(['git', '-C', str(root), 'ls-files', '--stage', '-z']).decode('utf-8').split('\0')
            names = set()
            for entry in filter(None, entries):
                metadata, name = entry.split('\t', 1)
                mode, _object, stage = metadata.split()
                if stage != '0' or mode == '120000':
                    raise ValueError('symlink or unresolved index entry')
                names.add(name)
        if not root.is_dir():
            raise ValueError('root is not a directory')
        problems, count = check(root, names)
        for problem in problems:
            print(problem)
        if problems:
            print(f'Public documentation: FAIL ({len(problems)} findings; {count} documents)')
            return 1
        print(f'Public documentation: PASS ({count} documents; human review still required)')
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Public documentation: FAIL: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
