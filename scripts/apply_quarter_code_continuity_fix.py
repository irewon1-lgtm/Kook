#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


base = Path("scripts/collect_real_quant.py")
replace_once(
    base,
    "r\"download_ext002\\('(\\d{4})','([A-Z]{2})',\\s*'([A-Z]{2})',\\s*'([^']+)'\\)\"",
    "r\"download_ext002\\('(\\d{4})','([A-Z0-9]{2})',\\s*'([A-Z]{2})',\\s*'([^']+)'\\)\"",
    "DART period regex",
)

history = Path("scripts/collect_quarterly_history.py")
text = history.read_text(encoding="utf-8")
anchor = '''def period_label(year: int, quarter: int) -> str:\n    return f"{year}Q{quarter}"\n\n\n'''
insert = '''def period_label(year: int, quarter: int) -> str:\n    return f"{year}Q{quarter}"\n\n\ndef assert_contiguous_entries(entries: list[dict[str, Any]], expected_count: int | None = None) -> None:\n    if expected_count is not None and len(entries) != expected_count:\n        raise RuntimeError(f"expected {expected_count} quarterly periods, got {len(entries)}")\n    if not entries:\n        raise RuntimeError("quarterly period list is empty")\n    indexes = [int(e["index"]) for e in entries]\n    expected = list(range(indexes[0], indexes[0] + len(indexes)))\n    if indexes != expected:\n        labels = [period_label(int(e["year"]), int(e["quarter"])) for e in entries]\n        raise RuntimeError(f"OpenDART periods are not contiguous quarters: {labels}")\n\n\n'''
if text.count(anchor) != 1:
    raise SystemExit(f"continuity helper anchor count={text.count(anchor)}")
text = text.replace(anchor, insert, 1)
old_support = '''    support = entries[-args.support_quarters:]\n    if len(support) < args.support_quarters:\n        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")\n'''
new_support = '''    support = entries[-args.support_quarters:]\n    if len(support) < args.support_quarters:\n        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")\n    assert_contiguous_entries(support, args.support_quarters)\n'''
if text.count(old_support) != 1:
    raise SystemExit(f"support anchor count={text.count(old_support)}")
text = text.replace(old_support, new_support, 1)
history.write_text(text, encoding="utf-8")

parallel = Path("scripts/collect_quarterly_history_parallel.py")
ptext = parallel.read_text(encoding="utf-8")
old_parallel = '''    support = entries[-args.support_quarters:]\n    if len(support) < args.support_quarters:\n        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")\n\n    parsed: dict[str, dict[str, dict[str, Any]]] = {}\n'''
new_parallel = '''    support = entries[-args.support_quarters:]\n    if len(support) < args.support_quarters:\n        raise RuntimeError(f"insufficient DART quarterly support periods: {len(support)}")\n    core.assert_contiguous_entries(support, args.support_quarters)\n\n    parsed: dict[str, dict[str, dict[str, Any]]] = {}\n'''
if ptext.count(old_parallel) != 1:
    raise SystemExit(f"parallel support anchor count={ptext.count(old_parallel)}")
parallel.write_text(ptext.replace(old_parallel, new_parallel, 1), encoding="utf-8")

print("QUARTER_CODE_CONTINUITY_PATCH_PASS")
