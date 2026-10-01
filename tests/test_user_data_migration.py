import tempfile
import unittest
from pathlib import Path
from core.user_paths import migrate_missing


class MigrationTests(unittest.TestCase):
    def test_preserves_destination_and_copies_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = Path(tmp) / 'old source', Path(tmp) / 'new user data'
            (old / 'config').mkdir(parents=True)
            (new / 'config').mkdir(parents=True)
            (old / 'config' / 'app_settings.json').write_text('old')
            (new / 'config' / 'app_settings.json').write_text('new')
            (old / 'config' / 'api_keys.json').write_text('test-only')
            (old / 'config' / '__init__.py').write_text('source code')
            migrate_missing(old, new)
            migrate_missing(old, new)
            self.assertEqual((new / 'config' / 'app_settings.json').read_text(), 'new')
            self.assertEqual((new / 'config' / 'api_keys.json').read_text(), 'test-only')
            self.assertFalse((new / 'config' / '__init__.py').exists())


if __name__ == '__main__':
    unittest.main()
