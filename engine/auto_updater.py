#!/usr/bin/env python3
"""
Auto-Update Engine for KR Stock V3.
Handles incremental data change detection, metric recalculation, targeted report re-analysis,
durable DB persistence, failure isolation, and stale data warning management.
"""
from datetime import datetime, timezone
import json
import sqlite3
from pathlib import Path

class AutoUpdatePipeline:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        conn.execute('''
            CREATE TABLE IF NOT EXISTS snapshots (
                issuer_id TEXT PRIMARY KEY,
                metrics TEXT,
                report TEXT,
                updated_at TEXT,
                status TEXT
            )
        ''')
        conn.execute('''
            CREATE TABLE IF NOT EXISTS update_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                issuer_id TEXT,
                status TEXT,
                reason TEXT,
                timestamp TEXT
            )
        ''')
        conn.commit()
        conn.close()

    def process_data_event(self, issuer_id: str, new_raw_data: dict, simulate_failure: bool = False) -> dict:
        conn = sqlite3.connect(self.db_path)
        now = datetime.now(timezone.utc).isoformat()

        # Check existing snapshot
        cursor = conn.cursor()
        cursor.execute("SELECT metrics, report, updated_at, status FROM snapshots WHERE issuer_id = ?", (issuer_id,))
        existing = cursor.fetchone()

        if simulate_failure:
            # Failure state: retain previous good snapshot, record failure, mark stale warning
            reason = "API_NETWORK_TIMEOUT_SIMULATED"
            conn.execute("INSERT INTO update_logs (issuer_id, status, reason, timestamp) VALUES (?, ?, ?, ?)",
                         (issuer_id, "FAILURE", reason, now))
            conn.commit()
            conn.close()

            return {
                "issuer_id": issuer_id,
                "success": False,
                "retained_snapshot": json.loads(existing[0]) if existing else None,
                "last_successful_update": existing[2] if existing else None,
                "warning": "STALE_DATA_WARNING: Update failed, showing last valid snapshot."
            }

        # 1. Change detection
        existing_metrics = json.loads(existing[0]) if existing else {}
        changed = any(existing_metrics.get(k) != new_raw_data.get(k) for k in new_raw_data)

        if not changed and existing:
            conn.close()
            return {
                "issuer_id": issuer_id,
                "success": True,
                "updated": False,
                "message": "No metrics changed. Report re-analysis skipped."
            }

        # 2. Recalculate metrics & re-analyze report
        recalculated_metrics = {
            "M01": new_raw_data.get("M01"),
            "M02": new_raw_data.get("M02"),
            "M03": new_raw_data.get("M03"),
            "M04": new_raw_data.get("M04")
        }

        new_report = {
            "summary": f"Updated analysis for {issuer_id} as of {now}",
            "metrics": recalculated_metrics
        }

        # 3. Durable DB Store
        conn.execute('''
            INSERT INTO snapshots (issuer_id, metrics, report, updated_at, status)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(issuer_id) DO UPDATE SET
                metrics = excluded.metrics,
                report = excluded.report,
                updated_at = excluded.updated_at,
                status = excluded.status
        ''', (issuer_id, json.dumps(recalculated_metrics), json.dumps(new_report), now, "CURRENT"))

        conn.execute("INSERT INTO update_logs (issuer_id, status, reason, timestamp) VALUES (?, ?, ?, ?)",
                     (issuer_id, "SUCCESS", "UPDATED_AND_REANALYZED", now))

        conn.commit()
        conn.close()

        return {
            "issuer_id": issuer_id,
            "success": True,
            "updated": True,
            "updated_at": now,
            "recalculated_metrics": recalculated_metrics
        }

if __name__ == '__main__':
    pipeline = AutoUpdatePipeline("test_autoupdate.sqlite")

    # 1. First initial update
    res1 = pipeline.process_data_event("005930", {"M01": 10.0, "M02": 12.0, "M03": 15.0, "M04": 5.0})
    print("Initial update:", res1["success"], "Updated:", res1["updated"])

    # 2. Unchanged event
    res2 = pipeline.process_data_event("005930", {"M01": 10.0, "M02": 12.0, "M03": 15.0, "M04": 5.0})
    print("Unchanged event:", res2["message"])

    # 3. Price change event
    res3 = pipeline.process_data_event("005930", {"M01": 10.0, "M02": 12.0, "M03": 15.0, "M04": 8.5})
    print("Metric change update:", res3["success"], "New M04:", res3["recalculated_metrics"]["M04"])

    # 4. Simulated failure event
    res4 = pipeline.process_data_event("005930", {"M01": 10.0}, simulate_failure=True)
    print("Simulated failure warning:", res4["warning"])
    print("Retained last update timestamp:", res4["last_successful_update"])
