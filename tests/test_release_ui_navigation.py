"""Installed-release navigation against original API34 accessibility evidence."""
import copy
import ast
import hashlib
import json
import textwrap
import xml.etree.ElementTree as ET
from types import SimpleNamespace
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = ROOT / "tests/fixtures/release-ui/42023412855-samsung-search.xml"


def helpers():
    workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
    code = workflow.split("# RELEASE_UI_HELPERS_BEGIN\n", 1)[1].split("          # RELEASE_UI_HELPERS_END", 1)[0]
    namespace = {}
    exec(textwrap.dedent(code), namespace)
    return namespace


def stock_bounds(xml):
    module = helpers()
    assert "ui_stock_bounds" in module, "split stock name/code navigation helper is missing"
    return module["ui_stock_bounds"](xml, "005930", "삼성전자")


def row(name="삼성전자", code="005930", **attributes):
    node = ET.Element("node", {"package": "com.krstock.v3", "text": "", "clickable": "true", "enabled": "true", "bounds": "[20,100][500,500]", **attributes})
    ET.SubElement(node, "node", {"package": "com.krstock.v3", "text": name, "enabled": "true", "bounds": "[40,120][160,160]"})
    ET.SubElement(node, "node", {"package": "com.krstock.v3", "text": code + "  ·  KOSPI", "enabled": "true", "bounds": "[40,180][400,220]"})
    ET.SubElement(node, "node", {"package": "com.krstock.v3", "text": "재무위험", "clickable": "true", "enabled": "true", "bounds": "[40,260][160,300]"})
    return node


def xml(*rows):
    root = ET.Element("hierarchy")
    root.extend(rows)
    return ET.tostring(root, encoding="unicode")


def test_original_search_xml_taps_exact_name_bounds_in_its_stock_card():
    # Actual name:[248,476][440,552]; badge is a separate clickable node.
    assert stock_bounds(ORIGINAL.read_text()) == (344, 514)


def test_separate_name_and_code_choose_name_instead_of_risk_badge():
    assert stock_bounds(xml(row())) == (100, 140)


def test_name_and_code_in_separate_rows_cannot_be_combined():
    with pytest.raises(ValueError):
        stock_bounds(xml(row(code="005935"), row(name="다른회사", code="005930")))


@pytest.mark.parametrize("name,code", [("삼성전자우", "005935"), ("삼성전자우", "005930"), ("삼성전자", "0059301")])
def test_preferred_share_or_code_substring_is_not_the_requested_stock(name, code):
    with pytest.raises(ValueError):
        stock_bounds(xml(row(name, code)))


@pytest.mark.parametrize("target", ["row", "name", "code"])
def test_disabled_card_or_identity_node_cannot_be_tapped(target):
    card = row()
    (card if target == "row" else card[0 if target == "name" else 1]).set("enabled", "false")
    with pytest.raises(ValueError):
        stock_bounds(xml(card))


@pytest.mark.parametrize("target", ["row", "name", "code"])
def test_zero_bounds_are_not_visible_navigation_targets(target):
    card = row()
    (card if target == "row" else card[0 if target == "name" else 1]).set("bounds", "[0,0][0,0]")
    with pytest.raises(ValueError):
        stock_bounds(xml(card))


def test_disabled_container_prevents_tapping_an_enabled_child_card():
    container = ET.Element("node", enabled="false")
    container.append(row())
    with pytest.raises(ValueError):
        stock_bounds(xml(container))


def test_nearest_clickable_ancestor_must_own_the_code():
    container = row(name="다른회사", code="005930")
    container.append(row(code="005935"))
    with pytest.raises(ValueError):
        stock_bounds(xml(container))


def test_ambiguous_duplicate_cards_or_mixed_codes_are_rejected():
    with pytest.raises(ValueError):
        stock_bounds(xml(row(), row()))
    card = row()
    card.append(copy.deepcopy(row(code="005935")[1]))
    with pytest.raises(ValueError):
        stock_bounds(xml(card))


def market(day="2026-11-05", price="2026-11-05", complete=2376):
    return {"snapshot_date_kst": day, "price_cutoff_date_kst": price, "universe_count": 2649,
            "coverage": {"complete_count": complete}, "records": {f"{i:06}": {} for i in range(2649)}}


def home(source="LIVE", day="2026-11-05", price="2026-11-05", complete=2376):
    return xml(ET.Element("node", text="데이터 출처 " + source),
               ET.Element("node", text=f"재무 {day} · 주가 {price}"),
               ET.Element("node", text=f"4지표 완성 {complete}/2649"))


def test_live_readiness_rejects_matching_cache_and_inexact_dates_or_counts():
    module = helpers()
    assert "ui_live_market_matches" in module, "actual LIVE receipt gate is missing"
    ready = module["ui_live_market_matches"]
    assert ready(home(), market())
    assert not ready(home("CACHE"), market())
    assert not ready(home(price="2026-11-04"), market())
    assert not ready(home(complete=2367), market())


@pytest.mark.parametrize("source", ["LIVE", "CACHE", "BACKUP", "BUNDLED", "FAILED"])
def test_actual_source_is_preserved_in_metadata(source):
    module = helpers()
    assert "ui_snapshot_source" in module, "actual source metadata parser is missing"
    assert module["ui_snapshot_source"](home(source)) == source


def test_actual_baseline_may_be_newer_but_never_older_or_missing_identities():
    module = helpers()
    assert "validate_market_oracle" in module, "actual baseline freshness gate is missing"
    validate = module["validate_market_oracle"]
    validate(market(), market(price="2026-11-04", complete=2367))
    with pytest.raises(ValueError):
        validate(market(price="2026-11-04"), market())
    incomplete = market()
    incomplete["records"].pop("000000")
    with pytest.raises(ValueError):
        validate(incomplete, market())


def release_waiter(tmp_path, screens):
    """Execute the actual workflow wait function with controlled device I/O."""
    workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
    code = workflow.split('cat > "$RUNNER_TEMP/kr4-stable-upgrade.py" <<\'PY\'\n', 1)[1].split("\n          PY", 1)[0]
    definitions = ast.parse(textwrap.dedent(code))
    waiter = next(node for node in definitions.body if isinstance(node, ast.FunctionDef) and node.name == "wait_for_release_live_cache")
    state = {"now": 0, "reads": 0, "pulls": 0, "captures": []}

    def read():
        screen = screens[min(state["reads"], len(screens) - 1)]
        state["reads"] += 1
        return screen

    def adb(*args):
        assert args[:2] == ("pull", "/private/actual-cache.json")
        state["pulls"] += 1
        Path(args[2]).write_text(json.dumps(market()))

    namespace = {**helpers(), "root": tmp_path, "cache_path": "/private/actual-cache.json",
                 "json": json, "hashlib": hashlib, "read_ui": read, "adb": adb,
                 "capture": lambda *args: state["captures"].append(args),
                 "time": SimpleNamespace(monotonic=lambda: state["now"],
                     sleep=lambda seconds: state.__setitem__("now", state["now"] + seconds))}
    exec(compile(ast.Module(body=[waiter], type_ignores=[]), "actual-release-waiter", "exec"), namespace)
    return namespace["wait_for_release_live_cache"], state


def test_actual_release_waits_past_cache_then_compares_current_downloaded_body(tmp_path):
    wait, state = release_waiter(tmp_path, [home("CACHE"), home("LIVE")])
    _xml, received, _digest = wait("updated-home", market(price="2026-11-04", complete=2367))
    assert state["reads"] == 2 and state["pulls"] == 1
    assert received["price_cutoff_date_kst"] == "2026-11-05"
    assert received["coverage"]["complete_count"] == 2376
    assert state["captures"][0][2]["network_succeeded"] is True


def test_matching_cache_never_satisfies_actual_release_gate_or_extends_90_seconds(tmp_path):
    wait, state = release_waiter(tmp_path, [home("CACHE")])
    with pytest.raises(AssertionError, match="live_current_market_cache"):
        wait("updated-home", market())
    assert state["now"] == 90
    assert state["pulls"] == 0 and state["captures"] == []
