# heatnet

Сервис подключения новых зданий к тепловой сети. На вход — один GeoJSON: существующая сеть, перспективные ОКС,
которые нужно подключить, и ограничения на местности. На выход — до трёх вариантов подключения одним GeoJSON:
трассы новых участков, места врезки в существующую сеть, расходы, диаметры, стоимость и ранжирование.
Веб-интерфейс с картой и HTTP API. Решение кейса «Сервис моделирования трасс подключения к тепловым сетям», ЛЦТ 2026.

**Развёрнутый сервис:** https://heatnet.datrics.online — интерфейс; API — https://heatnet.datrics.online/swagger-ui.html.

## Быстрый старт

Нужны Docker, docker-compose 1.29.2 (или Docker Compose v2 — тогда `docker compose` вместо `docker-compose`)
и интернет для первой сборки. Значения памяти по умолчанию рассчитаны на сервер с 16 ГБ; для машины с 8 ГБ —
строка в разделе «Если что-то не так». Требования к машине — [docs/install.md](docs/install.md).

```bash
docker-compose up -d --build                 # первая сборка 3–5 мин, дальше — секунды
curl http://localhost:8080/api/status        # сервис поднялся: {"running":0,"queued":0,"threads":2}
tools/e2e.sh test-data/contest-lct.geojson   # конкурсный набор: загрузка → расчёт → выгрузка → проверка
```

`e2e.sh` (нужны `curl` и `python3`) печатает примерно следующее (30–60 с в зависимости от машины):

```
dataset 925d30b1-… uploaded in 0 s
import: READY, features 144, imported 144, 89 ms, issues {'network.flow_assumed': 29, 'restriction.building': 85, …}
run 62c342a2-…: variant 1 rank 1 score 13.0767 cost 261915655 unconnected []; variant 2 rank 2 score 15.5371 …; variant 3 rank 3 score 45.8232 …
validation: errors 0, warnings 0; result    40529 bytes
OK in 27 s; files: target/e2e/dataset.json, target/e2e/run.json, target/e2e/result.geojson
```

`OK` значит: независимая проверка результата по обязательным правилам — 0 нарушений; три варианта и
`unconnected []` (все 17 ОКС подключены) видны в строке `run`. Каталог для файлов задаётся `OUT_DIR=…`, адрес
сервиса — вторым аргументом: `tools/e2e.sh test-data/contest-lct.geojson http://localhost:8081`. Замечания в `issues` — не ошибки:
так сервис сообщает, какие значения он восполнил (например, расход существующей сети, которого нет во входе);
они видны и в интерфейсе на шаге проверки данных.

**Готовая выгрузка на конкурсном наборе** — [results/contest-lct/](results/contest-lct/):
`results/contest-lct/result.geojson` (все три варианта в формате раздела 7 технического приложения) и
`results/contest-lct/run.json` (сводка вариантов и отчёт проверки).

## Что открыть

| Что | Адрес | Что делать |
|---|---|---|
| Интерфейс | http://localhost:8080/ | После запуска сервис пуст: `python3 tools/seed_datasets.py` заведёт конкурсный набор и три примера. В группе «Проекты» открыть «ЗИЛ» (это `contest-lct.geojson`) → «Рассчитать» → через полминуты результат: карта с тремя вариантами, таблица ОКС, стоимость; дальше вкладки «Сравнение» и «Выгрузка» |
| Swagger UI | http://localhost:8080/swagger-ui.html | Разделы идут в порядке сценария (1. Наборы данных → 2. Расчёт → 3. Результат и выгрузка → …); сценарий из 5 запросов описан в шапке; у ответов — примеры реального расчёта и описания полей |
| OpenAPI | http://localhost:8080/v3/api-docs | машиночитаемая спецификация |

Конкурсный набор — `test-data/contest-lct.geojson` (144 объекта, 17 ОКС). Ожидаемый итог: 3 варианта,
**17 из 17 подключено**, лучший вариант — S 13,08, 262 млн руб. Остальные наборы и что от них ждать —
[test-data/README.md](test-data/README.md).

## Настройки расчёта

Правила технического приложения (отступы, пересечения, врезки, диаметры, предельные длины, стоимость) не меняются.
Инженер может только ужесточить их — результат с любыми настройками проходит ту же проверку:

- дополнительный отступ от зданий и ограничений, 0–10 м;
- минимальный угол пересечения дорог и трамвайных путей, 45–90°;
- **углы поворота трассы**: любые до 90° / 30°, 60°, 90° / 45° и 90° (по умолчанию) / только 90°; и что делать,
  если у ОКС нет трассы с такими углами — построить с любыми углами или оставить без трассы;
- совместное подключение нескольких ОКС через одну врезку — вкл/выкл;
- штраф за поворот (насколько прямыми должны быть трассы).

В интерфейсе — экран «Расчёт», в API — тело `POST /api/datasets/{id}/runs` ([docs/api.md](docs/api.md)); полный
список параметров с основанием каждого правила — [docs/parameters.md](docs/parameters.md).

## Реконструкция существующей сети

Постановка (п. 2.10–2.11) требует участки и камеры реконструкции существующей сети. Техническое приложение
в редакции 18.09.2026 (п. 2.4) и разъяснение 14 её отменили: существующая сеть не перекладывается, реконструкция
не входит в стоимость, оценку S и файл результата. Сервис всё равно передаёт расход новых подключений от врезки
к источнику и показывает участки, которым не хватит пропускной способности (слой «Не хватит пропускной способности», карточка
«Пропускная способность существующей сети», `GET /api/runs/{id}/variants/{v}.geojson?derived=true`), — чтобы инженер видел
последствия подключения. Подробно — [docs/output.md](docs/output.md).

## Через API — 5 запросов

С `jq` (без него — выписать `id` из ответа руками: `{"id":"<uuid>","status":"UPLOADED"}`):

```bash
B=http://localhost:8080
DS=$(curl -s -F file=@test-data/contest-lct.geojson $B/api/datasets | jq -r .id)   # 202 {"id":…,"status":"UPLOADED"}
curl -s $B/api/datasets/$DS | jq .status                                              # "IMPORTING" → через 1–2 с "READY"
RUN=$(curl -s -X POST -H 'Content-Type: application/json' -d '{}' $B/api/datasets/$DS/runs | jq -r .id)   # 202 {"id":…,"status":"QUEUED"}
curl -s $B/api/runs/$RUN | jq '{status, errors: .validation.errors, unconnected: .summary[0].unconnected_oks_ids}'
#   "RUNNING" → через полминуты {"status":"DONE","errors":0,"unconnected":[]}
curl -s -o result.geojson $B/api/runs/$RUN/result.geojson                             # все варианты одним файлом
```

С другими углами поворота — тело `-d '{"turn_angles":"90"}'`. Версии набора с правками, журнал построения,
сравнение вариантов — [docs/api.md](docs/api.md).

## Если что-то не так

| Симптом | Что сделать |
|---|---|
| `Bind for 0.0.0.0:8080 failed` — порт занят | `APP_PORT=8081 docker-compose up -d` |
| Машина с 8 ГБ памяти, контейнеры падают | `JAVA_OPTS=-Xmx2g APP_MEM_LIMIT=4g DB_MEM_LIMIT=2g DB_SHARED_BUFFERS=512MB docker-compose up -d` |
| Apple Silicon | образ БД есть только под `linux/amd64` и идёт под эмуляцией: медленнее, но работает |
| docker-compose 1.29.2 с новым Docker Engine: `KeyError: 'ContainerConfig'` или «network … needs to be recreated» при повторном `up` | `docker-compose down && docker-compose up -d`; подробнее — [docs/install.md](docs/install.md) |
| `/api/status` не отвечает сразу после `up` | приложение стартует после того, как БД станет `healthy`: `docker-compose ps`, `docker-compose logs -f app` |

## Демонстрационные наборы

`python3 tools/seed_datasets.py` — конкурсный набор и три примера под названиями «Группа · Название»;
`--all` — все 15 файлов из `test-data/`, `--reset` — удалить всё в сервисе и завести заново.
Что в каждом файле и ожидаемый итог — [test-data/README.md](test-data/README.md).

## Документация

| Для кого | Что |
|---|---|
| Проверяющему | установка и настройки — [docs/install.md](docs/install.md); API — [docs/api.md](docs/api.md); наборы — [test-data/README.md](test-data/README.md), [docs/data.md](docs/data.md); интерфейс — [docs/ui.md](docs/ui.md) |
| Предметная область и алгоритм | объекты и правила — [docs/domain.md](docs/domain.md); алгоритм поиска трасс и совместного подключения — [docs/algorithm.md](docs/algorithm.md); формат выхода — [docs/output.md](docs/output.md); параметры — [docs/parameters.md](docs/parameters.md); ОКС без трассы — [docs/unconnected.md](docs/unconnected.md); границы применения — [docs/limits.md](docs/limits.md) |
| Разработка | требования и чем проверены — [docs/requirements.md](docs/requirements.md); замеры — [docs/metrics.md](docs/metrics.md); карта кода и команды — [docs/development.md](docs/development.md) |

Стек: Java 11, Spring Boot 2.6.3, PostgreSQL 18 + PostGIS 3.6, springdoc-openapi 1.7.0, docker-compose 1.29.2.

## Сборка и проверки

```bash
mvn verify                                    # сборка и тесты (JDK 11)
python3 tools/check_docs.py                   # ссылки, пути и имена классов в документации
python3 tools/loadtest.py --users 50          # нагрузка на запущенный сервис
```

Наборы `test-data/contest-osm*.geojson` собраны из OpenStreetMap: © участники OpenStreetMap, ODbL.

## Лицензия

Код — [MIT](LICENSE). Иконки интерфейса — Lucide (ISC), данные OpenStreetMap — ODbL; подробности —
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
