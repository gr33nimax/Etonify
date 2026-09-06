import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("hydrate", Path(__file__).with_name("hydrate_libbox.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class HydrationTest(unittest.TestCase):
    def test_mismatch_does_not_replace_existing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            target = root / "libbox.aar"
            target.write_bytes(b"existing")
            provenance = {"source": {"repository": "https://github.com/gr33nimax/hydracore", "version": "pinned"}, "artifacts": {"hydracore-client-libbox.aar": {"sha256": hashlib.sha256(b"expected").hexdigest()}}}
            with patch.object(module.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong")):
                with self.assertRaises(ValueError):
                    module.hydrate(provenance, target)
            self.assertEqual(b"existing", target.read_bytes())
            self.assertEqual([target], list(root.iterdir()))

    def test_downloads_pinned_artifact_and_reuses_verified_file(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "libbox.aar"
            data = b"test archive"
            provenance = {"source": {"repository": "https://github.com/gr33nimax/hydracore", "version": "pinned"}, "artifacts": {"hydracore-client-libbox.aar": {"sha256": hashlib.sha256(data).hexdigest()}}}
            with patch.object(module.urllib.request, "urlopen", return_value=io.BytesIO(data)) as opened:
                module.hydrate(provenance, target)
                self.assertIn("/releases/download/pinned/hydracore-client-libbox.aar", opened.call_args.args[0])
                module.hydrate(provenance, target)
                self.assertEqual(1, opened.call_count)
            self.assertEqual(data, target.read_bytes())

if __name__ == "__main__":
    unittest.main()
