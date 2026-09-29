package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.PipeSpec;

import java.util.List;

/**
 * A found route for one OKS. Coordinates run from the tie-in (upstream) to the connection point;
 * {@code edges.get(i)} describes the straight edge {@code coords[i] → coords[i+1]}.
 */
public final class Route {

    public final InputModel.Oks oks;
    public final PipeSpec pipe;
    public final TieOption tie;
    public final List<Coordinate> coords;
    public final List<RoutingContext.EdgeEval> edges;
    public final double score;
    /**
     * Goals the search reached with a valid last edge, cheapest first, the chosen one included — why this tie-in
     * (filled only when the construction journal is recorded, {@link PlanParams#journal}).
     */
    public final List<Alternative> alternatives = new java.util.ArrayList<>();

    /** A goal reached by the search with the score of the best route found to it. */
    public static final class Alternative {
        public final TieOption tie;
        public final double score;
        /**
         * The search stopped before building a route to this goal: {@link #score} is its lower bound
         * (the straight way from a turning point plus the cost of the tie-in), the real one is not smaller.
         */
        public final boolean estimate;

        Alternative(TieOption tie, double score, boolean estimate) {
            this.tie = tie;
            this.score = score;
            this.estimate = estimate;
        }
    }

    Route(InputModel.Oks oks, PipeSpec pipe, TieOption tie, List<Coordinate> coords,
          List<RoutingContext.EdgeEval> edges, double score) {
        this.oks = oks;
        this.pipe = pipe;
        this.tie = tie;
        this.coords = coords;
        this.edges = edges;
        this.score = score;
    }

    /** The same route with a last straight piece to the connection point inside the building. */
    Route withEnd(Coordinate end) {
        List<Coordinate> c = new java.util.ArrayList<>(coords);
        List<RoutingContext.EdgeEval> e = new java.util.ArrayList<>(edges);
        double len = c.get(c.size() - 1).distance(end);
        c.add(new Coordinate(end));
        e.add(new RoutingContext.EdgeEval(true, len, 0, java.util.Collections.<RoutingContext.Interval>emptyList(), null));
        Route r = new Route(oks, pipe, tie, c, e, score);
        r.alternatives.addAll(alternatives);
        return r;
    }

    public double length() {
        double l = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            l += coords.get(i).distance(coords.get(i + 1));
        }
        return l;
    }

    public int turns() {
        return Math.max(0, coords.size() - 2);
    }
}
