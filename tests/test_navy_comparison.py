"""Approved-vs-actual review evidence must preserve originals and honest labels."""
import base64
import hashlib
import importlib.util
import html
import json
import struct
import tempfile
import unittest
import zipfile
import zlib
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("navy_comparison", ROOT / "scripts/build_navy_comparison.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class NavyComparisonTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "captures"
        self.approved = self.root / "approved"
        self.output = self.root / "comparison"
        self.source.mkdir()
        self.approved.mkdir()
        # Small valid PNG and JPEG headers with image dimensions. The packager
        # reads headers and copies bytes; it never decodes/re-encodes evidence.
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        self.png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 2, 8, 2, 0, 0, 0))
        self.png += chunk(b"IDAT", zlib.compress(b"\0\xff\xff\xff\0\0\0\0")) + chunk(b"IEND", b"")
        self.jpeg = b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x02\x00\x01\x01\x01\x11\x00\xff\xd9"
        for screen in MODULE.SCREENS:
            (self.approved / f"{screen}.jpg").write_bytes(self.jpeg)
            self.evidence(screen)

    def evidence(self, screen, **updates):
        metadata = dict(approved_screen=screen, evidence_kind="REAL_MARKET_LIVE", source="LIVE",
                        scenario_source="LIVE", network_succeeded=True, controlled_state=False,
                        device_kind="EMULATOR", api_level=34, theme="DARK", viewport="PHONE",
                        activity_class="com.krstock.v3.MainActivity", full_screen=True,
                        launch_action="android.intent.action.MAIN", capture_method="UI_AUTOMATION_ORIGINAL_PNG")
        if screen == "detail":
            metadata.update(issuer="005930", detail_tab=1, actual_record={"name": "삼성전자", **{
                f"m0{metric}": {"raw": 1.5, "percentile": 60} for metric in range(1, 5)}})
        if screen == "chart":
            metadata.update(issuer="005930", chart_source="LIVE", chart_style="CANDLE",
                            price_cutoff_date_kst="2026-11-05", daily_source=[{
                                "localTradedAt": (date(2025, 1, 1) + timedelta(days=day)).isoformat(),
                                "openPrice": "60,000", "highPrice": 61000, "lowPrice": 59000,
                                "closePrice": 60500, "accumulatedTradingVolume": "1,000",
                            } for day in range(201)])
        metadata.update(updates)
        (self.source / f"navy-approved-{screen}.png").write_bytes(self.png)
        (self.source / f"navy-approved-{screen}.json").write_text(json.dumps(metadata), encoding="utf-8")

    def build(self):
        return MODULE.build_comparison(self.source, self.approved, self.output)

    def test_originals_hashes_embedded_html_and_zip_agree(self):
        manifest = self.build()
        self.assertEqual(list(MODULE.SCREENS), [screen["screen"] for screen in manifest["screens"]])
        self.assertFalse(manifest["originals_edited"])
        self.assertFalse(manifest["android_capture_pixels_modified"])
        self.assertFalse(manifest["browser_screenshot_is_android_capture"])
        document = (self.output / "index.html").read_text()
        self.assertIn('name="viewport"', document)
        self.assertIn("data:image/png;base64," + base64.b64encode(self.png).decode(), document)
        self.assertIn("data:image/jpeg;base64," + base64.b64encode(self.jpeg).decode(), document)
        self.assertIn("Independent review pending", document)
        self.assertIn("original MAIN activity", document)
        with zipfile.ZipFile(self.output / "KR4-navy-comparison.zip") as archive:
            self.assertEqual(manifest, json.loads(archive.read("manifest.json")))
            for screen in manifest["screens"]:
                self.assertEqual(self.png, archive.read(screen["actual_png"]))
                self.assertEqual(self.jpeg, archive.read(screen["approved_jpeg"]))
                self.assertEqual(hashlib.sha256(self.png).hexdigest(), screen["actual_png_sha256"])
                self.assertEqual(hashlib.sha256(self.jpeg).hexdigest(), screen["approved_jpeg_sha256"])
                self.assertEqual((1, 2), (screen["actual_width"], screen["actual_height"]))
                self.assertEqual((1, 2), (screen["approved_width"], screen["approved_height"]))
            for line in archive.read("SHA256SUMS.txt").decode().splitlines():
                digest, path = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256(archive.read(path)).hexdigest())

    def test_rubric_assigns_four_times_twenty_five_possible_points_without_score(self):
        manifest = self.build()
        review = manifest["review"]
        self.assertIsNone(review["reviewer_score"])
        self.assertEqual("PENDING_INDEPENDENT_REVIEW", review["review_status"])
        self.assertEqual(100, review["max_points"])
        self.assertEqual(25, sum(item["max_points"] for item in review["categories_per_screen"]))
        self.assertEqual({"color", "layout", "typography", "spacing", "readability", "information"},
                         {item["category"] for item in review["categories_per_screen"]})
        self.assertTrue(all(item["reviewer_score"] is None for item in review["categories_per_screen"]))
        self.assertTrue(all(screen["reviewer_score"] is None and screen["max_points"] == 25 for screen in manifest["screens"]))

    def test_standalone_release_html_embeds_review_evidence_without_ambiguous_relative_links(self):
        manifest = self.build()
        document = (self.output / "index.html").read_text()
        for relative in ("manifest.json", "review-rubric.json", "SHA256SUMS.txt"):
            self.assertTrue(f'href="{relative}"' not in document, f"Standalone HTML must not link relative {relative}")
        self.assertIn('href="KR4-navy-comparison.zip"', document)
        self.assertIn('id="evidence-manifest"', document)
        self.assertIn('id="review-rubric"', document)
        self.assertIn('id="original-checksums"', document)
        self.assertIn(html.escape(json.dumps(manifest, ensure_ascii=False, indent=2)), document)
        self.assertIn(html.escape(json.dumps(manifest["review"], ensure_ascii=False, indent=2)), document)
        for screen in manifest["screens"]:
            for asset, digest in (("approved_jpeg", "approved_jpeg_sha256"),
                                  ("actual_png", "actual_png_sha256"),
                                  ("actual_metadata", "actual_metadata_sha256")):
                self.assertIn(f'{screen[digest]}  {screen[asset]}', document)

    def test_missing_or_duplicate_required_screens_fail(self):
        (self.source / "navy-approved-home.json").unlink()
        with self.assertRaisesRegex(ValueError, "Missing actual captures: home"):
            self.build()
        self.evidence("home")
        (self.source / "duplicate.json").write_bytes((self.source / "navy-approved-home.json").read_bytes())
        (self.source / "duplicate.png").write_bytes(self.png)
        with self.assertRaisesRegex(ValueError, "Duplicate actual capture for home"):
            self.build()

    def test_nonlive_controlled_cache_or_failed_fetch_cannot_claim_approved_match(self):
        for update in ({"evidence_kind": "CONTROLLED_FIXTURE"}, {"controlled_state": True},
                       {"network_succeeded": False}, {"source": "CACHE"}, {"scenario_source": "CACHE"}):
            with self.subTest(update=update):
                self.evidence("home", **update)
                with self.assertRaisesRegex(ValueError, "Live original MAIN activity"):
                    self.build()
        self.evidence("home")

    def test_wrong_activity_api_capture_method_or_crop_is_rejected(self):
        for update in ({"activity_class": "com.krstock.v3.PreviewActivity"}, {"api_level": 33},
                       {"capture_method": "GENERATED_IMAGE"}, {"full_screen": False},
                       {"launch_action": "android.intent.action.VIEW"}):
            with self.subTest(update=update):
                self.evidence("home", **update)
                with self.assertRaises(ValueError):
                    self.build()
        self.evidence("home")

    def test_full_screen_and_main_launch_fields_are_required(self):
        for key in ("full_screen", "launch_action"):
            with self.subTest(key=key):
                self.evidence("home")
                sidecar = self.source / "navy-approved-home.json"
                metadata = json.loads(sidecar.read_text())
                metadata.pop(key)
                sidecar.write_text(json.dumps(metadata))
                with self.assertRaisesRegex(ValueError, "Live original MAIN activity"):
                    self.build()

    def test_detail_requires_approved_issuer_four_metric_tab_and_actual_record(self):
        for update in ({"issuer": "123456"}, {"detail_tab": 0}, {"actual_record": None},
                       {"actual_record": {"name": "삼성전자", "m01": {"raw": 1}}}):
            with self.subTest(update=update):
                self.evidence("detail", **update)
                with self.assertRaisesRegex(ValueError, "Detail requires"):
                    self.build()
        self.evidence("detail", issuer="000020")
        self.assertEqual("000020", self.build()["screens"][2]["capture"]["issuer"])

    def test_chart_requires_live_samsung_valid_style_and_substantial_source_history(self):
        for update in ({"issuer": "000020"}, {"chart_source": "CACHE"}, {"chart_style": "MOCK"},
                       {"daily_source": []}, {"daily_source": [{}] * 200}, {"daily_source": {"rows": 201}}):
            with self.subTest(update=update):
                self.evidence("chart", **update)
                with self.assertRaisesRegex(ValueError, "Chart requires"):
                    self.build()

    def test_chart_rejects_missing_nonfinite_fabricated_bounds_future_or_duplicate_ohlcv(self):
        mutations = [lambda rows: rows[0].pop("openPrice"),
                     lambda rows: rows[0].update(openPrice="NaN"),
                     lambda rows: rows[0].update(highPrice=1),
                     lambda rows: rows[0].update(lowPrice=999999),
                     lambda rows: rows[0].update(accumulatedTradingVolume=-1),
                     lambda rows: rows[0].update(accumulatedTradingVolume=0.5),
                     lambda rows: rows[0].update(localTradedAt="2099-01-01"),
                     lambda rows: rows[0].update(localTradedAt=rows[1]["localTradedAt"])]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                self.evidence("chart")
                sidecar = self.source / "navy-approved-chart.json"
                metadata = json.loads(sidecar.read_text())
                mutate(metadata["daily_source"])
                sidecar.write_text(json.dumps(metadata))
                with self.assertRaisesRegex(ValueError, "Invalid actual daily_source"):
                    self.build()

    def test_missing_originals_and_invalid_image_headers_fail(self):
        (self.source / "navy-approved-chart.png").unlink()
        with self.assertRaisesRegex(ValueError, "Missing original PNG"):
            self.build()
        self.evidence("chart")
        (self.source / "navy-approved-chart.png").write_bytes(b"\x89PNG\r\n\x1a\n")
        with self.assertRaisesRegex(ValueError, "Invalid original PNG"):
            self.build()
        self.evidence("chart")
        (self.approved / "chart.jpg").write_bytes(b"not an image")
        with self.assertRaisesRegex(ValueError, "Invalid approved JPEG"):
            self.build()
        (self.approved / "chart.jpg").unlink()
        with self.assertRaisesRegex(ValueError, "Missing approved JPEG"):
            self.build()

    def test_unrelated_metadata_is_ignored_but_unknown_screen_is_rejected(self):
        (self.source / "report.json").write_text('{"report":"not a capture"}')
        self.assertEqual(4, len(self.build()["screens"]))
        (self.source / "typo.json").write_text('{"approved_screen":"rnak"}')
        with self.assertRaisesRegex(ValueError, "Unknown approved_screen"):
            self.build()

    def test_unsafe_metadata_is_escaped_and_input_is_preserved(self):
        self.evidence("home", note='<script>alert("unsafe")</script>')
        before = {path: path.read_bytes() for path in self.source.iterdir()}
        self.build()
        self.assertNotIn('<script>alert(', (self.output / "index.html").read_text())
        self.assertEqual(before, {path: path.read_bytes() for path in self.source.iterdir()})

    def test_output_cannot_contain_inputs(self):
        with self.assertRaisesRegex(ValueError, "Output must not contain"):
            MODULE.build_comparison(self.source, self.approved, self.root)

    def test_nested_output_is_not_rediscovered_on_repeat(self):
        self.output = self.source / "comparison"
        first = self.build()
        self.assertEqual(first, self.build())


if __name__ == "__main__":
    unittest.main()
