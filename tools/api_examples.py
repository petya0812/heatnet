#!/usr/bin/env python3
"""Rebuilds `src/main/resources/openapi/examples.json` from a running service.

    python3 tools/api_examples.py [--base http://localhost:8080]

The file maps "METHOD /path" (the key set of the current file is preserved, in the same order) to the
response of a real call; `OpenApiConfig` shows these as examples in Swagger UI and derives the response
models from them, so every field of a response must be present here. What the script does:

  1. uploads test-data/small.geojson, waits for READY; starts a run with {"turn_penalty": 0.15}, waits for DONE;
  2. creates a version with one `add_restriction` edit (a small polygon inside the bbox), waits for READY,
     replaces its edits (PUT), renames the run (PATCH);
  3. calls every GET with the real identifiers (dataset, run, variant 1, first OKS of the summary, the first
     feature id of the file, the first issue code) and stores the responses;
  4. trims arrays to two elements (geometry coordinates — to the first three points), GeoJSON — to two features;
  5. deletes the datasets it created (the version goes with its parent). Nothing else is touched.

Responses that need something to cancel (POST …/cancel) are tried on a fresh run; if it finishes before
the cancel arrives, the value from the current file is kept. Needs only python3 and a service on --base.
"""
import argparse
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMPLES = os.path.join(ROOT, "src", "main", "resources", "openapi", "examples.json")
INPUT = os.path.join(ROOT, "test-data", "small.geojson")
MAX_ITEMS = 2
MAX_POINTS = 3


def api(base, path, method="GET", data=None, headers=None):
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req, timeout=600) as r:
        body = r.read()
    return json.loads(body) if body else None


def api_json(base, path, method, obj):
    return api(base, path, method, json.dumps(obj).encode(), {"Content-Type": "application/json"})


def upload(base, path):
    boundary = uuid.uuid4().hex
    name = os.path.basename(path)
    with open(path, "rb") as f:
        content = f.read()
    ctype = mimetypes.guess_type(name)[0] or "application/geo+json"
    head = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f"Content-Type: {ctype}\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    return api(base, "/api/datasets", "POST", head + content + tail,
               {"Content-Type": f"multipart/form-data; boundary={boundary}"})


def wait(base, path, done, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = api(base, path)
        if d["status"] in done:
            return d
        time.sleep(1)
    raise TimeoutError(f"{path} is not finished after {timeout} s")


def trim(value, key=None):
    """Arrays → first MAX_ITEMS elements, recursively; geometry coordinates → first MAX_POINTS points."""
    if isinstance(value, dict):
        return {k: trim(v, k) for k, v in value.items()}
    if isinstance(value, list):
        if key == "coordinates":
            return trim_coords(value)
        return [trim(v) for v in value[:MAX_ITEMS]]
    return value


def trim_coords(value):
    if value and all(isinstance(v, (int, float)) for v in value):
        return value                                    # one point
    if value and all(isinstance(v, list) and v and isinstance(v[0], (int, float)) for v in value):
        return value[:MAX_POINTS]                       # list of points
    return [trim_coords(v) for v in value[:MAX_ITEMS]]  # rings / parts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    args = ap.parse_args()
    base = args.base

    with open(EXAMPLES, encoding="utf-8") as f:
        old = json.load(f)
    try:
        api(base, "/api/status")
    except urllib.error.URLError as e:
        sys.exit(f"service is not available at {base}: {e}")

    created = []
    out = {}
    try:
        # 1. dataset and run
        accepted = upload(base, INPUT)
        ds_id = accepted["id"]
        created.append(ds_id)
        out["POST /api/datasets"] = accepted
        dataset = wait(base, f"/api/datasets/{ds_id}", ("READY", "FAILED"))
        if dataset["status"] != "READY":
            sys.exit(f"import failed: {dataset.get('error')}")
        started = api_json(base, f"/api/datasets/{ds_id}/runs", "POST", {"turn_penalty": 0.15})
        run_id = started["id"]
        out["POST /api/datasets/{id}/runs"] = started
        run = wait(base, f"/api/runs/{run_id}", ("DONE", "FAILED", "CANCELLED"))
        if run["status"] != "DONE":
            sys.exit(f"run failed: {run.get('error')}")
        print(f"dataset {ds_id}, run {run_id}: {len(run['summary'])} variants")

        # 2. version with one edit, replaced edits, renamed run
        bbox = api(base, f"/api/datasets/{ds_id}/bbox")
        cx, cy = (bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2
        d = 0.0003
        polygon = {"type": "Polygon", "coordinates": [[[cx - d, cy - d], [cx + d, cy - d], [cx + d, cy + d],
                                                       [cx - d, cy + d], [cx - d, cy - d]]]}
        edit = {"op": "add_restriction", "restriction_type": "prohibited_site", "comment": "стройплощадка",
                "geometry": polygon}
        version = api_json(base, f"/api/datasets/{ds_id}/versions", "POST",
                           {"note": "стройплощадка в центре района", "edits": [edit]})
        out["POST /api/datasets/{id}/versions"] = version
        wait(base, f"/api/datasets/{version['id']}", ("READY", "FAILED"))
        edit["comment"] = "стройплощадка до конца года"
        out["PUT /api/datasets/{id}/edits"] = api_json(base, f"/api/datasets/{version['id']}/edits", "PUT",
                                                       {"note": "стройплощадка в центре района", "edits": [edit]})
        wait(base, f"/api/datasets/{version['id']}", ("READY", "FAILED"))
        out["PATCH /api/runs/{id}"] = api_json(base, f"/api/runs/{run_id}", "PATCH",
                                               {"name": "прямые трассы", "note": "штраф за поворот 0,15", "pinned": True})

        # 3. cancels: a fresh run and a fresh upload, cancelled right away; kept from the file if too late
        try:
            r = api_json(base, f"/api/datasets/{ds_id}/runs", "POST", {})
            out["POST /api/runs/{id}/cancel"] = api(base, f"/api/runs/{r['id']}/cancel", "POST")
            wait(base, f"/api/runs/{r['id']}", ("DONE", "FAILED", "CANCELLED"))
        except urllib.error.HTTPError as e:
            print(f"POST /api/runs/{{id}}/cancel: {e.code}, keeping the current example")
        try:
            a = upload(base, INPUT)
            created.append(a["id"])
            out["POST /api/datasets/{id}/cancel"] = api(base, f"/api/datasets/{a['id']}/cancel", "POST")
            wait(base, f"/api/datasets/{a['id']}", ("READY", "FAILED", "CANCELLED"))
        except urllib.error.HTTPError as e:
            print(f"POST /api/datasets/{{id}}/cancel: {e.code}, keeping the current example")

        # 4. reads
        run = api(base, f"/api/runs/{run_id}")
        summary = run["summary"][0]
        oks_id = summary["oks"][0]["oks_id"] if summary.get("oks") else summary["unconnected_oks_ids"][0]
        with open(INPUT, encoding="utf-8") as f:
            first = json.load(f)["features"][0]
        fid = first.get("id") or first["properties"]["id"]
        issue_code = next(iter(dataset["issue_counts"]), None) or "restriction.unknown_type"
        q = urllib.parse.quote
        reads = {
            "GET /api/datasets": "/api/datasets",
            "GET /api/datasets/{id}": f"/api/datasets/{ds_id}",
            "GET /api/datasets/{id}/issues": f"/api/datasets/{ds_id}/issues",
            "GET /api/datasets/{id}/overview": f"/api/datasets/{ds_id}/overview",
            "GET /api/datasets/{id}/bbox": f"/api/datasets/{ds_id}/bbox",
            "GET /api/params": "/api/params",
            "GET /api/issue-catalog": "/api/issue-catalog",
            "GET /api/run-params": "/api/run-params",
            "GET /api/datasets/{id}/runs": f"/api/datasets/{ds_id}/runs",
            "GET /api/runs/{id}": f"/api/runs/{run_id}",
            "GET /api/runs/{id}/validation": f"/api/runs/{run_id}/validation",
            "GET /api/runs/{id}/journal": f"/api/runs/{run_id}/journal?variant=1&after=0",
            "GET /api/runs/{id}/oks/{oksId}/search": f"/api/runs/{run_id}/oks/{q(oks_id)}/search",
            "GET /api/compare": f"/api/compare?a={run_id}:1&b={run_id}:2",
            "GET /api/runs/{id}/variants/{variant}.geojson": f"/api/runs/{run_id}/variants/1.geojson?derived=true",
            "GET /api/runs/{id}/result.geojson": f"/api/runs/{run_id}/result.geojson",
            "GET /api/datasets/{id}/features/{fid}": f"/api/datasets/{ds_id}/features/{q(fid)}",
            "GET /api/datasets/{id}/issue-features": f"/api/datasets/{ds_id}/issue-features?code={q(issue_code)}",
            "GET /api/compare/features": f"/api/compare/features?a={run_id}:1&b={run_id}:2",
            "GET /api/status": "/api/status",
        }
        for key, path in reads.items():
            out[key] = api(base, path)
    finally:
        for ds in created:
            try:
                api(base, f"/api/datasets/{ds}", "DELETE")
            except urllib.error.HTTPError as e:
                print(f"DELETE {ds}: {e.code}")

    result = {}
    for key in old:                      # same keys, same order
        if key in out:
            result[key] = trim(out[key])
        else:
            print(f"{key}: not rebuilt, keeping the current example")
            result[key] = old[key]
    for key in out:
        if key not in old:
            result[key] = trim(out[key])
            print(f"{key}: new key")
    with open(EXAMPLES, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{EXAMPLES}: {len(result)} examples")


if __name__ == "__main__":
    main()
