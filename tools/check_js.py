#!/usr/bin/env python3
"""
Синтаксис интерфейса: каждый модуль должен разбираться как ES-модуль. Ловит то, что не видно глазом после
правки — незакрытую скобку, оборванный объект атрибутов. Нужен node (только для проверки, в сборку не входит).

  python3 tools/check_js.py
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/static/js"


def main():
    if not subprocess.run(["which", "node"], capture_output=True).returncode == 0:
        print("node не найден — проверка пропущена")
        return 0
    bad = 0
    for f in sorted(ROOT.rglob("*.js")):
        r = subprocess.run(["node", "--input-type=module", "--check"],
                           stdin=f.open("rb"), capture_output=True, text=True)
        if r.returncode:
            bad += 1
            line = next((x for x in r.stderr.splitlines() if "SyntaxError" in x), r.stderr.splitlines()[0] if r.stderr else "")
            print(f"{f.relative_to(ROOT.parent)}: {line.strip()}")
    print(f"{bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
