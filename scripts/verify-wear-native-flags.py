#!/usr/bin/env python3
"""Verify the actual Android Debug compile database keeps the core optimized."""
import json
from pathlib import Path
import shlex
import shutil
import sys

SOURCES = {'cpu.c','mem.c','periph.c','lcd.c','device.c','disasm.c','pn512.c',
           'backend.c','standalone.c','sha256.c','jni.c'}
ABIS = {'armeabi-v7a','arm64-v8a','x86_64'}

def check_commands(entries):
    checked = set()
    for entry in entries:
        name = Path(entry['file']).name
        if name not in SOURCES:
            continue
        args = entry.get('arguments') or shlex.split(entry['command'])
        optimization = [arg for arg in args if arg.startswith('-O')]
        if not optimization or optimization[-1] != '-O2':
            raise AssertionError(name+': final optimization flag must be -O2; got '+str(optimization))
        if any(arg in args for arg in ('-Ofast','-ffast-math','-funsafe-math-optimizations','-ffinite-math-only')):
            raise AssertionError(name+': unsafe floating-point optimization flag')
        if not any(arg.startswith('-g') for arg in args):
            raise AssertionError(name+': debug symbols missing')
        checked.add(name)
    if checked != SOURCES:
        raise AssertionError('Incomplete native compile database: '+str(SOURCES-checked))
    return len(checked)

def main():
    root = Path(__file__).resolve().parents[1]
    databases = list((root/'wear/app/.cxx/Debug').glob('**/compile_commands.json'))
    if not databases:
        raise SystemExit('No Android Debug compiler commands found; assembleDebug must run first.')
    found = set()
    report = root/'build/android-native-flags'
    report.mkdir(parents=True,exist_ok=True)
    for database in databases:
        abi = database.parent.name
        if abi not in ABIS:
            continue
        count = check_commands(json.loads(database.read_text()))
        found.add(abi)
        shutil.copyfile(database, report/(abi+'-compile_commands.json'))
        print(abi+': '+str(count)+' native sources use final -O2, retain debug symbols, no fast-math')
    if found != ABIS:
        raise SystemExit('Missing ABI compiler evidence: '+str(ABIS-found))

if __name__ == '__main__':
    main()
