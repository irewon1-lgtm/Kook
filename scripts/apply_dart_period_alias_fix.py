#!/usr/bin/env python3
from pathlib import Path


def patch(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


patch(
    "scripts/collect_quarterly_history.py",
    'PERIOD_TO_Q = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nQ_TO_PERIOD = {v: k for k, v in PERIOD_TO_Q.items()}\n',
    'PERIOD_TO_Q = {"Q1": 1, "1Q": 1, "HY": 2, "Q3": 3, "3Q": 3, "FY": 4}\nQ_TO_PERIOD = {1: "Q1", 2: "HY", 3: "Q3", 4: "FY"}\n',
    "quarterly period aliases",
)
patch(
    "scripts/collect_quarterly_history.py",
    '            "period": period,\n            "file": fname,\n',
    '            "period": Q_TO_PERIOD[quarter],\n            "file": fname,\n',
    "canonical quarterly period",
)

patch(
    "scripts/collect_real_quant_v3.py",
    'DART_PERIOD_RANK = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nDART_PERIOD_LABEL = {"Q1": "1분기", "HY": "반기", "Q3": "3분기", "FY": "사업연도"}\n',
    'DART_PERIOD_CANONICAL = {"Q1": "Q1", "1Q": "Q1", "HY": "HY", "Q3": "Q3", "3Q": "Q3", "FY": "FY"}\nDART_PERIOD_RANK = {"Q1": 1, "HY": 2, "Q3": 3, "FY": 4}\nDART_PERIOD_LABEL = {"Q1": "1분기", "HY": "반기", "Q3": "3분기", "FY": "사업연도"}\n',
    "v3 canonical map",
)
patch(
    "scripts/collect_real_quant_v3.py",
    '    for year, period, statement, fname in entries:\n        if statement != "PL" or period not in DART_PERIOD_RANK:\n            continue\n        y = int(year)\n',
    '    for year, period, statement, fname in entries:\n        period = DART_PERIOD_CANONICAL.get(period, period)\n        if statement != "PL" or period not in DART_PERIOD_RANK:\n            continue\n        y = int(year)\n',
    "v3 period normalization",
)

patch(
    "tests/test_quarterly_history.py",
    "      download_ext002('2025','Q1','PL','2025_Q1_PL.zip');\n      download_ext002('2025','HY','PL','2025_HY_PL.zip');\n      download_ext002('2025','Q3','PL','2025_Q3_PL.zip');\n      download_ext002('2025','FY','PL','2025_FY_PL.zip');\n",
    "      download_ext002('2025','1Q','PL','2025_Q1_PL.zip');\n      download_ext002('2025','HY','PL','2025_HY_PL.zip');\n      download_ext002('2025','3Q','PL','2025_Q3_PL.zip');\n      download_ext002('2025','FY','PL','2025_FY_PL.zip');\n",
    "real OpenDART quarter-code test fixture",
)
patch(
    "tests/test_quarterly_history.py",
    '    assert [row[1] for row in rows] == ["Q1", "HY", "Q3", "FY"], rows\n',
    '    assert [row[1] for row in rows] == ["1Q", "HY", "3Q", "FY"], rows\n    assert qh.PERIOD_TO_Q["1Q"] == 1 and qh.PERIOD_TO_Q["3Q"] == 3\n    assert qh.Q_TO_PERIOD[1] == "Q1" and qh.Q_TO_PERIOD[3] == "Q3"\n',
    "period alias assertions",
)

patch(
    "tests/test_auto_update_pipeline.py",
    '        ("2026", "Q1", "PL", "2026_Q1.zip"),\n        ("2026", "HY", "PL", "2026_HY.zip"),\n        ("2027", "Q1", "PL", "future.zip"),\n',
    '        ("2026", "1Q", "PL", "2026_Q1.zip"),\n        ("2026", "HY", "PL", "2026_HY.zip"),\n        ("2027", "1Q", "PL", "future.zip"),\n',
    "v3 1Q test fixture",
)
patch(
    "tests/test_auto_update_pipeline.py",
    '    entries.append(("2026", "Q3", "PL", "2026_Q3.zip"))\n    assert v3._select_latest_dart_entry(entries, date(2026, 11, 20)) == ("2026", "Q3", "2026_Q3.zip")\n',
    '    entries.append(("2026", "3Q", "PL", "2026_Q3.zip"))\n    assert v3._select_latest_dart_entry(entries, date(2026, 11, 20)) == ("2026", "Q3", "2026_Q3.zip")\n',
    "v3 3Q canonicalization test",
)
patch(
    "tests/test_auto_update_pipeline.py",
    '    assert v3._select_latest_dart_entry(entries, date(2027, 4, 1)) == ("2027", "Q1", "future.zip")\n',
    '    assert v3._select_latest_dart_entry(entries, date(2027, 4, 1)) == ("2027", "Q1", "future.zip")\n    assert v3.DART_PERIOD_CANONICAL["1Q"] == "Q1" and v3.DART_PERIOD_CANONICAL["3Q"] == "Q3"\n',
    "v3 alias assertion",
)

print("DART_PERIOD_ALIAS_PATCH_PASS")
