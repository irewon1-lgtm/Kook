"""Gallery output must preserve original evidence and refuse misleading labels."""
import hashlib
import base64
import importlib.util
import json
import struct
import tempfile
import textwrap
import unittest
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class GalleryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "phone-light"
        self.source.mkdir()
        self.output = self.root / "gallery"

    def build(self):
        script = ROOT / "scripts/build_ui_gallery.py"
        self.assertTrue(script.exists(), "gallery builder is missing")
        spec = importlib.util.spec_from_file_location("gallery", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.build_gallery(self.root, self.output)

    def evidence(self, **updates):
        # A real, tiny PNG; arbitrary bytes or a PNG signature alone are insufficient.
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(b"\0\xff\xff\xff")) + chunk(b"IEND", b"")
        (self.source / "home.png").write_bytes(png)
        metadata = dict(evidence_kind="CONTROLLED_FIXTURE", source="LIVE", network_succeeded=True,
                        controlled_state=True, device_kind="EMULATOR", api_level=34,
                        theme="LIGHT", viewport="PHONE", capture_method="UI_AUTOMATION_ORIGINAL_PNG")
        metadata.update(updates)
        (self.source / "home.json").write_text(json.dumps(metadata))
        return png

    def test_original_png_metadata_hashes_html_and_zip_agree(self):
        original = self.evidence()
        manifest = self.build()
        item = manifest["screens"][0]
        self.assertEqual("CONTROLLED_FIXTURE", item["evidence_kind"])
        self.assertEqual(hashlib.sha256(original).hexdigest(), item["png_sha256"])
        self.assertEqual(original, (self.output / item["png"]).read_bytes())
        html = (self.output / "index.html").read_text()
        self.assertIn('name="viewport"', html)
        self.assertIn("CONTROLLED_FIXTURE", html)
        self.assertIn("data:image/png;base64," + base64.b64encode(original).decode(), html)
        with zipfile.ZipFile(self.output / "KR4-UI-gallery.zip") as archive:
            self.assertEqual(original, archive.read(item["png"]))
            self.assertEqual(manifest, json.loads(archive.read("manifest.json")))
            self.assertIn("SHA256SUMS.txt", archive.namelist())
            for line in archive.read("SHA256SUMS.txt").decode().splitlines():
                digest, path = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256(archive.read(path)).hexdigest())

    def test_controlled_network_state_cannot_claim_live_market(self):
        self.evidence(evidence_kind="REAL_MARKET_LIVE")
        with self.assertRaisesRegex(ValueError, "controlled"):
            self.build()

    def test_real_live_requires_successful_http(self):
        self.evidence(evidence_kind="REAL_MARKET_LIVE", controlled_state=False, network_succeeded=False)
        with self.assertRaisesRegex(ValueError, "HTTP"):
            self.build()

    def test_real_recorded_requires_source_asset_provenance(self):
        self.evidence(evidence_kind="REAL_MARKET_RECORDED", controlled_state=False)
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.build()

    def test_uncontrolled_live_and_recorded_market_evidence_keep_their_labels(self):
        self.evidence(evidence_kind="REAL_MARKET_LIVE", controlled_state=False)
        self.assertEqual("REAL_MARKET_LIVE", self.build()["screens"][0]["evidence_kind"])
        self.evidence(evidence_kind="REAL_MARKET_RECORDED", controlled_state=False,
                      source_provenance={"asset": "actual-source.json", "sha256": "a" * 64})
        self.assertEqual("REAL_MARKET_RECORDED", self.build()["screens"][0]["evidence_kind"])

    def test_unknown_classification_and_corrupt_png_are_rejected(self):
        self.evidence(evidence_kind="LIVE")
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.build()
        self.evidence()
        (self.source / "home.png").write_bytes(b"\x89PNG\r\n\x1a\n")
        with self.assertRaisesRegex(ValueError, "PNG"):
            self.build()

    def test_missing_original_png_fails_instead_of_silent_skip(self):
        self.evidence()
        (self.source / "home.png").unlink()
        with self.assertRaisesRegex(ValueError, "PNG"):
            self.build()

    def test_orphan_png_and_unknown_provenance_fail(self):
        self.evidence()
        (self.source / "orphan.png").write_bytes((self.source / "home.png").read_bytes())
        with self.assertRaisesRegex(ValueError, "metadata"):
            self.build()

    def test_legacy_unclassified_evidence_is_conservatively_controlled(self):
        self.evidence()
        (self.source / "home.json").write_text(json.dumps({"source": "LIVE", "network_succeeded": True}))
        self.assertEqual("CONTROLLED_FIXTURE", self.build()["screens"][0]["evidence_kind"])

    def test_html_escapes_metadata_and_filename(self):
        self.evidence(label='<script>alert("unsafe")</script>')
        self.build()
        self.assertNotIn('<script>alert(', (self.output / "index.html").read_text())

    def test_empty_gallery_fails(self):
        with self.assertRaisesRegex(ValueError, "screens"):
            self.build()


class InstalledReleaseTouchTests(unittest.TestCase):
    def bounds(self, xml, text):
        workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
        self.assertTrue("# RELEASE_UI_HELPERS_BEGIN" in workflow, "Installed-release touch helpers missing")
        helper = workflow.split("# RELEASE_UI_HELPERS_BEGIN\n", 1)[1].split("          # RELEASE_UI_HELPERS_END", 1)[0]
        namespace = {}
        exec(textwrap.dedent(helper), namespace)
        return namespace["ui_bounds"](xml, text)

    def test_tap_bounds_are_taken_from_actual_visible_accessibility_node(self):
        xml = '<hierarchy><node text="차트" enabled="true" bounds="[40,100][140,160]"/></hierarchy>'
        self.assertEqual((90, 130), self.bounds(xml, "차트"))

    def test_invisible_disabled_and_substring_matches_are_rejected(self):
        for attributes in ['text="주봉" enabled="false" bounds="[1,2][3,4]"',
                           'text="주봉" enabled="true" bounds="[0,0][0,0]"',
                           'text="주봉 설명" enabled="true" bounds="[1,2][3,4]"']:
            with self.assertRaises(ValueError):
                self.bounds(f'<hierarchy><node {attributes}/></hierarchy>', "주봉")


if __name__ == "__main__":
    unittest.main()
