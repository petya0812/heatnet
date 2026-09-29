package ru.lct.heatnet.plan;

/** Why a connection point was left without a route (reported in the API and on the map, not in the output file). */
public final class Unconnected {

    public enum Reason {
        NO_CONNECTION_POINT("у ОКС нет точки подключения во входных данных"),
        FLOW_EXCEEDS_MAX_DN("расход больше пропускной способности наибольшего ДУ"),
        NO_NETWORK("нет существующей сети, связанной с источником"),
        SEARCH_LIMIT("поиск трассы остановлен по лимиту перебора"),
        NO_ROUTE("нет допустимой трассы в радиусе поиска: мешают ограничения"),
        LENGTH_LIMIT("трасса не укладывается в предельную длину ДУ"),
        CANCELLED("расчёт остановлен");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public final String oksId;
    public final double flowTph;
    public final Reason reason;
    public final String detail;

    public Unconnected(String oksId, double flowTph, Reason reason, String detail) {
        this.oksId = oksId;
        this.flowTph = flowTph;
        this.reason = reason;
        this.detail = detail;
    }

    /** Mutable holder filled by the route search when it gives up. */
    public static final class Failure {
        public Reason reason = Reason.NO_ROUTE;
        public String detail;

        void set(Reason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }
    }
}
