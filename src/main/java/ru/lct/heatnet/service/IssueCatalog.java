package ru.lct.heatnet.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain-language meaning of the diagnostic codes of the input check: title, what it means for the calculation and
 * the group it is shown in. The code itself stays available for a specialist.
 */
public final class IssueCatalog {

    /** Data completed by the service (assumptions), problems that block or change the result, notes. */
    public static final String ASSUMPTION = "assumption";
    public static final String PROBLEM = "problem";
    public static final String NOTE = "note";

    private static final Map<String, String[]> CODES = new LinkedHashMap<>();

    private static void c(String code, String group, String title, String meaning, String param) {
        CODES.put(code, new String[]{group, title, meaning, param});
    }

    static {
        // file and features
        c("file.not_object", PROBLEM, "Файл не является JSON-объектом", "Набор не прочитан.", null);
        c("file.not_feature_collection", PROBLEM, "Файл не является набором объектов GeoJSON",
                "Нужен один FeatureCollection.", null);
        c("file.no_features", PROBLEM, "В файле нет объектов", "Считать нечего.", null);
        c("feature.not_object", PROBLEM, "Объект записан не как JSON-объект", "Объект пропущен.", null);
        c("feature.no_properties", PROBLEM, "У объекта нет атрибутов", "Объект пропущен.", null);
        c("feature.no_id", PROBLEM, "У объекта нет идентификатора", "Объект пропущен.", null);
        c("feature.unknown_object_type", PROBLEM, "Неизвестный тип объекта", "Объект пропущен.", null);
        c("feature.no_geometry", PROBLEM, "У объекта нет геометрии", "Объект пропущен.", null);
        c("feature.geometry_type", PROBLEM, "Геометрия не того вида", "Например, участок сети не линией. Объект пропущен.",
                null);
        c("feature.bad_geometry", PROBLEM, "Геометрия не читается", "Объект пропущен.", null);
        c("feature.coordinates_out_of_range", PROBLEM, "Координаты вне допустимого диапазона",
                "Ожидаются долгота и широта (EPSG:4326). Объект пропущен.", null);
        c("feature.invalid_geometry", PROBLEM, "Невалидная геометрия",
                "Самопересечение контура, вырожденная линия и т. п. Объект не исправляется и в расчёт не берётся: "
                        + "исправьте геометрию во входном файле.", "invalid_geometry");
        c("feature.missing_attribute", PROBLEM, "Нет обязательного атрибута", "Объект пропущен.", null);
        c("feature.attribute_type", PROBLEM, "Атрибут не того типа", "Например, текст вместо числа. Объект пропущен.",
                null);
        c("feature.negative_flow", PROBLEM, "Отрицательный расход", "Объект пропущен.", null);
        c("feature.duplicate_id", PROBLEM, "Повторяющийся идентификатор", "Ссылки на такой объект неоднозначны.", null);
        // network
        c("network.empty", PROBLEM, "Нет существующей сети", "Подключаться некуда.", null);
        c("network.no_source", PROBLEM, "Нет источника", "Нельзя определить, куда идёт расход.", null);
        c("network.many_sources", PROBLEM, "Несколько источников", "Используется один из них.", null);
        c("network.bad_upstream_ref", PROBLEM, "Ссылка на несуществующий объект сети",
                "Цепочка к источнику обрывается.", null);
        c("network.chain_broken", PROBLEM, "Цепочка к источнику обрывается",
                "К этой части сети не присоединяемся.", null);
        c("network.chain_cycle", PROBLEM, "Цепочка к источнику замкнута в кольцо",
                "К этой части сети не присоединяемся.", null);
        c("network.topology_derived", ASSUMPTION, "Направление к источнику выведено по геометрии",
                "Ссылок участков друг на друга в данных нет: участки связаны по совпадающим концам.",
                "network_direction");
        c("network.flow_assumed", ASSUMPTION, "Расход существующей сети неизвестен",
                "Взят по настройке расчёта; влияет только на слой нехватки пропускной способности, "
                        + "не на трассы и стоимость.", "existing_flow");
        c("network.chamber_dn_assumed", ASSUMPTION, "ДУ камеры взят по примыкающим участкам",
                "У камеры нет диаметра в данных — взят наибольший ДУ примыкающих участков.", "chamber_dn");
        c("network.no_upstream", PROBLEM, "Объект сети не связан с источником",
                "Присоединяться к нему не к чему: тепло по нему не придёт.", null);
        c("network.detached_part", PROBLEM, "Часть сети не связана с источником",
                "Присоединения к ней не делаются: тепло по ней не придёт.", null);
        c("network.source_not_on_network", PROBLEM, "Источник не стоит на сети",
                "Сеть направлена от ближайшего к источнику конца участка.", null);
        c("network.upstream_not_adjacent", NOTE, "Объект «к источнику» не примыкает к участку",
                "Связь взята из данных как есть.", null);
        c("network.chamber_gap", NOTE, "Камера немного в стороне от конца участка",
                "Зазор до 2 м — камера привязана к участку.", null);
        c("network.chamber_inside_segment", NOTE, "Линия сети проходит через камеру без разрыва",
                "У камеры считаются две стороны участка.", null);
        c("network.dn_decreases_upstream", NOTE, "ДУ уменьшается в сторону источника",
                "Необычно для сети, но расчёту не мешает.", null);
        c("network.nonstandard_dn", NOTE, "Нестандартный ДУ",
                "Для расчётов взят ближайший больший ДУ из таблицы.", null);
        // OKS
        c("oks.no_flow", PROBLEM, "У ОКС нет расхода", "ОКС не подключается.", null);
        c("oks.no_connection_point", PROBLEM, "У ОКС нет точки подключения", "ОКС не подключается.", null);
        c("oks.connection_point_orphan", PROBLEM, "Точка подключения без ОКС", "Точка не используется.", null);
        c("oks.many_connection_points", NOTE, "У ОКС несколько точек подключения", "Используется одна.", null);
        c("oks.connection_point_inside", NOTE, "Точка подключения внутри контура ОКС",
                "Трасса подходит к контуру и заходит к точке.", null);
        c("oks.connection_point_off_boundary", NOTE, "Точка подключения в стороне от контура ОКС",
                "Трасса приходит в саму точку.", null);
        c("oks.building_from_restriction", ASSUMPTION, "Здание ОКС найдено среди ограничений",
                "Контура ОКС в данных нет — взято здание, внутри которого лежит точка подключения.", "oks_building");
        c("oks.point_without_building", ASSUMPTION, "Точка подключения не внутри здания",
                "Трасса приходит в саму точку.", "oks_building");
        c("oks.flow_exceeds_max_dn", PROBLEM, "Расход ОКС больше пропускной способности наибольшего ДУ",
                "ОКС не подключается.", null);
        // restrictions
        c("restriction.building", ASSUMPTION, "Контуры ОКС приходят ограничениями",
                "Тип oks: пересекать нельзя, отступ 5/7/9 м по ДУ.", "building_restrictions");
        c("restriction.unknown_type", ASSUMPTION, "Тип ограничения не из справочника",
                "Правила для него нет — объект обходится с отступом 1 м.", "unknown_restrictions");
        c("edit.restriction_added", NOTE, "Ограничение добавлено вручную",
                "Правка входных данных в этой версии набора.", null);
        c("edit.object_removed", NOTE, "Объект исключён вручную",
                "Правка входных данных в этой версии набора: объект родительского набора в расчёт не берётся.", null);
        c("edit.attribute_changed", NOTE, "Атрибут объекта изменён вручную",
                "Правка входных данных в этой версии набора: расчёт идёт по новому значению.", null);
        // calculation
        c("recon.flow_exceeds_max_dn", NOTE, "Расход на существующем участке больше наибольшего ДУ",
                "Нехватка пропускной способности посчитана по наибольшему ДУ.", null);
        c("route.dn_increased_for_length", NOTE, "ДУ увеличен на одну номенклатуру из-за предельной длины",
                "Трасса длиннее предела минимального ДУ.", "length_limit");
        c("route.not_found", PROBLEM, "Трасса не найдена", "ОКС остался без трассы.", null);
        c("route.search_limit", PROBLEM, "Поиск трассы остановлен по пределу",
                "ОКС остался без трассы.", "search_limits");
        c("route.vertices_truncated", NOTE, "Поиск ограничен по числу точек поворота",
                "Трасса может быть не самой выгодной.", "search_limits");
    }

    private IssueCatalog() {
    }

    /** Description of a code: group, title, meaning and the parameter it relates to (if any). */
    public static Map<String, Object> describe(String code) {
        String[] d = CODES.get(code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        if (d == null) {
            m.put("group", NOTE);
            m.put("title", "Замечание к данным");
            m.put("meaning", null);
            m.put("param", null);
        } else {
            m.put("group", d[0]);
            m.put("title", d[1]);
            m.put("meaning", d[2]);
            m.put("param", d[3]);
        }
        return m;
    }

    public static boolean isKnown(String code) {
        return CODES.containsKey(code);
    }

    public static java.util.Set<String> codes() {
        return CODES.keySet();
    }
}
