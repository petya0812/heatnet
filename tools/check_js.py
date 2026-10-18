#!/usr/bin/env python3
"""
Типы и синтаксис интерфейса (frontend/): vue-tsc проверяет каждый модуль и шаблон. Ловит то, что не видно глазом
после правки — опечатку в имени, незакрытый тег, обращение к несуществующему полю. Нужны node и установленные
зависимости (`npm --prefix frontend ci`); в сборку jar это не входит.

  python3 tools/check_js.py
"""
import subprocess
import sys
from pathlib import Path

FRONT = Path(__file__).resolve().parent.parent / "frontend"


def main():
    if subprocess.run(["which", "node"], capture_output=True).returncode != 0:
        print("node не найден — проверка пропущена")
        return 0
    if not (FRONT / "node_modules").exists():
        print("нет frontend/node_modules — выполните npm --prefix frontend ci")
        return 1
    r = subprocess.run(["npx", "vue-tsc", "--noEmit"], cwd=FRONT, capture_output=True, text=True)
    out = (r.stdout + r.stderr).strip()
    if out:
        print(out)
    print(f"{0 if r.returncode == 0 else len([l for l in out.splitlines() if 'error' in l])} problem(s)")
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
