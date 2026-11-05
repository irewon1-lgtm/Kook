"""Exercise source components against controlled local HTTP session context."""
from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import dart_filing_provenance as dart
import probe_context_sources as probe
from tests.test_dart_filing_provenance import EXPECTED, PROFILE, SEARCH


@pytest.fixture
def official_like_source():
    # A stateful search/profile pair: search establishes context; profile
    # refuses requests without it. This is controlled protocol data, not a
    # claim about FSS's production cookie requirements.
    control = 'local-source-session-context'
    profile = PROFILE.replace('</tbody>', '<tr><th>홈페이지</th><td><a href="https://www.samsung.com/">홈페이지</a></td></tr></tbody>')
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def respond(self, status, markup, *, establish=False):
            body = markup.encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            if establish:
                self.send_header('Set-Cookie', 'source_context=' + control + '; Path=/; HttpOnly')
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get('Content-Length', '0'))).decode()
            if self.path != '/dsab001/search.ax' or parse_qs(body).get('textCrpNm') != ['005930']:
                return self.respond(400, 'invalid search')
            self.respond(200, SEARCH, establish=True)

        def do_GET(self):
            if self.path != '/dsae001/selectPopup.ax?selectKey=00126380':
                return self.respond(404, 'unknown endpoint')
            if self.headers.get('Cookie') != 'source_context=' + control:
                return self.respond(403, 'search context required')
            self.respond(200, profile)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', control
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_collector_validates_profile_with_context_established_by_search(official_like_source, tmp_path):
    root, control = official_like_source
    with patch.object(dart, 'DART', root), patch.object(dart.time, 'sleep'):
        row = dart.collect_filing_provenance({'005930': EXPECTED}, tmp_path, workers=1, as_of='2026-11-05')['005930']
    assert row['verified'] is True, row['error']
    assert row['receipt_no'] == '20260814003699'
    assert row['profile_stock_code'] == '005930'
    # Session credentials must never enter request/response provenance files.
    assert all(control not in path.read_text() for path in tmp_path.rglob('*.json'))


def test_probe_reads_official_roots_using_context_from_its_search(official_like_source):
    root, _ = official_like_source
    with patch.object(probe, 'DART', root), patch.object(probe.time, 'sleep'):
        receipts, markup, corp = probe.dart_search('005930')
        roots = probe.dart_profile_roots(corp)
    assert receipts == ['20260814003699']
    assert corp == '00126380'
    assert roots == ['https://www.samsung.com']
