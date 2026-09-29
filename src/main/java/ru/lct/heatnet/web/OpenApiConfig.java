package ru.lct.heatnet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The published description of the API. The controller only maps HTTP; everything a reader of Swagger UI sees is
 * here, in one place: the sections and their order, the summary and the description of every operation, the
 * models of the responses (built from {@code openapi/examples.json} — responses of a real run, trimmed to a
 * couple of elements per collection — with a description of every field), the request bodies, the error format.
 */
@Configuration
public class OpenApiConfig {

    // ------------------------------------------------------------------ sections, in the order of the scenario

    static final String T_DATASETS = "1. Наборы данных";
    static final String T_RUNS = "2. Расчёт";
    static final String T_RESULT = "3. Результат и выгрузка";
    static final String T_EXPLAIN = "4. Разбор результата";
    static final String T_VERSIONS = "5. Версии набора и правки";
    static final String T_MAP = "6. Карта";
    static final String T_REFERENCE = "7. Справочники";
    static final String T_SERVICE = "8. Служебное";

    /** What Swagger UI shows for an operation. */
    static final class Doc {
        final String tag;
        final String summary;
        final String description;
        final String response;   // name of the response model, or null for what the controller declares

        Doc(String tag, String summary, String description, String response) {
            this.tag = tag;
            this.summary = summary;
            this.description = description;
            this.response = response;
        }
    }

    /** "METHOD /path" → its documentation; the order of this map is the order of operations in the document. */
    static final Map<String, Doc> DOCS = new LinkedHashMap<>();

    private static void doc(String op, String tag, String summary, String description, String response) {
        DOCS.put(op, new Doc(tag, summary, description, response));
    }

    static {
        // ---- 1. datasets
        doc("POST /api/datasets", T_DATASETS, "Шаг 1. Загрузить входной GeoJSON",
                "**Шаг 1 сценария.** `multipart/form-data`, поле `file` — один FeatureCollection в EPSG:4326, до 3 ГБ. "
                        + "Файл сохраняется на диск и импортируется в фоне, ответ 202 приходит сразу.\n\n"
                        + "Дальше — опрашивать `GET /api/datasets/{id}`, пока `status` не станет `READY`. "
                        + "Примеры входных файлов — `test-data/` репозитория, основной — `contest-lct.geojson`.",
                "Accepted");
        doc("GET /api/datasets/{id}", T_DATASETS, "Шаг 2. Статус набора и ход импорта",
                "**Шаг 2 сценария.** Статусы: `UPLOADED` → `IMPORTING` → `READY`; при ошибке `FAILED` (текст в `error`), "
                        + "при отмене `CANCELLED`. Опрашивать раз в 1–2 с.\n\n"
                        + "Пока идёт импорт, в `progress` — прочитанные и общие байты. У готового набора — число объектов "
                        + "по типам (`type_counts`), сводка замечаний по кодам (`issue_counts`, расшифровка кодов — "
                        + "`GET /api/issue-catalog`) и охват (`bbox`).",
                "Dataset");
        doc("GET /api/datasets/{id}/overview", T_DATASETS, "Проверка набора и готовность к расчёту",
                "Всё, что интерфейс показывает на шаге проверки: версии набора (`lineage`), состав данных (`contents`: "
                        + "сеть по ДУ, ОКС и их расходы, ограничения с правилом учёта), замечания с пояснением простым "
                        + "языком и группой (`assumption` — сервис восполнил недостающее значение, `problem` — ошибка, "
                        + "`note` — замечание), проверки готовности (`readiness`). Если блокирующая проверка не пройдена, "
                        + "расчёт запустить нельзя.",
                "DatasetOverview");
        doc("GET /api/datasets/{id}/issues", T_DATASETS, "Замечания к входным данным",
                "Замечания к объектам входного файла: уровень (`severity`: ERROR, WARNING, INFO), код (`code`), объект "
                        + "(`feature_id`), текст и пояснение. Фильтр по уровню — `severity`, ограничение числа — `limit`.",
                "IssueList");
        doc("GET /api/datasets/{id}/issue-features", T_DATASETS, "Объекты одного замечания (GeoJSON)",
                "Объекты, к которым относится замечание с кодом `code`, — чтобы показать их на карте. До 5000 объектов.",
                "FeatureCollection");
        doc("GET /api/datasets", T_DATASETS, "Список наборов",
                "Все загруженные наборы и их версии, новые сверху. Версия с правками ссылается на родителя "
                        + "(`parent_id`, `root_id`, `version`).",
                "DatasetList");
        doc("POST /api/datasets/{id}/cancel", T_DATASETS, "Отменить импорт",
                "Останавливает импорт, идущий или ожидающий в очереди. Набор переходит в `CANCELLED`.", "Dataset");
        doc("DELETE /api/datasets/{id}", T_DATASETS, "Удалить набор",
                "Удаляет набор вместе с версиями, расчётами, таблицами и файлами. Идущие импорт и расчёты отменяются.",
                null);

        // ---- 2. runs
        doc("POST /api/datasets/{id}/runs", T_RUNS, "Шаг 3. Запустить расчёт",
                "**Шаг 3 сценария.** Расчёт по всем перспективным ОКС набора: трассы, места присоединения, расходы, "
                        + "ДУ, стоимость, до трёх вариантов с ранжированием.\n\n"
                        + "Тело необязательно: без него действуют параметры по умолчанию (`GET /api/run-params`). "
                        + "Правила технических требований не меняются; задать можно недостающие данные и настройки "
                        + "трассировки — дополнительный отступ, угол пересечения дорог, углы поворота, совместное "
                        + "подключение. Настройки только ужесточают правила.\n\n"
                        + "Ответ 202 сразу, расчёт идёт в очереди (по умолчанию два одновременно). "
                        + "Дальше — `GET /api/runs/{id}`. 409 — набор не в статусе `READY` или не прошёл проверку готовности.",
                "Accepted");
        doc("GET /api/runs/{id}", T_RUNS, "Шаг 4. Статус и результат расчёта",
                "**Шаг 4 сценария.** Статусы: `QUEUED` → `RUNNING` → `DONE`; при ошибке `FAILED` (текст в `error`), при "
                        + "отмене `CANCELLED`. Опрашивать раз в 1–2 с.\n\n"
                        + "Пока идёт расчёт, в `progress` — этап и сколько подключений перебрано. У готового расчёта — "
                        + "`summary[]` по вариантам (место, оценка S, стоимость по статьям, длина новой сети, ОКС с местом "
                        + "присоединения, неподключённые с причиной), `validation` — независимая проверка файла результата "
                        + "по обязательным правилам, `diagnostics` — решения поиска.\n\n"
                        + "Файл результата — `GET /api/runs/{id}/result.geojson`.",
                "Run");
        doc("GET /api/datasets/{id}/runs", T_RUNS, "Расчёты набора",
                "Все расчёты набора, новые сверху: статус, параметры, оценка лучшего варианта, число нарушений правил.",
                "RunList");
        doc("PATCH /api/runs/{id}", T_RUNS, "Переименовать, добавить заметку, закрепить",
                "Все поля необязательны. Закреплённый расчёт (`pinned: true`) не удаляется автоматически по сроку хранения.",
                "Run");
        doc("POST /api/runs/{id}/cancel", T_RUNS, "Отменить расчёт",
                "Останавливает расчёт, идущий или ожидающий в очереди. Расчёт переходит в `CANCELLED`.", "Run");
        doc("DELETE /api/runs/{id}", T_RUNS, "Удалить расчёт", "Удаляет расчёт вместе с файлом результата.", null);

        // ---- 3. result
        doc("GET /api/runs/{id}/result.geojson", T_RESULT, "Шаг 5. Выгрузить результат (GeoJSON)",
                "**Шаг 5 сценария.** Файл результата в формате обмена (раздел 7 технических требований): все варианты в одном FeatureCollection "
                        + "(EPSG:4326) — новые участки сети, камеры, технические узлы и сводка каждого варианта "
                        + "(`variant_summary`). Отдаётся как файл (`Content-Disposition: attachment`). "
                        + "Только для расчёта в статусе `DONE`; состав — `docs/output.md`.",
                "FeatureCollection");
        doc("GET /api/runs/{id}/validation", T_RESULT, "Проверка результата по правилам",
                "Отчёт независимой проверки файла результата: сколько объектов проверено каждым правилом (`checked`, "
                        + "коды R-*), нарушения (`violations`) и предупреждения. Правила — `docs/requirements.md`.",
                "Validation");
        doc("GET /api/runs/{id}/variants/{variant}.geojson", T_RESULT, "Объекты одного варианта (GeoJSON)",
                "Объекты варианта в формате выгрузки. С `derived=true` (по умолчанию) добавляются служебные слои для "
                        + "карты: `flow_change` — дополнительный расход на существующей сети и участки, которым не "
                        + "хватит пропускной способности; `length_run` — непрерывные части одного ДУ с длиной и пределом; "
                        + "`unconnected_oks` — точки без маршрута с причиной. В файл выгрузки они не входят.",
                "FeatureCollection");
        doc("GET /api/runs/{id}/variants/{variant}/report.html", T_RESULT, "Отчёт по варианту (HTML)",
                "Страница для печати: сводка простыми словами, карта, показатели и статьи стоимости, ОКС, проверка "
                        + "правил, параметры с происхождением, версия данных. `download=true` — отдать как файл.",
                null);

        // ---- 4. explain
        doc("GET /api/runs/{id}/journal", T_EXPLAIN, "Журнал построения варианта",
                "Как строился вариант, шаг за шагом: `start` — порядок ОКС; `connect` и `rebuild` — трасса, выбранное "
                        + "место присоединения и рассмотренные места с оценкой S, изменения новой сети, нагруженные "
                        + "участки существующей; `unconnected`; `finish`. Координаты — EPSG:4326.\n\n"
                        + "Пока расчёт идёт — `live: true`, шаги появляются по мере построения; `after` — с какого "
                        + "номера дочитывать.",
                "Journal");
        doc("GET /api/runs/{id}/oks/{oksId}/search", T_EXPLAIN, "Как искалась трасса одного ОКС",
                "Повторный поиск трассы одного ОКС до существующей сети с записью шагов, без учёта других ОКС "
                        + "варианта: точки поворота, порядок просмотра, отвергнутые отрезки с причиной, зоны отступов "
                        + "и коридоры пересечений, найденная трасса или причина отказа. Для ответа на вопрос "
                        + "«почему трасса именно такая».",
                "SearchTrace");
        doc("GET /api/compare", T_EXPLAIN, "Сравнить два варианта",
                "`a` и `b` — «идентификатор расчёта:номер варианта»; варианты одного расчёта или разных расчётов и "
                        + "версий набора. В ответе показатели с разницей и что лучше, отличия параметров и правок "
                        + "данных, общая и различающаяся длина новой сети, объяснение ранжирования словами.",
                "Comparison");
        doc("GET /api/compare/features", T_EXPLAIN, "Сравнение на карте (GeoJSON)",
                "Новая сеть двух вариантов: `common` — общая, `only_a`, `only_b` — только в одном; точки — места "
                        + "присоединения.",
                "FeatureCollection");

        // ---- 5. versions
        doc("POST /api/datasets/{id}/versions", T_VERSIONS, "Создать версию набора с правками",
                "Исходный набор не меняется никогда: правки входа сохраняются новой версией, расчёты на ней идут "
                        + "отдельно и сравниваются с исходными. До 500 правок:\n\n"
                        + "- `add_restriction` — добавить ограничение: тип из справочника (`GET /api/params` → "
                        + "`restriction_types`) и полигон "
                        + "(EPSG:4326, от 1 м² до 25 км²);\n"
                        + "- `remove_object` — исключить объект родительского набора (источник и последнюю точку "
                        + "подключения ОКС исключить нельзя);\n"
                        + "- `set_attribute` — изменить один атрибут: `diameter` (ДУ из номенклатуры), `flow_tph` "
                        + "(0…10000), `upstream_object_id`, `restriction_type`.\n\n"
                        + "Версия строится в фоне: ответ 202, затем `GET /api/datasets/{id}` до `READY`.",
                "Accepted");
        doc("PUT /api/datasets/{id}/edits", T_VERSIONS, "Заменить правки версии",
                "Заменяет список правок версии целиком и пересобирает её. 409 — у версии уже есть расчёты или это "
                        + "загруженный файл, а не версия.",
                "Dataset");

        // ---- 6. map
        doc("GET /api/datasets/{id}/bbox", T_MAP, "Охват набора",
                "`[minLon, minLat, maxLon, maxLat]` в EPSG:4326 — чтобы показать набор на карте целиком.", "Bbox");
        doc("GET /api/datasets/{id}/tiles/{z}/{x}/{y}.mvt", T_MAP, "Векторный тайл входных слоёв",
                "Mapbox Vector Tile. Слои: `network`, `oks`, `source` — с масштаба z9; `chambers`, `connection_points`, "
                        + "`restrictions` — с z13; `buildings` — с z14. Режутся из PostGIS по пространственному "
                        + "индексу, набор целиком карта не загружает.",
                null);
        doc("GET /api/datasets/{id}/features/{fid}", T_MAP, "Объект набора по идентификатору (GeoJSON)",
                "Один входной объект — переход к нему из замечания или списка.", "FeatureCollection");

        // ---- 7. reference
        doc("GET /api/params", T_REFERENCE, "Каталог параметров расчёта",
                "Всё, от чего зависит расчёт, с происхождением. Класс `norm` — правило технических требований, не "
                        + "меняется; `interpretation` — как применяется правило, которое можно прочитать по-разному; "
                        + "`data_assumption` — значение, которого нет во входных данных; `algorithm` — настройки "
                        + "трассировки, только ужесточают правила. Изменяемые "
                        + "параметры (`editable: true`) принимаются в теле `POST /api/datasets/{id}/runs` под тем же "
                        + "ключом; у них есть тип, значение по умолчанию, диапазон или варианты. В конце — типы "
                        + "ограничений с правилом учёта. Тот же список — `docs/parameters.md`.",
                "ParamCatalog");
        doc("GET /api/run-params", T_REFERENCE, "Параметры расчёта по умолчанию",
                "Значения, которые действуют, если в теле запуска расчёта поле не задано (настройки сервиса).", null);
        doc("GET /api/issue-catalog", T_REFERENCE, "Тексты замечаний по кодам",
                "Код замечания → группа, название и что это значит простым языком. Коды встречаются в "
                        + "`issue_counts` набора и в списке замечаний.",
                "IssueCatalog");

        // ---- 8. service
        doc("GET /api/status", T_SERVICE, "Загрузка сервиса",
                "Сколько расчётов идёт сейчас, сколько ждёт в очереди, сколько может идти одновременно. "
                        + "Ответ `{\"running\":0,\"queued\":0,\"threads\":2}` — сервис поднялся и готов.",
                "ServiceStatus");
    }

    // ------------------------------------------------------------------ fields of the models

    /**
     * Descriptions of the fields of the responses. A key is a field name, or {@code Model.field} when the same
     * name means different things in different models. Models are built from the examples, so every field of a
     * real response gets its description here; a field without one is shown with its type only.
     */
    static final Map<String, String> FIELDS = new LinkedHashMap<>();

    private static void f(String key, String text) {
        FIELDS.put(key, text);
    }

    static {
        // identifiers, time, status
        f("id", "Идентификатор (UUID)");
        f("Accepted.id", "Идентификатор созданного набора или расчёта (UUID)");
        f("Accepted.status", "Начальный статус: UPLOADED у загруженного набора, IMPORTING у версии, QUEUED у расчёта");
        f("name", "Название");
        f("Dataset.name", "Название набора — имя загруженного файла без расширения");
        f("Run.name", "Название расчёта; по умолчанию — из параметров, отличных от настроек сервиса");
        f("Dataset.status", "UPLOADED — в очереди на импорт; IMPORTING — идёт; READY — готов; FAILED — ошибка; CANCELLED — отменён");
        f("Run.status", "QUEUED — в очереди; RUNNING — идёт; DONE — готов; FAILED — ошибка; CANCELLED — отменён");
        f("RunBrief.status", "QUEUED — в очереди; RUNNING — идёт; DONE — готов; FAILED — ошибка; CANCELLED — отменён");
        f("Journal.status", "Статус расчёта: QUEUED, RUNNING, DONE, FAILED, CANCELLED");
        f("DatasetOverview.status", "UPLOADED — в очереди на импорт; IMPORTING — идёт; READY — готов; FAILED — ошибка; CANCELLED — отменён");
        f("created_at", "Когда создан (UTC)");
        f("started_at", "Когда начался расчёт (UTC)");
        f("finished_at", "Когда завершился (UTC)");
        f("error", "Текст ошибки при статусе FAILED, иначе null");
        f("note", "Заметка пользователя");
        f("pinned", "Закреплён: не удаляется автоматически по сроку хранения");

        // dataset
        f("file_bytes", "Размер загруженного файла, байт");
        f("feature_count", "Объектов во входном файле");
        f("imported_count", "Объектов, принятых в набор (без отброшенных при разборе)");
        f("import_millis", "Длительность импорта, мс");
        f("type_counts", "Число объектов по типам (`object_type` входного формата)");
        f("issue_counts", "Число замечаний по кодам; расшифровка — GET /api/issue-catalog");
        f("bbox", "Охват набора: [minLon, minLat, maxLon, maxLat], EPSG:4326");
        f("parent_id", "Родительская версия набора; null у загруженного файла");
        f("root_id", "Исходный загруженный набор в цепочке версий");
        f("version", "Номер версии набора: 1 — загруженный файл, дальше — версии с правками");
        f("edits", "Правки входа этой версии; null у загруженного файла");
        f("run_count", "Сколько расчётов сделано на этой версии");
        f("progress", "Ход импорта: `bytes_read` и `bytes_total`; только пока статус IMPORTING");
        f("source", "Источников тепла");
        f("oks_future", "Контуров перспективных ОКС");
        f("oks_connection_point", "Точек подключения перспективных ОКС");
        f("oks_existing", "Существующих зданий");
        f("heat_network", "Участков существующей сети");
        f("heat_chamber", "Существующих тепловых камер");
        f("restriction", "Пространственных ограничений");
        f("lineage", "Версии набора от исходного файла к этой: id, version, note, число правок");
        f("contents", "Состав данных: сеть по ДУ с длиной, ОКС с расходами, ограничения с правилом учёта");
        f("issues", "Замечания по кодам: title, meaning, group (assumption, problem, note), count");
        f("readiness", "Проверки готовности к расчёту: title, ok, blocking, detail");

        // issues
        f("severity", "Уровень: ERROR — объект не принят, WARNING — принят с оговоркой, INFO — сведение");
        f("code", "Код замечания, например `restriction.unknown_type`");
        f("feature_id", "Идентификатор объекта входных данных, к которому относится замечание");
        f("message", "Текст замечания с деталями");
        f("group", "Группа: assumption — сервис восполнил значение; problem — ошибка данных; note — замечание");
        f("title", "Название");
        f("meaning", "Что это значит для расчёта, простым языком");
        f("param", "Ключ параметра из GET /api/params, к которому относится замечание, если есть");

        // run
        f("dataset_id", "Набор (версия), на котором сделан расчёт");
        f("dataset_name", "Название набора");
        f("dataset_version", "Версия набора");
        f("dataset_parent_id", "Родительская версия набора, если это версия с правками");
        f("dataset_root_id", "Исходный загруженный набор");
        f("result_bytes", "Размер файла результата, байт");
        f("params", "Параметры, с которыми сделан расчёт (см. RunParams)");
        f("Run.progress", "Ход расчёта: `stage` — этап, `done` и `total` — перебранные подключения; пока статус RUNNING");
        f("summary", "Сводка по вариантам, лучший первым");
        f("validation", "Независимая проверка файла результата по обязательным правилам");
        f("diagnostics", "Решения поиска: рост ДУ, выбор места присоединения, точки без маршрута — по кодам");
        f("best_score", "Оценка S лучшего варианта");
        f("validation_errors", "Число нарушений обязательных правил в файле результата");
        f("variants", "Число вариантов в результате");

        // variant summary
        f("variant_id", "Номер варианта в файле результата: 1, 2 или 3");
        f("rank", "Место варианта по оценке S; 1 — лучший");
        f("score", "Оценка S = 0,7 · стоимость / 25 млн + 0,3 · длина / 100 м; меньше — лучше");
        f("plain", "Вариант в одну фразу простыми словами");
        f("explanation", "Как получен вариант: стратегия и порядок присоединения, число частей сети и разветвлений");
        f("approach", "Как получен вариант, одной фразой: стратегия и порядок присоединения");
        f("strategy", "`separate` — каждый ОКС своей трассой и врезкой; `joint`, `joint-alt` — совместное подключение");
        f("calculated_cost", "Стоимость варианта, руб. (со штрафами за неподключённые ОКС)");
        f("construction_cost", "Стоимость строительства без штрафов, руб.");
        f("segment_cost", "Стоимость новых участков сети, руб.");
        f("chamber_construction_cost", "Стоимость новых тепловых камер, руб.");
        f("existing_chamber_tie_in_cost", "Стоимость врезок в существующие камеры, руб.");
        f("existing_chamber_tie_in_count", "Врезок в существующие камеры");
        f("unconnected_penalty", "Штраф за неподключённые ОКС, руб.");
        f("unconnected_oks_ids", "Идентификаторы ОКС без маршрута");
        f("unconnected", "ОКС без маршрута с причиной");
        f("new_network_length", "Длина новой сети, м");
        f("tie_ins", "Присоединений к существующей сети");
        f("new_chambers", "Новых тепловых камер");
        f("branching_chambers", "Из них камер-разветвлений");
        f("technical_nodes", "Технических узлов (смена способа прокладки)");
        f("network_parts", "Отдельных частей новой сети");
        f("routes", "Построенных трасс");
        f("turns", "Поворотов трассы");
        f("VariantSummary.turns", "Поворотов во всей новой сети");
        f("turns_per_km", "Поворотов на километр новой сети");
        f("sharp_turns", "Поворотов острее допустимых (норма — 0)");
        f("small_turns", "Мелких изломов (норма — 0)");
        f("min_straight_m", "Самый короткий прямой отрезок, м");
        f("max_detour_ratio", "Наибольшее отношение длины трассы к прямой");
        f("mean_detour_ratio", "Среднее отношение длины трассы к прямой");
        f("special_segments", "Участков в спецпроходе (дорога, трамвай, газопровод, кабель)");
        f("capacity_shortfalls", "Участков существующей сети, которым не хватит пропускной способности; в стоимость не входит");
        f("plan_millis", "Время построения варианта, мс");
        f("oks", "Перспективные ОКС варианта: как подключён каждый");

        // oks in a variant
        f("oks_id", "Идентификатор перспективного ОКС (точки подключения)");
        f("connected", "Подключён ли");
        f("flow_tph", "Расчётный расход, т/ч");
        f("joins", "`tie_in` — своё присоединение к существующей сети; `junction` — к новой сети через разветвление");
        f("tie_in_id", "Идентификатор объекта присоединения в результате");
        f("tie_object_id", "Существующий объект, в который сделана врезка (участок или камера)");
        f("tie_object_type", "Тип объекта врезки: heat_network или heat_chamber");
        f("diameter", "ДУ своей ветки, мм");
        f("own_length", "Длина своей ветки от разветвления или врезки до точки, м");
        f("own_cost", "Стоимость своей ветки, руб.");
        f("path_length", "Длина пути от места присоединения до точки, м");
        f("path_segment_ids", "Новые участки на пути от места присоединения до точки");
        f("shared_with", "ОКС, питающиеся через то же присоединение");
        f("existing_chain", "Участки существующей сети от врезки до источника");
        f("specials", "Типы ограничений, пересечённых спецпроходом");
        f("reason", "Код причины: NO_ROUTE, LENGTH_LIMIT, SEARCH_LIMIT, FLOW_EXCEEDS_MAX_DN, NO_CONNECTION_POINT, NO_NETWORK");
        f("reason_text", "Причина словами");
        f("detail", "Подробности: радиус поиска, ДУ, расстояние до сети");

        // validation
        f("errors", "Число нарушений обязательных правил");
        f("warnings", "Число предупреждений");
        f("checked", "Сколько объектов проверено каждым правилом (код R-* из docs/requirements.md)");
        f("violations", "Нарушения: код правила, вариант, объект, что не так");

        // journal
        f("run_id", "Идентификатор расчёта");
        f("live", "true — расчёт ещё идёт, журнал дописывается");
        f("variant", "Номер варианта");
        f("events", "Шаги построения по порядку (`seq`, `type`: start, connect, rebuild, unconnected, finish)");
        f("seq", "Порядковый номер шага");
        f("type", "Тип");
        f("JournalEvent.type", "start — порядок ОКС; connect — подключение ОКС; rebuild — перестройка; unconnected; finish");
        f("order", "Порядок присоединения ОКС в этом построении");
        f("next", "Номер, с которого дочитывать журнал (`after=next`)");
        f("point", "Координаты [lon, lat]");

        // search trace
        f("millis", "Время поиска, мс");
        f("radius_m", "Радиус поиска, м");
        f("attempts", "Попыток с расширением радиуса");
        f("limit_reached", "Поиск остановлен по пределу шагов");
        f("max_expansions", "Предел шагов поиска");
        f("vertices", "Точки поворота графа видимости [lon, lat]");
        f("starts", "Стартовых точек на контуре здания");
        f("connection_point", "Точка подключения [lon, lat]");
        f("expansions", "Порядок просмотра точек: [номер точки, откуда]");
        f("rejected", "Отвергнутые отрезки с причиной");
        f("rejected_kinds", "Виды препятствий, встреченные при поиске");
        f("rejected_by_kind", "Сколько отрезков отвергнуто по каждому виду препятствия");
        f("zones", "Зоны отступов вокруг зданий и ограничений (GeoJSON)");
        f("corridors", "Коридоры пересечений спецпроходом (GeoJSON)");
        f("route", "Найденная трасса: координаты, длина, куда присоединяется; null — причина в `reason`");
        f("coords", "Координаты трассы [lon, lat]");
        f("length", "Длина, м");
        f("target_object_id", "Объект, к которому пришла трасса");
        f("target_kind", "Вид присоединения: tie_segment, tie_chamber, join_edge, join_junction");

        // comparison
        f("a", "Вариант А");
        f("b", "Вариант Б");
        f("Comparison.metrics", "Показатели двух вариантов: значение А, Б, разница и что лучше");
        f("Metric.key", "Ключ показателя");
        f("Metric.unit", "Единица: руб., м или пусто");
        f("Metric.a", "Значение у варианта А");
        f("Metric.b", "Значение у варианта Б");
        f("delta", "Б − А");
        f("better", "У кого лучше: a, b или equal");
        f("Comparison.params", "Параметры расчёта, которые различаются: title, a, b");
        f("data", "Различия входных данных двух вариантов");
        f("same_dataset", "Одна и та же версия набора");
        f("same_family", "Версии одного исходного набора");
        f("edits_only_a", "Правки, которые есть только в наборе варианта А");
        f("edits_only_b", "Правки, которые есть только в наборе варианта Б");
        f("geometry", "Совпадение новой сети двух вариантов");
        f("tolerance_m", "Точность совпадения, м");
        f("common_length", "Длина общей новой сети, м");
        f("only_a_length", "Длина новой сети только в А, м");
        f("only_b_length", "Длина новой сети только в Б, м");
        f("Comparison.explanation", "Почему один вариант выше другого, словами");

        // params catalog
        f("classes", "Классы параметров: id, title, group_title (заголовок группы в интерфейсе), description");
        f("ParamCatalog.params", "Параметры расчёта");
        f("key", "Ключ параметра; у изменяемых — поле тела POST /api/datasets/{id}/runs");
        f("class", "Класс: norm, interpretation, data_assumption, algorithm");
        f("class_title", "Название класса");
        f("description", "Что это значит для расчёта");
        f("Param.source", "Документ, из которого взято правило (пункт технических требований, СП); нет — решение сервиса");
        f("basis", "Разъяснение к техническим требованиям, на котором основано правило, если есть");
        f("depends_on", "Показывается, только когда параметр key равен value (или не равен not)");
        f("Param.type", "fixed — только для чтения; number, enum, boolean — изменяемые");
        f("editable", "Можно ли задать в теле запуска расчёта");
        f("value", "Значение (у параметров только для чтения)");
        f("default", "Значение по умолчанию (у изменяемых)");
        f("unit", "Единица измерения");
        f("min", "Наименьшее допустимое значение");
        f("max", "Наибольшее допустимое значение");
        f("step", "Шаг изменения");
        f("options", "Варианты значения: value, label, effect");
        f("restriction_types", "Типы ограничений справочника с правилом учёта");
        f("rule", "Правило учёта словами");
        f("forbidden", "true — обходить, false — можно пересечь при условиях");

        // service
        f("running", "Расчётов в работе");
        f("queued", "Расчётов в очереди");
        f("threads", "Сколько расчётов может идти одновременно");

        // geojson
        f("features", "Объекты");
        f("properties", "Атрибуты объекта: `object_type` и поля его типа (docs/output.md)");
    }

    /** Which response of the example file is the base of which model; the model name is also its schema. */
    private static final Map<String, String> MODEL_EXAMPLES = new LinkedHashMap<String, String>() {{
        put("Accepted", "POST /api/datasets");
        put("Dataset", "GET /api/datasets/{id}");
        put("DatasetOverview", "GET /api/datasets/{id}/overview");
        put("Issue", "GET /api/datasets/{id}/issues");
        put("Run", "GET /api/runs/{id}");
        put("RunBrief", "GET /api/datasets/{id}/runs");
        put("Validation", "GET /api/runs/{id}/validation");
        put("Journal", "GET /api/runs/{id}/journal");
        put("SearchTrace", "GET /api/runs/{id}/oks/{oksId}/search");
        put("Comparison", "GET /api/compare");
        put("ParamCatalog", "GET /api/params");
        put("ServiceStatus", "GET /api/status");
        put("FeatureCollection", "GET /api/runs/{id}/variants/{variant}.geojson");
    }};

    /** Nested objects that deserve a model of their own: "Model.field" → model name. */
    private static final Map<String, String> NESTED = new LinkedHashMap<String, String>() {{
        put("Run.summary", "VariantSummary");
        put("Run.validation", "Validation");
        put("VariantSummary.oks", "OksResult");
        put("VariantSummary.unconnected", "Unconnected");
        put("Journal.events", "JournalEvent");
        put("Comparison.metrics", "Metric");
        put("ParamCatalog.params", "Param");
        put("ParamCatalog.restriction_types", "RestrictionType");
        put("FeatureCollection.features", "Feature");
        put("Dataset.type_counts", "TypeCounts");
        put("DatasetOverview.type_counts", "TypeCounts");
    }};

    private static final String ERROR_SCHEMA = "Error";

    /** Keys of the example file: "METHOD /path" exactly as the path appears in the document. */
    private final Map<String, JsonNode> examples = loadExamples();

    @Value("${server.port:8080}")
    private int port;

    // ------------------------------------------------------------------ the document

    @Bean
    public OpenAPI heatnetOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Трассы подключения к тепловой сети — API")
                        .version("1.0")
                        .description("Сервис строит варианты подключения перспективных зданий (ОКС) к существующей "
                                + "тепловой сети: трассы в обход ограничений, места присоединения, расходы, диаметры, "
                                + "проверку предельной длины и стоимость. Вход и выход — GeoJSON (EPSG:4326).\n\n"
                                + "### Сценарий из пяти запросов\n\n"
                                + "1. `POST /api/datasets` — загрузить файл; в ответе `id` набора.\n"
                                + "2. `GET /api/datasets/{id}` — дождаться `status: READY` (опрос раз в 1–2 с).\n"
                                + "3. `POST /api/datasets/{id}/runs` — запустить расчёт; в ответе `id` расчёта.\n"
                                + "4. `GET /api/runs/{id}` — дождаться `status: DONE`; здесь же сводка по вариантам.\n"
                                + "5. `GET /api/runs/{id}/result.geojson` — забрать файл результата.\n\n"
                                + "Разделы ниже идут в порядке сценария; операции внутри раздела — тоже. "
                                + "У каждой операции есть пример ответа с реального расчёта, у моделей — описание "
                                + "каждого поля (вкладка «Schema»).\n\n"
                                + "### Соглашения\n\n"
                                + "- Идентификаторы наборов и расчётов — UUID; время — UTC (ISO 8601).\n"
                                + "- Статусы набора: `UPLOADED → IMPORTING → READY`, иначе `FAILED` или `CANCELLED`. "
                                + "Статусы расчёта: `QUEUED → RUNNING → DONE`, иначе `FAILED` или `CANCELLED`.\n"
                                + "- Ошибка — JSON `{\"error\": \"что произошло\"}` с кодом 400 (параметры), 404 (не найдено), "
                                + "409 (состояние не позволяет), 503 (очередь заполнена), 507 (нет места на диске).\n"
                                + "- Импорт и расчёты идут в фоне в ограниченных пулах; запросы чтения отвечают сразу.\n\n"
                                + "Подробнее: `docs/api.md` (сценарии и curl), `docs/output.md` (формат результата), "
                                + "`docs/parameters.md` (параметры) в репозитории.")
                        .contact(new Contact().name("Веб-интерфейс сервиса").url("http://localhost:" + port + "/")))
                .servers(Collections.singletonList(new Server().url("http://localhost:" + port)
                        .description("Локальный запуск (docker-compose)")))
                .tags(Arrays.asList(
                        new Tag().name(T_DATASETS).description("Загрузка входного GeoJSON, импорт, проверка данных"),
                        new Tag().name(T_RUNS).description("Запуск расчёта, ход выполнения, список расчётов"),
                        new Tag().name(T_RESULT).description("Файл результата, проверка правил, вариант для карты, отчёт"),
                        new Tag().name(T_EXPLAIN).description("Журнал построения, поиск трассы, сравнение вариантов"),
                        new Tag().name(T_VERSIONS).description("Правки входных данных без изменения исходного файла"),
                        new Tag().name(T_MAP).description("Тайлы и объекты входных данных для карты"),
                        new Tag().name(T_REFERENCE).description("Параметры расчёта, тексты замечаний"),
                        new Tag().name(T_SERVICE).description("Состояние сервиса")));
    }

    /**
     * Fills in what the controller does not carry: sections and their order, summaries and descriptions, response
     * models, request bodies, real status codes and the errors a controller can actually return.
     */
    @Bean
    public OpenApiCustomiser heatnetOperations() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            Components components = openApi.getComponents();
            if (components.getSchemas() != null) {
                components.getSchemas().remove("StreamingResponseBody");
            }
            registerModels(components);
            registerRequestBodies(components);

            Paths ordered = new Paths();
            List<String> seen = new ArrayList<>();
            // operations in the order of the scenario; anything not listed goes last, as the controller declares it
            for (String key : DOCS.keySet()) {
                String path = key.substring(key.indexOf(' ') + 1);
                PathItem item = openApi.getPaths().get(path);
                if (item != null && !ordered.containsKey(path)) {
                    ordered.addPathItem(path, item);
                    seen.add(path);
                }
            }
            openApi.getPaths().forEach((path, item) -> {
                if (!seen.contains(path)) {
                    ordered.addPathItem(path, item);
                }
            });
            openApi.setPaths(ordered);

            openApi.getPaths().forEach((path, item) -> {
                for (Map.Entry<PathItem.HttpMethod, Operation> e : item.readOperationsMap().entrySet()) {
                    String key = e.getKey() + " " + path;
                    Operation op = e.getValue();
                    Doc d = DOCS.get(key);
                    if (d != null) {
                        op.setTags(Collections.singletonList(d.tag));
                        op.setSummary(d.summary);
                        op.setDescription(d.description);
                    } else {
                        op.setTags(Collections.singletonList(T_SERVICE));
                    }
                    op.setOperationId(operationId(e.getKey(), path));
                    describeParameters(op, path);
                    describeRequestBody(op, key);
                    normalizeSuccess(op, e.getKey(), path, d);
                    addExample(op, key);
                    addErrors(op, e.getKey(), path);
                }
            });
        };
    }

    private static String operationId(PathItem.HttpMethod method, String path) {
        StringBuilder sb = new StringBuilder(method.name().toLowerCase());
        for (String part : path.replace("/api/", "").replaceAll("[{}.]", "").split("/")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).replace('-', '_'));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ responses

    /** Success responses: a real status code, a readable description, the media type and the model. */
    private static void normalizeSuccess(Operation op, PathItem.HttpMethod method, String path, Doc d) {
        ApiResponses r = op.getResponses();
        if (r == null) {
            return;
        }
        ApiResponse ok = r.remove("200");
        if (ok == null) {
            return;
        }
        String code = "200";
        String text = "Успешный ответ";
        if (method == PathItem.HttpMethod.DELETE) {
            code = "204";
            text = "Удалено";
            ok.setContent(null);
        } else if ("/api/datasets".equals(path) && method == PathItem.HttpMethod.POST) {
            code = "202";
            text = "Файл принят, импорт идёт в фоне";
        } else if (path.endsWith("/runs") && method == PathItem.HttpMethod.POST) {
            code = "202";
            text = "Расчёт поставлен в очередь";
        } else if (path.endsWith("/versions") && method == PathItem.HttpMethod.POST) {
            code = "202";
            text = "Версия создаётся в фоне";
        } else if (path.endsWith("/edits") && method == PathItem.HttpMethod.PUT) {
            code = "202";
            text = "Правки приняты, версия пересобирается";
        } else if (path.endsWith(".geojson")) {
            text = "Объекты в GeoJSON, EPSG:4326";
        } else if (path.endsWith(".mvt")) {
            text = "Векторный тайл (Mapbox Vector Tile)";
        } else if (path.endsWith("report.html")) {
            text = "Отчёт по варианту — страница для печати";
        }
        ok.setDescription(text);
        if (ok.getContent() != null && ok.getContent().containsKey("*/*")) {
            MediaType any = ok.getContent().remove("*/*");
            ok.getContent().addMediaType(path.endsWith(".mvt") ? "application/vnd.mapbox-vector-tile"
                    : path.endsWith(".geojson") ? "application/geo+json" : "application/json", any);
        }
        if (path.endsWith(".mvt") && ok.getContent() != null) {
            for (MediaType media : ok.getContent().values()) {
                media.setSchema(new Schema<>().type("string").format("binary"));
            }
        }
        if (d != null && d.response != null && ok.getContent() != null) {
            for (MediaType media : ok.getContent().values()) {
                media.setSchema(new Schema<>().$ref("#/components/schemas/" + d.response));
            }
        }
        r.addApiResponse(code, ok);
    }

    private void addExample(Operation op, String key) {
        JsonNode value = examples.get(key);
        ApiResponses responses = op.getResponses();
        if (value == null || responses == null) {
            return;
        }
        responses.forEach((code, response) -> {
            if (!code.startsWith("2") || response.getContent() == null) {
                return;
            }
            response.getContent().forEach((type, media) -> media.addExamples("Ответ реального расчёта",
                    new Example().value(value)));
        });
    }

    private static void addErrors(Operation op, PathItem.HttpMethod method, String path) {
        ApiResponses r = op.getResponses();
        if (r == null) {
            return;
        }
        boolean write = method != PathItem.HttpMethod.GET;
        if ("/api/datasets".equals(path) && method == PathItem.HttpMethod.POST) {
            error(r, "400", "Нет поля file или файл не GeoJSON", "Required request part 'file' is not present");
            error(r, "507", "На диске нет места для файла", "Not enough free disk space");
            return;
        }
        if (path.contains("{id}") || path.contains("{fid}")) {
            error(r, "404", path.startsWith("/api/runs") ? "Расчёт не найден"
                    : path.contains("{fid}") ? "Набор или объект не найден" : "Набор не найден",
                    path.startsWith("/api/runs") ? "Run 02e344ba… not found" : "Dataset 7d2f… not found");
        }
        if (path.startsWith("/api/compare")) {
            error(r, "400", "Неверный формат a или b", "Expected <run id>:<variant>, got 1");
            error(r, "404", "Расчёт или вариант не найден", "Run 02e344ba… not found");
        }
        if (path.contains("{variant}")) {
            error(r, "400", "Номер варианта не 1, 2 или 3", "variant must be 1, 2 or 3, got 4");
            if (r.get("404") != null) {
                r.get("404").setDescription("Расчёт не найден или в нём нет такого варианта");
            }
        }
        if (path.endsWith("/runs") && method == PathItem.HttpMethod.POST) {
            error(r, "400", "Параметр вне диапазона или неверное тело", "turn_penalty must be within [0, 10]");
            error(r, "409", "Набор не готов: не READY, нет источника, сети или ОКС", "Dataset is IMPORTING, expected READY");
            error(r, "503", "Очередь расчётов заполнена", "The queue is full, try again later");
            return;
        }
        if (path.endsWith("/versions") || path.endsWith("/edits")) {
            error(r, "400", "Правка не проходит проверку", "add_restriction: polygon area must be within 1 m² … 25 km²");
            error(r, "409", "Версия уже использована в расчётах или набор не готов", "Version already has runs");
            return;
        }
        if (path.endsWith("/cancel")) {
            error(r, "409", "Нечего отменять: работа уже завершена", path.startsWith("/api/runs")
                    ? "Run is DONE, expected QUEUED or RUNNING" : "Dataset is READY, expected UPLOADED or IMPORTING");
            return;
        }
        if (path.endsWith("result.geojson") || path.endsWith("/validation") || path.contains("/variants/")
                || path.endsWith("/journal") || path.contains("/search")) {
            error(r, "409", "Расчёт ещё не завершён", "Run is RUNNING, expected DONE");
        }
        if (path.contains("/tiles/") || path.endsWith("/bbox") || path.contains("/features")
                || path.contains("/issue-features")) {
            error(r, "409", "Набор ещё импортируется", "Dataset is IMPORTING, expected READY");
        }
        if (write && method == PathItem.HttpMethod.PATCH) {
            error(r, "400", "Неверное тело запроса", "name must be a string");
        }
    }

    private static void error(ApiResponses responses, String code, String description, String example) {
        if (responses.containsKey(code)) {
            return;
        }
        responses.addApiResponse(code, new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))
                        .addExamples("Пример", new Example()
                                .value(Collections.singletonMap("error", example))))));
    }

    // ------------------------------------------------------------------ parameters and request bodies

    private static final Map<String, String> PARAM_TEXT = new LinkedHashMap<String, String>() {{
        put("fid", "Идентификатор объекта во входных данных (поле `id` объекта GeoJSON)");
        put("oksId", "Идентификатор перспективного ОКС (точки подключения)");
        put("variant", "Номер варианта подключения: 1, 2 или 3 (1 — лучший)");
        put("a", "Вариант А в виде «идентификатор расчёта:номер варианта»");
        put("b", "Вариант Б в виде «идентификатор расчёта:номер варианта»");
        put("code", "Код замечания из GET /api/issue-catalog");
        put("severity", "Уровень замечания: ERROR, WARNING или INFO; без параметра — все");
        put("limit", "Сколько записей вернуть (по умолчанию 1000)");
        put("after", "Вернуть шаги журнала после этого номера — для дочитывания во время расчёта");
        put("derived", "Добавить служебные слои для карты: дополнительный расход, участки одного ДУ, ОКС без маршрута");
        put("download", "Отдать ответ как файл для скачивания (Content-Disposition: attachment)");
        put("z", "Масштаб тайла (zoom)");
        put("x", "Колонка тайла");
        put("y", "Строка тайла");
    }};

    private static final Map<String, String> PARAM_EXAMPLE = new LinkedHashMap<String, String>() {{
        put("id", "e2e87e99-507f-4d80-aedb-19137f332bd7");
        put("fid", "seg-12");
        put("variant", "1");
        put("a", "02e344ba-b65e-4994-8e75-ab382ba50c06:1");
        put("b", "02e344ba-b65e-4994-8e75-ab382ba50c06:2");
        put("severity", "WARNING");
        put("code", "restriction.unknown_type");
        put("oksId", "oks-1");
        put("z", "15");
        put("x", "19806");
        put("y", "10230");
        put("after", "0");
    }};

    private static void describeParameters(Operation op, String path) {
        if (op.getParameters() == null) {
            return;
        }
        for (Parameter p : op.getParameters()) {
            if ("id".equals(p.getName())) {
                p.setDescription(path.startsWith("/api/runs") ? "Идентификатор расчёта (UUID)" : "Идентификатор набора (UUID)");
                p.setExample(path.startsWith("/api/runs") ? "02e344ba-b65e-4994-8e75-ab382ba50c06" : PARAM_EXAMPLE.get("id"));
                continue;
            }
            if (p.getDescription() == null || p.getDescription().isEmpty()) {
                p.setDescription(PARAM_TEXT.get(p.getName()));
            }
            if (p.getExample() == null && PARAM_EXAMPLE.containsKey(p.getName())) {
                p.setExample(PARAM_EXAMPLE.get(p.getName()));
            }
            if ("variant".equals(p.getName())) {
                p.setSchema(new IntegerSchema().addEnumItem(1).addEnumItem(2).addEnumItem(3));
            }
        }
    }

    /** Bodies the controller takes as a plain map get a model and an example here. */
    private static void describeRequestBody(Operation op, String key) {
        String model = null;
        Object example = null;
        if ("POST /api/datasets/{id}/versions".equals(key)) {
            model = "VersionRequest";
            example = map("note", "стройплощадка на Родченко, 2", "edits", Arrays.asList(
                    map("op", "add_restriction", "restriction_type", "prohibited_site", "comment", "стройплощадка",
                            "geometry", map("type", "Polygon", "coordinates", Collections.singletonList(Arrays.asList(
                                    Arrays.asList(37.6521, 55.6951), Arrays.asList(37.6534, 55.6951),
                                    Arrays.asList(37.6534, 55.6958), Arrays.asList(37.6521, 55.6958),
                                    Arrays.asList(37.6521, 55.6951))))),
                    map("op", "set_attribute", "target_id", "seg-12", "attribute", "diameter", "value", 200),
                    map("op", "remove_object", "target_id", "restr-7", "comment", "объекта больше нет")));
        } else if ("PUT /api/datasets/{id}/edits".equals(key)) {
            model = "VersionRequest";
            example = map("edits", Collections.singletonList(
                    map("op", "set_attribute", "target_id", "seg-12", "attribute", "flow_tph", "value", 120.5)));
        } else if ("PATCH /api/runs/{id}".equals(key)) {
            model = "RunPatch";
            example = map("name", "сеть загружена на 50 %", "note", "смотрим запас пропускной способности", "pinned", true);
        } else if ("POST /api/datasets/{id}/runs".equals(key)) {
            example = map("existing_flow", "ZERO", "turn_penalty", 0.15, "extra_clearance_m", 1.0,
                    "min_crossing_angle_deg", 60, "turn_angles", "90", "turn_angles_strict", false,
                    "joint_connection", true, "name", "отступ +1 м, дороги под 60°, повороты под 90°",
                    "note", "проверяем более осторожную трассировку");
        }
        if (model == null && example == null) {
            return;
        }
        RequestBody body = op.getRequestBody();
        if (body == null) {
            body = new RequestBody().required(model != null);
            op.setRequestBody(body);
        }
        if (body.getContent() == null || body.getContent().isEmpty()) {
            body.setContent(new Content().addMediaType("application/json", new MediaType()));
        }
        for (MediaType media : body.getContent().values()) {
            if (model != null) {
                media.setSchema(new Schema<>().$ref("#/components/schemas/" + model));
            }
            if (example != null) {
                media.addExamples("Пример", new Example().value(example));
            }
        }
        if ("POST /api/datasets/{id}/runs".equals(key)) {
            body.setDescription("Все поля необязательны; незаданные берутся из настроек сервиса. Ключи совпадают с "
                    + "изменяемыми параметрами каталога GET /api/params.");
        }
    }

    private static void registerRequestBodies(Components c) {
        Schema<?> geometry = new ObjectSchema().description("Полигон GeoJSON в EPSG:4326")
                .addProperties("type", new StringSchema()._enum(Collections.singletonList("Polygon")))
                .addProperties("coordinates", new ArraySchema().items(new ArraySchema().items(
                        new ArraySchema().items(new NumberSchema()))).description("Кольца полигона: [[[lon, lat], …]]"));
        Schema<?> edit = new ObjectSchema().description("Одна правка входных данных")
                .addProperties("op", new StringSchema().description("Операция")
                        ._enum(Arrays.asList("add_restriction", "remove_object", "set_attribute")))
                .addProperties("restriction_type", new StringSchema()
                        .description("add_restriction: тип ограничения из справочника (GET /api/params → restriction_types)")
                        .example("prohibited_site"))
                .addProperties("geometry", geometry)
                .addProperties("target_id", new StringSchema()
                        .description("remove_object, set_attribute: идентификатор объекта родительского набора").example("seg-12"))
                .addProperties("attribute", new StringSchema().description("set_attribute: атрибут")
                        ._enum(Arrays.asList("diameter", "flow_tph", "upstream_object_id", "restriction_type")))
                .addProperties("value", new Schema<>().description("set_attribute: новое значение — число, строка или null"))
                .addProperties("comment", new StringSchema().description("Зачем правка (до 500 символов), попадает в историю версий"));
        edit.setRequired(Collections.singletonList("op"));
        Schema<?> version = new ObjectSchema().description("Правки входных данных")
                .addProperties("note", new StringSchema().description("Что это за версия, для истории"))
                .addProperties("edits", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/EditOperation"))
                        .description("Правки, до 500"));
        version.setRequired(Collections.singletonList("edits"));
        Schema<?> patch = new ObjectSchema().description("Изменяемые поля расчёта; все необязательны")
                .addProperties("name", new StringSchema().description("Название расчёта"))
                .addProperties("note", new StringSchema().description("Заметка"))
                .addProperties("pinned", new BooleanSchema().description("Закрепить: не удалять по сроку хранения"));
        c.addSchemas("EditOperation", edit);
        c.addSchemas("VersionRequest", version);
        c.addSchemas("RunPatch", patch);
    }

    // ------------------------------------------------------------------ models from the examples

    private void registerModels(Components c) {
        c.addSchemas(ERROR_SCHEMA, new ObjectSchema().description("Ошибка запроса")
                .addProperties("error", new StringSchema().description("Что произошло").example("Dataset 7d2f… not found")));
        for (Map.Entry<String, String> e : MODEL_EXAMPLES.entrySet()) {
            JsonNode example = examples.get(e.getValue());
            if (example == null) {
                continue;
            }
            String name = e.getKey();
            if (example.isArray()) {
                example = example.size() > 0 ? example.get(0) : null;
                if (example == null) {
                    continue;
                }
            }
            c.addSchemas(name, objectSchema(name, example, c));
        }
        c.addSchemas("DatasetList", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/Dataset"))
                .description("Наборы, новые сверху"));
        c.addSchemas("IssueList", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/Issue")));
        c.addSchemas("RunList", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/RunBrief"))
                .description("Расчёты набора, новые сверху"));
        c.addSchemas("Bbox", new ArraySchema().items(new NumberSchema()).minItems(4).maxItems(4)
                .description("[minLon, minLat, maxLon, maxLat], EPSG:4326").example(Arrays.asList(37.62, 55.688, 37.662, 55.708)));
        Schema<?> issueText = new ObjectSchema()
                .addProperties("code", new StringSchema().description(FIELDS.get("code")))
                .addProperties("group", new StringSchema().description(FIELDS.get("group")))
                .addProperties("title", new StringSchema().description(FIELDS.get("title")))
                .addProperties("meaning", new StringSchema().description(FIELDS.get("meaning")))
                .addProperties("param", new StringSchema().nullable(true).description(FIELDS.get("param")));
        c.addSchemas("IssueText", issueText);
        c.addSchemas("IssueCatalog", new ObjectSchema().description("Код замечания → его описание")
                .additionalProperties(new Schema<>().$ref("#/components/schemas/IssueText")));
        // the schema of the run parameters comes from the annotated class; its fields are documented there
    }

    /** An object model from an example object: a property per field, typed by the value, described by FIELDS. */
    private static Schema<?> objectSchema(String model, JsonNode example, Components c) {
        ObjectSchema s = new ObjectSchema();
        Iterator<Map.Entry<String, JsonNode>> it = example.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            s.addProperties(f.getKey(), propertySchema(model, f.getKey(), f.getValue(), c));
        }
        return s;
    }

    private static Schema<?> propertySchema(String model, String field, JsonNode v, Components c) {
        String nested = NESTED.get(model + "." + field);
        Schema<?> s;
        if (v.isArray()) {
            JsonNode first = v.size() > 0 ? v.get(0) : null;
            Schema<?> items;
            if (nested != null && first != null && first.isObject()) {
                if (c.getSchemas() == null || !c.getSchemas().containsKey(nested)) {
                    c.addSchemas(nested, objectSchema(nested, first, c));
                }
                items = new Schema<>().$ref("#/components/schemas/" + nested);
            } else if (first == null) {
                items = new Schema<>();
            } else {
                items = propertySchema(model, field, first, c);
                items.setDescription(null);
            }
            s = new ArraySchema().items(items);
        } else if (v.isObject()) {
            if (nested != null) {
                if (c.getSchemas() == null || !c.getSchemas().containsKey(nested)) {
                    c.addSchemas(nested, objectSchema(nested, v, c));
                }
                s = new Schema<>().$ref("#/components/schemas/" + nested);
                // a $ref cannot carry a description in OpenAPI 3.0: wrap it so the field text is shown
                Schema<?> wrapper = new ObjectSchema();
                wrapper.setAllOf(Collections.singletonList(s));
                s = wrapper;
            } else if (v.size() == 0) {
                s = new ObjectSchema().additionalProperties(new IntegerSchema());
            } else {
                s = objectSchema(model + "." + field, v, c);
            }
        } else if (v.isBoolean()) {
            s = new BooleanSchema();
        } else if (v.isIntegralNumber()) {
            s = new IntegerSchema().format(v.canConvertToInt() ? "int32" : "int64");
        } else if (v.isNumber()) {
            s = new NumberSchema().format("double");
        } else if (v.isNull()) {
            s = new StringSchema().nullable(true);
        } else {
            StringSchema ss = new StringSchema();
            if ("status".equals(field)) {
                ss.setEnum(Arrays.asList("Accepted".equals(model) ? new String[]{"UPLOADED", "IMPORTING", "QUEUED"}
                        : "Run".equals(model) || "RunBrief".equals(model) || "Journal".equals(model)
                        ? new String[]{"QUEUED", "RUNNING", "DONE", "FAILED", "CANCELLED"}
                        : new String[]{"UPLOADED", "IMPORTING", "READY", "FAILED", "CANCELLED"}));
            } else if (field.endsWith("_at")) {
                ss.setFormat("date-time");
            } else if (field.equals("id") || field.endsWith("_id") && v.asText().length() == 36) {
                ss.setFormat("uuid");
            }
            s = ss;
        }
        String text = FIELDS.get(model + "." + field);
        if (text == null) {
            text = FIELDS.get(field);
        }
        if (text != null) {
            s.setDescription(text);
        }
        if (!v.isContainerNode() && !v.isNull()) {
            s.setExample(v.isTextual() ? v.asText() : v.numberValue() != null ? v.numberValue() : v.asBoolean());
        }
        return s;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, JsonNode> loadExamples() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("openapi/examples.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            root.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue()));
        } catch (IOException e) {
            throw new IllegalStateException("openapi/examples.json is missing or broken", e);
        }
        return out;
    }
}
