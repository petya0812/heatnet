package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds connection variants (ТЗ 2.8, ТП 6):
 * <ul>
 *     <li>{@code joint} — Steiner-tree heuristic: OKS are added one by one, each either ties in to the existing
 *     network or joins the new network of the variant in a branching chamber, whichever is cheaper in score;
 *     then local search re-connects every OKS while the score decreases;</li>
 *     <li>{@code separate} — every OKS has its own tie-in (baseline);</li>
 *     <li>alternatives — other connection orders, or joint without the main tie-in of the best variant.</li>
 * </ul>
 * Up to three structurally different variants (different tie-ins or grouping of OKS) are returned, ranked by score.
 */
public final class Planner {

    public static final String STRATEGY_SEPARATE = "separate";
    public static final String STRATEGY_JOINT = "joint";
    /** Pieces shorter than this are merged into a neighbour (no slivers between special sections). */
    static final double MIN_PIECE_M = 0.04;
    /** Variant id of an assembly made only to score a candidate. */
    private static final String TRIAL = "x";

    private final ReferenceData ref;
    private final PlanParams params;
    private Progress progress = (stage, done, total) -> { };
    private final java.util.concurrent.atomic.AtomicInteger connects = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int connectsTotal;
    private volatile String stage = "";

    public Planner(ReferenceData ref, PlanParams params) {
        this.ref = ref;
        this.params = params;
    }

    /** Progress of a calculation: stage and connection attempts done of an estimated total. */
    public interface Progress {
        void update(String stage, int done, int total);
    }

    public Planner withProgress(Progress progress) {
        this.progress = progress;
        return this;
    }

    /**
     * Steps of the construction as they happen (with {@link PlanParams#journal}): {@code build} names the build
     * ({@code separate}, {@code joint-near}, {@code joint-far}, {@code joint-flow}, {@code joint-alt}); the listener
     * is called from the planning threads. Besides the journal events it gets {@code candidate} events with the
     * score of every finished build.
     */
    public interface JournalListener {
        void event(String build, Map<String, Object> event);
    }

    private JournalListener journalListener = (build, event) -> { };

    public Planner withJournal(JournalListener listener) {
        this.journalListener = listener;
        return this;
    }

    private void stage(String s, int total) {
        stage = s;
        connectsTotal = total;
        connects.set(0);
        progress.update(s, 0, total);
    }

    private void tick() {
        progress.update(stage, connects.incrementAndGet(), connectsTotal);
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Planning cancelled");
        }
    }

    /** A built network with its unconnected OKS and a label; score computed by a trial assembly. */
    private final class Candidate {
        final Draft draft;
        final Map<String, Unconnected> unconnected;
        final String strategy;
        String explanation;
        final double score;
        /** Construction journal (null unless recorded) and the name of the build it belongs to. */
        final JournalRecorder.Log log;
        final String build;

        Candidate(Draft draft, Map<String, Unconnected> unconnected, String strategy, String explanation,
                  InputModel model, JournalRecorder.Log log, String build) {
            this(draft, unconnected, strategy, explanation,
                    assemble(model, draft, unconnected, TRIAL, new Diagnostics()).summary.score, log, build);
        }

        Candidate(Draft draft, Map<String, Unconnected> unconnected, String strategy, String explanation,
                  double score, JournalRecorder.Log log, String build) {
            this.draft = draft;
            this.unconnected = unconnected;
            this.strategy = strategy;
            this.explanation = explanation;
            this.score = score;
            this.log = log;
            this.build = build;
        }

        /** Tells the journal listener that this build is finished (live view of a calculation). */
        Candidate announce() {
            if (log != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", "candidate");
                e.put("strategy", strategy);
                e.put("explanation", explanation);
                e.put("score", score);
                e.put("unconnected", unconnected.size());
                journalListener.event(build, e);
            }
            return this;
        }
    }

    public List<Variant> plan(InputModel model, ObstacleSource obstacles, Diagnostics diag) {
        long t0 = System.currentTimeMillis();
        RouteFinder finder = new RouteFinder(ref, params, model, obstacles);
        JournalRecorder recorder = params.journal ? new JournalRecorder(ref, model) : null;
        List<InputModel.Oks> near = new ArrayList<>(model.getOks());
        near.sort(Comparator.comparingDouble(o -> model.distanceToNetwork(o.connectionPoint.getCoordinate())));
        List<InputModel.Oks> far = new ArrayList<>(near);
        Collections.reverse(far);

        List<InputModel.Oks> byFlow = new ArrayList<>(near);
        byFlow.sort(Comparator.comparingDouble((InputModel.Oks o) -> -o.flowTph));
        Diagnostics quiet = new Diagnostics();
        int n = model.getOks().size();
        stage("построение вариантов", 4 * n);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(params.threads);
        List<Candidate> cands = new ArrayList<>();
        Candidate sep;
        Candidate improved;
        try {
            // independent builds in parallel: the separate baseline and three orders of joint connection
            java.util.concurrent.Future<Candidate> fSep = pool.submit(() -> build(model, finder, recorder, near, false,
                    diag, STRATEGY_SEPARATE, "Раздельное подключение: каждый ОКС своей трассой и своей врезкой",
                    "separate"));
            List<java.util.concurrent.Future<Candidate>> fJoint = new ArrayList<>();
            if (params.jointConnection) {
                fJoint.add(pool.submit(() -> build(model, finder, recorder, near, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются от ближних к сети", "joint-near")));
                fJoint.add(pool.submit(() -> build(model, finder, recorder, far, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются от дальних от сети", "joint-far")));
                fJoint.add(pool.submit(() -> build(model, finder, recorder, byFlow, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются по убыванию расхода", "joint-flow")));
            }
            sep = fSep.get();
            cands.add(sep);
            List<Candidate> joints = new ArrayList<>();
            for (java.util.concurrent.Future<Candidate> f : fJoint) {
                joints.add(f.get());
            }
            // local search from every start: the result depends on the start more than on the start's own score
            stage("перестройка совместного подключения", 3 * n * params.localSearchPasses);
            List<java.util.concurrent.Future<Candidate>> fImp = new ArrayList<>();
            for (Candidate j : joints) {
                fImp.add(pool.submit(() -> improve(model, finder, recorder, j, quiet)));
            }
            List<Candidate> improvedAll = new ArrayList<>();
            for (java.util.concurrent.Future<Candidate> f : fImp) {
                improvedAll.add(f.get());
            }
            improvedAll.sort(Comparator.comparingDouble(c -> c.score));
            improved = improvedAll.isEmpty() ? sep : improvedAll.get(0);
            cands.addAll(improvedAll);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Planning cancelled");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.util.concurrent.CancellationException) {
                throw (java.util.concurrent.CancellationException) e.getCause();
            }
            throw new IllegalStateException("Planning failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
        if (params.jointConnection && distinct(cands).size() < 3) {
            Set<String> banned = mainTieObjects(improved.draft);
            if (!banned.isEmpty()) {
                stage("альтернативный вариант", n);
                finder.setBannedTieObjects(banned);
                Candidate alt = build(model, finder, recorder, near, true, quiet, STRATEGY_JOINT + "-alt",
                        "Совместное подключение без врезки в " + String.join(", ", banned)
                                + " (основная врезка лучшего варианта)",
                        "joint-alt");
                finder.setBannedTieObjects(Collections.<String>emptySet());
                cands.add(alt);
            }
        }
        List<Candidate> chosen = select(cands, sep);
        stage("сборка вариантов", 0);
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Candidate c = chosen.get(i);
            Variant v = assemble(model, c.draft, c.unconnected, String.valueOf(i + 1),
                    i == 0 ? diag : new Diagnostics());
            v.metrics.strategy = c.strategy;
            v.metrics.explanation = describe(c);
            v.metrics.approach = c.explanation;
            if (c.log != null) {
                v.journal = new ArrayList<>(c.log.events);
                Map<String, Object> fin = new LinkedHashMap<>();
                fin.put("seq", v.journal.size());
                fin.put("type", "finish");
                fin.put("build", c.build);
                fin.put("score", v.summary.score);
                fin.put("calculated_cost", v.summary.calculatedCost);
                fin.put("unconnected", v.unconnected.size());
                v.journal.add(fin);
            }
            variants.add(v);
        }
        rank(variants);
        long ms = System.currentTimeMillis() - t0;
        for (Variant v : variants) {
            v.metrics.computeMillis = ms;
        }
        for (Diagnostics.Issue i : quiet.getIssues()) {
            if (i.getCode().startsWith("route.search_limit") || i.getCode().startsWith("route.vertices")) {
                diag.add(i.getSeverity(), i.getCode(), i.getFeatureId(), i.getMessage());
            }
        }
        return variants;
    }

    /** Ranks variants by score (appendix 6): smaller score — higher rank. */
    public static void rank(List<Variant> variants) {
        variants.sort(Comparator.comparingDouble(x -> x.summary.score));
        for (int i = 0; i < variants.size(); i++) {
            variants.get(i).summary.rank = i + 1;
        }
    }

    private static List<Candidate> distinct(List<Candidate> cands) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Candidate> sorted = new ArrayList<>(cands);
        sorted.sort(Comparator.comparingDouble(c -> c.score));
        for (Candidate c : sorted) {
            if (seen.add(signature(c))) {
                out.add(c);
            }
        }
        return out;
    }

    static String signature(Candidate c) {
        List<String> un = new ArrayList<>(c.unconnected.keySet());
        Collections.sort(un);
        return c.draft.signature() + "|" + String.join(",", un);
    }

    /** Best candidate first; the separate baseline is kept among the variants when it is distinct. */
    private static List<Candidate> select(List<Candidate> cands, Candidate sep) {
        List<Candidate> d = distinct(cands);
        List<Candidate> out = new ArrayList<>(d.subList(0, Math.min(3, d.size())));
        if (!out.contains(sep) && d.contains(sep)) {
            out.set(out.size() - 1, sep);
        }
        return out;
    }

    private static Set<String> mainTieObjects(Draft d) {
        Draft.Node best = null;
        double bestFlow = -1;
        for (Draft.Node r : d.roots) {
            double f = Draft.treeFlow(r);
            if (f > bestFlow) {
                bestFlow = f;
                best = r;
            }
        }
        Set<String> out = new HashSet<>();
        if (best != null) {
            out.add(best.tie.existingObjectId());
        }
        return out;
    }

    private String describe(Candidate c) {
        int trees = c.draft.roots.size();
        int junctions = 0;
        for (Draft.Node n : c.draft.nodes.values()) {
            if (n.kind == Draft.Kind.JUNCTION) {
                junctions++;
            }
        }
        return String.format("%s. Частей сети (врезок): %d, камер-разветвлений: %d, ОКС без трассы: %d",
                c.explanation, trees, junctions, c.unconnected.size());
    }

    // ------------------------------------------------------------------ building a network

    private Candidate build(InputModel model, RouteFinder finder, JournalRecorder recorder, List<InputModel.Oks> order,
                            boolean joint, Diagnostics diag, String strategy, String explanation, String build) {
        Draft d = new Draft();
        Map<String, Unconnected> unconnected = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : model.getUnroutableOks().entrySet()) {
            unconnected.put(e.getKey(), new Unconnected(e.getKey(), e.getValue(),
                    Unconnected.Reason.NO_CONNECTION_POINT, null));
        }
        JournalRecorder.Log log = recorder == null ? null : recorder.start(strategy, explanation, order);
        if (log != null) {
            journalListener.event(build, log.events.get(0));
        }
        for (InputModel.Oks oks : order) {
            Unconnected.Failure fail = new Unconnected.Failure();
            Route[] used = new Route[1];
            Draft next = connect(model, finder, d, oks, joint, diag, fail, used);
            tick();
            if (next == null) {
                Unconnected u = new Unconnected(oks.id, oks.flowTph, fail.reason, fail.detail);
                unconnected.put(oks.id, u);
                diag.warning("route.not_found", oks.id, "No automatic route found for OKS: " + fail.reason);
                if (log != null) {
                    journalListener.event(build, recorder.unconnected(log, oks, u));
                }
            } else {
                d = next;
                if (log != null) {
                    journalListener.event(build, recorder.connected(log, "connect", oks, used[0], d));
                }
            }
        }
        // A connection point is never dropped because it is expensive: leaving it out is allowed only when no
        // admissible route exists (appendix 2.5, clarification 15).
        return new Candidate(d, unconnected, strategy, explanation, model, log, build).announce();
    }

    /** Local search: take every OKS out and connect it again while the variant score decreases. */
    private Candidate improve(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                              Diagnostics diag) {
        Candidate cur = c;
        for (int pass = 0; pass < params.localSearchPasses; pass++) {
            boolean better = false;
            List<InputModel.Oks> all = new ArrayList<>(model.getOks());
            for (InputModel.Oks oks : all) {
                Draft trial = cur.draft.copy();
                trial.remove(oks);
                if (!trial.recompute(ref) || !tieRuleHolds(model, trial)) {
                    continue;
                }
                Route[] used = new Route[1];
                Draft next = connect(model, finder, trial, oks, true, diag, new Unconnected.Failure(), used);
                tick();
                Map<String, Unconnected> un = new LinkedHashMap<>(cur.unconnected);
                if (next == null) {
                    if (!cur.unconnected.containsKey(oks.id)) {
                        continue;
                    }
                    next = trial;
                    used[0] = null;
                } else {
                    un.remove(oks.id);
                }
                double score = assemble(model, next, un, TRIAL, new Diagnostics()).summary.score;
                if (score < cur.score - 1e-9) {
                    JournalRecorder.Log log = null;
                    if (cur.log != null) {
                        log = cur.log.copy();
                        journalListener.event(c.build, recorder.connected(log, "rebuild", oks, used[0], next));
                    }
                    cur = new Candidate(next, un, cur.strategy, cur.explanation, score, log, c.build);
                    better = true;
                }
            }
            if (!better) {
                break;
            }
        }
        if (cur != c) {
            cur.explanation = c.explanation + "; схема улучшена перестройкой";
            cur.announce();
        }
        return cur;
    }

    /**
     * Connects one OKS to a copy of the draft; returns the new draft or null. The route is searched with the
     * minimal DN for the OKS flow; if flows, DN and the length limit of the whole tree cannot be satisfied with
     * that geometry, the route is searched again for the larger DN.
     */
    private Draft connect(InputModel model, RouteFinder finder, Draft d, InputModel.Oks oks, boolean joint,
                          Diagnostics diag, Unconnected.Failure fail, Route[] used) {
        PipeSpec pipe = ref.minPipeForFlow(oks.flowTph);
        if (pipe == null) {
            diag.error("oks.flow_exceeds_max_dn", oks.id, "Flow exceeds the capacity of the largest DN");
            fail.set(Unconnected.Reason.FLOW_EXCEEDS_MAX_DN, String.format("%.1f т/ч", oks.flowTph));
            return null;
        }
        d.recompute(ref);
        boolean useAttach = joint && !d.edges.isEmpty();
        for (int attempt = 0; attempt < 6; attempt++) {
            NetworkState state = NetworkState.fromDraft(model, ref, d);
            List<RouteFinder.Built> built = new ArrayList<>();
            for (Draft.Edge e : d.edges.values()) {
                built.add(new RouteFinder.Built(line(e.coords), ref.pipe(e.dn), "e" + e.id));
            }
            DraftAttach attach = useAttach ? new DraftAttach(d, state, model) : null;
            Route r = finder.find(oks, pipe, built, state, attach, diag, fail);
            if (r == null) {
                return null;
            }
            if (oks.connectionInside()) {
                r = r.withEnd(oks.connectionPoint.getCoordinate());
            }
            Draft trial = d.copy();
            Draft.Node cp = trial.insert(r);
            if (!trial.recompute(ref)) {
                if (r.tie.isAttach()) {
                    useAttach = false;
                    continue;
                }
                fail.set(Unconnected.Reason.LENGTH_LIMIT, String.format("трасса %.0f м, ДУ %d, предел %.0f м",
                        r.length(), pipe.getDn(), pipe.getMaxLengthM()));
                return null;
            }
            Draft.Edge bad = recheck(finder, trial);
            if (bad == null && trial.hasCrossings()) {
                // the new route crosses one that is already there (possible after a re-check for a larger DN)
                fail.set(Unconnected.Reason.NO_ROUTE, "трасса пересекает уже построенную");
                return null;
            }
            if (bad == null && trial.hasSharpTurns(ref)) {
                fail.set(Unconnected.Reason.NO_ROUTE, "поворот трассы острее 90°");
                return null;
            }
            if (bad == null && !tieRuleHolds(model, trial)) {
                // the tie-in of this draft would have to be made in a chamber that has a free branch
                fail.set(Unconnected.Reason.NO_ROUTE, "врезка ближе 10 м к камере со свободным местом");
                return null;
            }
            if (bad == null) {
                used[0] = r;
                if (cp.up.dn > pipe.getDn() && !r.tie.isAttach()) {
                    diag.warning("route.dn_increased_for_length", oks.id, String.format(
                            "Route %.1f m > limit %.0f m of DN %d, DN increased to %d", r.length(),
                            pipe.getMaxLengthM(), pipe.getDn(), cp.up.dn));
                }
                return trial;
            }
            // the tree needs a larger DN than the route was checked for: search again with that DN,
            // or without joining the new network if the shared part is the problem
            int newDn = cp.up.dn;
            if (bad == cp.up && newDn > pipe.getDn()) {
                pipe = ref.pipe(newDn);
            } else if (r.tie.isAttach()) {
                useAttach = false;
            } else {
                fail.set(Unconnected.Reason.NO_ROUTE, "трасса не проходит по отступам с увеличенным ДУ " + newDn);
                return null;
            }
        }
        fail.set(Unconnected.Reason.NO_ROUTE, "не удалось согласовать ДУ и отступы за 6 попыток");
        return null;
    }

    /**
     * R-TIE-1 for the whole variant: a tie-in into a segment is only allowed when no existing chamber within
     * {@code tieInChamberRadiusM} still has a free branch. Taking an OKS out during the local search can free a
     * branch of a chamber, so the rule is re-checked for every draft, not only when the tie-in is chosen.
     */
    private boolean tieRuleHolds(InputModel model, Draft d) {
        Map<String, Integer> added = new HashMap<>();
        for (Draft.Node r : d.roots) {
            if (r.tie.chamber != null) {
                added.merge(r.tie.chamber.id, r.down.size(), Integer::sum);
            }
        }
        for (Draft.Node r : d.roots) {
            if (r.tie.chamber != null) {
                continue;
            }
            for (InputModel.Chamber c : model.chambersNear(r.c, ref.getTieInChamberRadiusM())) {
                if (c.existingDegree + added.getOrDefault(c.id, 0) + 1 <= ref.getMaxChamberDegree()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Re-checks edges whose DN exceeds the DN they were routed for; returns the first failing edge or null. */
    private Draft.Edge recheck(RouteFinder finder, Draft d) {
        for (Draft.Edge e : d.edges.values()) {
            if (e.dn <= e.checkedDn) {
                continue;
            }
            InputModel.Oks own = e.down.kind == Draft.Kind.CP ? e.down.oks : null;
            if (!finder.checkPolyline(e.coords, ref.pipe(e.dn), own, e.up.kind == Draft.Kind.TIE)) {
                return e;
            }
            e.checkedDn = e.dn;
        }
        return null;
    }

    /** A vertex closer than this to the previous one is dropped: such a piece has no direction of its own. */
    static final double MIN_VERTEX_GAP_M = 0.05;

    static LineString line(List<Coordinate> coords) {
        List<Coordinate> clean = new ArrayList<>(coords.size());
        for (int i = 0; i < coords.size(); i++) {
            Coordinate c = coords.get(i);
            boolean last = i == coords.size() - 1;
            if (clean.isEmpty() || c.distance(clean.get(clean.size() - 1)) >= MIN_VERTEX_GAP_M) {
                clean.add(c);
            } else if (last) {
                // the end of the piece is a node of the network: keep it and drop the vertex before it
                clean.set(clean.size() - 1, c);
            }
        }
        if (clean.size() < 2) {
            clean = coords;
        }
        return Crs.UTM_FACTORY.createLineString(clean.toArray(new Coordinate[0]));
    }

    // ------------------------------------------------------------------ joining the new network

    /** Attach candidates on the draft with an estimate of the full marginal cost of joining there. */
    private final class DraftAttach implements RouteFinder.AttachProvider {
        private final Draft d;
        private final NetworkState state;
        private final InputModel model;
        private final Map<String, Double> chainMemo = new HashMap<>();
        private final Map<Integer, Double> upMemo = new HashMap<>();

        DraftAttach(Draft d, NetworkState state, InputModel model) {
            this.d = d;
            this.state = state;
            this.model = model;
        }

        @Override
        public double distance(Coordinate v) {
            double best = Double.POSITIVE_INFINITY;
            for (Draft.Edge e : d.edges.values()) {
                for (int i = 0; i + 1 < e.coords.size(); i++) {
                    best = Math.min(best, Distance.pointToSegment(v, e.coords.get(i), e.coords.get(i + 1)));
                }
            }
            return best;
        }

        @Override
        public List<TieOption> attachOptions(Coordinate v, PipeSpec pipe, double flow) {
            List<TieOption> out = new ArrayList<>();
            for (Draft.Edge e : d.edges.values()) {
                double len = e.length();
                double bestD = Double.POSITIVE_INFINITY;
                double bestPos = -1;
                Coordinate bestQ = null;
                double acc = 0;
                for (int i = 0; i + 1 < e.coords.size(); i++) {
                    Coordinate a = e.coords.get(i);
                    Coordinate b = e.coords.get(i + 1);
                    double l = a.distance(b);
                    double f = l > 0 ? ((v.x - a.x) * (b.x - a.x) + (v.y - a.y) * (b.y - a.y)) / (l * l) : 0;
                    f = Math.max(0, Math.min(1, f));
                    Coordinate q = new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
                    double dist = q.distance(v);
                    if (dist < bestD) {
                        bestD = dist;
                        bestPos = acc + f * l;
                        bestQ = q;
                    }
                    acc += l;
                }
                if (bestQ == null || bestD > params.attachSearchM) {
                    continue;
                }
                // a junction next to a turning point goes to the turning point itself (no tiny pieces)
                double vpos = 0;
                for (int i = 1; i + 1 < e.coords.size(); i++) {
                    vpos += e.coords.get(i - 1).distance(e.coords.get(i));
                    if (Math.abs(vpos - bestPos) < 1.0) {
                        bestPos = vpos;
                        bestQ = new Coordinate(e.coords.get(i));
                        break;
                    }
                }
                double margin = params.attachEndMarginM;
                if (bestPos < margin || bestPos > len - margin || insideSpecial(e, bestPos)) {
                    continue;
                }
                TieOption o = new TieOption(bestQ, e.id, bestPos, 0, edgeScore(e, bestPos, flow, pipe));
                o.attachDn = Math.max(grown(e, flow), pipe.getDn());
                out.add(o);
            }
            for (Draft.Node n : d.nodes.values()) {
                if (n.kind == Draft.Kind.JUNCTION && n.degree() < ref.getMaxChamberDegree()
                        && n.c.distance(v) <= params.attachSearchM) {
                    TieOption o = new TieOption(n.c, 0, 0, n.id, junctionScore(n, flow, pipe));
                    int dn = Math.max(grown(n.up, flow), pipe.getDn());
                    for (Draft.Edge e : n.down) {
                        dn = Math.max(dn, e.dn);
                    }
                    o.attachDn = dn;
                    out.add(o);
                }
            }
            return out;
        }

        private boolean insideSpecial(Draft.Edge e, double pos) {
            for (RoutingContext.Interval iv : e.intervals) {
                if (pos > iv.from - 0.5 && pos < iv.to + 0.5) {
                    return true;
                }
            }
            return false;
        }

        private int grown(Draft.Edge e, double flow) {
            PipeSpec p = ref.minPipeForFlow(e.flow + flow);
            return Math.max(e.dn, p == null ? ref.getPipes().get(ref.getPipes().size() - 1).getDn() : p.getDn());
        }

        private double pipeDelta(Draft.Edge e, int newDn, double from, double to) {
            if (newDn == e.dn) {
                return 0;
            }
            return ref.scoreOfCost((ref.pipe(newDn).getNewCostPerM() - ref.pipe(e.dn).getNewCostPerM())
                    * e.weightedLength(from, to));
        }

        /** New junction on an edge: chamber + growth of the upstream part of the edge + everything above. */
        double edgeScore(Draft.Edge e, double pos, double flow, PipeSpec pipe) {
            int newDn = grown(e, flow);
            double s = ref.scoreOfCost(ref.chamberCost(Math.max(newDn, pipe.getDn())));
            s += pipeDelta(e, newDn, 0, pos);
            return s + above(e.up, flow, newDn);
        }

        double junctionScore(Draft.Node j, double flow, PipeSpec pipe) {
            int oldMax = pipe.getDn();
            int newMax = pipe.getDn();
            for (Draft.Edge e : j.down) {
                oldMax = Math.max(oldMax, e.dn);
                newMax = Math.max(newMax, e.dn);
            }
            int upNew = grown(j.up, flow);
            oldMax = Math.max(oldMax, j.up.dn);
            newMax = Math.max(newMax, upNew);
            double s = ref.scoreOfCost(ref.chamberCost(newMax) - ref.chamberCost(oldMax));
            s += pipeDelta(j.up, upNew, 0, j.up.length());
            return s + above(j.up.up, flow, upNew);
        }

        /** Marginal cost above a node when {@code flow} is added below it; {@code childDn} — new DN of the edge below. */
        private double above(Draft.Node n, double flow, int childDn) {
            if (n.kind == Draft.Kind.TIE) {
                return tieDelta(n, flow, childDn);
            }
            Double memo = upMemo.get(n.id);
            if (memo != null) {
                return memo;
            }
            Draft.Edge up = n.up;
            int newDn = grown(up, flow);
            int oldMax = up.dn;
            int newMax = newDn;
            for (Draft.Edge e : n.down) {
                oldMax = Math.max(oldMax, e.dn);
                newMax = Math.max(newMax, e.dn);
            }
            newMax = Math.max(newMax, childDn);
            double s = ref.scoreOfCost(ref.chamberCost(newMax) - ref.chamberCost(oldMax));
            s += pipeDelta(up, newDn, 0, up.length());
            s += above(up.up, flow, newDn);
            upMemo.put(n.id, s);
            return s;
        }

        private double tieDelta(Draft.Node root, double flow, int rootEdgeDn) {
            TieOption tie = root.tie;
            int oldRoot = 0;
            for (Draft.Edge e : root.down) {
                oldRoot = Math.max(oldRoot, e.dn);
            }
            int newRoot = Math.max(oldRoot, rootEdgeDn);
            double s = 0;
            if (tie.segment != null) {
                // a new chamber in the point of the existing segment: its DN follows the adjoining segments
                InputModel.Segment seg = tie.segment;
                int before = Math.max(oldRoot, seg.dn);
                int after = Math.max(newRoot, seg.dn);
                s += ref.scoreOfCost(ref.chamberCost(after) - ref.chamberCost(before));
            }
            // an existing chamber is not rebuilt, and the tie-in cost does not depend on the DN
            return s;
        }
    }

    // ------------------------------------------------------------------ assembly

    private static final class Piece {
        final List<Coordinate> coords = new ArrayList<>();
        final boolean special;
        final double k;

        Piece(boolean special, double k) {
            this.special = special;
            this.k = k;
        }
    }

    Variant assemble(InputModel model, Draft d, Map<String, Unconnected> unconnected, String variantId,
                     Diagnostics diag) {
        d.recompute(ref);
        Variant v = new Variant();
        v.variantId = variantId;
        String p = "v" + variantId + "-";
        int[] n = new int[4]; // seg, node, tie, chamber
        List<ReconstructionCalculator.Load> loads = new ArrayList<>();
        Map<String, Integer> chamberNewDn = new LinkedHashMap<>();
        List<Object[]> tieChambers = new ArrayList<>(); // tie option, max new DN, chamber
        Map<String, Variant.NewChamber> tieChamberAt = new LinkedHashMap<>(); // one chamber per tie-in point
        Map<Integer, String> nodeIds = new HashMap<>();
        List<Variant.NewChamber> junctionChambers = new ArrayList<>();
        Map<Variant.NewChamber, Draft.Node> junctionOf = new HashMap<>();
        double sumDetour = 0;
        double maxDetour = 0;
        int turns = 0;
        int smallTurns = 0;
        int sharpTurns = 0;
        double minStraight = Double.POSITIVE_INFINITY;
        int routes = 0;
        Map<Integer, List<String>> edgeSegments = new HashMap<>();
        Map<Integer, Double> edgeCosts = new HashMap<>();

        for (Draft.Node root : d.roots) {
            TieOption tie = root.tie;
            double flow = Draft.treeFlow(root);
            int rootDn = 0;
            for (Draft.Edge e : root.down) {
                rootDn = Math.max(rootDn, e.dn);
            }
            Variant.TieIn ti = new Variant.TieIn();
            ti.id = p + "tie-" + (++n[2]);
            ti.point = Crs.UTM_FACTORY.createPoint(root.c);
            ti.existingObjectId = tie.existingObjectId();
            ti.existingObjectType = tie.chamber != null ? "heat_chamber" : "heat_network";
            ti.existingDn = tie.chamber != null ? tie.chamber.dn : tie.segment.dn;
            ti.requiredDn = rootDn;
            ti.flowTph = flow;
            ti.positionM = tie.t;
            if (tie.chamber != null) {
                // every new segment that ends in an existing chamber is one tie-in of 5 mln (appendix 3.2)
                ti.cost = ref.getTieInCost() * root.down.size();
                ti.existingChamber = true;
                // the segments start in the existing chamber of the input
                nodeIds.put(root.id, tie.chamber.id);
                loads.add(new ReconstructionCalculator.Load(tie.chamber.id, true, 0, flow));
            } else {
                // a new chamber right in the chosen point of the existing segment; its cost already includes
                // the attachment, no separate tie-in is charged (appendix 2.4, 3.2)
                ti.cost = 0;
                loads.add(new ReconstructionCalculator.Load(tie.segment.id, false, tie.t, flow));
                // several parts of the new network may tie into the same point: that is one chamber
                String key = String.format("%.2f,%.2f", root.c.x, root.c.y);
                Variant.NewChamber ch = tieChamberAt.get(key);
                if (ch == null) {
                    ch = new Variant.NewChamber();
                    ch.id = p + "ch-" + (++n[3]);
                    ch.point = ti.point;
                    v.chambers.add(ch);
                    tieChamberAt.put(key, ch);
                }
                nodeIds.put(root.id, ch.id);
                tieChambers.add(new Object[]{tie, rootDn, ch});
            }
            v.tieIns.add(ti);
            List<Draft.Edge> stack = new ArrayList<>(root.down);
            while (!stack.isEmpty()) {
                Draft.Edge e = stack.remove(stack.size() - 1);
                String start = nodeIds.get(e.up.id);
                String end;
                Draft.Node dn = e.down;
                if (dn.kind == Draft.Kind.CP) {
                    end = dn.oks.connectionPointId;
                    routes++;
                    double pathLen = 0;
                    for (Draft.Edge pe : Draft.pathToRoot(dn)) {
                        pathLen += pe.length();
                    }
                    double straight = root.c.distance(dn.c);
                    double detour = straight > 1e-6 ? pathLen / straight : 1;
                    sumDetour += detour;
                    maxDetour = Math.max(maxDetour, detour);
                } else {
                    Variant.NewChamber ch = new Variant.NewChamber();
                    ch.id = p + "ch-" + (++n[3]);
                    ch.point = Crs.UTM_FACTORY.createPoint(dn.c);
                    v.chambers.add(ch);
                    junctionChambers.add(ch);
                    junctionOf.put(ch, dn);
                    end = ch.id;
                    nodeIds.put(dn.id, end);
                    stack.addAll(dn.down);
                }
                for (int k = 1; k + 1 < e.coords.size(); k++) {
                    double ang = deflectionDeg(e.coords.get(k - 1), e.coords.get(k), e.coords.get(k + 1));
                    if (ang > 2) {
                        turns++;
                        if (ang < 10) {
                            smallTurns++;
                        }
                    }
                    if (!ref.isAllowedTurn(ang)) {
                        sharpTurns++;
                    }
                }
                // the inlet piece inside the building is not a route piece: it does not count as a short one
                int pieces = e.coords.size() - 1 - (dn.kind == Draft.Kind.CP && dn.oks.connectionInside() ? 1 : 0);
                for (int k = 0; k < pieces; k++) {
                    minStraight = Math.min(minStraight, e.coords.get(k).distance(e.coords.get(k + 1)));
                }
                int first = v.segments.size();
                emitEdge(v, p, n, e, start, end);
                List<String> ids = new ArrayList<>();
                double cost = 0;
                for (int k = first; k < v.segments.size(); k++) {
                    ids.add(v.segments.get(k).id);
                    cost += v.segments.get(k).cost;
                }
                edgeSegments.put(e.id, ids);
                edgeCosts.put(e.id, cost);
            }
        }
        if (!TRIAL.equals(variantId)) {
            oksInfo(model, d, v, unconnected, nodeIds, edgeSegments, edgeCosts);
        }
        for (Variant.NewChamber ch : junctionChambers) {
            Draft.Node j = junctionOf.get(ch);
            int dn = j.up.dn;
            for (Draft.Edge e : j.down) {
                dn = Math.max(dn, e.dn);
            }
            ch.dn = dn;
            ch.cost = ref.chamberCost(dn);
        }

        // The mandatory model does not rebuild the existing network (appendix 2.4, clarification 14). The
        // capacity check is kept as an informational layer only: it never enters the cost, the score or the
        // output file, and it answers ТЗ 2.5 (load of the existing network) in the UI and the API.
        ReconstructionCalculator rc = new ReconstructionCalculator(model, ref);
        List<ReconstructionCalculator.Piece> recon = rc.compute(loads, diag);
        for (ReconstructionCalculator.Piece pc : recon) {
            if (pc.to - pc.from >= ReconstructionCalculator.MIN_RECON_PIECE_M) {
                Variant.FlowChange fc = new Variant.FlowChange();
                fc.line = pc.line();
                fc.existingObjectId = pc.segment.id;
                fc.existingFlowTph = pc.segment.flowTph;
                fc.addedFlowTph = pc.addedFlow;
                fc.existingDn = pc.segment.dn;
                fc.requiredDn = Math.max(pc.segment.dn, pc.requiredDn);
                v.flowChanges.add(fc);
            }
        }
        for (Object[] o : tieChambers) {
            Variant.NewChamber ch = (Variant.NewChamber) o[2];
            // the DN of a chamber follows the largest DN of every segment adjoining it (appendix 3.2): the new
            // ones and both halves of every existing line the chamber stands on
            int dn = Math.max(ch.dn, (Integer) o[1]);
            org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(
                    ch.point.getCoordinate());
            env.expandBy(CHAMBER_ON_SEGMENT_TOL_M);
            for (InputModel.Segment s : model.segmentsNear(env)) {
                if (s.line.distance(ch.point) <= CHAMBER_ON_SEGMENT_TOL_M) {
                    dn = Math.max(dn, s.dn);
                }
            }
            ch.dn = dn;
            ch.cost = ref.chamberCost(ch.dn);
        }

        Variant.Summary sm = v.summary;
        sm.id = p + "summary";
        double segmentCost = 0;
        for (Variant.NewSegment s : v.segments) {
            segmentCost += s.cost;
            sm.newNetworkLength += s.length;
        }
        for (Variant.NewChamber c : v.chambers) {
            sm.chamberConstructionCost += c.cost;
        }
        for (Variant.TieIn t : v.tieIns) {
            if (t.existingChamber) {
                sm.existingChamberTieInCost += t.cost;
                sm.existingChamberTieInCount += (int) Math.round(t.cost / ref.getTieInCost());
            }
        }
        sm.constructionCost = segmentCost + sm.chamberConstructionCost + sm.existingChamberTieInCost;
        List<String> un = new ArrayList<>(unconnected.keySet());
        Collections.sort(un);
        for (String id : un) {
            sm.unconnectedPenalty += ref.unconnectedPenalty(unconnected.get(id).flowTph);
            sm.unconnectedOksIds.add(id);
            v.unconnected.add(unconnected.get(id));
        }
        sm.calculatedCost = sm.constructionCost + sm.unconnectedPenalty;
        sm.score = ref.score(sm.calculatedCost, sm.newNetworkLength);
        v.metrics.routes = routes;
        v.metrics.turns = turns;
        v.metrics.smallTurns = smallTurns;
        v.metrics.sharpTurns = sharpTurns;
        v.metrics.minStraightM = Double.isInfinite(minStraight) ? 0 : minStraight;
        v.metrics.turnsPerKm = sm.newNetworkLength > 0 ? turns / (sm.newNetworkLength / 1000) : 0;
        v.metrics.meanDetourRatio = routes == 0 ? 0 : sumDetour / routes;
        v.metrics.maxDetourRatio = maxDetour;
        LengthRuns.compute(v, ref, params.rules);
        v.metrics.trees = d.roots.size();
        v.metrics.junctions = junctionChambers.size();
        return v;
    }

    /** How every OKS is connected in the variant (list and card of an OKS in the UI; not part of the output file). */
    private static void oksInfo(InputModel model, Draft d, Variant v, Map<String, Unconnected> unconnected,
                                Map<Integer, String> nodeIds, Map<Integer, List<String>> edgeSegments,
                                Map<Integer, Double> edgeCosts) {
        List<Draft.Node> cps = new ArrayList<>();
        for (Draft.Node node : d.nodes.values()) {
            if (node.kind == Draft.Kind.CP) {
                cps.add(node);
            }
        }
        cps.sort(Comparator.comparing(x -> x.oks.id));
        for (Draft.Node cp : cps) {
            Variant.OksInfo oi = new Variant.OksInfo();
            oi.oksId = cp.oks.id;
            oi.flowTph = cp.oks.flowTph;
            oi.connected = true;
            Draft.Edge own = cp.up;
            oi.dn = own.dn;
            oi.ownLength = round(own.length(), 2);
            oi.ownCost = round(edgeCosts.getOrDefault(own.id, 0.0), 2);
            oi.turns = Math.max(0, own.coords.size() - 2 - (cp.oks.connectionInside() ? 1 : 0));
            for (RoutingContext.Interval iv : own.intervals) {
                if (iv.type != null && !oi.specials.contains(iv.type)) {
                    oi.specials.add(iv.type);
                }
            }
            oi.joins = own.up.kind == Draft.Kind.TIE ? "tie_in" : "junction";
            Draft.Node root = Draft.rootOf(cp);
            oi.tieInId = nodeIds.get(root.id);
            oi.tieObjectId = root.tie.existingObjectId();
            oi.tieObjectType = root.tie.chamber != null ? "heat_chamber" : "heat_network";
            List<Draft.Edge> path = Draft.pathToRoot(cp);
            double len = 0;
            for (int i = path.size() - 1; i >= 0; i--) {
                len += path.get(i).length();
                oi.pathSegmentIds.addAll(edgeSegments.getOrDefault(path.get(i).id, Collections.<String>emptyList()));
            }
            oi.pathLength = round(len, 2);
            List<String> tree = new ArrayList<>();
            collectOks(root, tree);
            Collections.sort(tree);
            for (String id : tree) {
                if (!id.equals(cp.oks.id)) {
                    oi.sharedWith.add(id);
                }
            }
            java.util.Set<String> seen = new HashSet<>();
            String cur = root.tie.chamber != null ? root.tie.chamber.id : root.tie.segment.id;
            while (cur != null && !model.isSource(cur) && seen.add(cur) && oi.existingChain.size() < 10000) {
                oi.existingChain.add(cur);
                cur = model.upstreamOf(cur);
            }
            v.oks.add(oi);
        }
        List<String> un = new ArrayList<>(unconnected.keySet());
        Collections.sort(un);
        for (String id : un) {
            Variant.OksInfo oi = new Variant.OksInfo();
            oi.oksId = id;
            oi.flowTph = unconnected.get(id).flowTph;
            oi.connected = false;
            v.oks.add(oi);
        }
        v.oks.sort(Comparator.comparing(x -> x.oksId));
    }

    private static void collectOks(Draft.Node n, List<String> out) {
        if (n.kind == Draft.Kind.CP) {
            out.add(n.oks.id);
        }
        for (Draft.Edge e : n.down) {
            collectOks(e.down, out);
        }
    }

    /** Output segments of one draft edge: split where the laying method changes, technical nodes between. */
    private void emitEdge(Variant v, String p, int[] n, Draft.Edge e, String start, String end) {
        List<Piece> pieces = split(e);
        String from = start;
        PipeSpec pipe = ref.pipe(e.dn);
        for (int i = 0; i < pieces.size(); i++) {
            Piece pc = pieces.get(i);
            // a turn of the route costs nothing extra (appendix 2.1): the coefficient is the one of the
            // special crossing, if any
            double k = pc.k;
            String to;
            if (i == pieces.size() - 1) {
                to = end;
            } else {
                Variant.TechnicalNode tn = new Variant.TechnicalNode();
                tn.id = p + "node-" + (++n[1]);
                tn.point = Crs.UTM_FACTORY.createPoint(pc.coords.get(pc.coords.size() - 1));
                v.technicalNodes.add(tn);
                to = tn.id;
            }
            Variant.NewSegment s = new Variant.NewSegment();
            s.id = p + "seg-" + (++n[0]);
            s.line = line(pc.coords);
            s.startNodeId = from;
            s.endNodeId = to;
            s.flowTph = e.flow;
            s.dn = e.dn;
            s.length = round(s.line.getLength(), 3);
            s.layingMethod = pc.special ? "special" : "base";
            s.k = k;
            s.cost = round(s.length * pipe.getNewCostPerM() * k, 2);
            v.segments.add(s);
            from = to;
        }
    }

    /** A new chamber this close to an existing line stands on it, and the halves of that line adjoin it. */
    static final double CHAMBER_ON_SEGMENT_TOL_M = 0.05;

    /** Splits an edge into pieces of constant laying method (base / special with its coefficient). */
    private static List<Piece> split(Draft.Edge e) {
        List<Piece> out = new ArrayList<>();
        Piece cur = null;
        double acc = 0;
        for (int i = 0; i + 1 < e.coords.size(); i++) {
            Coordinate a = e.coords.get(i);
            Coordinate b = e.coords.get(i + 1);
            double len = a.distance(b);
            if (len < 1e-9) {
                continue;
            }
            List<double[]> parts = new ArrayList<>(); // from, to (local), special?1:0, k
            double pos = 0;
            for (RoutingContext.Interval iv : e.intervals) {
                double f = Math.max(0, iv.from - acc);
                double t = Math.min(len, iv.to - acc);
                if (t - f <= 1e-9) {
                    continue;
                }
                if (f > pos + 1e-9) {
                    parts.add(new double[]{pos, f, 0, 1.0});
                }
                parts.add(new double[]{Math.max(f, pos), t, 1, iv.k});
                pos = Math.max(pos, t);
            }
            if (len > pos + 1e-9) {
                parts.add(new double[]{pos, len, 0, 1.0});
            }
            mergeSlivers(parts);
            for (double[] part : parts) {
                boolean special = part[2] == 1;
                double k = part[3];
                Coordinate from = interpolate(a, b, part[0] / len);
                Coordinate to = interpolate(a, b, part[1] / len);
                if (cur == null || cur.special != special || Math.abs(cur.k - k) > 1e-9) {
                    cur = new Piece(special, k);
                    cur.coords.add(from);
                    out.add(cur);
                }
                cur.coords.add(to);
            }
            acc += len;
        }
        return out;
    }

    /** Base parts shorter than {@link #MIN_PIECE_M} next to a special part are given to that part. */
    private static void mergeSlivers(List<double[]> parts) {
        for (int i = 0; i < parts.size(); i++) {
            double[] p = parts.get(i);
            if (p[2] == 1 || p[1] - p[0] >= MIN_PIECE_M) {
                continue;
            }
            double[] prev = i > 0 ? parts.get(i - 1) : null;
            double[] next = i + 1 < parts.size() ? parts.get(i + 1) : null;
            double[] into = prev != null && prev[2] == 1 ? prev : next != null && next[2] == 1 ? next : null;
            if (into == null) {
                continue;
            }
            into[0] = Math.min(into[0], p[0]);
            into[1] = Math.max(into[1], p[1]);
            parts.remove(i--);
        }
    }

    /** Deflection of the route at vertex b, degrees (0 — straight on). */
    static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = c.x - b.x;
        double vy = c.y - b.y;
        double den = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (den < 1e-12) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / den))));
    }

    static double round(double v, int decimals) {
        return ru.lct.heatnet.geo.GeoJsonGeometry.round(v, decimals);
    }

    private static Coordinate interpolate(Coordinate a, Coordinate b, double f) {
        if (f <= 0) {
            return new Coordinate(a);
        }
        if (f >= 1) {
            return new Coordinate(b);
        }
        return new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
    }
}
