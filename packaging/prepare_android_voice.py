"""Fetch pinned Apache-2.0 offline models and prepare assets for reproducible APK builds."""
from pathlib import Path, PurePosixPath
import hashlib
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODELS = [
    ('en', 'vosk-model-small-en-us-0.15', '30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'),
    ('hi', 'vosk-model-small-hi-0.22', '7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48'),
]

def main():
    assets = ROOT / 'friday-connect-android/app/src/main/assets'
    cache = ROOT / 'scratch/android-voice-models'; cache.mkdir(parents=True, exist_ok=True)
    records = []
    for language, name, digest in MODELS:
        archive = cache / (name + '.zip')
        if not archive.exists():
            urllib.request.urlretrieve('https://alphacephei.com/vosk/models/' + name + '.zip', archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Voice model checksum mismatch: ' + name)
        with zipfile.ZipFile(archive) as model:
            for entry in model.infolist():
                path = PurePosixPath(entry.filename)
                if path.is_absolute() or '..' in path.parts:
                    raise RuntimeError('Unsafe model archive path')
                relative = path.parts[1:]
                if not relative: continue
                target = assets / ('voice-' + language) / Path(*relative)
                if entry.is_dir(): target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(model.read(entry))
        records.append({'language': language, 'model': name, 'license': 'Apache-2.0', 'sha256': digest})
        print(name + ' verified and prepared', flush=True)
    (assets / 'voice-models.json').write_text(json.dumps(records, indent=2), encoding='utf-8')

if __name__ == '__main__': main()
