#!/usr/bin/env python3
"""
Проверка единого языка оформления: цвет в интерфейсе задаётся только токенами из начала frontend/src/styles.css.

Где цвет разрешён прямым значением:
  - объявления токенов в styles.css (`--что-то: #rrggbb`);
  - слои карты (`src/map/`) — там цвет принадлежит данным: ДУ, типы ограничений, состояния объектов;
  - значок страницы в index.html.

  python3 tools/check_tokens.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "frontend/src"
HEX = re.compile(r"#[0-9a-fA-F]{3,8}\b")
MAP_COLOURS_OK = ("map/",)


def check_css(path):
    out = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        code = line.split("/*")[0]
        if not HEX.search(code):
            continue
        if re.match(r"\s*--[\w-]+\s*:", code):
            continue
        out.append(f"{path.relative_to(ROOT)}:{n}: цвет вне токена — {code.strip()}")
    return out


def check_code(path):
    rel = str(path.relative_to(ROOT))
    if rel.startswith(MAP_COLOURS_OK):
        return []
    out = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if HEX.search(line.split("//")[0]):
            out.append(f"{rel}:{n}: цвет в коде интерфейса — {line.strip()[:100]}")
    return out


def main():
    problems = []
    problems += check_css(ROOT / "styles.css")
    for f in sorted(list(ROOT.rglob("*.vue")) + list(ROOT.rglob("*.ts"))):
        problems += check_code(f)
    for p in problems:
        print(p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
