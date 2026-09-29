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

    @Schema(description = "Совместное подключение нескольких ОКС через одну врезку (по умолчанию true); при false — "
            + "каждый ОКС своей трассой и своей врезкой", defaultValue = "true")
    @JsonProperty("joint_connection")
    public Boolean jointConnection;

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
        r.extraClearanceM = p.extraClearanceM;
        r.minCrossingAngleDeg = p.minCrossingAngleDeg > 0 ? p.minCrossingAngleDeg : 45;
        r.turnAngles = turnAnglesName(p.turnStepDeg);
        r.turnAnglesStrict = p.turnStepStrict;
        r.jointConnection = p.jointConnection;
        return r;
    }
}
