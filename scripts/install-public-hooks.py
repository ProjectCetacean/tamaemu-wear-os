#!/usr/bin/env python3
"""Explicitly install the publication pre-push guard without replacing local hooks.

Run this command yourself in each checkout. It copies the supplied hook into
Git's normal hooks directory, refuses any configured core.hooksPath and refuses
any existing pre-push file or symlink. It never changes Git configuration.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        result = subprocess.run(['git', '-C', str(args.root), 'rev-parse', '--show-toplevel'],
                                capture_output=True, check=True)
        root = Path(os.fsdecode(result.stdout).rstrip('\n'))
        config = subprocess.run(['git', '-C', str(root), 'config', '--get', 'core.hooksPath'],
                                capture_output=True)
        if config.returncode != 1:
            print('Hook installation refused: existing hooksPath or unreadable configuration.',
                  file=sys.stderr)
            return 1
        source = root / '.githooks/pre-push'
        if not source.is_file() or source.is_symlink():
            print('Hook installation requires the supplied regular pre-push hook.', file=sys.stderr)
            return 1
        result = subprocess.run(['git', '-C', str(root), 'rev-parse', '--git-path', 'hooks/pre-push'],
                                capture_output=True, check=True)
        destination = Path(os.fsdecode(result.stdout).rstrip('\n'))
        if not destination.is_absolute():
            destination = root / destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation refuses regular files, directories and dangling symlinks.
        with destination.open('xb') as output:
            output.write(source.read_bytes())
            os.fchmod(output.fileno(), 0o755)
        print('Publication pre-push hook installed; Git configuration unchanged.')
        return 0
    except FileExistsError:
        print('Hook installation refused: pre-push already exists; preserve or integrate it manually.',
              file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError):
        print('Hook installation failed; no existing hook was replaced.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
