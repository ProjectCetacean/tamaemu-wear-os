#!/usr/bin/env python3
"""Persist a private local signer and sign CI APKs without an Android SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLING = ROOT / '.tooling'
PRIVATE = ROOT / 'private'
IDENTITY = PRIVATE / 'signing'
APKSIG_VERSION = '8.13.2'
APKSIG_SHA256 = 'c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89'
JAR = TOOLING / ('apksig-' + APKSIG_VERSION + '.jar')


def protected(path, directory=False):
    if path.is_symlink() or not path.exists():
        raise SystemExit('Missing or symlinked private signing material; refusing replacement: '+str(path))
    if directory != path.is_dir() or path.stat().st_mode & 0o077 or path.stat().st_uid != os.getuid():
        raise SystemExit('Private signing material must be owner-only: '+str(path))


def java(*args, capture=False):
    return subprocess.run([str(jdk/'bin/java'), '-cp', str(CLASSES)+os.pathsep+str(JAR),
                           'LocalApkSigner', *map(str,args)], check=True,
                          stdout=subprocess.PIPE if capture else None, text=True).stdout


def identity():
    protected(IDENTITY, directory=True)
    for name in ('keystore.p12', 'password.txt', 'identity.json'):
        protected(IDENTITY/name)
    record = json.loads((IDENTITY/'identity.json').read_text())
    actual = java('identity', IDENTITY/'keystore.p12', IDENTITY/'password.txt', capture=True).strip()
    if actual != record['certificate_sha256']:
        raise SystemExit('Private signing identity does not match record; refusing replacement.')
    return actual


def initialize():
    if IDENTITY.exists() or IDENTITY.is_symlink():
        return identity()
    staging = PRIVATE / ('signing-init-'+uuid.uuid4().hex)
    staging.mkdir(mode=0o700)
    # Incomplete staging is retained for diagnosis; it never replaces an existing identity.
    password_file = staging/'password.txt'
    with password_file.open('x') as out:
        os.chmod(password_file,0o600)
        out.write(secrets.token_urlsafe(40)+'\n')
    subprocess.run([str(jdk/'bin/keytool'), '-genkeypair', '-alias','tamaemu',
                    '-keystore', str(staging/'keystore.p12'), '-storetype','PKCS12',
                    '-storepass:file', str(password_file), '-keypass:file', str(password_file),
                    '-keyalg','RSA','-keysize','3072','-validity','12000',
                    '-dname','CN=Tamaemu Private Local Signing', '-noprompt'],check=True)
    os.chmod(staging/'keystore.p12',0o600)
    fingerprint = java('identity',staging/'keystore.p12',password_file,capture=True).strip()
    with (staging/'identity.json').open('x') as out:
        os.chmod(staging/'identity.json',0o600)
        json.dump({'certificate_sha256':fingerprint,'alias':'tamaemu','format':'PKCS12'},out,indent=2)
        out.write('\n')
    if IDENTITY.exists() or IDENTITY.is_symlink():
        raise SystemExit('Signing identity appeared during initialization; retained staging and refused replacement.')
    staging.rename(IDENTITY)
    return fingerprint


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jdk', type=Path, default=Path(os.environ.get('JAVA_HOME',
    str(TOOLING/'jdk-17.0.20.1+1'/'Contents'/'Home'))))
commands = parser.add_subparsers(dest='command',required=True)
commands.add_parser('init')
p=commands.add_parser('sign');p.add_argument('input',type=Path);p.add_argument('output',type=Path)
p=commands.add_parser('verify');p.add_argument('apk',type=Path)
options=parser.parse_args()
jdk=options.jdk.resolve()
if not (jdk/'bin/java').is_file() or not (jdk/'bin/javac').is_file():
    raise SystemExit('Set --jdk to an existing JDK17 directory.')
os.umask(0o077)
TOOLING.mkdir(exist_ok=True)
PRIVATE.mkdir(mode=0o700,exist_ok=True)
protected(PRIVATE,directory=True)
lock=PRIVATE/'.signing.lock'
try:
    lock_fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
except FileExistsError:
    raise SystemExit('Another signing operation is active, or a stale private/.signing.lock needs review.')
try:
    if not JAR.exists():
        url='https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/'+APKSIG_VERSION+'/apksig-'+APKSIG_VERSION+'.jar'
        content=urllib.request.urlopen(url,timeout=60).read()
        if hashlib.sha256(content).hexdigest()!=APKSIG_SHA256:
            raise SystemExit('Downloaded apksig hash mismatch.')
        with JAR.open('xb') as out: out.write(content)
    if JAR.is_symlink() or hashlib.sha256(JAR.read_bytes()).hexdigest()!=APKSIG_SHA256:
        raise SystemExit('Pinned apksig jar changed; refusing to use it.')
    CLASSES=TOOLING/'local-sign-classes';CLASSES.mkdir(exist_ok=True)
    subprocess.run([str(jdk/'bin/javac'),'-cp',str(JAR),'-d',str(CLASSES),
                    str(ROOT/'scripts/java/LocalApkSigner.java')],check=True)
    fingerprint=initialize() if options.command=='init' else identity()
    if options.command=='init':
        print('Durable local signing identity ready; certificate SHA-256 '+fingerprint)
    elif options.command=='verify':
        java('verify',options.apk.resolve(),fingerprint)
    else:
        source=options.input.resolve();target=options.output.absolute()
        if target.exists() or target.is_symlink() or source==target:
            raise SystemExit('Output already exists or equals input; refusing replacement.')
        with zipfile.ZipFile(source) as archive:
            names=archive.namelist()
            if 'classes.dex' not in names or 'AndroidManifest.xml' not in names:
                raise SystemExit('Input is not an application APK.')
        target.parent.mkdir(parents=True,exist_ok=True)
        fd,temporary=tempfile.mkstemp(prefix='.local-sign-',suffix='.apk',dir=target.parent)
        os.close(fd)
        try:
            java('sign',IDENTITY/'keystore.p12',IDENTITY/'password.txt',fingerprint,source,temporary)
            # Atomic exclusive publication preserves any existing destination.
            os.link(temporary,target)
        finally:
            Path(temporary).unlink(missing_ok=True)
        print('Signed local APK: '+str(target))
        print('APK SHA-256 '+hashlib.sha256(target.read_bytes()).hexdigest())
finally:
    os.close(lock_fd)
    lock.unlink()
