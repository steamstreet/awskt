#!/usr/bin/env python3
"""
Measure the deployed native Lambda's cold start, warm duration and memory footprint.

Closes the plan's §M0.5 item "Record measured cold-start and binary size" — 2.3.x's claimed
~35-60 ms init and ~4.1 MB zipped were never verified and are the only quantitative basis for the
whole cold-start argument for Kotlin/Native.

Numbers come from Lambda's own REPORT line (via `--log-type Tail`), not from wall clock on the
caller, so network latency to the caller is excluded and `Init Duration` is attributed by the
platform rather than inferred.

Cold starts are forced by rewriting an environment variable to a nonce before each sample, which
is what actually recycles the execution environment; simply waiting does not, and re-invoking a
warm function measures nothing.

Two workloads:
  - ping  — returns immediately, so `Duration` is Kotlin/Native handler overhead alone
  - full  — DynamoDB put+get, two S3 puts, an S3 get and two presigns, so `Duration` is dominated
            by AWS round trips

Usage:
  aws sso login --profile vegasful-test
  AWS_PROFILE=vegasful-test python3 .github/scripts/native-perf.py
"""

import argparse
import base64
import json
import re
import statistics
import subprocess
import sys
import tempfile

REPORT = re.compile(
    r"Duration:\s*([\d.]+)\s*ms.*?"
    r"Billed Duration:\s*([\d.]+)\s*ms.*?"
    r"Memory Size:\s*(\d+)\s*MB.*?"
    r"Max Memory Used:\s*(\d+)\s*MB"
    r"(?:.*?Init Duration:\s*([\d.]+)\s*ms)?",
    re.S,
)


def aws(*args, region):
    out = subprocess.run(
        ["aws", "--region", region, *args],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise SystemExit(f"aws {' '.join(args)} failed:\n{out.stderr.strip()}")
    return out.stdout


def invoke(function, payload, region):
    """Invoke once and return the parsed REPORT fields."""
    with tempfile.NamedTemporaryFile() as out:
        raw = aws(
            "lambda", "invoke",
            "--function-name", function,
            "--cli-binary-format", "raw-in-base64-out",
            "--payload", json.dumps(payload),
            "--log-type", "Tail",
            out.name,
            region=region,
        )
        meta = json.loads(raw)
        if meta.get("FunctionError"):
            body = open(out.name).read()[:400]
            raise SystemExit(f"invocation failed: {meta['FunctionError']}: {body}")

    logs = base64.b64decode(meta["LogResult"]).decode("utf-8", "replace")
    m = REPORT.search(logs)
    if not m:
        raise SystemExit(f"no REPORT line in log tail:\n{logs[-800:]}")
    duration, billed, size, used, init = m.groups()
    return {
        "duration": float(duration),
        "billed": float(billed),
        "memory_size": int(size),
        "max_memory_used": int(used),
        "init": float(init) if init else None,
    }


def set_env(function, extra, region):
    """Rewrite the environment, preserving the function's real configuration."""
    cfg = json.loads(aws(
        "lambda", "get-function-configuration",
        "--function-name", function, "--query", "Environment.Variables",
        region=region,
    ))
    cfg.update(extra)
    aws(
        "lambda", "update-function-configuration",
        "--function-name", function,
        "--environment", json.dumps({"Variables": cfg}),
        region=region,
    )
    aws("lambda", "wait", "function-updated", "--function-name", function, region=region)


def set_memory(function, mb, region):
    aws(
        "lambda", "update-function-configuration",
        "--function-name", function, "--memory-size", str(mb),
        region=region,
    )
    aws("lambda", "wait", "function-updated", "--function-name", function, region=region)


def summarize(values):
    if not values:
        return "-"
    if len(values) == 1:
        return f"{values[0]:.1f}"
    return (f"{min(values):.1f} / {statistics.median(values):.1f} / {max(values):.1f}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--function", default="awskt-native-smoke")
    p.add_argument("--region", default="us-west-2")
    p.add_argument("--memory", default="128,512,1024",
                   help="comma-separated memory sizes in MB")
    p.add_argument("--cold", type=int, default=5, help="cold samples per memory size")
    p.add_argument("--warm", type=int, default=15, help="warm samples per memory size")
    p.add_argument("--modes", default="ping,full",
                   help="comma-separated handler modes; 'full' sends an empty payload")
    args = p.parse_args()

    sizes = [int(x) for x in args.memory.split(",")]
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    results = []

    for mb in sizes:
        print(f"\n=== {mb} MB ===", flush=True)
        set_memory(args.function, mb, args.region)

        for mode in modes:
            payload = {} if mode == "full" else {"mode": mode}
            colds, cold_durations, used = [], [], []
            for i in range(args.cold):
                # Rewriting an env var is what actually recycles the execution environment.
                set_env(args.function, {"PERF_NONCE": f"{mb}-{mode}-{i}"}, args.region)
                r = invoke(args.function, payload, args.region)
                if r["init"] is None:
                    print(f"  warn: sample {i} reused a warm environment, discarding", flush=True)
                    continue
                colds.append(r["init"])
                cold_durations.append(r["duration"])
                used.append(r["max_memory_used"])

            warms = []
            for _ in range(args.warm):
                r = invoke(args.function, payload, args.region)
                if r["init"] is not None:
                    continue  # unexpected cold, not a warm sample
                warms.append(r["duration"])
                used.append(r["max_memory_used"])

            row = {
                "memory": mb, "mode": mode,
                "init": colds, "cold_duration": cold_durations,
                "warm": warms, "max_used": max(used) if used else 0,
            }
            results.append(row)
            print(
                f"  {mode:8s} init {summarize(colds)} ms | "
                f"cold handler {summarize(cold_durations)} ms | "
                f"warm {summarize(warms)} ms | peak {row['max_used']} MB",
                flush=True,
            )

    print("\n\n| Memory | Workload | Init (min/med/max ms) | Cold handler | Warm handler | Peak mem |")
    print("|---|---|---|---|---|---|")
    for r in results:
        print(f"| {r['memory']} MB | {r['mode']} | {summarize(r['init'])} | "
              f"{summarize(r['cold_duration'])} | {summarize(r['warm'])} | {r['max_used']} MB |")

    print("\n(min / median / max across samples; durations are Lambda's own REPORT values)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
