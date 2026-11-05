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
CHART_ORIGINAL = ROOT / "tests/fixtures/release-ui/42031830546-chart-day-controls.xml"


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


def test_exact_search_content_description_is_a_real_touch_target():
    icon = ET.Element("node", {"text": "", "content-desc": "종목 검색", "enabled": "true", "bounds": "[400,30][490,120]"})
    assert helpers()["ui_bounds"](xml(icon), "종목 검색") == (445, 75)


@pytest.mark.parametrize("attributes", [
    {"content-desc": "종목 검색 열기"},
    {"content-desc": "종목 검색", "enabled": "false"},
    {"content-desc": "종목 검색", "bounds": "[0,0][0,0]"},
])
def test_search_icon_substrings_disabled_and_invisible_targets_are_rejected(attributes):
    icon = ET.Element("node", {"text": "", "enabled": "true", "bounds": "[400,30][490,120]", **attributes})
    with pytest.raises(ValueError):
        helpers()["ui_bounds"](xml(icon), "종목 검색")


def prepare_signed_search(title="종목 순위"):
    """Run the actual installed-release CTA→search statements with a hidden field."""
    workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
    code = workflow.split('cat > "$RUNNER_TEMP/kr4-stable-upgrade.py" <<\'PY\'\n', 1)[1].split("\n          PY", 1)[0]
    tree = ast.parse(textwrap.dedent(code))
    block = next(node for node in ast.walk(tree) if isinstance(node, ast.Try) and any(
        isinstance(statement, ast.Expr) and isinstance(statement.value, ast.Call)
        and isinstance(statement.value.func, ast.Name) and statement.value.func.id == "touch"
        and statement.value.args and isinstance(statement.value.args[0], ast.Constant)
        and statement.value.args[0].value == "4지표 전체 종목보기" for statement in node.body))
    begin = next(index for index, statement in enumerate(block.body) if isinstance(statement, ast.Expr)
                 and isinstance(statement.value, ast.Call) and isinstance(statement.value.func, ast.Name)
                 and statement.value.func.id == "touch"
                 and statement.value.args[0].value == "4지표 전체 종목보기")
    end = next(index for index, statement in enumerate(block.body[begin:], begin) if isinstance(statement, ast.Assign)
               and any(isinstance(target, ast.Name) and target.id == "edit" for target in statement.targets))
    state = {"search_open": False, "events": []}

    def read():
        state["events"].append("read")
        nodes = [ET.Element("node", text=title), ET.Element("node", {"text": "", "content-desc": "종목 검색", "enabled": "true", "bounds": "[400,30][490,120]"})]
        if state["search_open"]:
            nodes.append(ET.Element("node", {"class": "android.widget.EditText", "text": "", "enabled": "true", "bounds": "[20,140][480,210]"}))
        return xml(*nodes)

    def touch(label, **_options):
        state["events"].append(label)
        if label == "종목 검색":
            helpers()["ui_bounds"](read(), label)
            state["search_open"] = True

    namespace = {"read_ui": read, "touch": touch, "capture": lambda *_: None, "ET": ET}
    exec(compile(ast.Module(body=block.body[begin:end + 1], type_ignores=[]), "actual-release-search", "exec"), namespace)
    return namespace["edit"], state


def test_signed_release_opens_hidden_search_then_rereads_actual_editable_field():
    edit, state = prepare_signed_search()
    assert edit.get("class") == "android.widget.EditText"
    assert state["search_open"] is True
    assert state["events"].index("종목 검색") < len(state["events"]) - 1
    assert state["events"][-1] == "read"


@pytest.mark.parametrize("title", ["국내주식 조합순위", "종목 순위 설명"])
def test_signed_release_requires_the_exact_current_ranking_title(title):
    with pytest.raises(AssertionError):
        prepare_signed_search(title)


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


def chart_assertion(ui, label):
    workflow = (ROOT / ".github/workflows/android-build.yml").read_text()
    code = workflow.split('cat > "$RUNNER_TEMP/kr4-stable-upgrade.py" <<\'PY\'\n', 1)[1].split("\n          PY", 1)[0]
    definitions = ast.parse(textwrap.dedent(code))
    assertion = next(node for node in ast.walk(definitions) if isinstance(node, ast.Assert)
                     and isinstance(node.msg, ast.Constant) and node.msg.value == "release_chart_interval_did_not_select")
    namespace = {**helpers(), "ET": ET, "ui": ui, "label": label}
    exec(compile(ast.Module(body=[assertion], type_ignores=[]), "actual-release-chart-assertion", "exec"), namespace)


def chart_tree(selected="일봉"):
    tree = ET.fromstring(CHART_ORIGINAL.read_text())
    parents = {child: parent for parent in tree.iter() for child in parent}
    controls = {}
    for node in tree.iter("node"):
        if node.get("text") in ("일봉", "주봉", "월봉"):
            control = parents[node]
            control.set("checked", "true" if node.get("text") == selected else "false")
            controls[node.get("text")] = (node, control)
    return tree, controls


def test_actual_signed_day_chip_selection_belongs_to_parent_not_text():
    chart_assertion(CHART_ORIGINAL.read_text(), "일봉")
    with pytest.raises(AssertionError):
        chart_assertion(CHART_ORIGINAL.read_text(), "주봉")


@pytest.mark.parametrize("selected", ["일봉", "주봉", "월봉"])
def test_exactly_the_checked_chip_passes_the_actual_release_assertion(selected):
    tree, _ = chart_tree(selected)
    body = ET.tostring(tree, encoding="unicode")
    chart_assertion(body, selected)
    for other in {"일봉", "주봉", "월봉"} - {selected}:
        with pytest.raises(AssertionError):
            chart_assertion(body, other)


@pytest.mark.parametrize("selected", [None, "TWO"])
def test_missing_or_multiple_checked_intervals_cannot_pass(selected):
    tree, controls = chart_tree(None)
    if selected == "TWO":
        controls["일봉"][1].set("checked", "true")
        controls["주봉"][1].set("checked", "true")
    with pytest.raises((AssertionError, ValueError)):
        chart_assertion(ET.tostring(tree, encoding="unicode"), "일봉")


@pytest.mark.parametrize("target", ["label", "control", "ancestor"])
def test_disabled_chart_identity_or_ancestor_cannot_claim_selection(target):
    tree, controls = chart_tree()
    label, control = controls["일봉"]
    parents = {child: parent for parent in tree.iter() for child in parent}
    node = label if target == "label" else control if target == "control" else parents[control]
    node.set("enabled", "false")
    with pytest.raises((AssertionError, ValueError)):
        chart_assertion(ET.tostring(tree, encoding="unicode"), "일봉")


@pytest.mark.parametrize("target", ["label", "control"])
def test_nonvisible_or_foreign_chart_control_is_rejected(target):
    for attribute, value in [("bounds", "[0,0][0,0]"), ("package", "another.app")]:
        tree, controls = chart_tree()
        controls["일봉"][0 if target == "label" else 1].set(attribute, value)
        with pytest.raises((AssertionError, ValueError)):
            chart_assertion(ET.tostring(tree, encoding="unicode"), "일봉")


def test_checked_unrelated_container_or_child_does_not_select_a_chip():
    for target in ["ancestor", "label"]:
        tree, controls = chart_tree(None)
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = parents[controls["일봉"][1]] if target == "ancestor" else controls["일봉"][0]
        node.set("selected", "true")
        with pytest.raises((AssertionError, ValueError)):
            chart_assertion(ET.tostring(tree, encoding="unicode"), "일봉")


def test_duplicate_interval_label_or_mixed_control_is_ambiguous():
    for mode in ["duplicate", "mixed"]:
        tree, controls = chart_tree()
        label, control = controls["일봉"]
        control.append(copy.deepcopy(label if mode == "duplicate" else controls["주봉"][0]))
        with pytest.raises((AssertionError, ValueError)):
            chart_assertion(ET.tostring(tree, encoding="unicode"), "일봉")


def received_daily_bars():
    return [{"localTradedAt": day, "openPrice": opening, "highPrice": opening + 9,
             "lowPrice": opening - 3, "closePrice": opening + 5, "accumulatedTradingVolume": volume}
            for day, opening, volume in [("2026-11-27", 100, 10), ("2026-11-30", 120, 20),
                                         ("2026-12-01", 130, 30), ("2026-12-02", 140, 40)]]


def summary_xml(*texts):
    return xml(*(ET.Element("node", {"text": text, "package": "com.krstock.v3", "enabled": "true",
                                    "bounds": f"[10,{index * 35 + 10}][800,{index * 35 + 40}]"})
                 for index, text in enumerate(texts)))


@pytest.mark.parametrize("interval,ohlc,volume,days", [
    ("DAY", "시가 140 · 고가 149 · 저가 137 · 종가 145", "거래량 40주 · 실제 1거래일", 1),
    ("WEEK", "시가 120 · 고가 149 · 저가 117 · 종가 145", "거래량 90주 · 실제 3거래일", 3),
    ("MONTH", "시가 130 · 고가 149 · 저가 127 · 종가 145", "거래량 70주 · 실제 2거래일", 2),
])
def test_signed_chart_summary_uses_independent_received_daily_ohlcv(interval, ohlc, volume, days):
    module = helpers()
    expected = module["expected_chart_summary"](received_daily_bars(), interval, "2026-12-02")
    assert expected["ohlc"] == ohlc and expected["volume"] == volume and expected["trading_days"] == days
    body = summary_xml(expected["anchor"], ohlc, volume)
    assert module["ui_chart_summary_visible"](body, expected["label"])
    assert module["ui_chart_summary_matches"](body, expected)


def test_latest_daily_quote_cannot_satisfy_selected_weekly_or_monthly_summary():
    module = helpers()
    latest_only = summary_xml("최근 거래 가격", "시가 140 · 고가 149 · 저가 137 · 종가 145", "거래량 40주 · 실제 거래일 종가")
    for interval, label in [("WEEK", "주봉"), ("MONTH", "월봉")]:
        expected = module["expected_chart_summary"](received_daily_bars(), interval, "2026-12-02")
        assert not module["ui_chart_summary_visible"](latest_only, label)
        assert not module["ui_chart_summary_matches"](latest_only, expected)


def test_selected_chart_anchor_still_requires_matching_received_prices_and_trading_days():
    module = helpers()
    expected = module["expected_chart_summary"](received_daily_bars(), "WEEK", "2026-12-02")
    for body in [summary_xml("최근 일봉 · 제공 원본 OHLC", expected["ohlc"], expected["volume"]),
                 summary_xml(expected["anchor"], "시가 140 · 고가 149 · 저가 137 · 종가 145", expected["volume"]),
                 summary_xml(expected["anchor"], expected["ohlc"], "거래량 90주 · 실제 1거래일")]:
        assert not module["ui_chart_summary_matches"](body, expected)


@pytest.mark.parametrize("attribute,value", [("enabled", "false"), ("bounds", "[0,0][0,0]"), ("package", "another.app")])
def test_invisible_disabled_or_foreign_summary_cannot_verify_actual_ohlcv(attribute, value):
    module = helpers()
    expected = module["expected_chart_summary"](received_daily_bars(), "DAY", "2026-12-02")
    tree = ET.fromstring(summary_xml(expected["anchor"], expected["ohlc"], expected["volume"]))
    tree[0].set(attribute, value)
    assert not module["ui_chart_summary_matches"](ET.tostring(tree, encoding="unicode"), expected)
