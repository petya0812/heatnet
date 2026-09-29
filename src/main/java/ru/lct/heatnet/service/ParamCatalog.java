package ru.lct.heatnet.service;

import org.springframework.stereotype.Component;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RestrictionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a calculation depends on, with where it comes from: a rule of the technical requirements (read-only),
 * how a rule open to more than one reading is applied, a value the input does not carry, or a setting of the routing.
 * For each — a plain description, the document it comes from and, for the documentation, the clarification it rests
 * on. The UI and {@code docs/parameters.md} are both built from this list.
 */
@Component
public class ParamCatalog {

    public static final String NORM = "norm";
    public static final String INTERPRETATION = "interpretation";
    public static final String DATA_ASSUMPTION = "data_assumption";
    public static final String ALGORITHM = "algorithm";

    public static final Map<String, String> CLASS_TITLES = new LinkedHashMap<>();

    static {
        CLASS_TITLES.put(NORM, "Правило");
        CLASS_TITLES.put(INTERPRETATION, "Уточнение правила");
        CLASS_TITLES.put(DATA_ASSUMPTION, "Недостающие данные");
        CLASS_TITLES.put(ALGORITHM, "Настройка трассировки");
    }

    /** Titles of the groups of parameters in the UI and in the documentation, by class. */
    public static final Map<String, String> GROUP_TITLES = new LinkedHashMap<>();

    static {
        GROUP_TITLES.put(NORM, "Правила проектирования");
        GROUP_TITLES.put(INTERPRETATION, "Как применяются правила");
        GROUP_TITLES.put(DATA_ASSUMPTION, "Недостающие данные");
        GROUP_TITLES.put(ALGORITHM, "Настройки трассировки");
    }

    /** Human names of restriction types (table 2 and types met in the data). */
    public static final Map<String, String> RESTRICTION_TITLES = new LinkedHashMap<>();

    static {
        RESTRICTION_TITLES.put("road", "дорога");
        RESTRICTION_TITLES.put("tram_tracks", "трамвайные пути");
        RESTRICTION_TITLES.put("gas_pipeline", "газопровод");
        RESTRICTION_TITLES.put("power_cable", "электрокабель");
        RESTRICTION_TITLES.put("heat_network", "существующая теплосеть");
        RESTRICTION_TITLES.put("park", "парк, сквер");
        RESTRICTION_TITLES.put("social_area", "территория социального объекта");
        RESTRICTION_TITLES.put("prohibited_site", "запретная территория");
        RESTRICTION_TITLES.put("water", "водный объект");
        RESTRICTION_TITLES.put("railway", "железная дорога");
        RESTRICTION_TITLES.put("oks", "существующее здание");
        RESTRICTION_TITLES.put("oks_existing", "существующее здание");
        RESTRICTION_TITLES.put("building", "существующее здание");
        RESTRICTION_TITLES.put("metro", "метрополитен");
        RESTRICTION_TITLES.put("support", "опора");
    }

    /** The rules the service is built on: the technical appendix of the case. */
    private static final String TT = "Технические требования";

    private final ReferenceData ref;
    private final List<Map<String, Object>> entries;

    public ParamCatalog(PlanParams defaults, ReferenceData ref) {
        this.ref = ref;
        this.entries = build(defaults, ref);
    }

    public List<Map<String, Object>> entries() {
        return entries;
    }

    public Map<String, Object> catalog() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> classes = new ArrayList<>();
        for (Map.Entry<String, String> c : CLASS_TITLES.entrySet()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.getKey());
            x.put("title", c.getValue());
            x.put("group_title", GROUP_TITLES.get(c.getKey()));
            x.put("description", classDescription(c.getKey()));
            classes.add(x);
        }
        m.put("classes", classes);
        m.put("params", entries);
        m.put("restriction_types", restrictionTypes(ref));
        return m;
    }

    private static String classDescription(String c) {
        switch (c) {
            case NORM:
                return "Правило или справочник технических требований. Не меняется.";
            case INTERPRETATION:
                return "Как сервис применяет правило, которое можно прочитать по-разному.";
            case DATA_ASSUMPTION:
                return "Значения нет во входных данных — сервис восполняет его по правилу. Видно, сколько объектов "
                        + "затронуто.";
            default:
                return "Может только ужесточить правила, но не ослабить: результат всегда проходит проверку.";
        }
    }

    /** Restriction types of table 2 with their rule, for the legend and for the edits of the input. */
    public static List<Map<String, Object>> restrictionTypes(ReferenceData ref) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RestrictionRule r : ref.getRestrictionRules()) {
            if ("heat_network".equals(r.getType())) {
                continue; // the existing network comes as its own object type, not as a restriction
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.getType());
            m.put("title", RESTRICTION_TITLES.getOrDefault(r.getType(), r.getType()));
            m.put("rule", ruleText(r));
            m.put("forbidden", r.isForbidden());
            out.add(m);
        }
        return out;
    }

    public static String ruleText(RestrictionRule r) {
        if (r.isForbidden()) {
            return String.format(Locale.ROOT, "обходить, не ближе %s м", num(r.getMinDistanceM()));
        }
        StringBuilder sb = new StringBuilder("можно пересечь спецпроходом");
        if (r.getMinAngleDeg() > 0) {
            sb.append(" под углом не меньше ").append(num(r.getMinAngleDeg())).append('°');
        }
        sb.append(", стоимость ×").append(num(r.getK()));
        sb.append("; вдоль — не ближе ").append(num(r.getMinDistanceM())).append(" м");
        return sb.toString();
    }

    private static String num(double v) {
        String s = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return s.replace('.', ',');
    }

    // ------------------------------------------------------------------ entries

    private static List<Map<String, Object>> build(PlanParams d, ReferenceData ref) {
        Entries out = new Entries();

        // ---- rules of the technical requirements
        StringBuilder bd = new StringBuilder();
        double prev = 0;
        for (double[] x : ref.getBuildingDistances()) {
            if (bd.length() > 0) {
                bd.append("; ");
            }
            bd.append(num(x[1])).append(" м при ДУ ");
            bd.append(prev == 0 ? "меньше " + num(x[0])
                    : x[0] >= 100000 ? "от " + num(prev) : "от " + num(prev) + " и меньше " + num(x[0]));
            prev = x[0];
        }
        out.add(fixed("building_clearance", NORM, "Отступ от существующих зданий",
                "Новая труба проходит не ближе этого расстояния от контура существующего здания. Чем больше ДУ, "
                        + "тем больше отступ.", bd.toString(), TT + ", табл. 2"));

        List<String> rules = new ArrayList<>();
        for (Map<String, Object> t : restrictionTypes(ref)) {
            rules.add(t.get("title") + " — " + t.get("rule"));
        }
        out.add(fixed("restriction_rules", NORM, "Правила для ограничений",
                "Что можно пересекать и как: запретные объекты обходятся с отступом, дороги, трамвай, газопровод "
                        + "и кабель пересекаются спецпроходом, который дороже обычной прокладки.",
                String.join("; ", rules), TT + ", табл. 2"));

        out.add(fixed("crossing_angle", NORM, "Угол пересечения дорог и трамвайных путей",
                "Дорогу и трамвайные пути трасса пересекает под углом не меньше 45°. Спецпроход — пересечение "
                        + "плюс 3 м с каждой стороны, внутри него трасса не поворачивает.",
                "не меньше 45° к границе полигона в точке входа спецпрохода; трасса пересекает объект, а не идёт "
                        + "вдоль него внутри",
                TT + ", табл. 2").basis("разъяснение 6"));

        out.add(fixed("overlapping_specials", NORM, "Несколько спецпроходов в одном месте",
                "Если под дорогой проходит газопровод или кабель, надбавки не складываются.",
                "на общем фрагменте выполняются все требования, коэффициент — наибольший, без сложения и "
                        + "перемножения", TT + ", п. 4").basis("разъяснение 8"));

        out.add(fixed("tie_in_rules", NORM, "Врезка в существующую сеть",
                "Где и как новая сеть присоединяется к существующей.",
                String.format(Locale.ROOT, "в существующую камеру, если до неё ближе %s м и после подключения в ней "
                                + "будет не больше %d участков (врезка %s млн руб. за участок); иначе новая камера "
                                + "прямо в точке участка, её стоимость уже включает присоединение",
                        num(ref.getTieInChamberRadiusM()), ref.getMaxChamberDegree(),
                        num(ref.getTieInCost() / 1e6)), TT + ", пп. 2.4, 3.2").basis("разъяснения 11–13"));

        out.add(fixed("reconstruction_scope", NORM, "Реконструкция существующей сети",
                "Существующая сеть не перекладывается: реконструкция не входит в стоимость варианта.",
                "участки, которым не хватит пропускной способности после подключения, показываются отдельным слоем "
                        + "и списком; на стоимость, оценку S и файл результата это не влияет",
                TT + ", п. 2.4").basis("разъяснение 14"));

        StringBuilder chambers = new StringBuilder();
        for (long[] c : ref.getChamberCosts()) {
            if (chambers.length() > 0) {
                chambers.append(", ");
            }
            chambers.append(num(c[2] / 1e6)).append(" млн при ДУ ").append(c[0]).append('–').append(c[1]);
        }
        PipeSpec small = ref.getPipes().get(0);
        PipeSpec large = ref.getPipes().get(ref.getPipes().size() - 1);
        out.add(fixed("costs", NORM, "Стоимость",
                "Из чего складывается стоимость варианта: новые участки, новые камеры, врезки в существующие "
                        + "камеры, штрафы за неподключённые ОКС.",
                String.format(Locale.ROOT, "врезка %s млн руб.; камера — %s; метр трубы от %s тыс. руб. (ДУ %d) "
                                + "до %s тыс. руб. (ДУ %d)", num(ref.getTieInCost() / 1e6), chambers,
                        num(Math.round(small.getNewCostPerM() / 100) / 10.0), small.getDn(),
                        num(Math.round(large.getNewCostPerM() / 100) / 10.0), large.getDn()), TT + ", пп. 3, 6"));

        out.add(fixed("score", NORM, "Оценка варианта S",
                "По ней варианты ранжируются: меньше — лучше. Стоимость делится на 25 млн руб. (≈ 100 м сети), "
                        + "длина — на 100 м.",
                String.format(Locale.ROOT, "S = %s · стоимость / %s млн + %s · длина / %s м",
                        num(ref.getCostWeight()), num(ref.getCostBase() / 1e6), num(ref.getLengthWeight()),
                        num(ref.getLengthBase())), TT + ", п. 6"));

        out.add(fixed("unconnected_penalty", NORM, "Штраф за неподключённый ОКС",
                "Добавляется к стоимости варианта за каждый ОКС без трассы.",
                String.format(Locale.ROOT, "%s млн руб. + %s млн руб. × расход, т/ч", num(ref.getPenaltyFixed() / 1e6),
                        num(ref.getPenaltyPerTph() / 1e6)), TT + ", п. 6"));

        out.add(fixed("length_limit", NORM, "Предельная длина участков одного ДУ",
                "У каждого ДУ есть предельная длина непрерывной части новой сети. Она проверяется по каждому "
                        + "непрерывному пути от точки подключения к месту присоединения: общий участок учитывается "
                        + "в каждом пути, длины параллельных ветвей не складываются.",
                "ДУ — минимальный, удовлетворяющий и расходу, и предельной длине; к месту присоединения ДУ не "
                        + "уменьшается; если подходящего ДУ нет — ищется другое присоединение или объединение",
                TT + ", п. 2.3, табл. 1").basis("разъяснения 1 и 2"));

        out.add(fixed("along_roads", NORM, "Трасса вдоль дорог и трамвайных путей",
                "Идти параллельно дороге можно, выдерживая минимальное расстояние до её границы; пересекать — "
                        + "только спецпроходом под углом не меньше 45°.",
                "разрешено, не ближе 1,5 м от полигона дороги или трамвайных путей",
                TT + ", п. 4; СП 124.13330, п. 12.20"));

        out.add(fixed("oks_geometry", NORM, "Контуры перспективных ОКС",
                "Трасса приходит в точку подключения. Контуры других перспективных ОКС — препятствия.",
                "контуры чужих ОКС — непроходимые ограничения с отступом 5/7/9 м; своя точка подключения — цель",
                TT + ", пп. 1.2, 2.2").basis("разъяснения 3 и 4"));

        out.add(fixed("unconnected_policy", NORM, "Когда ОКС остаётся без трассы",
                "Только если допустимой трассы нет: отказаться от возможного подключения ради лучшей оценки нельзя.",
                "без трассы — только когда трассы по правилам не существует", TT + ", п. 2.5")
                .basis("разъяснение 15"));

        out.add(fixed("turns", NORM, "Повороты трассы",
                "Трасса строится прямыми участками, соединёнными поворотами. Поворот измеряется как изменение "
                        + "направления относительно продолжения предыдущего участка.",
                String.format(Locale.ROOT, "допустим любой угол до %s° включительно, отдельного удорожания нет; "
                        + "трасса без мелких изломов и зигзагов, допуск угла ±1°", num(ref.getMaxTurnDeg())),
                TT + ", п. 2.1").basis("разъяснение 5"));

        out.add(fixed("oks_building", NORM, "Подход к точке подключения",
                "Связи точки подключения с полигоном ОКС во входе нет: своим считается полигон, внутри которого "
                        + "лежит точка.",
                "один финальный прямой участок от ближайшей к точке границы своего полигона до самой точки; "
                        + "отступ к своему полигону на него не распространяется",
                TT + ", п. 2.2").basis("разъяснения 3 и 4")
                .codes("oks.building_from_restriction", "oks.point_without_building"));

        out.add(fixed("building_restrictions", NORM, "Контуры ОКС приходят ограничениями",
                "Все полигоны ОКС передаются ограничениями с типом oks.",
                "пересекать нельзя, отступ 5/7/9 м по ДУ", TT + ", пп. 1.2, 4")
                .codes("restriction.building"));

        // ---- how the rules are applied where they can be read in more than one way
        out.add(fixed("crossing_axis", INTERPRETATION, "Направление границы при пересечении",
                "Угол меряется от границы, через которую проходит трасса, а граница состоит из коротких звеньев.",
                "направление — у кромки, которую пересекает трасса (соседние звенья присоединяются, пока контур "
                        + "почти прямой); в середине пересечения трасса не идёт вдоль контура",
                TT + ", п. 4").basis("разъяснение 6"));

        out.add(fixed("invalid_geometry", INTERPRETATION, "Невалидная геометрия во входных данных",
                "Самопересекающийся контур, вырожденная линия и т. п. Ошибка относится к объекту, а не ко всему "
                        + "набору.",
                "ошибка в проверке данных; объект в расчёт не берётся, остальное считается", null)
                .codes("feature.invalid_geometry"));

        out.add(fixed("endpoint_clearance", INTERPRETATION, "Отступы у концов трассы",
                "Точка подключения или врезка может оказаться ближе минимального расстояния к соседнему зданию "
                        + "или ограничению — без послабления у самого конца такой ОКС нельзя было бы подключить.",
                "в радиусе 1,1 · отступ + 1,5 м от конца трассы отступ может нарушаться; сам объект пересекать "
                        + "нельзя", null));

        out.add(fixed("building_inlet", INTERPRETATION, "Ввод в здание",
                "Точка подключения часто лежит внутри контура здания.",
                "трасса приходит к контуру и последним отрезком заходит к точке; этот отрезок не проверяется на "
                        + "отступы и пересечения, его длина и стоимость входят в участок", null));

        out.add(fixed("chamber_segments", INTERPRETATION, "Камера на проходящей линии",
                "Если существующая линия проходит через камеру без разрыва, она занимает два из четырёх примыканий.",
                "два примыкания", TT + ", п. 2.1").basis("разъяснение 12"));

        out.add(fixed("branching_chamber_place", INTERPRETATION, "Где ставить камеру-разветвление",
                "Место камеры правилами не задано.",
                "не ближе 2 м к концам участка, не внутри спецпрохода и коридоров коммуникаций",
                "СП 124.13330, пп. 9.14, 9.16"));

        // ---- values the input does not carry
        out.add(choice("existing_flow", DATA_ASSUMPTION, "Расход существующей сети",
                "Во входных данных расхода у участков сети нет. Влияет только на слой нехватки пропускной "
                        + "способности: стоимость и оценку S не меняет.",
                d.rules.existingFlow.name(), null, "HEATNET_EXISTING_FLOW",
                option("ZERO", "Свободна", "весь запас ДУ считается доступным новым подключениям"),
                option("CAPACITY_SHARE", "Частично загружена",
                        "занята доля пропускной способности каждого участка"))
                .codes("network.flow_assumed"));

        out.add(number("existing_flow_share", DATA_ASSUMPTION, "Занятая доля пропускной способности",
                "Какая доля пропускной способности существующих участков уже занята.",
                d.rules.existingFlowShare, null, "HEATNET_EXISTING_FLOW_SHARE", "доля", 0, 1, 0.05)
                .dependsOn("existing_flow", "CAPACITY_SHARE"));

        out.add(fixed("network_direction", DATA_ASSUMPTION, "Направление сети к источнику",
                "Связей участков между собой во входных данных нет. Направление нужно, чтобы передать расход новых "
                        + "подключений от врезки к источнику.",
                "выводится по геометрии: концы участков ближе 0,5 м — один узел, обход от источника", null)
                .codes("network.topology_derived"));

        out.add(fixed("chamber_dn", DATA_ASSUMPTION, "ДУ существующей камеры",
                "Во входных данных он не обязателен; нужен для показа и для ДУ новой камеры рядом.",
                "наибольший ДУ примыкающих участков", TT + ", п. 1.1")
                .codes("network.chamber_dn_assumed"));

        RestrictionRule unknown = ref.getUnknownRestriction();
        out.add(fixed("unknown_restrictions", DATA_ASSUMPTION, "Ограничения вне справочника",
                "Тип ограничения, которого нет в справочнике, всё равно учитывается — как запретная зона.",
                "обходить, не ближе " + num(unknown.getMinDistanceM()) + " м", TT + ", п. 4")
                .basis("разъяснение 9")
                .codes("restriction.unknown_type"));

        // ---- settings of the routing: stricter than the rules, never looser
        out.add(number("extra_clearance_m", ALGORITHM, "Дополнительный отступ от зданий и ограничений",
                "Прибавляется к отступу по правилам (5/7/9 м от зданий по ДУ, 1–2 м от ограничений). Трассы "
                        + "отходят дальше от препятствий, обычно становятся длиннее и дороже.",
                d.extraClearanceM, null, null, "м", 0, 10, 0.5));

        out.add(number("min_crossing_angle_deg", ALGORITHM, "Минимальный угол пересечения дорог и трамвайных путей",
                "По правилам — не меньше 45°. Большее значение заставляет трассу пересекать дорогу ближе к "
                        + "перпендикуляру: спецпроход короче, но обход может стать длиннее.",
                d.minCrossingAngleDeg > 0 ? d.minCrossingAngleDeg : 45, null, null, "°", 45, 90, 5));

        out.add(choice("turn_angles", ALGORITHM, "Углы поворота трассы",
                "Правила допускают любой угол до 90°. Типовые углы дают аккуратную трассу вдоль фасадов и улиц; "
                        + "чем меньше разрешённых углов, тем длиннее обходы.",
                RunParams.turnAnglesName(d.turnStepDeg), null, null,
                option("ANY", "Любые до 90°", "трасса короче, повороты под произвольными углами"),
                option("30_60_90", "30°, 60°, 90°", "повороты кратны 30°"),
                option("45_90", "45° и 90°", "как принято в проектах тепловых сетей"),
                option("90", "Только 90°", "трасса из взаимно перпендикулярных участков, обходы длиннее")));

        out.add(flag("turn_angles_strict", ALGORITHM, "Если трассы с такими углами нет",
                "Когда препятствия не дают подойти к ОКС только выбранными углами.",
                d.turnStepStrict, null, null,
                option("false", "Любые углы до 90°", "ОКС подключается трассой с произвольными углами"),
                option("true", "Не подключать", "ОКС остаётся без трассы, в причине указаны выбранные углы"))
                .dependsOnNot("turn_angles", "ANY"));

        out.add(flag("joint_connection", ALGORITHM, "Совместное подключение ОКС",
                "Несколько ОКС питаются через одну врезку и общий участок новой сети с разветвлениями. Без него "
                        + "каждый ОКС подключается своей трассой и своей врезкой.",
                d.jointConnection, null, null,
                option("true", "Да", "сервис ищет общие участки и разветвления"),
                option("false", "Нет", "только раздельное подключение — врезок больше, сеть проще")));

        out.add(number("turn_penalty", ALGORITHM, "Штраф за поворот",
                "Сколько «стоит» поворот при поиске трассы, в единицах оценки S. Больше штраф — прямее трассы и "
                        + "меньше изломов. На стоимость варианта не влияет.",
                d.turnPenaltyScore, null, null, "S", 0, 10, 0.05));

        out.add(fixed("search_limits", ALGORITHM, "Пределы поиска трассы",
                "Защита от зависания на больших районах: если предел достигнут, ОКС остаётся без трассы с этой "
                        + "причиной.",
                String.format(Locale.ROOT, "радиус до %s км, до %d шагов поиска на одну трассу",
                        num(d.searchRadiusMaxM / 1000), d.maxExpansions), null));

        out.add(fixed("joint_strategy", ALGORITHM, "Как ищутся варианты",
                "Совместное подключение строится из нескольких порядков присоединения ОКС и улучшается перестройкой.",
                "3 порядка присоединения + раздельное подключение; перестройка «вынуть ОКС и подключить заново»; "
                        + "до 3 разных вариантов", null));
        return out;
    }

    // ------------------------------------------------------------------ builders

    /** A list of entries that also takes the builders. */
    private static final class Entries extends ArrayList<Map<String, Object>> {
        boolean add(Entry e) {
            return add(e.map);
        }
    }

    private static final class Entry {
        final Map<String, Object> map = new LinkedHashMap<>();

        Entry(String key, String cls, String title, String description, String source) {
            map.put("key", key);
            map.put("class", cls);
            map.put("class_title", CLASS_TITLES.get(cls));
            map.put("title", title);
            map.put("description", description);
            if (source != null) {
                map.put("source", source);
            }
        }

        /** The clarification of the requirements the entry rests on (for the documentation). */
        Entry basis(String basis) {
            map.put("basis", basis);
            return this;
        }


        Entry codes(String... codes) {
            map.put("diagnostic_codes", Arrays.asList(codes));
            return this;
        }

        /** Shown only when parameter {@code key} has {@code value}. */
        Entry dependsOn(String key, Object value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", value);
            map.put("depends_on", m);
            return this;
        }

        /** Shown only when parameter {@code key} has any value but {@code value}. */
        Entry dependsOnNot(String key, Object value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("not", value);
            map.put("depends_on", m);
            return this;
        }
    }

    private static Entry fixed(String key, String cls, String title, String description, String value, String source) {
        Entry e = new Entry(key, cls, title, description, source);
        e.map.put("type", "fixed");
        e.map.put("editable", false);
        e.map.put("value", value);
        return e;
    }

    private static Map<String, Object> option(String value, String label, String effect) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        m.put("effect", effect);
        return m;
    }

    @SafeVarargs
    private static Entry choice(String key, String cls, String title, String description, String def, String source,
                                String env, Map<String, Object>... options) {
        Entry e = new Entry(key, cls, title, description, source);
        e.map.put("type", "enum");
        e.map.put("editable", true);
        e.map.put("default", def);
        e.map.put("options", Arrays.asList(options));
        if (env != null) {
            e.map.put("env", env);
        }
        return e;
    }

    private static Entry flag(String key, String cls, String title, String description, boolean def, String source,
                              String env, Map<String, Object> first, Map<String, Object> second) {
        Entry e = new Entry(key, cls, title, description, source);
        e.map.put("type", "boolean");
        e.map.put("editable", true);
        e.map.put("default", def);
        e.map.put("options", Arrays.asList(first, second));
        if (env != null) {
            e.map.put("env", env);
        }
        return e;
    }

    private static Entry number(String key, String cls, String title, String description, double def, String source,
                                String env, String unit, double min, double max, double step) {
        Entry e = new Entry(key, cls, title, description, source);
        e.map.put("type", "number");
        e.map.put("editable", true);
        e.map.put("default", def);
        e.map.put("unit", unit);
        e.map.put("min", min);
        e.map.put("max", max);
        e.map.put("step", step);
        if (env != null) {
            e.map.put("env", env);
        }
        return e;
    }

    // ------------------------------------------------------------------ documentation

    /** {@code docs/parameters.md}: the same list as a document. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Параметры расчёта\n\n");
        sb.append("Документ собирается из каталога параметров сервиса (`ParamCatalog`, `GET /api/params`)\n");
        sb.append("тестом `ParamCatalogTest`: интерфейс, API и этот документ не расходятся. «Технические требования» —\n");
        sb.append("техническое приложение к кейсу, «разъяснение N» — пункт разъяснений по вопросам участников.\n\n");
        for (Map.Entry<String, String> c : CLASS_TITLES.entrySet()) {
            sb.append("## ").append(GROUP_TITLES.get(c.getKey())).append("\n\n")
                    .append(classDescription(c.getKey())).append("\n\n");
            sb.append("| Параметр | Значение | Основание |\n|---|---|---|\n");
            for (Map<String, Object> e : entries) {
                if (!c.getKey().equals(e.get("class"))) {
                    continue;
                }
                sb.append("| **").append(e.get("title")).append("**<br>").append(e.get("description"));
                if (Boolean.TRUE.equals(e.get("editable"))) {
                    sb.append("<br>поле запроса `").append(e.get("key")).append('`');
                    if (e.get("env") != null) {
                        sb.append(", переменная `").append(e.get("env")).append('`');
                    }
                }
                List<String> basis = new ArrayList<>();
                for (String f : new String[]{"source", "basis"}) {
                    if (e.get(f) != null) {
                        basis.add(String.valueOf(e.get(f)));
                    }
                }
                sb.append(" | ").append(valueText(e)).append(" | ")
                        .append(basis.isEmpty() ? "решение сервиса" : String.join("; ", basis)).append(" |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String valueText(Map<String, Object> e) {
        String type = (String) e.get("type");
        if ("fixed".equals(type)) {
            return String.valueOf(e.get("value"));
        }
        if ("number".equals(type)) {
            return "по умолчанию " + num(((Number) e.get("default")).doubleValue()) + " " + e.get("unit")
                    + " (" + num(((Number) e.get("min")).doubleValue()) + "…"
                    + num(((Number) e.get("max")).doubleValue()) + ")";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> o : (List<Map<String, Object>>) e.get("options")) {
            boolean def = String.valueOf(e.get("default")).equals(o.get("value"));
            sb.append(def ? "**" : "").append(o.get("label")).append(def ? "** (по умолчанию)" : "")
                    .append(" — ").append(o.get("effect")).append("<br>");
        }
        return sb.toString();
    }

}
