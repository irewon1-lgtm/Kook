#!/usr/bin/env python3
"""Compare approved JPEG references with original, live Android14 captures.

This packages evidence for human review. It never changes image pixels and never
assigns a visual score. A browser screenshot of index.html documents the review
page; the original Android PNGs remain the device evidence.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import shutil
import struct
import zipfile
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

SCREENS = ("home", "ranking", "detail", "chart")
TITLES = {"home": "Home", "ranking": "Ranking", "detail": "Four-metric detail", "chart": "Actual OHLCV chart"}
# Each screen is reviewed independently, for 25 points; all four total 100.
CATEGORIES = (
    ("color", 5, "Navy backgrounds, blue accents, text contrast, semantic gain/loss and status colors match the approved reference."),
    ("layout", 5, "Screen hierarchy, card shapes, navigation and alignment follow the approved reference while preserving working controls."),
    ("typography", 4, "Korean labels, headings, numeric emphasis, font sizes and weights match the reference hierarchy."),
    ("spacing", 4, "Page gutters, card padding, row gaps and vertical rhythm are consistent with the reference."),
    ("readability", 4, "All visible text and values remain readable without clipping or overlap; narrow screens and long names retain usable controls."),
    ("information", 3, "Required live values, dates, source labels and screen-specific information remain accurate and visible."),
)
REQUIRED_INFORMATION = {
    "home": "App identity, live data date/source, the four metric choices, ranking entry and navigation.",
    "ranking": "Ranking title, selected four-metric combination, stock identity/code, ranks and actual composite values.",
    "detail": "Actual stock identity/code, four-metric tab, raw metrics, percentiles, dates and source/availability reasons.",
    "chart": "005930 identity, actual OHLCV history, date/price axes and interval/window/style controls; no invented candles.",
}


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _png_dimensions(raw: bytes, path: Path) -> tuple[int, int]:
    if (len(raw) < 45 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR"
            or raw[-8:-4] != b"IEND"):
        raise ValueError(f"Invalid original PNG: {path}")
    width, height = struct.unpack(">II", raw[16:24])
    if not width or not height:
        raise ValueError(f"Invalid original PNG dimensions: {path}")
    return width, height


def _jpeg_dimensions(raw: bytes, path: Path) -> tuple[int, int]:
    if not raw.startswith(b"\xff\xd8") or not raw.endswith(b"\xff\xd9"):
        raise ValueError(f"Invalid approved JPEG: {path}")
    cursor = 2
    while cursor < len(raw) - 1:
        if raw[cursor] != 0xFF:
            break
        while cursor < len(raw) and raw[cursor] == 0xFF:
            cursor += 1
        if cursor >= len(raw):
            break
        marker = raw[cursor]
        cursor += 1
        if marker in {0xD8, 0xD9} or 0xD0 <= marker <= 0xD7:
            continue
        if cursor + 2 > len(raw):
            break
        size = int.from_bytes(raw[cursor:cursor + 2], "big")
        if size < 2 or cursor + size > len(raw):
            break
        if marker in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
            if size < 8:
                break
            height, width = struct.unpack(">HH", raw[cursor + 3:cursor + 7])
            if width and height:
                return width, height
            break
        cursor += size
    raise ValueError(f"Approved JPEG has no valid dimensions: {path}")


def _validate_capture(data: dict, sidecar: Path) -> None:
    required = {
        "evidence_kind": "REAL_MARKET_LIVE",
        "controlled_state": False,
        "network_succeeded": True,
        "source": "LIVE",
        "scenario_source": "LIVE",
        "activity_class": "com.krstock.v3.MainActivity",
        "api_level": 34,
        "capture_method": "UI_AUTOMATION_ORIGINAL_PNG",
        "launch_action": "android.intent.action.MAIN",
        "full_screen": True,
    }
    for key, value in required.items():
        if data.get(key) != value or (isinstance(value, bool) and data.get(key) is not value):
            raise ValueError(f"Live original MAIN activity capture requires {key}={value!r}: {sidecar}")
    if data.get("device_kind") not in {"EMULATOR", "PHYSICAL"}:
        raise ValueError(f"Explicit device_kind required: {sidecar}")
    if data.get("theme") not in {"DARK", "LIGHT"} or data.get("viewport") not in {"PHONE", "TABLET"}:
        raise ValueError(f"Explicit system theme and viewport required: {sidecar}")
    if data["approved_screen"] == "detail":
        if data.get("issuer") not in {"000020", "005930"} or data.get("detail_tab") != 1:
            raise ValueError(f"Detail requires approved actual issuer and four-metric tab: {sidecar}")
        record = data.get("actual_record")
        if not isinstance(record, dict) or not isinstance(record.get("name"), str) or not record["name"].strip():
            raise ValueError(f"Detail requires its actual received source record: {sidecar}")
        for metric in ("m01", "m02", "m03", "m04"):
            value = record.get(metric)
            if not isinstance(value, dict) or not {"raw", "percentile"} <= value.keys():
                raise ValueError(f"Detail requires actual source metric {metric}: {sidecar}")
            if value["raw"] is None:
                if value["percentile"] is not None:
                    raise ValueError(f"Unavailable source metric cannot have a percentile: {sidecar}")
            elif (not _number(value["raw"]).is_finite() or value["percentile"] is None
                  or not 0 <= _number(value["percentile"]) <= 100):
                raise ValueError(f"Invalid actual source metric {metric}: {sidecar}")
    if data["approved_screen"] == "chart":
        _validate_chart(data, sidecar)


def _number(value: object) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError("Actual trading values must be numeric")
    try:
        number = Decimal(str(value).replace(",", "").strip())
    except InvalidOperation as error:
        raise ValueError("Actual trading values must be numeric") from error
    if not number.is_finite():
        raise ValueError("Actual trading values must be finite")
    return number


def _validate_chart(data: dict, sidecar: Path) -> None:
    if data.get("issuer") != "005930" or data.get("chart_source") != "LIVE":
        raise ValueError(f"Chart requires 005930 actual LIVE history: {sidecar}")
    if data.get("chart_style") not in {"LINE", "CANDLE"}:
        raise ValueError(f"Chart requires explicit valid chart_style: {sidecar}")
    rows = data.get("daily_source")
    if not isinstance(rows, list) or len(rows) <= 200:
        raise ValueError(f"Chart requires more than 200 actual daily_source trading rows: {sidecar}")
    try:
        cutoff = date.fromisoformat(data["price_cutoff_date_kst"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"Chart requires its actual price cutoff: {sidecar}") from error
    seen = set()
    for row in rows:
        try:
            observed = date.fromisoformat(row["localTradedAt"][:10])
            prices = [_number(row[key]) for key in ("openPrice", "highPrice", "lowPrice", "closePrice")]
            volume = _number(row["accumulatedTradingVolume"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"Invalid actual daily_source OHLCV row: {sidecar}") from error
        open_price, high, low, close = prices
        if (observed in seen or observed > cutoff or any(value <= 0 for value in prices)
                or low > min(open_price, close) or high < max(open_price, close)
                or low > high or volume < 0 or volume != volume.to_integral_value()):
            raise ValueError(f"Invalid actual daily_source OHLCV values or dates: {sidecar}")
        seen.add(observed)


def build_comparison(source: Path, approved: Path, output: Path) -> dict:
    source, approved, output = (Path(path).resolve() for path in (source, approved, output))
    if output == source or output == approved or output in source.parents or output in approved.parents:
        raise ValueError("Output must not contain either input directory")
    found = {}
    for sidecar in sorted(source.rglob("*.json")):
        if output in sidecar.parents:
            continue
        try:
            data = json.loads(sidecar.read_text(encoding="utf-8"))
        except (ValueError, UnicodeError):
            continue
        if not isinstance(data, dict) or "approved_screen" not in data:
            continue
        screen = data["approved_screen"]
        if screen not in SCREENS:
            raise ValueError(f"Unknown approved_screen {screen!r}: {sidecar}")
        if screen in found:
            raise ValueError(f"Duplicate actual capture for {screen}: {sidecar}")
        _validate_capture(data, sidecar)
        png = sidecar.with_suffix(".png")
        if not png.is_file():
            raise ValueError(f"Missing original PNG: {png}")
        raw = png.read_bytes()
        width, height = _png_dimensions(raw, png)
        reference = approved / f"{screen}.jpg"
        if not reference.is_file():
            raise ValueError(f"Missing approved JPEG: {reference}")
        reference_raw = reference.read_bytes()
        reference_width, reference_height = _jpeg_dimensions(reference_raw, reference)
        found[screen] = (png, sidecar, reference, {
            "screen": screen, "title": TITLES[screen], "max_points": 25,
            "reviewer_score": None, "reviewer_notes": None,
            "approved_jpeg": f"approved/{screen}.jpg",
            "approved_jpeg_sha256": _sha(reference_raw),
            "approved_width": reference_width, "approved_height": reference_height,
            "actual_png": f"actual/{screen}.png", "actual_png_sha256": _sha(raw),
            "actual_metadata": f"actual/{screen}.json", "actual_metadata_sha256": _sha(sidecar.read_bytes()),
            "actual_width": width, "actual_height": height,
            "source_capture": png.relative_to(source).as_posix(), "capture": data,
            "required_information": REQUIRED_INFORMATION[screen],
        })
    missing = set(SCREENS) - found.keys()
    if missing:
        raise ValueError("Missing actual captures: " + ", ".join(sorted(missing)))
    screens = [found[screen][3] for screen in SCREENS]
    rubric = {
        "max_points": 100, "points_per_screen": 25, "reviewer_score": None,
        "review_status": "PENDING_INDEPENDENT_REVIEW",
        "instructions": "An independent reviewer scores each of the four actual captures against its approved JPEG. Record evidence for each deduction. No automatic or inferred visual score is assigned.",
        "categories_per_screen": [{"category": name, "max_points": points, "criterion": criterion, "reviewer_score": None}
                                  for name, points, criterion in CATEGORIES],
    }
    manifest = {"schema_version": 1, "originals_edited": False, "android_capture_pixels_modified": False,
                "browser_screenshot_is_android_capture": False, "review": rubric, "screens": screens}
    output.mkdir(parents=True, exist_ok=True)
    for screen in SCREENS:
        png, sidecar, reference, item = found[screen]
        for src, relative in ((png, item["actual_png"]), (sidecar, item["actual_metadata"]),
                              (reference, item["approved_jpeg"])):
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, destination)
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "review-rubric.json").write_text(json.dumps(rubric, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cards = []
    for item in screens:
        def embedded(key: str, mime: str) -> str:
            return f"data:image/{mime};base64," + base64.b64encode((output / item[key]).read_bytes()).decode("ascii")
        capture = item["capture"]
        summary = html.escape(f"{capture['device_kind']} · Android {capture['api_level']} · system {capture['theme']} · {capture['viewport']} · REAL_MARKET_LIVE")
        rows = "".join(f"<tr><th>{html.escape(name.title())}</th><td>{points}</td><td>{html.escape(criterion)}</td><td>Pending</td></tr>"
                       for name, points, criterion in CATEGORIES)
        cards.append(f'<article id="{item["screen"]}"><h2>{html.escape(item["title"])} <small>25 points · review pending</small></h2>'
                     f'<p>{summary}</p><div class="pair"><figure><figcaption>Approved original JPEG</figcaption>'
                     f'<img src="{embedded("approved_jpeg", "jpeg")}" alt="Approved {item["screen"]} reference"></figure>'
                     f'<figure><figcaption>Actual original Android PNG</figcaption><img src="{embedded("actual_png", "png")}" '
                     f'alt="Actual {item["screen"]} full-screen live capture"></figure></div>'
                     f'<p><strong>Required information:</strong> {html.escape(item["required_information"])}</p>'
                     f'<details><summary>25-point review rubric</summary><table><thead><tr><th>Criterion</th><th>Max</th><th>Evidence to inspect</th><th>Score</th></tr></thead><tbody>{rows}</tbody></table></details>'
                     f'<details><summary>Original capture metadata and checksums</summary><pre>{html.escape(json.dumps(item, ensure_ascii=False, indent=2))}</pre></details></article>')
    # The public release renames this standalone HTML. Bare relative manifest or
    # checksum links could then resolve to an unrelated release gallery asset.
    # Embed the review evidence and use fragment links in either location.
    original_files = sorted([output / "manifest.json", output / "review-rubric.json"]
                            + [output / item[key] for item in screens
                               for key in ("actual_png", "actual_metadata", "approved_jpeg")])
    embedded_checksums = "".join(f"{_sha(path.read_bytes())}  {path.relative_to(output).as_posix()}\n"
                                 for path in original_files)
    evidence_details = (
        '<details id="evidence-manifest"><summary>Evidence manifest</summary><pre>'
        + html.escape(json.dumps(manifest, ensure_ascii=False, indent=2)) + '</pre></details>'
        + '<details id="review-rubric"><summary>Independent review rubric</summary><pre>'
        + html.escape(json.dumps(rubric, ensure_ascii=False, indent=2)) + '</pre></details>'
        + '<details id="original-checksums"><summary>Original file SHA256 checksums</summary>'
        + '<p>These hashes cover the unchanged images, original capture metadata, manifest and rubric. '
        + 'The ZIP also contains SHA256SUMS.txt covering its HTML page.</p><pre>'
        + html.escape(embedded_checksums) + '</pre></details>'
    )
    document = ('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                '<title>KR4 approved navy UI comparison</title><style>'
                '*{box-sizing:border-box}body{margin:0;padding:24px;font:16px/1.5 system-ui,sans-serif;background:#eef1f6;color:#172338}'
                'header,main{max-width:1240px;margin:auto}h1{margin-top:0}nav{display:flex;gap:16px;flex-wrap:wrap;margin:16px 0}'
                'article{margin:24px 0;padding:20px;border:1px solid #cbd3df;border-radius:12px;background:white;min-width:0}'
                'h2{margin:0;font-size:22px}small{font-size:14px;font-weight:400;display:block}a{color:#154ea1}'
                '.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;align-items:start}'
                'figure{margin:0;min-width:0}figcaption{font-weight:700;margin-bottom:8px}img{display:block;width:auto;max-width:100%;height:auto;max-height:650px;margin:auto;border:1px solid #acb8c9}'
                'pre{white-space:pre-wrap;overflow-wrap:anywhere}details{margin-top:16px}summary{cursor:pointer;font-weight:600}'
                'table{border-collapse:collapse;width:100%;font-size:14px}th,td{border:1px solid #cbd3df;padding:8px;text-align:left;vertical-align:top}'
                '@media(max-width:540px){body{padding:10px}article{padding:10px}.pair{gap:8px}figcaption{font-size:12px}h2{font-size:19px}}'
                '@media print{body{padding:0}article{break-before:page}.pair{grid-template-columns:1fr 1fr}details{display:block}}'
                '</style><header><h1>KR4 approved navy UI comparison</h1>'
                '<p>Unchanged user-approved JPEGs appear beside original full-screen Android14 PNGs captured from the original MAIN activity after successful live HTTP. '
                'Images scale only for browser display. Original bytes and checksums are included. A browser screenshot of this page is review evidence; the PNG files are the Android evidence.</p>'
                '<p><strong>Independent review pending:</strong> four screens × 25 points = 100 possible points. No visual score has been assigned. '
                'Inspect color, layout, typography, spacing, readability and information using each screen’s rubric.</p>'
                '<nav><a href="#home">Home</a><a href="#ranking">Ranking</a><a href="#detail">Detail</a><a href="#chart">Chart</a>'
                '<a href="#evidence-manifest">Manifest</a><a href="#review-rubric">Rubric</a><a href="#original-checksums">Checksums</a>'
                '<a href="KR4-navy-comparison.zip">Download originals ZIP</a></nav></header><main>' + "".join(cards) + evidence_details + '</main></html>')
    (output / "index.html").write_text(document, encoding="utf-8")
    files = sorted([output / "index.html", output / "manifest.json", output / "review-rubric.json"]
                   + [output / item[key] for item in screens for key in ("actual_png", "actual_metadata", "approved_jpeg")])
    (output / "SHA256SUMS.txt").write_text("".join(f"{_sha(path.read_bytes())}  {path.relative_to(output).as_posix()}\n" for path in files), encoding="utf-8")
    with zipfile.ZipFile(output / "KR4-navy-comparison.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files + [output / "SHA256SUMS.txt"]:
            archive.write(path, path.relative_to(output).as_posix())
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Actual Android PNGs and adjacent capture JSON metadata")
    parser.add_argument("approved", type=Path, help="Unchanged approved home/ranking/detail/chart.jpg files")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = build_comparison(args.source, args.approved, args.output)
    print(f"NAVY_COMPARISON_PACKAGED screens={len(result['screens'])} review=PENDING_INDEPENDENT_REVIEW output={args.output}")
