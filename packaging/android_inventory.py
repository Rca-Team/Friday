"""Build the Android coverage asset from actual desktop registrations."""
import sys
from pathlib import Path
import json
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from friday_connect.gateway.mobile_api import desktop_inventory
root = Path(__file__).resolve().parents[1]
asset = root / 'friday-connect-android/app/src/main/assets/feature-coverage.json'
asset.write_text(json.dumps(desktop_inventory(root / 'main.py'), indent=2, ensure_ascii=False), encoding='utf-8')
print('Inventoried', len(json.loads(asset.read_text(encoding='utf-8'))), 'desktop tools')
