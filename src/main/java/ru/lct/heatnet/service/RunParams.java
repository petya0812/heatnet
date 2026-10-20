package ru.lct.heatnet.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.reference.RuleOptions;

/**
 * Parameters of one calculation. Missing fields take the service defaults ({@code heatnet.*} settings);
 * the planner and the output validator of the run use the same values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Параметры расчёта; незаданные поля берутся из настроек сервиса")
public class RunParams {

    @Schema(description = "Расход существующей сети, если его нет во входных данных: ZERO — сеть свободна, "
            + "CAPACITY_SHARE — занята долей пропускной способности ДУ",
            allowableValues = {"ZERO", "CAPACITY_SHARE"}, defaultValue = "ZERO")
    @JsonProperty("existing_flow")
    public String existingFlow;

    @Schema(description = "Доля пропускной способности ДУ при existing_flow=CAPACITY_SHARE (0…1)",
            minimum = "0", maximum = "1", defaultValue = "0.5", example = "0.5")
    @JsonProperty("existing_flow_share")
    public Double existingFlowShare;

    @Schema(description = "Штраф за поворот в единицах оценки S: больше — прямее трассы",
            minimum = "0", maximum = "10", defaultValue = "0.15", example = "0.15")
    @JsonProperty("turn_penalty")
    public Double turnPenalty;

    @Schema(description = "Цена стыка с сетью не буквой «Т» (под нестандартным углом к трубе), в поворотах: 0 — "
            + "бесплатно, 1 — как один поворот, 2 — как два (угол к обоим направлениям трубы)",
            minimum = "0", maximum = "4", defaultValue = "1", example = "1")
    @JsonProperty("odd_joint_turns")
    public Double oddJointTurns;

    @Schema(description = "Учитывать сложность формы при выборе схемы: при сравнении схем (перестройка, выбор порядка "
            + "присоединения, отбор вариантов) к оценке S добавляется shape_weight × (штраф за поворот × повороты + "
            + "цена стыка не «Т» × штраф за поворот × такие стыки). 0 — схемы сравниваются только по S; оценка S "
            + "варианта и его ранг (по S) от этого не меняются",
            minimum = "0", maximum = "3", defaultValue = "1", example = "1")
    @JsonProperty("shape_weight")
    public Double shapeWeight;

    @Schema(description = "Дополнительный отступ от зданий и ограничений сверх отступа по правилам, м (0…10; по "
            + "умолчанию 0). Отступ по правилам — минимум, он только увеличивается", minimum = "0", maximum = "10",
            defaultValue = "0", example = "1.0")
    @JsonProperty("extra_clearance_m")
    public Double extraClearanceM;

    @Schema(description = "Минимальный угол пересечения дорог и трамвайных путей, градусы (45…90; по умолчанию 45 — "
            + "по правилам). Больше — трасса пересекает ближе к перпендикуляру", minimum = "45", maximum = "90",
            defaultValue = "45", example = "60")
    @JsonProperty("min_crossing_angle_deg")
    public Double minCrossingAngleDeg;

    @Schema(description = "Углы поворота трассы: ANY — любые до 90°, 30_60_90 — 30°, 60° и 90°, 45_90 — 45° и 90°, "
            + "90 — только прямые углы", allowableValues = {"ANY", "30_60_90", "45_90", "90"}, defaultValue = "45_90")
    @JsonProperty("turn_angles")
    public String turnAngles;

    @Schema(description = "Если у ОКС нет трассы с выбранными углами поворота: true — оставить без трассы, "
            + "false — построить с любыми углами до 90° (по умолчанию)", defaultValue = "false")
    @JsonProperty("turn_angles_strict")
    public Boolean turnAnglesStrict;

    @Schema(description = "Ввод в здание, если точка подключения внутри контура: NEAREST — только от ближайшей к "
            + "точке стены (по техническим требованиям, п. 2.2; если с неё не выйти — ОКС без трассы с причиной "
            + "ENTRY_BLOCKED), NEAR — если ближайшая закрыта, другая стена с отрезком внутри здания длиннее не больше "
            + "чем на entry_slack_m, ANY — если ближайшая закрыта, любая стена, OUTLINE — с любой точки контура под "
            + "любым углом, без отступа к своему зданию и прямого участка от стены. NEAR, ANY и OUTLINE отступают от "
            + "технических требований: такой ввод — предупреждение R-ENTRY в проверке результата",
            allowableValues = {"NEAREST", "NEAR", "ANY", "OUTLINE"}, defaultValue = "NEAREST")
    @JsonProperty("building_entry")
    public String buildingEntry;

    @Schema(description = "При building_entry=NEAR: насколько отрезок внутри здания может быть длиннее, чем от "
            + "ближайшей стены, м (0…50)", minimum = "0", maximum = "50", defaultValue = "5", example = "5")
    @JsonProperty("entry_slack_m")
    public Double entrySlackM;

    @Schema(description = "Прохождение трассы сквозь другие здания: NONE — нельзя (по умолчанию: технические "
            + "требования этого не предусматривают), EXISTING — через существующие здания, кроме зданий подключаемых ОКС, "
            + "ANY — через существующие здания и здания других ОКС (СП 124, п. 9.3). Здание пересекается прямым "
            + "спецучастком (контур здания и по 1 м с каждой стороны) с коэффициентом building_crossing_k, отступ к "
            + "нему не действует",
            allowableValues = {"NONE", "EXISTING", "ANY"}, defaultValue = "NONE")
    @JsonProperty("through_buildings")
    public String throughBuildings;

    @Schema(description = "При through_buildings, отличном от NONE: коэффициент стоимости трубы внутри здания "
            + "(1…3; по умолчанию 1 — базовая цена)", minimum = "1", maximum = "3", defaultValue = "1", example = "1")
    @JsonProperty("building_crossing_k")
    public Double buildingCrossingK;

    @Schema(description = "Прохождение сквозь отдельные части своего здания (полигоны мультиполигона без точки "
            + "подключения): false — нельзя, это то же здание с отступом (по умолчанию), true — можно, отступ к ним "
            + "не действует", defaultValue = "false")
    @JsonProperty("through_own_parts")
    public Boolean throughOwnParts;

    @Schema(description = "Врезка в существующую сеть в зоне отступа к зданию или запретному ограничению: false — "
            + "нельзя (по умолчанию: технические требования, п. 2.2 и разд. 4, освобождают от отступа только прямой "
            + "участок ввода в своё здание), врезка ставится вне зон отступа, а если иначе нельзя — ОКС без трассы с "
            + "причиной TIE_IN_IN_CLEARANCE; true — можно, трасса ближе отступа только у самой врезки",
            defaultValue = "false")
    @JsonProperty("tie_in_in_clearance")
    public Boolean tieInInClearance;

    @Schema(description = "Прямой участок трассы от стены здания по перпендикуляру к ней не короче, м (0…20; по "
            + "умолчанию 0). Он всегда пересекает всю зону отступа к своему зданию (технические требования, п. 2.2) — "
            + "5/7/9 м по ДУ; больше — длиннее", minimum = "0", maximum = "20", defaultValue = "0", example = "10")
    @JsonProperty("entry_leg_m")
    public Double entryLegM;

    @Schema(description = "Граница данных, м (0…50; по умолчанию 5): за пределами набора зданий и препятствий данных "
            + "нет — трасса не уходит дальше этого расстояния от крайних объектов набора; 0 — без границы",
            minimum = "0", maximum = "50", defaultValue = "5", example = "5")
    @JsonProperty("data_boundary_m")
    public Double dataBoundaryM;

    @Schema(description = "Совместное подключение нескольких ОКС через одну врезку (по умолчанию true); при false — "
            + "каждый ОКС своей трассой и своей врезкой", defaultValue = "true")
    @JsonProperty("joint_connection")
    public Boolean jointConnection;

    @Schema(description = "Перестройка группами при совместном подключении: ветка сети (ОКС ниже одной камеры) "
            + "вынимается целиком и подключается заново в нескольких порядках. Иногда находит схему лучше, расчёт "
            + "дольше примерно вдвое (по умолчанию false)", defaultValue = "false")
    @JsonProperty("group_rebuild")
    public Boolean groupRebuild;

    @Schema(description = "Название расчёта (по умолчанию — из параметров, отличных от настроек сервиса); "
            + "не параметр расчёта")
    @JsonProperty("name")
    public String name;

    @Schema(description = "Заметка к расчёту; не параметр расчёта")
    @JsonProperty("note")
    public String note;

    /** Values of a calculation: these parameters over the defaults. */
    public PlanParams apply(PlanParams defaults) {
        PlanParams p = defaults.copy();
        if (existingFlow != null) {
            p.rules.existingFlow = RuleOptions.ExistingFlow.valueOf(existingFlow.trim().toUpperCase());
        }
        if (existingFlowShare != null) {
            if (existingFlowShare < 0 || existingFlowShare > 1) {
                throw new IllegalArgumentException("existing_flow_share must be within [0, 1]");
            }
            p.rules.existingFlowShare = existingFlowShare;
        }
        if (turnPenalty != null) {
            if (turnPenalty < 0 || turnPenalty > 10) {
                throw new IllegalArgumentException("turn_penalty must be within [0, 10]");
            }
            p.turnPenaltyScore = turnPenalty;
        }
        if (oddJointTurns != null) {
            if (oddJointTurns < 0 || oddJointTurns > 4) {
                throw new IllegalArgumentException("odd_joint_turns must be within [0, 4]");
            }
            p.oddJointTurns = oddJointTurns;
        }
        if (shapeWeight != null) {
            if (shapeWeight < 0 || shapeWeight > 3) {
                throw new IllegalArgumentException("shape_weight must be within [0, 3]");
            }
            p.shapeWeight = shapeWeight;
        }
        if (extraClearanceM != null) {
            if (extraClearanceM < 0 || extraClearanceM > 10) {
                throw new IllegalArgumentException("extra_clearance_m must be within [0, 10]");
            }
            p.extraClearanceM = extraClearanceM;
        }
        if (minCrossingAngleDeg != null) {
            if (minCrossingAngleDeg < 45 || minCrossingAngleDeg > 90) {
                throw new IllegalArgumentException("min_crossing_angle_deg must be within [45, 90]");
            }
            p.minCrossingAngleDeg = minCrossingAngleDeg;
        }
        if (turnAngles != null) {
            p.turnStepDeg = turnStep(turnAngles);
        }
        if (turnAnglesStrict != null) {
            p.turnStepStrict = turnAnglesStrict;
        }
        if (jointConnection != null) {
            p.jointConnection = jointConnection;
        }
        if (groupRebuild != null) {
            p.groupRebuild = groupRebuild;
        }
        if (buildingEntry != null) {
            try {
                p.rules.entry = RuleOptions.Entry.valueOf(buildingEntry.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("building_entry must be one of NEAREST, NEAR, ANY, OUTLINE");
            }
        }
        if (entrySlackM != null) {
            if (entrySlackM < 0 || entrySlackM > 50) {
                throw new IllegalArgumentException("entry_slack_m must be within [0, 50]");
            }
            p.rules.entrySlackM = entrySlackM;
        }
        if (throughBuildings != null) {
            try {
                p.rules.throughBuildings = RuleOptions.ThroughBuildings.valueOf(throughBuildings.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("through_buildings must be one of NONE, EXISTING, ANY");
            }
        }
        if (buildingCrossingK != null) {
            if (buildingCrossingK < 1 || buildingCrossingK > 3) {
                throw new IllegalArgumentException("building_crossing_k must be within [1, 3]");
            }
            p.rules.buildingCrossingK = buildingCrossingK;
        }
        if (throughOwnParts != null) {
            p.rules.throughOwnParts = throughOwnParts;
        }
        if (tieInInClearance != null) {
            p.rules.tieInInClearance = tieInInClearance;
        }
        if (dataBoundaryM != null) {
            if (dataBoundaryM < 0 || dataBoundaryM > 50) {
                throw new IllegalArgumentException("data_boundary_m must be within [0, 50]");
            }
            p.dataBoundaryM = dataBoundaryM;
        }
        if (entryLegM != null) {
            if (entryLegM < 0 || entryLegM > 20) {
                throw new IllegalArgumentException("entry_leg_m must be within [0, 20]");
            }
            p.entryLegM = entryLegM;
        }
        return p;
    }

    /** Values of {@code turn_angles} in the order of {@link PlanParams#TURN_STEPS}. */
    public static final String[] TURN_ANGLES = {"ANY", "30_60_90", "45_90", "90"};

    /** Step of the turns of a {@code turn_angles} value, degrees (0 — any angle). */
    public static int turnStep(String value) {
        for (int i = 0; i < TURN_ANGLES.length; i++) {
            if (TURN_ANGLES[i].equalsIgnoreCase(value.trim())) {
                return PlanParams.TURN_STEPS[i];
            }
        }
        throw new IllegalArgumentException("turn_angles must be one of ANY, 30_60_90, 45_90, 90");
    }

    /** {@code turn_angles} value of a step of the turns. */
    public static String turnAnglesName(int step) {
        for (int i = 0; i < TURN_ANGLES.length; i++) {
            if (PlanParams.TURN_STEPS[i] == step) {
                return TURN_ANGLES[i];
            }
        }
        throw new IllegalArgumentException("Unsupported step of turns: " + step);
    }

    /** The effective values of a calculation, as stored with the run. */
    public static RunParams of(PlanParams p) {
        RunParams r = new RunParams();
        r.existingFlow = p.rules.existingFlow.name();
        r.existingFlowShare = p.rules.existingFlowShare;
        r.turnPenalty = p.turnPenaltyScore;
        r.oddJointTurns = p.oddJointTurns;
        r.shapeWeight = p.shapeWeight;
        r.extraClearanceM = p.extraClearanceM;
        r.minCrossingAngleDeg = p.minCrossingAngleDeg > 0 ? p.minCrossingAngleDeg : 45;
        r.turnAngles = turnAnglesName(p.turnStepDeg);
        r.turnAnglesStrict = p.turnStepStrict;
        r.jointConnection = p.jointConnection;
        r.groupRebuild = p.groupRebuild;
        r.buildingEntry = p.rules.entry.name();
        r.entrySlackM = p.rules.entrySlackM;
        r.entryLegM = p.entryLegM;
        r.dataBoundaryM = p.dataBoundaryM;
        r.throughBuildings = p.rules.throughBuildings.name();
        r.buildingCrossingK = p.rules.buildingCrossingK;
        r.throughOwnParts = p.rules.throughOwnParts;
        r.tieInInClearance = p.rules.tieInInClearance;
        return r;
    }
}
