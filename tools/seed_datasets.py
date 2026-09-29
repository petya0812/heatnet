#!/usr/bin/env python3
"""Заводит в работающем сервисе демонстрационные наборы из `test-data/` с понятными названиями.

    python3 tools/seed_datasets.py                 # четыре основных набора (добавить недостающие)
    python3 tools/seed_datasets.py --all           # все наборы из списка ниже
    python3 tools/seed_datasets.py --reset [--all] # удалить все наборы в сервисе и завести заново
    python3 tools/seed_datasets.py --only ЗИЛ      # только наборы, в названии которых есть подстрока

Имя набора в сервисе — это имя файла, под которым он загружен, без расширения. Поэтому файл из
`test-data/` копируется во временный каталог под человеческим именем и загружается уже так.

Название — «Группа · Название»: разделитель — пробел, средняя точка (U+00B7), пробел; интерфейс делит
по нему и показывает группу отдельно. Группы: «Проект» — рабочие данные (конкурсный набор ЗИЛ), «Пример» — небольшие
наборы на одно правило или сценарий, «Нагрузка» — большие наборы для замеров. Что в каждом файле и
какой итог ожидать — `test-data/README.md`.

Порядок в списке ниже — порядок загрузки; в интерфейсе новые наборы сверху, поэтому ЗИЛ
грузится последним и оказывается первым.
"""
import argparse
import json
import mimetypes
import os
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SEP = " \u00b7 "  # « · »

# файл в test-data -> как набор называется в сервисе; порядок = порядок загрузки (конкурсный — последним)
DATASETS = [
    ("random-7.geojson", "Нагрузка · Случайный район, 30 ОКС (seed 7)"),
    ("random-42.geojson", "Нагрузка · Случайный район, 30 ОКС (seed 42)"),
    ("random-100.geojson", "Нагрузка · Случайный район, 30 ОКС (seed 100)"),
    ("dense-big.geojson", "Нагрузка · Плотная застройка 5×5 км, 180 ОКС"),
    ("dense.geojson", "Нагрузка · Плотная застройка, 40 ОКС"),
    ("contest-osm-big.geojson", "Нагрузка · Район 3×3 км, 167 ОКС"),
    ("contest-osm-50.geojson", "Пример · ЗИЛ + 50 ОКС"),
    ("chambers.geojson", "Пример · Выбор камеры в 10 м"),
    ("expensive.geojson", "Пример · Далёкий ОКС"),
    ("star.geojson", "Пример · Совместное против раздельного"),
    ("chain.geojson", "Пример · Рост ДУ и предельная длина"),
    ("dirty.geojson", "Пример · Ошибки во входных данных"),
    ("small.geojson", "Пример · Все правила на 6 ОКС"),
    ("contest-osm.geojson", "Пример · ЗИЛ с окружением OSM"),
    ("contest-lct.geojson", "Проект · ЗИЛ"),
]

# что заводится без --all: конкурсный набор и три примера
DEFAULT = ("contest-lct.geojson", "contest-osm.geojson", "small.geojson", "dirty.geojson")


def api(base, path, method="GET", data=None, headers=None):
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req, timeout=600) as r:
        body = r.read()
    return json.loads(body) if body else None


def upload(base, path, name):
    """multipart/form-data без внешних зависимостей."""
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    ctype = mimetypes.guess_type(name)[0] or "application/geo+json"
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        f"Content-Type: {ctype}\r\n\r\n"
    ).encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    body = head + content + tail
    return api(base, "/api/datasets", "POST", body,
               {"Content-Type": f"multipart/form-data; boundary={boundary}"})


def wait_ready(base, ds_id, timeout=900):
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = api(base, f"/api/datasets/{ds_id}")
        if d["status"] in ("READY", "FAILED"):
            return d
        time.sleep(2)
    raise TimeoutError(f"набор {ds_id} импортируется дольше {timeout} с")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--reset", action="store_true", help="сначала удалить все существующие наборы")
    ap.add_argument("--all", action="store_true", help="все наборы из списка, а не только основные")
    ap.add_argument("--only", help="загрузить только наборы, в названии которых есть эта подстрока")
    args = ap.parse_args()

    try:
        existing = api(args.base, "/api/datasets")
    except urllib.error.URLError as e:
        sys.exit(f"сервис недоступен на {args.base}: {e}")

    if args.reset:
        for d in existing:
            api(args.base, f"/api/datasets/{d['id']}", "DELETE")
            print(f"удалён {d['name']}")
        existing = []

    have = {d["name"] for d in existing}
    tmp = tempfile.mkdtemp(prefix="heatnet-seed-")
    try:
        for file_name, title in DATASETS:
            assert SEP in title, f"название без разделителя «Группа · Название»: {title}"
            if args.only and args.only.lower() not in title.lower():
                continue
            if not args.all and not args.only and file_name not in DEFAULT:
                continue
            if title in have:
                print(f"уже есть: {title}")
                continue
            src = os.path.join(ROOT, "test-data", file_name)
            if not os.path.exists(src):
                print(f"нет файла {src}, пропуск")
                continue
            named = os.path.join(tmp, title + ".geojson")
            shutil.copyfile(src, named)
            r = upload(args.base, named, os.path.basename(named))
            d = wait_ready(args.base, r["id"])
            print(f"{d['status']:7} {title} — объектов {d.get('feature_count')}, "
                  f"импорт {d.get('import_millis')} мс")
            os.remove(named)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    final = api(args.base, "/api/datasets")
    print(f"наборов в сервисе: {len(final)}")


if __name__ == "__main__":
    main()
