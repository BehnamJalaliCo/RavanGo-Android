#!/usr/bin/env python3
"""Builds summary.md + result.env from a run-suite.sh output directory.

Per flow: Maestro pass/fail (+ the failing step), app crashes (Java stack traces from the crash buffer, native
crash backtraces), ANRs (ActivityManager block from logcat + main-thread stack from dropbox), screenshots taken.
"""
import os
import re
import sys
from pathlib import Path

APP_ID = os.environ.get("APP_ID", "com.ravango.app.debug")
MAX_TRACE_LINES = 60


def read(path):
    try:
        return Path(path).read_text(errors="replace")
    except OSError:
        return ""


def java_crashes(text):
    """FATAL EXCEPTION blocks of our process from `logcat -v threadtime` output."""
    blocks = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if "FATAL EXCEPTION" in line and "AndroidRuntime" in line:
            m = re.match(r"^\S+\s+\S+\s+(\d+)\s+(\d+)", line)
            pid = m.group(1) if m else None
            block = [line]
            j = i + 1
            while j < len(lines) and "AndroidRuntime" in lines[j] and (pid is None or re.match(r"^\S+\s+\S+\s+" + pid + r"\s", lines[j])):
                block.append(lines[j])
                j += 1
            joined = "\n".join(block)
            if f"Process: {APP_ID}" in joined:
                blocks.append(clean(block))
            i = j
        else:
            i += 1
    return blocks


def native_crashes(text):
    blocks = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if f">>> {APP_ID} <<<" in line:
            start = max(0, i - 3)
            block = [l for l in lines[start:i + 45] if " DEBUG " in l or "DEBUG  :" in l or "crash_dump" in l or "libc" in l]
            blocks.append(clean(block or lines[start:i + 30]))
    return blocks


def anrs(logcat, dropbox_anr, start_time):
    out = []
    lines = logcat.splitlines()
    for i, line in enumerate(lines):
        if f"ANR in {APP_ID}" in line:
            block = [l for l in lines[i:i + 25] if "ActivityManager" in l]
            out.append(clean(block))
    # Main-thread stack from dropbox entries written during this flow.
    for entry in dropbox_entries(dropbox_anr, start_time):
        if APP_ID not in entry:
            continue
        m = re.search(r'"main".*?(?=\n\n|\Z)', entry, re.S)
        head = entry.splitlines()[:8]
        body = (m.group(0).splitlines() if m else [])[:40]
        out.append("\n".join(head + ["..."] + body))
    return out


def dropbox_entries(text, start_time):
    entries = re.split(r"\n=+\n", "\n" + text)
    result = []
    for e in entries:
        m = re.search(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", e.strip(), re.M)
        if m and (not start_time or m.group(1) >= start_time):
            result.append(e.strip())
    return result


def clean(block):
    """Drops the logcat prefix (date time pid tid level); keeps the head of the trace and every "Caused by"."""
    lines = [re.sub(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+\w\s+", "", l) for l in block]
    if len(lines) <= MAX_TRACE_LINES:
        return "\n".join(lines)
    keep = set(range(25))
    for i, l in enumerate(lines):
        if "Caused by" in l:
            keep.update(range(i, min(i + 12, len(lines))))
    out, last = [], -1
    for i in sorted(keep):
        if i != last + 1:
            out.append("        ...")
        out.append(lines[i])
        last = i
    if last < len(lines) - 1:
        out.append(f"        ... ({len(lines) - 1 - last} more lines)")
    return "\n".join(out)


def maestro_failure(log):
    lines = [l.rstrip() for l in log.splitlines() if l.strip()]
    keep = []
    for l in lines:
        if re.search(r"FAILED|Element not found|Assertion is false|Exception|Error|timed out|❌", l) and "JAVA_TOOL_OPTIONS" not in l:
            keep.append(l.strip())
    return keep[-8:] if keep else lines[-8:]


def screenshots(flow_dir):
    d = Path(flow_dir)
    shots = sorted(p.relative_to(d).as_posix() for p in (d / "screenshots").glob("*.png"))
    if not shots:  # older layout: Maestro's own folders
        shots = sorted(p.relative_to(d).as_posix() for p in d.rglob("takeScreenshot/*.png"))
    return shots


def main(out_dir):
    out = Path(out_dir)
    rows = []
    results = out / "results.tsv"
    if results.exists():
        for line in results.read_text().splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) >= 7:
                rows.append(dict(zip(["locale", "flow", "status", "exit", "seconds", "crash", "anr"], parts)))

    device = read(out / "device.txt").strip()
    total_crashes = total_anrs = failed = 0
    details = []
    table = ["| Locale | Flow | Result | Time | Crash | ANR | Screenshots |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        d = out / r["locale"] / r["flow"]
        logcat = read(d / "logcat.txt")
        crash_buf = read(d / "crash_buffer.txt")
        start = read(d / "start_time.txt").strip()
        jc = java_crashes(crash_buf) or java_crashes(logcat)
        nc = native_crashes(crash_buf + "\n" + logcat)
        dropbox_crash = [e for e in dropbox_entries(read(d / "dropbox_data_app_crash.txt"), start) if APP_ID in e]
        if not jc and dropbox_crash:
            jc = ["\n".join(e.splitlines()[:MAX_TRACE_LINES]) for e in dropbox_crash]
        an = anrs(logcat, read(d / "dropbox_data_app_anr.txt"), start)
        crashed = bool(jc or nc) or r["crash"] == "1"
        anred = bool(an) or r["anr"] == "1"
        total_crashes += int(crashed)
        total_anrs += int(anred)
        if r["status"] != "passed":
            failed += 1
        shots = screenshots(d)
        icon = {"passed": "✅ pass", "failed": "❌ fail", "timeout": "⏱ timeout"}.get(r["status"], r["status"])
        table.append(f"| {r['locale']} | {r['flow']} | {icon} | {r['seconds']}s | {'💥 yes' if crashed else '–'} | {'🧊 yes' if anred else '–'} | {len(shots)} |")

        if r["status"] != "passed" or crashed or anred:
            sec = [f"### {r['locale']} / {r['flow']} — {r['status']}"]
            if r["status"] != "passed":
                sec.append("Maestro (last relevant lines):")
                sec.append("```\n" + "\n".join(maestro_failure(read(d / "maestro.log"))) + "\n```")
            for t in jc:
                sec.append("**App crash (Java):**\n```\n" + t + "\n```")
            for t in nc:
                sec.append("**App crash (native):**\n```\n" + t + "\n```")
            for t in an:
                sec.append("**ANR:**\n```\n" + t + "\n```")
            if (d / "failure.png").exists():
                sec.append(f"Failure screenshot: `{r['locale']}/{r['flow']}/failure.png`")
            if shots:
                sec.append("Last step screenshots: " + ", ".join(f"`{r['locale']}/{r['flow']}/{s}`" for s in shots[-6:]))
            if (d / "video").exists():
                sec.append(f"Video: `{r['locale']}/{r['flow']}/video/`")
            details.append("\n\n".join(sec))

    verdict = "✅ all flows passed, no crashes or ANRs" if rows and not (failed or total_crashes or total_anrs) else (
        f"❌ {failed} failed flow(s), {total_crashes} with app crash(es), {total_anrs} with ANR(s)" if rows else "❌ no flows ran")
    if (out / "result.env").exists() and "INSTALL_FAILED" in read(out / "result.env"):
        verdict = "❌ APK install failed:\n```\n" + read(out / "install.txt") + "\n```"

    md = [
        "# RavanGo device test summary",
        "",
        f"**{verdict}**",
        "",
        "```",
        device or "(device info missing)",
        "```",
        "",
        *table,
        "",
    ]
    if details:
        md += ["## Failures, crashes and ANRs", "", *details, ""]
    md += [
        "Per flow folder: `maestro.log` (step log), `report.xml` (JUnit), "
        "`screenshots/` (one per step), `failure.png` (screen when a flow failed), `debug/` (Maestro command log + view hierarchy), `logcat.txt`, `crash_buffer.txt`, `dropbox_*.txt`, "
        "`video/` (only for failed/crashed flows).",
        "",
    ]
    (out / "summary.md").write_text("\n".join(md))
    env = read(out / "result.env")
    if "INSTALL_FAILED" not in env:
        (out / "result.env").write_text(
            f"FLOWS={len(rows)}\nFAILED_FLOWS={failed}\nCRASHES={total_crashes}\nANRS={total_anrs}\n")
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
