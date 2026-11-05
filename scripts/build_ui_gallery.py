#!/usr/bin/env python3
"""Package original Android screenshots with conservative provenance labels."""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import shutil
import struct
import zipfile
from pathlib import Path

KINDS = {"REAL_MARKET_LIVE", "REAL_MARKET_RECORDED", "CONTROLLED_FIXTURE"}


def build_gallery(source: Path, output: Path) -> dict:
    source, output = Path(source).resolve(), Path(output).resolve()
    pngs = sorted(p for p in source.rglob("*.png") if output not in p.parents)
    metadata = sorted(p for p in source.rglob("*.json") if output not in p.parents)
    # Source directories may also contain reports. Only screenshot metadata carries
    # a capture method or has a matching PNG; missing classified PNGs are errors.
    for path in metadata:
        try:
            data = json.loads(path.read_text())
        except (ValueError, UnicodeError):
            continue
        if isinstance(data, dict) and ("capture_method" in data or "evidence_kind" in data):
            if not path.with_suffix(".png").is_file():
                raise ValueError(f"Missing original PNG: {path}")
    if not pngs:
        raise ValueError("No original screenshot screens found")
    screens = []
    for png in pngs:
        sidecar = png.with_suffix(".json")
        if not sidecar.is_file():
            raise ValueError(f"Missing screenshot metadata: {png}")
        data = json.loads(sidecar.read_text())
        kind = data.get("evidence_kind", "CONTROLLED_FIXTURE")
        if kind not in KINDS:
            raise ValueError(f"Unknown evidence provenance: {kind}")
        if kind != "CONTROLLED_FIXTURE" and data.get("controlled_state", True):
            raise ValueError(f"A controlled state cannot claim real market evidence: {sidecar}")
        if kind == "REAL_MARKET_LIVE" and not (
            data.get("network_succeeded") is True and data.get("source") == "LIVE"
        ):
            raise ValueError(f"Live evidence requires successful actual HTTP: {sidecar}")
        if kind == "REAL_MARKET_RECORDED" and not data.get("source_provenance"):
            raise ValueError(f"Recorded evidence requires source provenance: {sidecar}")
        raw = png.read_bytes()
        if len(raw) < 45 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR" or raw[-8:-4] != b"IEND":
            raise ValueError(f"Invalid original PNG: {png}")
        width, height = struct.unpack(">II", raw[16:24])
        if width == 0 or height == 0:
            raise ValueError(f"Invalid PNG dimensions: {png}")
        relative = Path("originals") / png.relative_to(source)
        screens.append({**data, "evidence_kind": kind, "name": str(png.relative_to(source).with_suffix("")),
                        "png": relative.as_posix(), "metadata": relative.with_suffix(".json").as_posix(),
                        "png_sha256": hashlib.sha256(raw).hexdigest(),
                        "metadata_sha256": hashlib.sha256(sidecar.read_bytes()).hexdigest(),
                        "width": width, "height": height})
    output.mkdir(parents=True, exist_ok=True)
    originals = output / "originals"
    if originals.exists():
        shutil.rmtree(originals)
    for png, screen in zip(pngs, screens):
        destination = output / screen["png"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(png, destination)
        shutil.copyfile(png.with_suffix(".json"), output / screen["metadata"])
    manifest = {"schema_version": 1, "screens": screens,
                "originals_edited": False, "physical_device_run_inferred": False}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    cards = []
    for item in screens:
        caption = html.escape(f"{item['name']} · {item['evidence_kind']} · {item.get('theme', 'UNSPECIFIED')} · {item.get('viewport', 'UNSPECIFIED')}")
        image_data = "data:image/png;base64," + base64.b64encode((output / item["png"]).read_bytes()).decode("ascii")
        details = html.escape(json.dumps(item, ensure_ascii=False, indent=2))
        cards.append(f'<article><h2>{caption}</h2><img loading="lazy" decoding="async" src="{image_data}" alt="{caption}"><details><summary>Original evidence metadata</summary><pre>{details}</pre></details></article>')
    document = ('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                '<title>KR4 original UI evidence</title><style>body{font:16px system-ui;margin:16px;background:#f5f3f1;color:#211d22}'
                'main{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr));gap:20px}'
                'article{background:white;padding:12px;min-width:0}img{width:100%;height:auto}h2{font-size:16px;overflow-wrap:anywhere}'
                'pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><h1>KR4 original UI evidence</h1>'
                '<p>REAL_MARKET_LIVE: successful actual HTTP. REAL_MARKET_RECORDED: original source fixtures. '
                'CONTROLLED_FIXTURE: injected status, error or network conditions. Screenshots are original, unedited device captures.</p>'
                '<p><a href="manifest.json">Manifest</a> · <a href="SHA256SUMS.txt">SHA256 checksums</a> · <a href="KR4-UI-gallery.zip">Download originals ZIP</a></p><main>'
                + ''.join(cards) + '</main></html>')
    (output / "index.html").write_text(document)
    files = sorted(p for p in output.rglob("*") if p.is_file() and p.name not in {"SHA256SUMS.txt", "KR4-UI-gallery.zip"})
    (output / "SHA256SUMS.txt").write_text(''.join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(output).as_posix()}\n" for p in files))
    with zipfile.ZipFile(output / "KR4-UI-gallery.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files + [output / "SHA256SUMS.txt"]:
            archive.write(path, path.relative_to(output).as_posix())
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = build_gallery(args.source, args.output)
    print(f"UI_GALLERY_PASS screens={len(result['screens'])} output={args.output}")
