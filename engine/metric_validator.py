#!/usr/bin/env python3
import sys
import json
import math

def validate_4metrics(data):
    """
    Validates a 4-metric dictionary structure against core rules:
    - M01: Revenue growth rate (%)
    - M02: Operating margin (%)
    - M03: Trailing PER (배) - Non-financial positive earnings only; loss-making or EPS<=0 -> None/NA
    - M04: 6-Month price return (%)
    """
    results = {}
    for issuer, metrics in data.items():
        errs = []
        # Check keys
        for k in ['M01', 'M02', 'M03', 'M04']:
            v = metrics.get(k)
            if v is not None:
                if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
                    errs.append(f"Invalid non-finite or non-numeric value for {k}: {v}")

        # Check PER rules
        per = metrics.get('M03')
        is_financial = metrics.get('is_financial', False)
        is_loss_making = metrics.get('is_loss_making', False)

        if (is_financial or is_loss_making) and per is not None:
            errs.append(f"PER must be None for financial or loss-making company, got {per}")

        results[issuer] = {
            'valid': len(errs) == 0,
            'errors': errs,
            'available_count': sum(1 for k in ['M01', 'M02', 'M03', 'M04'] if metrics.get(k) is not None)
        }
    return results

if __name__ == '__main__':
    test_data = {
        "005930": {"M01": 15.2, "M02": 12.5, "M03": 14.2, "M04": 8.5, "is_financial": False},
        "055550": {"M01": 5.1, "M02": None, "M03": None, "M04": 3.2, "is_financial": True},
        "999999": {"M01": -10.0, "M02": -5.0, "M03": None, "M04": -12.0, "is_loss_making": True}
    }
    print(json.dumps(validate_4metrics(test_data), indent=2, ensure_ascii=False))
