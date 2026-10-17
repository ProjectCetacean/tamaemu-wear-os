#!/usr/bin/env python3
"""Report ZIP/package size without opening or uploading any private input."""
import argparse
from pathlib import Path
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apks', nargs='+', type=Path)
p.add_argument('--baseline', type=Path, help='Optional prior APK for measured size delta')
a = p.parse_args()
for apk in a.apks:
    with zipfile.ZipFile(apk) as z:
        dex = sum(i.file_size for i in z.infolist() if i.filename.endswith('.dex'))
        native = sum(i.file_size for i in z.infolist() if i.filename.startswith('lib/'))
    size = apk.stat().st_size
    delta = f', delta vs baseline {size-a.baseline.stat().st_size:+,} bytes' if a.baseline else ', prior APK delta not measured'
    print(f'{apk.name}: {size:,} ZIP bytes, {dex:,} DEX bytes, {native:,} native bytes{delta}')
