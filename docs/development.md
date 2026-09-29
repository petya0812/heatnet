# Разработка

Карта кода, команды и правила, которые нельзя нарушать при изменениях. Установка и запуск — [install.md](install.md).

## Команды

Нужны JDK 11 и Maven 3.8+; для сквозных проверок — запущенный сервис (`docker-compose up -d --build`).

```bash
mvn verify                                    # сборка и все тесты без БД
mvn test -Dtest=PipelineTest                  # один класс; -Dtest=Class#method — один тест
mvn test -Dtest=BenchRun -Dbench=contest-lct.geojson,random-42.geojson -Dreasons=1   # замеры вариантов
mvn test -Dtest=BenchRun -Dbench=contest-lct.geojson -DturnStep=90                   # то же с другими углами поворота
mvn test -Dtest=ParamCatalogTest -DupdateDocs=true   # пересобрать docs/parameters.md из каталога параметров
python3 tools/check_docs.py                   # ссылки, пути и имена классов в документации
python3 tools/check_js.py && python3 tools/check_tokens.py   # синтаксис и оформление интерфейса
tools/e2e.sh test-data/contest-lct.geojson    # загрузка → расчёт → выгрузка → проверка через API
python3 tools/seed_datasets.py [--reset] [--all]   # демонстрационные наборы в работающем сервисе
python3 tools/api_examples.py                 # пересобрать openapi/examples.json с работающего сервиса
python3 tools/loadtest.py --users 50          # нагрузка на запущенный сервис
```

Интеграционные тесты API (`web/ApiIntegrationTest`) идут только с `-Dheatnet.it.db=...`, из сети compose
(`heatnet_default`, имя проекта задано в `.env`):

```bash
docker run --rm --network heatnet_default -v "$PWD":/w -v ~/.m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-11 \
  mvn -B verify -Dheatnet.it.db=jdbc:postgresql://db:5432/heatnet
```

## Ограничения стека

- **Java 11** (`release=11`), **spring-boot-starter-parent 2.6.3** → Maven, `javax.*`, Spring 5.3. Версию
  родителя не поднимать; управляемые ею зависимости переопределять можно.
- PostgreSQL ≤ 18 + PostGIS, springdoc-openapi-ui 1.7.0, docker-compose 1.29.2 (формат файла 2.4).
- `postgis/postgis` публикуется только под amd64 — в compose стоит `platform: linux/amd64`.
- Вход до 3 ГБ — потоково через диск, выгрузка до 500 МБ — потоково. До 50 пользователей, один экземпляр, офлайн.

## Инварианты расчёта

- Координаты, id, число объектов и прочую конфигурацию набора в коде не фиксировать. Справочники и правила —
  `src/main/resources/reference/tech-appendix.json`, не россыпью по коду.
- Вход в EPSG:4326, **все длины, расстояния, буферы — в EPSG:32637**, выход — в 4326.
- В выходе у каждого `object_type` только свои атрибуты, чужих полей с `null` нет.
- Маршруты не правятся вручную.
- Планировщик и `OutputValidator` применяют одни и те же правила; новое правило — в оба, плюс тест, который
  портит результат и ждёт ошибку проверки.
- Результат не зависит от числа потоков (`ServiceLayersTest`), журнал построения не меняет результат
  (`JournalTest`). Всё, что планировщик делит между потоками, — неизменяемое и прогретое.
- Требование меняется — меняется строка в [requirements.md](requirements.md); `RequirementsCoverageTest` сверяет
  реестр с кодом.

## Архитектура (`src/main/java/ru/lct/heatnet`)

- `input` — потоковый разбор (`FeatureParser`), модель сети в UTM (`InputModel`), достройка топологии по
  геометрии (`Topology`), препятствия по области (`ObstacleSource`, в памяти или запросом к PostGIS).
- `plan` — `RouteFinder` (A* по графу видимости: вершины — углы буферов, повороты до 90°; при заданных углах
  поворота — по направлениям `Frame` с шагом 30°, 45° или 90°; цели — места присоединения и точки новой сети),
  `RoutingContext` (отступы, спецпроходы, кромка дороги `PolygonBoundary`), `Draft` (сеть варианта, расходы, ДУ,
  предельная длина по каждому пути), `Planner` (варианты, перестройка, сборка выхода), `ReconstructionCalculator`
  (где существующей сети не хватит пропускной способности).
- `output/ResultWriter` — формат выхода; `validate/OutputValidator` — независимая проверка результата.
- `service`, `web` — Spring: импорт в `data.ds_<id>`, расчёты в пуле, результаты файлом и таблицей для карты,
  тайлы (`MapRepository`), каталог параметров (`ParamCatalog` → [parameters.md](parameters.md) и экран «Расчёт»),
  тексты диагностики (`IssueCatalog`), версии набора с правками, журнал построения, сравнение и отчёт
  (`ExplainService`).
- Описание API — целиком в `web/OpenApiConfig`: разделы в порядке сценария, summary и description каждой
  операции, модели ответов строятся из `src/main/resources/openapi/examples.json` (снимки реального прогона,
  `tools/api_examples.py`) с описанием полей в `FIELDS`. В контроллере аннотаций Swagger нет; новая операция —
  строка `doc(...)` в `DOCS`, новое поле ответа — строка в `FIELDS`.
- Параметры расчёта (`RunParams` → `PlanParams`): недостающие данные и настройки трассировки
  (`extra_clearance_m`, `min_crossing_angle_deg`, `turn_angles`, `turn_angles_strict`, `joint_connection`,
  `turn_penalty`) только ужесточают правила; `OutputValidator` проверяет минимумы технического приложения
  (`EngineerPreferencesTest`). Новый параметр — поле `RunParams`, поле `PlanParams`, запись в `ParamCatalog`
  (интерфейс, сравнение и отчёт подхватывают её сами), `ParamCatalogTest -DupdateDocs=true`.
- `src/main/resources/static` — интерфейс: четыре шага (данные → проверка → расчёт → результат с вкладками
  сравнения и выгрузки), ES-модули без сборки, MapLibre из webjar, знаки — `icons.svg`; устройство — [ui.md](ui.md).
  Имя набора «Группа · Название» (см. `tools/seed_datasets.py`) раскладывает наборы по группам на первом экране.
