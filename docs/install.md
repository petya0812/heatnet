# Развёртывание и запуск

Сервис состоит из двух контейнеров: `db` — PostgreSQL 18 с PostGIS 3.6, `app` — приложение на Java 11
(Spring Boot 2.6.3). Оба собираются и поднимаются одной командой docker-compose 1.29.2 (ТЗ, раздел 3).

## Требования к серверу

| Что | Значение |
|---|---|
| ОС | Ubuntu Server 22.04 x86-64 |
| Память | 16 ГБ: БД до 5 ГБ, приложение до 9 ГБ (куча 6 ГБ), остальное — ОС и кэш |
| Диск | ≥ 20 ГБ свободно: набор 3 ГБ занимает в PostGIS ≈ 4,3 ГБ с индексами, на время загрузки нужен ещё его размер |
| Сеть | порт 8080 наружу; интернет нужен только для сборки образа (зависимости Maven и базовые образы) |
| ПО | Docker Engine, docker-compose 1.29.2 |

## Установка Docker и docker-compose 1.29.2

```bash
sudo apt-get update && sudo apt-get install -y docker.io curl
sudo curl -L "https://github.com/docker/compose/releases/download/1.29.2/docker-compose-Linux-x86_64" \
  -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
docker-compose version    # docker-compose version 1.29.2
```

## Запуск

```bash
cd <корень репозитория>         # там, где лежит docker-compose.yml
docker-compose up -d --build    # сборка образа приложения и запуск; первая сборка ≈ 3–5 мин (нужен интернет)
docker-compose ps               # db — healthy, app — Up
curl -f http://localhost:8080/api/status   # готовность: {"running":0,"queued":0,"threads":2}
```

У контейнера `app` нет healthcheck: `Up` в `docker-compose ps` — это запущенная JVM, а не готовность к запросам.
Признак готовности — ответ `/api/status` (`curl -f` возвращает ненулевой код, пока сервис не поднялся); приложение
стартует только после того, как `db` станет `healthy`, при первом запуске это 10–30 с. Если ответа нет дольше
минуты — `docker-compose logs -f app`.

После запуска сервис пуст. Демонстрационные наборы (конкурсный и три примера): `python3 tools/seed_datasets.py`;
`--all` — все наборы из `test-data/` ([test-data/README.md](../test-data/README.md)).

После запуска:

| Адрес | Что |
|---|---|
| http://сервер:8080/ | интерфейс: загрузка набора, расчёт, карта, сравнение вариантов, выгрузка |
| http://сервер:8080/swagger-ui.html | документация API (springdoc-openapi-ui 1.7.0) |
| http://сервер:8080/v3/api-docs | описание API в формате OpenAPI |

Проверка всей цепочки без интерфейса — загрузка, импорт, расчёт, выгрузка, проверка правил:

```bash
tools/e2e.sh test-data/contest-lct.geojson http://localhost:8080   # конкурсный набор; остальные — test-data/README.md
```

Остановка: `docker-compose down` (данные сохраняются в томах), полная очистка вместе с данными: `docker-compose down -v`.

## Настройки

Задаются переменными окружения при запуске (`KEY=value docker-compose up -d`) или в файле `.env` рядом
с `docker-compose.yml`. Значения по умолчанию подобраны под сервер 16 ГБ. В `.env` уже задано имя стека
`COMPOSE_PROJECT_NAME=heatnet`: контейнеры, тома и сеть (`heatnet_default`) называются одинаково, в какую бы папку
ни был склонирован репозиторий.

| Переменная | По умолчанию | Что |
|---|---|---|
| `APP_PORT` | 8080 | порт сервиса на хосте |
| `JAVA_OPTS` | `-Xmx6g -XX:+ExitOnOutOfMemoryError` | параметры JVM |
| `APP_MEM_LIMIT`, `DB_MEM_LIMIT` | 9g, 5g | лимиты памяти контейнеров |
| `DB_SHARED_BUFFERS` | 1536MB | `shared_buffers` PostgreSQL |
| `DB_PASSWORD` | heatnet | пароль БД (внутренняя сеть compose, наружу БД не публикуется) |
| `HEATNET_RUN_THREADS` | 2 | сколько расчётов идёт одновременно; остальные ждут в очереди (до 200) |
| `HEATNET_IMPORT_THREADS` | 2 | сколько импортов идёт одновременно |
| `HEATNET_EXISTING_FLOW` | ZERO | расход существующей сети, если его нет во входе: `ZERO` — сеть свободна, `CAPACITY_SHARE` — занята долей пропускной способности |
| `HEATNET_EXISTING_FLOW_SHARE` | 0.5 | доля при `CAPACITY_SHARE` |
| `HEATNET_MIN_FREE_DISK_MB` | 2048 | меньше свободного места — загрузка и запись результата отклоняются |
| `HEATNET_KEEP_RUNS`, `HEATNET_KEEP_RUNS_MIN_HOURS` | 50, 24 | хранится 50 последних расчётов набора; более старые удаляются, если им больше 24 ч |

Допущение о расходе сети — значение по умолчанию; в интерфейсе и в API его можно задать для отдельного расчёта.
Все параметры и их происхождение — [parameters.md](parameters.md).

**Машина с 8 ГБ памяти.** Значения по умолчанию (куча 6 ГБ + БД 5 ГБ) на ней не поместятся: `mem_limit`
не защитит, Java всё равно попробует взять 6 ГБ. Запускать так:

```bash
JAVA_OPTS=-Xmx2g APP_MEM_LIMIT=4g DB_MEM_LIMIT=2g DB_SHARED_BUFFERS=512MB docker-compose up -d
```

Конкурсный набор и примеры считаются в этих пределах; набор 3 ГБ на такой машине не проверялся.
Порт занят (`Bind for 0.0.0.0:8080 failed`) — `APP_PORT=8081 docker-compose up -d`.

## Хранение данных

| Том | Что |
|---|---|
| `db-data` | БД: служебные таблицы (`dataset`, `run`, `dataset_issue`), наборы (`data.ds_<id>`), результаты для карты (`data.run_<id>`) |
| `app-data` | `/data/results` — файлы выгрузки; `/data/uploads`, `/data/tmp` — загружаемый файл до конца импорта |

Загруженный файл удаляется сразу после импорта: данные живут в PostGIS. При перезапуске сервиса незавершённые
импорты и расчёты помечаются как прерванные, их частичные таблицы и временные файлы удаляются.

## Известные особенности docker-compose 1.29.2

docker-compose 1.29.2 задан ТЗ, но это последняя версия старой ветки (2021), и с новым Docker Engine у неё есть
расхождения. Обе особенности касаются только **повторного** `up` для уже созданного стека; первый запуск на чистой
машине проходит.

1. `Network "heatnet_default" needs to be recreated - option ... has changed` — сеть не пересоздаётся, контейнер
   не поднимается (воспроизведено). Решение:

   ```bash
   docker-compose down && docker network rm heatnet_default; docker-compose up -d
   ```

2. `KeyError: 'ContainerConfig'` при пересоздании контейнера — по сообщениям пользователей, docker-compose 1.29.2
   с Docker Engine ≥ 25 падает так при `up` поверх существующего контейнера (движок перестал отдавать поле
   `ContainerConfig`, на которое рассчитывает старый compose). На наших машинах не воспроизведено. Обход тот же —
   не пересоздавать, а поднять заново:

   ```bash
   docker-compose down && docker-compose up -d
   ```

   Данные сохраняются в томах, `down` их не трогает (удаляет только `down -v`).

Apple Silicon: образ `postgis/postgis` есть только под `linux/amd64`, в compose стоит `platform: linux/amd64`, БД
идёт под эмуляцией — медленнее, но работает; замеры в [metrics.md](metrics.md) сняты именно так.

## Сборка и тесты без Docker Compose

```bash
docker run --rm -v "$PWD":/w -v ~/.m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-11 mvn -B verify
```

Интеграционные тесты API против PostGIS запускаются, если передать адрес БД (создают отдельную базу `heatnet_it`):

```bash
docker run --rm --network heatnet_default -v "$PWD":/w -v ~/.m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-11 \
  mvn -B verify -Dheatnet.it.db=jdbc:postgresql://db:5432/heatnet
```
