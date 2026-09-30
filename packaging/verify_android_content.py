"""Inspect packaged Android assets; fail if required assistant assets are missing."""
from pathlib import Path
import hashlib
import json
import zipfile
import struct

root = Path(__file__).resolve().parents[1]
apk = root / 'dist/Friday-AI-Android-1.0.0.apk'
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    required = ['AndroidManifest.xml', 'classes.dex', 'assets/feature-coverage.json', 'assets/voice-models.json',
                'assets/voice-en/am/final.mdl', 'assets/voice-hi/am/final.mdl', 'assets/third-party/apache-2.0.txt']
    missing = [name for name in required if name not in names]
    if missing: raise RuntimeError('Missing APK assets: ' + ', '.join(missing))
    tools = json.loads(archive.read('assets/feature-coverage.json'))
    assert len(tools) == 57, 'Unexpected desktop registration count'
    models = json.loads(archive.read('assets/voice-models.json'))
    native = []
    for name in sorted(names):
        if not (name.startswith('lib/') and name.endswith('.so')): continue
        data = archive.read(name)
        if data[:4] != b'\x7fELF': continue
        endian = '<' if data[5] == 1 else '>'
        if data[4] == 2:
            offset = struct.unpack_from(endian + 'Q', data, 32)[0]
            size, count = struct.unpack_from(endian + 'HH', data, 54)
            alignment_offset, alignment_format = 48, 'Q'
        else:
            offset = struct.unpack_from(endian + 'I', data, 28)[0]
            size, count = struct.unpack_from(endian + 'HH', data, 42)
            alignment_offset, alignment_format = 28, 'I'
        alignments = []
        for i in range(count):
            header = offset + size * i
            if struct.unpack_from(endian + 'I', data, header)[0] == 1:
                alignments.append(struct.unpack_from(endian + alignment_format, data, header + alignment_offset)[0])
        native.append({'path': name, 'load_segment_alignment': alignments, 'elf_16kb_aligned': all(x >= 16384 for x in alignments)})
    proof = {'apk': apk.name, 'size_bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
             'required_assets_present': True, 'desktop_tool_count': len(tools), 'voice_models': models, 'native_libraries': native}
(root / 'dist/Friday-AI-Android-1.0.0-content.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in proof.items() if k != 'native_libraries'}, indent=2))
print('Native libraries without 16 KB ELF alignment:', [x['path'] for x in native if not x['elf_16kb_aligned']])
