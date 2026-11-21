#!/usr/bin/env python3
"""Explicit historical inputs for a narrow fixture measurement, never current-build receipts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/ci"))
from github_read import get_json
from artifact_candidates import select_artifact

RUN = 44758282880
ATTEMPT = 1
HEAD = "9eec2089061fb5eb0586cd59da16d56ed7e1dfd2"
CHECKOUT = "03c1517f0f4b56d790f450cb970a3485179a7616"
TREE = "e1668715917f484fac30e19249bde858e0ca2a8a"
REPO = "gesher-runtime/gesher"
INPUTS = {
    "classes": dict(id=14641088521, name="framework-classes", size=56875046,
        digest="sha256:1d41a980177e61a396cb011056a4cf4e98e9bb4b2a4f9f3e33fd9f211c611da6",
        job=136364601928, producer="framework / framework"),
    "framework": dict(id=14640894199, name="framework", size=61660561,
        digest="sha256:52c5e0b89a2f75b3cb8d92e06e44d179be60149a8dc9c6aa600d18f24cb2476b",
        job=136364601928, producer="framework / framework"),
    "installer": dict(id=14641931016, name="gesher-setup", size=105433842,
        digest="sha256:a4fd54ccc10bfae2874b2f22f6dafebac06140ee0e5c41768f4f20dfaff21232",
        job=136374556363, producer="package"),
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", nargs="+", choices=INPUTS, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY") != REPO:
        raise RuntimeError("Diagnostic repository mismatch")
    run = get_json(f"repos/{REPO}/actions/runs/{RUN}/attempts/{ATTEMPT}")
    repo_id = run["repository"]["id"]
    if (run["id"] != RUN or run["run_attempt"] != ATTEMPT or run["head_sha"] != HEAD
        or run["event"] != "pull_request" or run["name"] != "ci"
        or run["repository"]["full_name"] != REPO or run["head_repository"]["full_name"] != REPO
        or run["status"] != "completed"):
        raise RuntimeError("Historical run/source/attempt/repository mismatch")
    for commit in (HEAD, CHECKOUT):
        value = get_json(f"repos/{REPO}/git/commits/{commit}")
        if value["sha"] != commit or value["tree"]["sha"] != TREE:
            raise RuntimeError("Historical source/checkout tree mismatch")
    args.destination.mkdir(parents=True, exist_ok=True)
    rows = []
    for key in args.inputs:
        expected = INPUTS[key]
        item = get_json(f"repos/{REPO}/actions/artifacts/{expected['id']}")
        job = get_json(f"repos/{REPO}/actions/jobs/{expected['job']}")
        if (job["id"] != expected["job"] or job["name"] != expected["producer"]
            or job["run_id"] != RUN or job["run_attempt"] != ATTEMPT or job["head_sha"] != HEAD
            or job["status"] != "completed" or job["conclusion"] != "success"):
            raise RuntimeError("Historical producer mismatch")
        if (item["id"] != expected["id"] or item["name"] != expected["name"]
            or item["size_in_bytes"] != expected["size"] or item["digest"] != expected["digest"]):
            raise RuntimeError("Historical artifact identity/digest/size mismatch")
        selected = select_artifact([item], expected["name"], job,
            run_id=str(RUN), repo_id=str(repo_id), head_repo_id=str(repo_id), head=HEAD)
        if selected["id"] != expected["id"]:
            raise RuntimeError("Historical artifact ownership mismatch")
        target = args.destination / key
        subprocess.run([sys.executable, str(ROOT / "tools/surface/download-verified-artifact.py"),
            "--id", str(expected["id"]), "--digest", expected["digest"], "--destination", str(target)], check=True)
        output_hashes = {str(p.relative_to(target)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in sorted(target.rglob("*")) if p.is_file()}
        row = dict(role="HISTORICAL_DIAGNOSTIC_INPUT_ONLY", source=HEAD, checkout=CHECKOUT,
            tree=TREE, run=RUN, attempt=ATTEMPT, expected=expected, artifact=item,
            producer={k:job[k] for k in ("id","name","run_id","run_attempt","head_sha","started_at","completed_at","conclusion")},
            output_sha256=output_hashes)
        rows.append(row)
        print("HISTORICAL_INPUT " + json.dumps({k:row[k] for k in ("role","source","checkout","run","attempt","expected")}), flush=True)
    (args.destination / "historical-input-receipt.json").write_text(json.dumps(rows, indent=2) + "\n")
if __name__ == "__main__":
    main()
