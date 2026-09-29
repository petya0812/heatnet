#!/usr/bin/env python3
"""Checks the maintained documentation for broken references.

  python3 tools/check_docs.py            # exit code 1 if something is broken

What is checked in every published Markdown file (not ignored by git):
  * relative links [text](path) point to existing published files;
  * paths in backticks (`docs/…`, `src/…`, `tools/…`, `test-data/…`) and bare file names (`output.md`) exist and are
    published — a link to a file that is not published breaks in the published repository;
  * Java names in backticks (`Planner`, `plan/Draft`, `RouteFinder.tieOptions`, `ParamCatalogTest`) exist in the
    sources: the class file, and the member name inside it.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {"target", "node_modules", ".git", "generated"}
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
CODE = re.compile(r"`([^`\n]+)`")
PATH_PREFIXES = ("docs/", "src/", "tools/", "test-data/")
BARE_FILE = re.compile(r"^[\w.-]+\.(md|geojson|py|sh|json|yml)$")
JAVA = re.compile(r"^([a-z]+/)?([A-Z][A-Za-z0-9]*)(?:\.([a-z][A-Za-z0-9]*))?(?:\(\))?$")
# CamelCase words in backticks that are not Java names of this project
NOT_JAVA = {"GeoJSON", "FeatureCollection", "LineString", "MultiLineString", "MultiPolygon", "PostGIS",
            "MapLibre", "OpenSearch", "JdbcTemplate", "DataSource", "EditText", "MultiPoint", "GeometryCollection",
            "OpenStreetMap", "LengthIndexedLine", "VariantSummary", "ContainerConfig", "OutOfMemoryError", "SpringBootApplication", "JavaScript", "TopologyPreservingSimplifier"}


def java_index():
    """Class name -> files declaring it (top-level files and nested classes, interfaces, enums)."""
    classes = {}
    nested = re.compile(r"\b(?:class|interface|enum)\s+([A-Z][A-Za-z0-9]*)")
    for base in ("src/main/java", "src/test/java"):
        for d, _, files in os.walk(os.path.join(ROOT, base)):
            for f in files:
                if f.endswith(".java"):
                    path = os.path.join(d, f)
                    names = set(nested.findall(open(path, encoding="utf-8").read())) | {f[:-5]}
                    for name in names:
                        classes.setdefault(name, []).append(path)
    return classes


def ignored(paths):
    """The paths git would not publish (.gitignore), relative to the root."""
    import subprocess
    if not paths:
        return set()
    out = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"], cwd=ROOT, input="\n".join(paths),
                         capture_output=True, text=True).stdout
    return {line.strip() for line in out.splitlines() if line.strip()}


def maintained():
    found = []
    for d, dirs, files in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS and not x.startswith(".")]
        for f in files:
            if f.endswith(".md"):
                found.append(os.path.relpath(os.path.join(d, f), ROOT))
    hidden = ignored(found)
    return [f for f in found if f not in hidden]


def published(path, cache={}):
    """An existing file or directory that git publishes."""
    rel = os.path.relpath(path, ROOT)
    if rel not in cache:
        cache[rel] = os.path.exists(path) and not ignored([rel])
    return cache[rel]


def main():
    classes = java_index()
    problems = []
    for rel in sorted(maintained()):
        text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        base = os.path.dirname(os.path.join(ROOT, rel))
        for m in LINK.finditer(text):
            target = m.group(1)
            if re.match(r"^[a-z]+:", target) or target.startswith("#"):
                continue
            path = target.split("#")[0]
            if path and not published(os.path.normpath(os.path.join(base, path))):
                problems.append(f"{rel}: broken link {target}")
        for m in CODE.finditer(text):
            token = m.group(1).strip()
            if token.startswith(PATH_PREFIXES) and " " not in token:
                path = re.split(r"[:#§]", token)[0].rstrip("/.,")
                if "*" in path or "{" in path or "<" in path:
                    continue
                if not published(os.path.join(ROOT, path)):
                    problems.append(f"{rel}: missing path `{token}`")
                continue
            if BARE_FILE.match(token):
                here = os.path.join(base, token)
                if not (published(here) or published(os.path.join(ROOT, token))
                        or any(published(os.path.join(ROOT, d, token)) for d in ("docs", "test-data", "tools"))):
                    problems.append(f"{rel}: missing file `{token}`")
                continue
            j = JAVA.match(token)
            if not j or j.group(2) in NOT_JAVA:
                continue
            camel = re.match(r"^[A-Z][a-z0-9]+[A-Z]", j.group(2)) is not None
            if not (j.group(1) or j.group(3) or camel) or j.group(2).isupper():
                continue  # a plain word (`Planner` alone is too ambiguous to tell from prose)
            files = classes.get(j.group(2))
            if not files:
                problems.append(f"{rel}: no Java class `{token}`")
                continue
            member = j.group(3)
            if member and not any(re.search(r"\b" + re.escape(member) + r"\b", open(f, encoding="utf-8").read())
                                  for f in files):
                problems.append(f"{rel}: no member `{token}`")
    for p in problems:
        print(p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
