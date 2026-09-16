from __future__ import annotations

import re
from pathlib import Path

SCRIPT = Path("scripts/stage4567_ci.sh")
s = SCRIPT.read_text(encoding="utf-8")
lines = s.splitlines(keepends=True)
out: list[str] = []
i = 0
patched = 0
seen_stdin_blocks = 0

# A Python program supplied as `python - <<'PY'` consumes stdin for its source.
# If that same program calls json.load(sys.stdin), piped/here-string JSON is no
# longer available. Move only the Python source to fd 3 so stdin remains data.
pattern = re.compile(r"\b(python3?|python)\s+-\s+<<('PY'|\"PY\"|PY)")

while i < len(lines):
    line = lines[i]
    if line.lstrip().startswith("#"):
        out.append(line)
        i += 1
        continue
    m = pattern.search(line)
    if not m:
        out.append(line)
        i += 1
        continue

    token = m.group(2)
    terminator = token.strip("'\"")
    j = i + 1
    while j < len(lines) and lines[j].strip() != terminator:
        j += 1
    if j >= len(lines):
        raise SystemExit(f"unterminated Python heredoc near line {i + 1}")

    code = "".join(lines[i + 1 : j])
    if "sys.stdin" in code:
        seen_stdin_blocks += 1
        replacement = (
            line[: m.start()]
            + f"{m.group(1)} /dev/fd/3 3<<{token}"
            + line[m.end() :]
        )
        out.append(replacement)
        out.extend(lines[i + 1 : j + 1])
        patched += 1
    else:
        out.extend(lines[i : j + 1])
    i = j + 1

patched_text = "".join(out)
SCRIPT.write_text(patched_text, encoding="utf-8")

# Refuse silent partial transformation: any heredoc Python block that still
# uses sys.stdin must no longer use `python - <<...` as its source transport.
check_lines = patched_text.splitlines(keepends=True)
k = 0
remaining_broken = []
while k < len(check_lines):
    line = check_lines[k]
    m = pattern.search(line)
    if not m:
        k += 1
        continue
    terminator = m.group(2).strip("'\"")
    j = k + 1
    while j < len(check_lines) and check_lines[j].strip() != terminator:
        j += 1
    if j >= len(check_lines):
        raise SystemExit(f"unterminated patched heredoc near line {k + 1}")
    code = "".join(check_lines[k + 1 : j])
    if "sys.stdin" in code:
        remaining_broken.append(k + 1)
    k = j + 1

if remaining_broken:
    raise SystemExit(f"stdin/heredoc conflict remains at lines {remaining_broken}")

print(
    "POST_STDIN_TRANSPORT_PATCH_PASS",
    {"stdin_blocks_seen": seen_stdin_blocks, "patched": patched},
)
