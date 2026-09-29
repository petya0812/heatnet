#!/usr/bin/env python3
"""
Load test of a running service: N simultaneous users (default 50), each does what a user of the map does.

  python3 tools/loadtest.py [--base http://localhost:8080] [--users 50] [--uploaders 10]
                            [--map-dataset <id>] [--out target/loadtest.json]

Every user: list of datasets -> dataset card -> 30 map tiles around the network -> start a calculation ->
poll its status every second -> layers of the variants -> download of the result.
The first --uploaders users first upload their own copy of test-data/small.geojson and wait for the import.
Calculations go to the queue of the service (heatnet.run-threads in parallel), the rest wait there.
Datasets created by the test are deleted at the end. Only the standard library is used.
"""
import argparse
import json
import math
import random
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from pathlib import Path

lock = threading.Lock()
lat_by_kind = defaultdict(list)
errors = defaultdict(int)
error_samples = []
run_times = []


def record(kind, dt, ok, detail=None):
    with lock:
        lat_by_kind[kind].append(dt)
        if not ok:
            errors[kind] += 1
            if len(error_samples) < 20:
                error_samples.append(f"{kind}: {detail}")


def call(base, kind, method, path, body=None, headers=None, raw=False, timeout=300):
    req = urllib.request.Request(base + path, data=body, method=method, headers=headers or {})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            record(kind, time.time() - t, True)
            if raw:
                return data
            return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        record(kind, time.time() - t, False, f"{e.code} {e.read()[:200]!r}")
    except Exception as e:  # connection errors, timeouts
        record(kind, time.time() - t, False, repr(e))
    return None


def upload(base, file):
    boundary = uuid.uuid4().hex
    content = Path(file).read_bytes()
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{Path(file).name}\"\r\n"
            f"Content-Type: application/geo+json\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    r = call(base, "upload", "POST", "/api/datasets", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    return r["id"] if r else None


def tiles_around(bbox, count):
    lon = (bbox[0] + bbox[2]) / 2
    lat = (bbox[1] + bbox[3]) / 2
    out = []
    for i in range(count):
        z = random.choice([13, 14, 15, 16])
        n = 2 ** z
        jl = lon + random.uniform(-0.5, 0.5) * (bbox[2] - bbox[0])
        jt = lat + random.uniform(-0.5, 0.5) * (bbox[3] - bbox[1])
        x = int((jl + 180) / 360 * n)
        r = math.radians(jt)
        y = int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n)
        out.append((z, x, y))
    return out


def user(i, args, map_ds, run_ds, created):
    base = args.base
    ds_for_run = run_ds
    if i < args.uploaders:
        own = upload(base, args.upload_file)
        if own:
            with lock:
                created.append(own)
            t0 = time.time()
            while time.time() - t0 < 300:
                d = call(base, "dataset", "GET", f"/api/datasets/{own}")
                if d and d["status"] in ("READY", "FAILED"):
                    break
                time.sleep(1)
            ds_for_run = own
    call(base, "datasets", "GET", "/api/datasets")
    call(base, "dataset", "GET", f"/api/datasets/{map_ds}")
    bbox = call(base, "bbox", "GET", f"/api/datasets/{map_ds}/bbox")
    if bbox:
        for z, x, y in tiles_around(bbox, args.tiles):
            call(base, "tile", "GET", f"/api/datasets/{map_ds}/tiles/{z}/{x}/{y}.mvt", raw=True)
    t0 = time.time()
    r = call(base, "run_start", "POST", f"/api/datasets/{ds_for_run}/runs", json.dumps({}).encode(),
             {"Content-Type": "application/json"})
    if not r:
        return
    run = r["id"]
    status = None
    while time.time() - t0 < args.run_timeout:
        s = call(base, "run_status", "GET", f"/api/runs/{run}")
        status = s["status"] if s else None
        if s is None or status in ("DONE", "FAILED", "CANCELLED"):
            break
        time.sleep(1)
    with lock:
        run_times.append(time.time() - t0)
    if status != "DONE":
        record("run_result", 0, False, f"run {run} ended {status}")
        return
    record("run_result", 0, True)
    for v in s.get("summary") or []:
        call(base, "variant_layers", "GET", f"/api/runs/{run}/variants/{v['variant_id']}.geojson", raw=True)
    call(base, "result_download", "GET", f"/api/runs/{run}/result.geojson", raw=True)


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    k = min(len(v) - 1, max(0, int(math.ceil(p / 100 * len(v))) - 1))
    return v[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--users", type=int, default=50)
    ap.add_argument("--uploaders", type=int, default=10)
    ap.add_argument("--tiles", type=int, default=30)
    ap.add_argument("--upload-file", default="test-data/small.geojson")
    ap.add_argument("--map-dataset", help="READY dataset for the map (default: a fresh upload of test-data/dense.geojson)")
    ap.add_argument("--run-timeout", type=int, default=900)
    ap.add_argument("--out", default="target/loadtest.json")
    args = ap.parse_args()

    created = []
    map_ds = args.map_dataset
    if not map_ds:
        map_ds = upload(args.base, "test-data/dense.geojson")
        created.append(map_ds)
        while call(args.base, "dataset", "GET", f"/api/datasets/{map_ds}")["status"] not in ("READY", "FAILED"):
            time.sleep(1)
    run_ds = upload(args.base, args.upload_file)
    created.append(run_ds)
    while call(args.base, "dataset", "GET", f"/api/datasets/{run_ds}")["status"] not in ("READY", "FAILED"):
        time.sleep(1)
    lat_by_kind.clear()
    errors.clear()

    t0 = time.time()
    threads = [threading.Thread(target=user, args=(i, args, map_ds, run_ds, created)) for i in range(args.users)]
    for t in threads:
        t.start()
    peak = {"running": 0, "queued": 0}
    while any(t.is_alive() for t in threads):
        s = call(args.base, "status", "GET", "/api/status")
        if s:
            peak["running"] = max(peak["running"], s["running"])
            peak["queued"] = max(peak["queued"], s["queued"])
        time.sleep(1)
    wall = time.time() - t0

    report = {"users": args.users, "uploaders": args.uploaders, "wall_s": round(wall, 1), "peak": peak,
              "errors_total": sum(errors.values()), "errors": dict(errors), "error_samples": error_samples,
              "run_s": {"n": len(run_times), "p50": round(pct(run_times, 50) or 0, 1),
                        "p95": round(pct(run_times, 95) or 0, 1), "max": round(max(run_times or [0]), 1)},
              "latency_ms": {}}
    for kind, v in sorted(lat_by_kind.items()):
        if kind == "run_result":
            continue
        report["latency_ms"][kind] = {"n": len(v), "p50": round(1000 * statistics.median(v)),
                                      "p95": round(1000 * pct(v, 95)), "max": round(1000 * max(v))}
    for ds in created:
        call(args.base, "cleanup", "DELETE", f"/api/datasets/{ds}")
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1 if report["errors_total"] else 0)


if __name__ == "__main__":
    main()
