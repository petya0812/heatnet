#!/usr/bin/env bash
# End-to-end check against a running service: upload -> import -> run -> download -> validation report.
#   tools/e2e.sh test-data/contest-lct.geojson [http://localhost:8080] [--no-run]
# Needs curl and python3 (JSON is read with python3, no jq). Prints "OK in N s" when the run finished with
# 0 validation errors; the responses and result.geojson are written to $OUT_DIR (default target/e2e/).
set -euo pipefail

if [[ $# -lt 1 || "$1" == "-h" || "$1" == "--help" ]]; then
  echo "usage: tools/e2e.sh <input.geojson> [base-url] [--no-run]" >&2
  echo "  input.geojson  GeoJSON to upload, e.g. test-data/contest-lct.geojson" >&2
  echo "  base-url       service address, default http://localhost:8080" >&2
  echo "  --no-run       upload and import only, without the calculation" >&2
  echo "  OUT_DIR        where to put dataset.json, run.json, result.geojson (default target/e2e)" >&2
  exit 2
fi

FILE=$1
BASE=${2:-http://localhost:8080}
MODE=${3:-}
OUT_DIR=${OUT_DIR:-target/e2e}
mkdir -p "$OUT_DIR"
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
[[ -f "$FILE" ]] || { echo "no such file: $FILE" >&2; exit 2; }

json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

t0=$(date +%s)
ds=$(curl -sf -F "file=@${FILE}" "$BASE/api/datasets" | json 'd["id"]')
echo "dataset $ds uploaded in $(( $(date +%s) - t0 )) s"

while true; do
  st=$(curl -sf "$BASE/api/datasets/$ds" | json 'd["status"]')
  [[ "$st" == "READY" || "$st" == "FAILED" ]] && break
  sleep 2
done
curl -sf "$BASE/api/datasets/$ds" > "$OUT_DIR/dataset.json"
echo "import: $(json '"%s, features %s, imported %s, %s ms, issues %s" % (d["status"], d["feature_count"], d["imported_count"], d["import_millis"], d["issue_counts"])' < "$OUT_DIR/dataset.json")"
[[ "$st" == "READY" ]] || { echo "import failed"; exit 1; }
[[ "$MODE" == "--no-run" ]] && exit 0

run=$(curl -sf -X POST "$BASE/api/datasets/$ds/runs" | json 'd["id"]')
while true; do
  st=$(curl -sf "$BASE/api/runs/$run" | json 'd["status"]')
  [[ "$st" == "DONE" || "$st" == "FAILED" ]] && break
  sleep 2
done
curl -sf "$BASE/api/runs/$run" > "$OUT_DIR/run.json"
[[ "$st" == "DONE" ]] || { echo "run failed: $(json 'd["error"]' < "$OUT_DIR/run.json")"; exit 1; }
curl -sf -o "$OUT_DIR/result.geojson" "$BASE/api/runs/$run/result.geojson"
echo "run $run: $(json '"; ".join("variant %s rank %s score %.4f cost %.0f unconnected %s" % (v["variant_id"], v["rank"], v["score"], v["calculated_cost"], v["unconnected_oks_ids"]) for v in d["summary"])' < "$OUT_DIR/run.json")"
errors=$(json 'd["validation"]["errors"]' < "$OUT_DIR/run.json")
echo "validation: errors $errors, warnings $(json 'd["validation"]["warnings"]' < "$OUT_DIR/run.json"); result $(wc -c < "$OUT_DIR/result.geojson") bytes"
[[ "$errors" == "0" ]] || { json '"\n".join(d["validation"]["violations"][:20])' < "$OUT_DIR/run.json"; exit 1; }
echo "OK in $(( $(date +%s) - t0 )) s; files: $OUT_DIR/dataset.json, $OUT_DIR/run.json, $OUT_DIR/result.geojson"
