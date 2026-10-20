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
import java.util.Arrays;
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
 *     <li>{@code separate} — every OKS has its own tie-in;</li>
 *     <li>alternatives — other connection orders, or joint without the main tie-in of the best variant.</li>
 * </ul>
 * The best {@link PlanParams#maxVariants} structurally different candidates (different tie-ins or grouping of OKS)
 * are returned, ranked by score; the separate connection is among them only when it is one of the best.
 */
public final class Planner {

    public static final String STRATEGY_SEPARATE = "separate";
    public static final String STRATEGY_JOINT = "joint";
    /** Pieces shorter than this are merged into a neighbour (no slivers between special sections). */
    static final double MIN_PIECE_M = 0.04;
    /** Distinct candidates below which the alternative without the main tie-in is built. */
    private static final int CANDIDATES_MIN = 3;
    /** Variant id of an assembly made only to score a candidate. */
    private static final String TRIAL = "x";
    /** An entry into the building counts as from the nearest wall within this much of the shortest piece, metres. */
    static final double ENTRY_TOL_M = 0.1;

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
        /** Score S of the variant (appendix 6) and the score the schemes are compared by (S with the shape). */
        final double score;
        final double sel;
        /** Turns plus odd joints in turns ({@link Variant.Metrics#shapeTurns}, {@link Variant.Metrics#oddJoints}). */
        final double shape;
        /** Construction journal (null unless recorded) and the name of the build it belongs to. */
        final JournalRecorder.Log log;
        final String build;

        Candidate(Draft draft, Map<String, Unconnected> unconnected, String strategy, String explanation,
                  InputModel model, JournalRecorder.Log log, String build) {
            this(draft, unconnected, strategy, explanation,
                    assemble(model, draft, unconnected, TRIAL, new Diagnostics()), log, build);
        }

        Candidate(Draft draft, Map<String, Unconnected> unconnected, String strategy, String explanation,
                  Variant trial, JournalRecorder.Log log, String build) {
            this.draft = draft;
            this.unconnected = unconnected;
            this.strategy = strategy;
            this.explanation = explanation;
            this.score = trial.summary.score;
            this.sel = trial.metrics.selectionScore;
            this.shape = trial.metrics.shapeTurns + params.oddJointTurns * trial.metrics.oddJoints;
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
                e.put("selection_score", sel);
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
            // independent builds in parallel: the separate connection and three orders of joint connection. Every
            // build with its local search has a finder of its own (shared data caches, own hints of failed searches),
            // so the result does not depend on the number of threads or the order they run in
            RouteFinder fSepFinder = finder.session();
            java.util.concurrent.Future<Candidate> fSep = pool.submit(() -> build(model, fSepFinder, recorder, near,
                    false, diag, STRATEGY_SEPARATE, "Раздельное подключение: каждый ОКС своей трассой и своей врезкой",
                    "separate"));
            List<java.util.concurrent.Future<Candidate>> fJoint = new ArrayList<>();
            List<RouteFinder> sessions = new ArrayList<>();
            if (params.jointConnection) {
                RouteFinder fNear = finder.session();
                RouteFinder fFar = finder.session();
                RouteFinder fFlow = finder.session();
                sessions.addAll(Arrays.asList(fNear, fFar, fFlow));
                fJoint.add(pool.submit(() -> build(model, fNear, recorder, near, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются от ближних к сети", "joint-near")));
                fJoint.add(pool.submit(() -> build(model, fFar, recorder, far, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются от дальних от сети", "joint-far")));
                fJoint.add(pool.submit(() -> build(model, fFlow, recorder, byFlow, true, quiet, STRATEGY_JOINT,
                        "Совместное подключение, ОКС присоединяются по убыванию расхода", "joint-flow")));
            }
            sep = fSep.get();
            cands.add(sep);
            List<Candidate> joints = new ArrayList<>();
            for (java.util.concurrent.Future<Candidate> f : fJoint) {
                joints.add(f.get());
            }
            // local search from every start: the result depends on the start more than on the start's own score
            stage("перестройка совместного подключения", 3 * (n * params.localSearchPasses
                    + (params.groupRebuild ? Math.min(GROUP_CONNECTS, MAX_GROUPS * groupConnects(2) + n) : 0)));
            List<java.util.concurrent.Future<Candidate>> fImp = new ArrayList<>();
            for (int i = 0; i < joints.size(); i++) {
                Candidate j = joints.get(i);
                RouteFinder f = sessions.get(i);
                fImp.add(pool.submit(() -> improve(model, f, recorder, j, quiet)));
            }
            List<Candidate> improvedAll = new ArrayList<>();
            for (java.util.concurrent.Future<Candidate> f : fImp) {
                improvedAll.add(f.get());
            }
            improvedAll.sort(Comparator.comparingDouble(c -> c.sel));
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
        // the pool of candidates the variants are chosen from: when the builds give fewer than three distinct ones, the
        // joint connection without the main tie-in is added (it is often the best or the second best)
        if (params.jointConnection && distinct(cands).size() < CANDIDATES_MIN) {
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
        List<Candidate> chosen = select(cands);
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

    /**
     * Ranks variants by score (appendix 6, 7.2): smaller score — higher rank. Which candidates become the variants is
     * decided by the score of the choice of a scheme ({@link #select}); the rank in the result follows S.
     */
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
        sorted.sort(Comparator.comparingDouble(c -> c.sel));
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

    /**
     * The best distinct candidates by the score of the choice of a scheme (S with the shape), best first, at most
     * {@link PlanParams#maxVariants}.
     */
    private List<Candidate> select(List<Candidate> cands) {
        List<Candidate> d = distinct(cands);
        return new ArrayList<>(d.subList(0, Math.min(params.maxVariants, d.size())));
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
        Candidate cur = relocate(model, finder, recorder, c, c, diag);
        // an OKS whose way out of its building a route of the variant runs through stays unconnected whatever order
        // the single moves take: take that route out, connect the OKS, put the route back (bounded rounds)
        for (int round = 0; round < MAX_UNBLOCK_ROUNDS; round++) {
            Candidate next = unblock(model, finder, recorder, c, cur, diag);
            if (next == cur) {
                break;
            }
            cur = relocate(model, finder, recorder, c, next, diag);
        }
        // a branch of the network is taken out as a whole and connected again in several orders: the single moves keep
        // the shared pieces where the first OKS of the build laid them
        if (params.groupRebuild) {
            Candidate next = regroup(model, finder, recorder, c, cur, diag);
            if (next != cur) {
                cur = relocate(model, finder, recorder, c, next, diag);
            }
        }
        // pieces with turns (hooks left after an OKS was taken out, ways of an OKS that is no longer there) laid anew
        if (params.straightenShared) {
            cur = straighten(model, finder, recorder, c, cur);
        }
        if (cur != c) {
            cur.explanation = c.explanation + "; схема улучшена перестройкой";
            cur.announce();
        }
        return cur;
    }

    /** Rounds of {@link #unblock} (each followed by single moves) per local search. */
    private static final int MAX_UNBLOCK_ROUNDS = 2;
    /** Routes tried to be taken out for one OKS without a way out of its building: the nearest ones. */
    private static final int MAX_BLOCKERS = 3;

    /** Single moves: take every OKS out and connect it again while the variant score decreases. */
    private Candidate relocate(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                               Candidate start, Diagnostics diag) {
        Candidate cur = start;
        // the OKS after the last improvement of a pass were tried on the network the next pass starts from
        int lastChange = -1;
        for (int pass = 0; pass < params.localSearchPasses; pass++) {
            boolean better = false;
            int changed = -1;
            List<InputModel.Oks> all = new ArrayList<>(model.getOks());
            for (int i = 0; i < all.size(); i++) {
                if (pass > 0 && !better && i > lastChange) {
                    break; // the same network, the same OKS: the same result as in the previous pass, no improvement
                }
                InputModel.Oks oks = all.get(i);
                Draft trial = cur.draft.copy();
                trial.remove(oks);
                if (!trial.recompute(ref) || !tieRuleHolds(model, trial) || !mergeHolds(finder, trial, oks)) {
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
                Variant v = assemble(model, next, un, TRIAL, new Diagnostics());
                if (v.metrics.selectionScore < cur.sel - 1e-9) {
                    JournalRecorder.Log log = null;
                    if (cur.log != null) {
                        log = cur.log.copy();
                        journalListener.event(c.build, recorder.connected(log, "rebuild", oks, used[0], next));
                    }
                    cur = new Candidate(next, un, cur.strategy, cur.explanation, v, log, c.build);
                    better = true;
                    changed = i;
                }
            }
            if (!better) {
                break;
            }
            lastChange = changed;
        }
        return cur;
    }

    /**
     * A move for an OKS left without a way out of its building ({@code ENTRY_BLOCKED}) by a route of this variant: the
     * OKS that route leads to is taken out, the blocked OKS is connected, the taken one is connected again (now around
     * it). The first such move that lowers the variant score is taken for each blocked OKS. Single moves cannot do it:
     * taking one OKS out and connecting it again puts its route back where it blocks.
     */
    private Candidate unblock(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                              Candidate start, Diagnostics diag) {
        Candidate cur = start;
        Map<String, InputModel.Oks> byId = new HashMap<>();
        for (InputModel.Oks o : model.getOks()) {
            byId.put(o.id, o);
        }
        for (String id : new ArrayList<>(start.unconnected.keySet())) {
            Unconnected u = cur.unconnected.get(id);
            InputModel.Oks blocked = byId.get(id);
            PipeSpec pipe = blocked == null ? null : ref.minPipeForFlow(blocked.flowTph);
            if (u == null || u.reason != Unconnected.Reason.ENTRY_BLOCKED || pipe == null) {
                continue;
            }
            // routes to a connection point (the part only that OKS uses) that run through the way out, nearest first
            List<Draft.Node> owners = new ArrayList<>();
            for (Draft.Node n : cur.draft.nodes.values()) {
                if (n.kind == Draft.Kind.CP && finder.blocksEntry(blocked, pipe, n.up.coords, ref.pipe(n.up.dn))) {
                    owners.add(n);
                }
            }
            Coordinate at = blocked.routePoint.getCoordinate();
            owners.sort(Comparator.comparingDouble((Draft.Node n) -> line(n.up.coords).distance(
                    Crs.UTM_FACTORY.createPoint(at))).thenComparingInt(n -> n.id));
            for (Draft.Node owner : owners.subList(0, Math.min(MAX_BLOCKERS, owners.size()))) {
                InputModel.Oks taken = owner.oks;
                Draft trial = cur.draft.copy();
                trial.remove(taken);
                if (!trial.recompute(ref) || !tieRuleHolds(model, trial) || !mergeHolds(finder, trial, taken)) {
                    continue;
                }
                Route[] usedBlocked = new Route[1];
                Draft withBlocked = connect(model, finder, trial, blocked, true, diag, new Unconnected.Failure(),
                        usedBlocked);
                tick();
                if (withBlocked == null) {
                    continue;
                }
                Map<String, Unconnected> un = new LinkedHashMap<>(cur.unconnected);
                un.remove(blocked.id);
                Route[] usedTaken = new Route[1];
                Unconnected.Failure fail = new Unconnected.Failure();
                Draft next = connect(model, finder, withBlocked, taken, true, diag, fail, usedTaken);
                tick();
                if (next == null) {
                    next = withBlocked;
                    un.put(taken.id, new Unconnected(taken.id, taken.flowTph, fail.reason, fail.detail));
                }
                Variant v = assemble(model, next, un, TRIAL, new Diagnostics());
                if (v.metrics.selectionScore < cur.sel - 1e-9) {
                    JournalRecorder.Log log = null;
                    if (cur.log != null) {
                        log = cur.log.copy();
                        journalListener.event(c.build, recorder.connected(log, "rebuild", blocked, usedBlocked[0],
                                withBlocked));
                        if (usedTaken[0] != null) {
                            journalListener.event(c.build, recorder.connected(log, "rebuild", taken, usedTaken[0],
                                    next));
                        } else {
                            journalListener.event(c.build, recorder.unconnected(log, taken, un.get(taken.id)));
                        }
                    }
                    cur = new Candidate(next, un, cur.strategy, cur.explanation, v, log, c.build);
                    break;
                }
            }
        }
        return cur;
    }

    // ------------------------------------------------------------------ group moves

    /** Groups taken out and connected again per local search, at most. */
    static final int MAX_GROUPS = 8;
    /** Largest group (OKS below one branching chamber): more is a rebuild of a whole variant, not a local move. */
    static final int MAX_GROUP_OKS = 6;
    /** Groups up to this size are connected again in every order; larger ones — far first, by flow, near first. */
    static final int PERMUTE_MAX_OKS = 4;
    /** Connection attempts of the group moves per local search, at most (a group of 4 in every order takes 64). */
    static final int GROUP_CONNECTS = 160;
    /**
     * Search work of the group moves per local search and per group, at most: legs evaluated by the searches
     * ({@link RouteFinder#work} — the same for any threads, unlike a clock; on the city data of the case 1,2 million legs
     * take 3–4 s). A group that runs over its share is dropped unfinished.
     */
    static final long GROUP_WORK = 1_200_000L;
    static final long GROUP_WORK_ONE = 600_000L;

    /** Connection attempts a group of {@code k} OKS takes: every order with shared beginnings, or three orders. */
    static int groupConnects(int k) {
        if (k > PERMUTE_MAX_OKS) {
            return 3 * k;
        }
        int total = 0;
        int level = 1;
        for (int i = 0; i < k; i++) {
            level *= k - i;
            total += level;
        }
        return total;
    }

    /** A group: the OKS below a branching chamber (or of a whole tree) and where it hangs on the rest of the network. */
    static final class Group {
        final List<InputModel.Oks> oks;
        final Coordinate anchor;
        final double shape;
        /** Turns per 100 m of the pipe the move lays anew: a dense web of turns first. */
        final double density;
        final int nodeId;

        Group(List<InputModel.Oks> oks, Coordinate anchor, double shape, double length, int nodeId) {
            this.oks = oks;
            this.anchor = anchor;
            this.shape = shape;
            this.density = shape / Math.max(length / 100, 0.01);
            this.nodeId = nodeId;
        }

        String key() {
            List<String> ids = new ArrayList<>();
            for (InputModel.Oks o : oks) {
                ids.add(o.id);
            }
            Collections.sort(ids);
            return String.join(",", ids);
        }
    }

    /**
     * Groups of a draft, the densest web of turns (turns per 100 m of the pipe the move lays anew) first: for every branching chamber and every tree, the OKS below it (2 to
     * {@link #MAX_GROUP_OKS}); the shape is the turns and odd joints of the piece above the chamber and everything
     * below it — the part the move lays anew. A group below one chamber of a larger group is a group of its own, so
     * neighbouring branches that share a trunk are taken out together too.
     */
    List<Group> groups(Draft d) {
        List<Group> out = new ArrayList<>();
        double tol = ref.getStraightDeg();
        for (Draft.Node n : d.nodes.values()) {
            if (n.kind == Draft.Kind.CP) {
                continue;
            }
            List<InputModel.Oks> oks = new ArrayList<>();
            collectOksOf(n, oks);
            if (oks.size() < 2 || oks.size() > MAX_GROUP_OKS) {
                continue;
            }
            double shape = 0;
            double length = 0;
            List<Draft.Edge> stack = new ArrayList<>(n.down);
            if (n.up != null) {
                stack.add(n.up);
            }
            List<Draft.Node> joints = new ArrayList<>();
            joints.add(n);
            while (!stack.isEmpty()) {
                Draft.Edge e = stack.remove(stack.size() - 1);
                shape += edgeTurns(e, tol);
                length += e.length();
                if (e != n.up && e.down.kind == Draft.Kind.JUNCTION) {
                    joints.add(e.down);
                    stack.addAll(e.down.down);
                }
            }
            for (Draft.Node j : joints) {
                if (j.kind == Draft.Kind.JUNCTION) {
                    int[] js = junctionShape(nodeDirections(j), tol, params.oddJointTurns);
                    shape += js[0] + params.oddJointTurns * js[1];
                }
            }
            Coordinate anchor = n.up != null ? n.up.up.c : n.c;
            out.add(new Group(oks, anchor, shape, length, n.id));
        }
        out.sort(Comparator.comparingDouble((Group g) -> -g.density).thenComparingInt(g -> g.nodeId));
        return out;
    }

    private static void collectOksOf(Draft.Node n, List<InputModel.Oks> out) {
        if (n.kind == Draft.Kind.CP) {
            out.add(n.oks);
        }
        for (Draft.Edge e : n.down) {
            collectOksOf(e.down, out);
        }
    }

    /**
     * Group moves: a branch of the network (the OKS below a branching chamber, or a whole small tree) is taken out with
     * its shared pieces and connected again in several orders; the best order is taken when it lowers the score of the
     * choice of a scheme. Up to {@link #MAX_GROUPS} groups, the densest web of turns first, {@link #GROUP_CONNECTS}
     * connection attempts and {@link #GROUP_WORK} of search work; after a taken move the groups of the new network are
     * listed again (the tried ones skipped).
     */
    private Candidate regroup(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                              Candidate start, Diagnostics diag) {
        Candidate cur = start;
        Set<String> tried = new HashSet<>();
        int budget = GROUP_CONNECTS;
        long workLimit = finder.work() + GROUP_WORK;
        int done = 0;
        List<Group> list = groups(cur.draft);
        int at = 0;
        while (done < MAX_GROUPS && at < list.size() && finder.work() < workLimit) {
            Group g = list.get(at++);
            int need = groupConnects(g.oks.size());
            if (!tried.add(g.key()) || need > budget) {
                continue;
            }
            done++;
            budget -= need;
            Candidate next = reinsert(model, finder, recorder, c, cur, g,
                    Math.min(workLimit, finder.work() + GROUP_WORK_ONE), diag);
            if (next != null) {
                cur = next;
                list = groups(cur.draft);
                at = 0;
            }
        }
        return cur;
    }

    /** One step of a group move: the OKS, its route (null — left without one) and the draft after it. */
    private static final class Move {
        final InputModel.Oks oks;
        final Route route;
        final Draft draft;
        final Unconnected failed;

        Move(InputModel.Oks oks, Route route, Draft draft, Unconnected failed) {
            this.oks = oks;
            this.route = route;
            this.draft = draft;
            this.failed = failed;
        }
    }

    /** Takes a group out and connects it again in every order (or three); returns a better candidate or null. */
    private Candidate reinsert(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                               Candidate cur, Group g, long workLimit, Diagnostics diag) {
        Draft base = cur.draft.copy();
        Map<InputModel.Oks, Coordinate> exits = new LinkedHashMap<>();
        for (InputModel.Oks o : g.oks) {
            Draft.Node cp = base.cpOf(o);
            if (cp == null) {
                return null;
            }
            List<Coordinate> own = cp.up.coords;
            exits.put(o, own.get(own.size() - (o.connectionInside() && own.size() > 2 ? 2 : 1)));
        }
        for (InputModel.Oks o : g.oks) {
            base.remove(o);
        }
        if (!base.recompute(ref) || !tieRuleHolds(model, base)) {
            return null;
        }
        // pieces merged where the group left must keep the clearances to the buildings of the group
        for (Draft.Edge e : base.edges.values()) {
            if (cur.draft.edges.containsKey(e.id)) {
                continue;
            }
            for (Map.Entry<InputModel.Oks, Coordinate> x : exits.entrySet()) {
                if (!mergeHolds(finder, base, e, x.getKey(), x.getValue())) {
                    return null;
                }
            }
        }
        Map<String, Unconnected> un = new LinkedHashMap<>(cur.unconnected);
        for (InputModel.Oks o : g.oks) {
            un.remove(o.id);
        }
        Object[] best = new Object[2]; // moves, trial variant
        if (g.oks.size() <= PERMUTE_MAX_OKS) {
            permute(model, finder, base, un, new ArrayList<>(g.oks), new ArrayList<Move>(), best, workLimit, diag);
        } else {
            for (List<InputModel.Oks> order : groupOrders(model, g)) {
                Draft d = base;
                Map<String, Unconnected> u = new LinkedHashMap<>(un);
                List<Move> moves = new ArrayList<>();
                for (InputModel.Oks o : order) {
                    if (finder.work() >= workLimit) {
                        return null; // the work of the group moves ran out: the group is dropped unfinished
                    }
                    Move m = connectMove(model, finder, d, o, diag);
                    moves.add(m);
                    if (m.failed != null) {
                        u.put(o.id, m.failed);
                    } else {
                        d = m.draft;
                    }
                }
                offer(model, d, u, moves, best);
            }
        }
        if (best[0] == null || finder.work() > workLimit) {
            return null;
        }
        Variant v = (Variant) best[1];
        if (!(v.metrics.selectionScore < cur.sel - 1e-9)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Move> moves = (List<Move>) best[0];
        Draft last = base;
        Map<String, Unconnected> u = new LinkedHashMap<>(un);
        JournalRecorder.Log log = cur.log == null ? null : cur.log.copy();
        for (Move m : moves) {
            if (m.failed != null) {
                u.put(m.oks.id, m.failed);
                if (log != null) {
                    journalListener.event(c.build, recorder.unconnected(log, m.oks, m.failed));
                }
            } else {
                last = m.draft;
                if (log != null) {
                    journalListener.event(c.build, recorder.connected(log, "rebuild", m.oks, m.route, m.draft));
                }
            }
        }
        return new Candidate(last, u, cur.strategy, cur.explanation, v, log, c.build);
    }

    /** Every order of the remaining OKS; drafts of a shared beginning are made once. */
    private void permute(InputModel model, RouteFinder finder, Draft d, Map<String, Unconnected> un,
                         List<InputModel.Oks> rest, List<Move> moves, Object[] best, long workLimit,
                         Diagnostics diag) {
        if (rest.isEmpty()) {
            offer(model, d, un, moves, best);
            return;
        }
        for (int i = 0; i < rest.size() && finder.work() <= workLimit; i++) {
            InputModel.Oks o = rest.get(i);
            Move m = connectMove(model, finder, d, o, diag);
            List<InputModel.Oks> r = new ArrayList<>(rest);
            r.remove(i);
            List<Move> mv = new ArrayList<>(moves);
            mv.add(m);
            if (m.failed != null) {
                Map<String, Unconnected> u = new LinkedHashMap<>(un);
                u.put(o.id, m.failed);
                permute(model, finder, d, u, r, mv, best, workLimit, diag);
            } else {
                permute(model, finder, m.draft, un, r, mv, best, workLimit, diag);
            }
        }
    }

    private Move connectMove(InputModel model, RouteFinder finder, Draft d, InputModel.Oks o, Diagnostics diag) {
        Route[] used = new Route[1];
        Unconnected.Failure fail = new Unconnected.Failure();
        Draft next = connect(model, finder, d, o, true, diag, fail, used);
        tick();
        return next == null ? new Move(o, null, null, new Unconnected(o.id, o.flowTph, fail.reason, fail.detail))
                : new Move(o, used[0], next, null);
    }

    /** Keeps the order whose result has the smallest score of the choice (the first of equal ones). */
    private void offer(InputModel model, Draft d, Map<String, Unconnected> un, List<Move> moves, Object[] best) {
        Variant v = assemble(model, d, un, TRIAL, new Diagnostics());
        if (best[0] == null || v.metrics.selectionScore < ((Variant) best[1]).metrics.selectionScore - 1e-9) {
            best[0] = new ArrayList<>(moves);
            best[1] = v;
        }
    }

    /**
     * Orders of a large group: from the farthest from where the group hangs, by decreasing flow, from the nearest (the
     * same orders as the joint builds, relative to the branch); repeated orders are left out.
     */
    private static List<List<InputModel.Oks>> groupOrders(InputModel model, Group g) {
        Comparator<InputModel.Oks> near = Comparator.comparingDouble(
                (InputModel.Oks o) -> o.connectionPoint.getCoordinate().distance(g.anchor)).thenComparing(o -> o.id);
        List<InputModel.Oks> byNear = new ArrayList<>(g.oks);
        byNear.sort(near);
        List<InputModel.Oks> byFar = new ArrayList<>(byNear);
        Collections.reverse(byFar);
        List<InputModel.Oks> byFlow = new ArrayList<>(byNear);
        byFlow.sort(Comparator.comparingDouble((InputModel.Oks o) -> -o.flowTph));
        List<List<InputModel.Oks>> out = new ArrayList<>();
        for (List<InputModel.Oks> o : Arrays.asList(byFar, byFlow, byNear)) {
            if (!out.contains(o)) {
                out.add(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ straightening

    /** Pieces laid anew per local search, at most (those with the most turns first). */
    static final int MAX_STRAIGHTEN = 24;

    /**
     * Lays pieces with turns anew between their nodes ({@link RouteFinder#relay}: the same graph, rules and turn
     * penalty as the search of a route); a piece to a connection point keeps its last straight piece (the entry into
     * the building). Chambers stay where they are. A new way is taken when the whole draft holds and the score of the
     * choice of a scheme is lower, or the same with less turns.
     */
    private Candidate straighten(InputModel model, RouteFinder finder, JournalRecorder recorder, Candidate c,
                                 Candidate start) {
        Candidate cur = start;
        double tol = ref.getStraightDeg();
        List<Draft.Edge> list = new ArrayList<>();
        for (Draft.Edge e : cur.draft.edges.values()) {
            if (headTurns(e, tol) > 0) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingInt((Draft.Edge e) -> -headTurns(e, tol)).thenComparingInt(e -> e.id));
        for (Draft.Edge e0 : list.subList(0, Math.min(MAX_STRAIGHTEN, list.size()))) {
            Draft t = cur.draft.copy();
            Draft.Edge e = t.edges.get(e0.id);
            if (e == null || !relayHead(model, finder, t, e)) {
                continue;
            }
            Variant v = assemble(model, t, cur.unconnected, TRIAL, new Diagnostics());
            double shape = v.metrics.shapeTurns + params.oddJointTurns * v.metrics.oddJoints;
            if (v.metrics.selectionScore < cur.sel - 1e-9
                    || v.metrics.selectionScore <= cur.sel + 1e-9 && shape < cur.shape) {
                JournalRecorder.Log log = null;
                if (cur.log != null) {
                    log = cur.log.copy();
                    List<InputModel.Oks> fed = new ArrayList<>();
                    collectOksOf(e0.down, fed);
                    journalListener.event(c.build, recorder.straightened(log, fed.get(0).id, t));
                }
                cur = new Candidate(t, cur.unconnected, cur.strategy, cur.explanation, v, log, c.build);
            }
        }
        return cur;
    }

    /** Pieces at the end of an edge that stay when it is laid anew: the entry into the building (and the inlet). */
    private static int keptPieces(Draft.Edge e) {
        return e.down.kind == Draft.Kind.CP ? 1 + (e.down.oks.connectionInside() ? 1 : 0) : 0;
    }

    /** Turns of the part of an edge that may be laid anew. */
    static int headTurns(Draft.Edge e, double tol) {
        int end = e.coords.size() - 1 - keptPieces(e);
        int n = 0;
        for (int k = 1; k < end; k++) {
            if (deflectionDeg(e.coords.get(k - 1), e.coords.get(k), e.coords.get(k + 1)) > tol) {
                n++;
            }
        }
        return n;
    }

    /** Lays the head of an edge anew in the draft; false (the draft is then spoilt) when there is no valid way. */
    boolean relayHead(InputModel model, RouteFinder finder, Draft t, Draft.Edge e) {
        int end = e.coords.size() - 1 - keptPieces(e);
        if (end < 2) {
            return false;
        }
        List<Coordinate> head = new ArrayList<>(e.coords.subList(0, end + 1));
        List<RouteFinder.Built> others = new ArrayList<>();
        for (Draft.Edge o : t.edges.values()) {
            if (o != e && o.up != e.up && o.up != e.down && o.down != e.up) {
                others.add(new RouteFinder.Built(line(o.coords), ref.pipe(o.dn), "e" + o.id));
            }
        }
        PipeSpec pipe = ref.pipe(e.dn);
        boolean atTie = e.up.kind == Draft.Kind.TIE;
        List<Coordinate> way = finder.relay(head, pipe, atTie, others);
        if (way == null || sameWay(way, head)) {
            return false;
        }
        if (e.down.kind == Draft.Kind.CP && e.down.oks.polygon != null) {
            // a footprint of an earlier input format is no obstacle of the search, but the route keeps out of its own
            InputModel.Oks own = e.down.oks;
            org.locationtech.jts.geom.Geometry b = params.rules.throughOwnParts ? own.mainPart() : own.polygon;
            if (line(way).intersects(b.buffer(-0.05))) {
                return false;
            }
        }
        List<RoutingContext.EdgeEval> evals = finder.evaluatePolyline(way, pipe, null, atTie);
        if (evals == null) {
            return false;
        }
        t.relayHead(e, end, way, evals);
        return apartFromNeighbours(e) && t.recompute(ref) && recheck(finder, t) == null && !t.hasCrossings()
                && !t.hasSharpTurns(ref) && tieRuleHolds(model, t);
    }

    /**
     * A piece laid anew is searched without the pieces that share its nodes (they meet it there): beyond a short
     * stretch at the shared node it must keep apart from them as from any other new route — the pipes plus the
     * clearance between new routes. Without this the new way could run along a branch of the same chamber.
     */
    private boolean apartFromNeighbours(Draft.Edge e) {
        LineString l = line(e.coords);
        double hw = ref.pipe(e.dn).getHalfWidthM();
        for (Draft.Node n : Arrays.asList(e.up, e.down)) {
            List<Draft.Edge> near = new ArrayList<>(n.down);
            if (n.up != null) {
                near.add(n.up);
            }
            for (Draft.Edge o : near) {
                if (o == e) {
                    continue;
                }
                double gap = hw + ref.pipe(o.dn).getHalfWidthM() + params.newRouteClearanceM;
                org.locationtech.jts.geom.Geometry disk = Crs.UTM_FACTORY.createPoint(n.c).buffer(2 * gap, 8);
                org.locationtech.jts.geom.Geometry a = l.difference(disk);
                org.locationtech.jts.geom.Geometry b = line(o.coords).difference(disk);
                if (!a.isEmpty() && !b.isEmpty() && a.distance(b) < gap - 1e-6) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameWay(List<Coordinate> a, List<Coordinate> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).distance(b.get(i)) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ shape of a scheme

    /** Turns inside an edge (the vertex at the wall before an inlet piece is not a turn of the route). */
    static int edgeTurns(Draft.Edge e, double tol) {
        int last = e.coords.size() - 1
                - (e.down.kind == Draft.Kind.CP && e.down.oks.connectionInside() ? 1 : 0);
        int n = 0;
        for (int k = 1; k < last; k++) {
            if (deflectionDeg(e.coords.get(k - 1), e.coords.get(k), e.coords.get(k + 1)) > tol) {
                n++;
            }
        }
        return n;
    }

    /**
     * Shape of a draft as the search prices it: {turns, joints that are not a T}. Turns — inside the edges and where the
     * pipe bends in a branching chamber; joints — branches at an odd angle to the pipe in a branching chamber and new
     * pipes at an odd angle to an existing segment at a tie-in (an existing chamber has no angle).
     */
    static int[] shape(Draft d, ReferenceData ref, double oddJointTurns) {
        double tol = ref.getStraightDeg();
        int turns = 0;
        int odd = 0;
        for (Draft.Edge e : d.edges.values()) {
            turns += edgeTurns(e, tol);
        }
        for (Draft.Node n : d.nodes.values()) {
            if (n.kind == Draft.Kind.JUNCTION) {
                int[] j = junctionShape(nodeDirections(n), tol, oddJointTurns);
                turns += j[0];
                odd += j[1];
            } else if (n.kind == Draft.Kind.TIE && n.tie.pipeDirs != null) {
                for (Draft.Edge e : n.down) {
                    Coordinate q = e.coords.get(1);
                    int[] j = jointShape(n.tie.pipeDirs, new double[]{q.x - n.c.x, q.y - n.c.y}, tol);
                    turns += j[0];
                    odd += j[1];
                }
            }
        }
        return new int[]{turns, odd};
    }

    /** Directions of the edges at a node, away from it (the edge above first). */
    static double[][] nodeDirections(Draft.Node n) {
        List<double[]> out = new ArrayList<>();
        if (n.up != null) {
            List<Coordinate> c = n.up.coords;
            Coordinate p = c.get(c.size() - 2);
            out.add(new double[]{p.x - n.c.x, p.y - n.c.y});
        }
        for (Draft.Edge e : n.down) {
            Coordinate q = e.coords.get(1);
            out.add(new double[]{q.x - n.c.x, q.y - n.c.y});
        }
        return out.toArray(new double[0][]);
    }

    /**
     * A branching chamber: the pipe through it is the pair of directions that gives the simplest shape; it bends
     * (a turn) unless the two are opposite; every other direction is a branch, a T when it is at 90° or opposite to
     * both directions of the pipe, otherwise an odd joint. Returns {turns, odd joints}.
     */
    static int[] junctionShape(double[][] dirs, double tol, double oddJointTurns) {
        int m = dirs.length;
        if (m < 2) {
            return new int[]{0, 0};
        }
        int[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                int turn = 180 - angleDeg(dirs[i], dirs[j]) > tol ? 1 : 0;
                int odd = 0;
                for (int k = 0; k < m; k++) {
                    if (k != i && k != j && !isT(dirs[k], new double[][]{dirs[i], dirs[j]}, tol)) {
                        odd++;
                    }
                }
                double cost = turn + oddJointTurns * odd;
                if (cost < bestCost - 1e-9 || Math.abs(cost - bestCost) <= 1e-9 && odd < best[1]) {
                    bestCost = cost;
                    best = new int[]{turn, odd};
                }
            }
        }
        return best;
    }

    /**
     * A new pipe joining an existing one (the rule of {@link RouteFinder#junctionScore}): at the end of the pipe a bend
     * like any other — nothing straight on, a turn up to 90°, an odd joint sharper; inside it or at its bend nothing for
     * a T, an odd joint otherwise. Returns {turns, odd joints}.
     */
    static int[] jointShape(double[][] pipeDirs, double[] branch, double tol) {
        if (Math.hypot(branch[0], branch[1]) < 1e-9) {
            return new int[]{0, 0};
        }
        if (pipeDirs.length == 1) {
            double bend = 180 - angleDeg(branch, pipeDirs[0]);
            return bend <= tol ? new int[]{0, 0} : bend <= 90 + tol ? new int[]{1, 0} : new int[]{0, 1};
        }
        return isT(branch, pipeDirs, tol) ? new int[]{0, 0} : new int[]{0, 1};
    }

    private static boolean isT(double[] b, double[][] pipe, double tol) {
        for (double[] d : pipe) {
            if (Math.hypot(d[0], d[1]) < 1e-9) {
                continue;
            }
            double a = angleDeg(b, d);
            if (!(Math.abs(a - 90) <= tol || a >= 180 - tol)) {
                return false;
            }
        }
        return true;
    }

    private static double angleDeg(double[] u, double[] v) {
        double lu = Math.hypot(u[0], u[1]);
        double lv = Math.hypot(v[0], v[1]);
        if (lu < 1e-9 || lv < 1e-9) {
            return 180;
        }
        double cos = (u[0] * v[0] + u[1] * v[1]) / (lu * lv);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /**
     * Joins of the new network excluded after one failed are searched for again at most this many times per OKS
     * (every exclusion takes away at least the part of the network the failed join led through, so the number of
     * searches is bounded by the size of the network anyway; this keeps the time of one connection bounded).
     */
    static final int MAX_JOIN_EXCLUSIONS = 6;
    /** Searches for the DN of the route itself (the route was checked for a smaller DN than the tree needs). */
    private static final int MAX_DN_RETRIES = 6;
    /** Shared pieces laid anew for one join (each grew to a larger DN and no longer keeps the clearances). */
    static final int MAX_RELAYS = 3;

    /**
     * Connects one OKS to a copy of the draft; returns the new draft or null. The route is searched with the
     * minimal DN for the OKS flow; if flows, DN and the length limit of the whole tree cannot be satisfied with
     * that geometry, the route is searched again for the larger DN.
     * <p>
     * A join of the new network can fail after the route is found: the shared pieces up to the tie-in carry more and
     * grow to a larger DN that no longer keeps the clearances, or a path gets longer than the limit of its DN. Then
     * <ol>
     *   <li>such a grown shared piece is laid anew for its new DN between the same chambers ({@link #relayShared});
     *   if the whole draft then holds, it is a candidate;</li>
     *   <li>the joins below the failed piece are excluded (only they lead through it) and the search runs again —
     *   other joins and tie-ins into the existing network stay; only when nothing is found, tie-ins alone.</li>
     * </ol>
     * The cheapest (by the variant score) of the draft found that way, the re-laid candidates and the connection by a
     * tie-in into the existing network alone is taken.
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
        // pieces of the draft a failed join led through: joins below them are not searched for again
        Set<Integer> excluded = new HashSet<>();
        int exclusions = 0;
        int dnRetries = 0;
        // other ways to connect (a grown shared piece laid anew, tie-ins alone) with their routes, compared at the end
        List<Draft> others = new ArrayList<>();
        List<Route> otherRoutes = new ArrayList<>();
        Draft found = null;
        Route foundRoute = null;
        int maxAttempts = MAX_DN_RETRIES + MAX_JOIN_EXCLUSIONS + 1;
        // the search ended with a reason of its own (otherwise the attempts ran out)
        boolean stopped = false;
        for (int attempt = 0; attempt < maxAttempts && found == null && !stopped; attempt++) {
            NetworkState state = NetworkState.fromDraft(model, ref, d);
            List<RouteFinder.Built> built = new ArrayList<>();
            for (Draft.Edge e : d.edges.values()) {
                built.add(new RouteFinder.Built(line(e.coords), ref.pipe(e.dn), "e" + e.id));
            }
            DraftAttach attach = useAttach ? new DraftAttach(d, state, model, excluded) : null;
            Route r = finder.find(oks, pipe, built, state, attach, diag, fail);
            if (r == null) {
                if (attach != null && !excluded.isEmpty()) {
                    useAttach = false; // nothing without the excluded joins: tie-ins into the existing network only
                    continue;
                }
                stopped = true;
                continue;
            }
            if (oks.connectionInside()) {
                r = r.withEnd(oks.connectionPoint.getCoordinate());
            }
            Draft trial = d.copy();
            Draft.Node cp = trial.insert(r);
            if (!trial.recompute(ref)) {
                if (r.tie.isAttach()) {
                    useAttach = exclude(d, r.tie, -1, excluded) && ++exclusions < MAX_JOIN_EXCLUSIONS;
                    continue;
                }
                fail.set(Unconnected.Reason.LENGTH_LIMIT, String.format("трасса %.0f м, ДУ %d, предел %.0f м",
                        r.length(), pipe.getDn(), pipe.getMaxLengthM()));
                stopped = true;
                continue;
            }
            Draft.Edge bad = recheck(finder, trial);
            if (bad == null && trial.hasCrossings()) {
                // the new route crosses one that is already there (possible after a re-check for a larger DN)
                fail.set(Unconnected.Reason.NO_ROUTE, "трасса пересекает уже построенную");
                stopped = true;
                continue;
            }
            if (bad == null && trial.hasSharpTurns(ref)) {
                fail.set(Unconnected.Reason.NO_ROUTE, "поворот трассы острее 90°");
                stopped = true;
                continue;
            }
            if (bad == null && !tieRuleHolds(model, trial)) {
                // the tie-in of this draft would have to be made in a chamber that has a free branch
                fail.set(Unconnected.Reason.NO_ROUTE, "врезка ближе 10 м к камере со свободным местом");
                stopped = true;
                continue;
            }
            if (bad == null) {
                found = trial;
                foundRoute = r;
                if (cp.up.dn > pipe.getDn() && !r.tie.isAttach()) {
                    diag.warning("route.dn_increased_for_length", oks.id, String.format(
                            "Route %.1f m > limit %.0f m of DN %d, DN increased to %d", r.length(),
                            pipe.getMaxLengthM(), pipe.getDn(), cp.up.dn));
                }
                break;
            }
            // the tree needs a larger DN than the route was checked for: search again with that DN,
            // or, if a shared piece is the problem, lay it anew and look for other joins
            int newDn = cp.up.dn;
            if (bad == cp.up && newDn > pipe.getDn()) {
                pipe = ref.pipe(newDn);
                if (++dnRetries >= MAX_DN_RETRIES) {
                    break; // the attempts ran out
                }
            } else if (r.tie.isAttach()) {
                Draft again = relayShared(model, finder, trial, bad, cp);
                if (again != null) {
                    others.add(again);
                    otherRoutes.add(r);
                }
                int failedId = d.edges.containsKey(bad.id) ? bad.id : -1;
                useAttach = exclude(d, r.tie, failedId, excluded) && ++exclusions < MAX_JOIN_EXCLUSIONS;
            } else {
                fail.set(Unconnected.Reason.NO_ROUTE, "трасса не проходит по отступам с увеличенным ДУ " + newDn);
                stopped = true;
                continue;
            }
        }
        // after a failed join the tie-ins into the existing network alone are a candidate too (as before, when a failed
        // join ended the search of joins): the estimate of a join does not see every growth of the tree
        if (exclusions > 0 && (found == null ? useAttach : foundRoute.tie.isAttach())) {
            Route[] alone = new Route[1];
            Draft tieOnly = connect(model, finder, d, oks, false, new Diagnostics(), new Unconnected.Failure(), alone);
            if (tieOnly != null) {
                others.add(tieOnly);
                otherRoutes.add(alone[0]);
            }
        }
        // the cheapest of the draft found, the drafts with a shared piece laid anew and the tie-in alone
        Draft best = found;
        Route bestRoute = foundRoute;
        double bestScore = found == null || others.isEmpty() ? Double.POSITIVE_INFINITY : trialScore(model, found);
        for (int i = 0; i < others.size(); i++) {
            double s = trialScore(model, others.get(i));
            if (best == null || s < bestScore - 1e-9) {
                bestScore = s;
                best = others.get(i);
                bestRoute = otherRoutes.get(i);
            }
        }
        if (best == null) {
            if (!stopped) {
                fail.set(Unconnected.Reason.NO_ROUTE, "не удалось согласовать ДУ и отступы за " + maxAttempts
                        + " попыток");
            }
            return null;
        }
        used[0] = bestRoute;
        return best;
    }

    /**
     * Score of a draft for comparing ways of connecting the same OKS (the unconnected ones are the same): the score of
     * the choice of a scheme (S with the shape).
     */
    private double trialScore(InputModel model, Draft d) {
        return assemble(model, d, Collections.<String, Unconnected>emptyMap(), TRIAL, new Diagnostics())
                .metrics.selectionScore;
    }

    /**
     * Excludes the joins that lead through a failed piece of the draft (its id, or -1 when the failed piece is the
     * part of the joined edge above the new junction): joins on that piece and everything below it. The join that
     * failed is always excluded, so every call takes something away. Returns whether joins remain worth searching.
     */
    private static boolean exclude(Draft d, TieOption tie, int failedEdgeId, Set<Integer> excluded) {
        if (failedEdgeId > 0) {
            excluded.add(failedEdgeId);
        }
        if (!leadsThrough(d, tie, excluded)) {
            // the failed piece is not on the way of this join (a run of one DN grew as a whole): leave out the join
            excluded.add(tie.attachEdgeId > 0 ? tie.attachEdgeId : d.nodes.get(tie.attachNodeId).up.id);
        }
        return true;
    }

    /** The edges with ids in {@code excluded} and all edges below them. */
    static Set<Integer> below(Draft d, Set<Integer> excluded) {
        if (excluded.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> out = new HashSet<>();
        List<Draft.Edge> stack = new ArrayList<>();
        for (int id : excluded) {
            Draft.Edge e = d.edges.get(id);
            if (e != null) {
                stack.add(e);
            }
        }
        while (!stack.isEmpty()) {
            Draft.Edge e = stack.remove(stack.size() - 1);
            if (out.add(e.id)) {
                stack.addAll(e.down.down);
            }
        }
        return out;
    }

    /** Whether a join leads through one of the {@code excluded} edges (lies on it or below it). */
    static boolean leadsThrough(Draft d, TieOption tie, Set<Integer> excluded) {
        Draft.Edge e = tie.attachEdgeId > 0 ? d.edges.get(tie.attachEdgeId) : d.nodes.get(tie.attachNodeId).up;
        while (e != null) {
            if (excluded.contains(e.id)) {
                return true;
            }
            e = e.up.up;
        }
        return false;
    }

    /**
     * Lays the grown shared pieces of a trial anew for their DN (between the same chambers, entry legs and tie-ins
     * untouched) until the whole draft holds: flows, DN, the length limit, clearances, crossings, turns, the tie-in
     * rule. Returns the draft or null. Only pieces between chambers are laid anew: a piece ending at a connection
     * point holds the straight entry into the building.
     */
    private Draft relayShared(InputModel model, RouteFinder finder, Draft trial, Draft.Edge bad, Draft.Node cp) {
        Draft t = trial.copy();
        Draft.Edge e = t.edges.get(bad.id);
        for (int i = 0; i < MAX_RELAYS && e != null; i++) {
            if (e.down.kind != Draft.Kind.JUNCTION || e.id == cp.up.id) {
                return null;
            }
            List<RouteFinder.Built> others = new ArrayList<>();
            for (Draft.Edge o : t.edges.values()) {
                if (o != e && o.up != e.up && o.up != e.down && o.down != e.up) {
                    others.add(new RouteFinder.Built(line(o.coords), ref.pipe(o.dn), "e" + o.id));
                }
            }
            PipeSpec pipe = ref.pipe(e.dn);
            boolean atTie = e.up.kind == Draft.Kind.TIE;
            List<Coordinate> way = finder.relay(e.coords, pipe, atTie, others);
            List<RoutingContext.EdgeEval> evals = way == null ? null : finder.evaluatePolyline(way, pipe, null, atTie);
            if (evals == null) {
                return null;
            }
            t.relay(e, way, evals);
            if (!t.recompute(ref)) {
                return null;
            }
            e = recheck(finder, t);
        }
        if (e != null || t.hasCrossings() || t.hasSharpTurns(ref) || !tieRuleHolds(model, t)) {
            return null;
        }
        return t;
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

    /**
     * After an OKS is taken out, the edge merged where it left feeds only the others. The route of that OKS kept no
     * clearance to its own building and might break clearances near its end (route ends are excused): the merged edge
     * must keep the clearance to that building, now a foreign one, and is checked again near that end.
     */
    private boolean mergeHolds(RouteFinder finder, Draft d, InputModel.Oks removed) {
        return d.merged == null || mergeHolds(finder, d, d.merged, removed, d.removedExit);
    }

    /** {@link #mergeHolds(RouteFinder, Draft, InputModel.Oks)} for an edge and the outdoor end of the removed route. */
    private boolean mergeHolds(RouteFinder finder, Draft d, Draft.Edge e, InputModel.Oks removed, Coordinate exit) {
        LineString l = line(e.coords);
        PipeSpec pipe = ref.pipe(e.dn);
        double clearance = ref.buildingDistance(e.dn) + pipe.getHalfWidthM() + params.extraClearanceM;
        if (exit != null && l.isWithinDistance(Crs.UTM_FACTORY.createPoint(exit),
                RoutingContext.escapeRadius(clearance))) {
            return finder.checkPolyline(e.coords, pipe, e.down.kind == Draft.Kind.CP ? e.down.oks : null,
                    e.up.kind == Draft.Kind.TIE);
        }
        if (removed.ownBuildingId == null || removed.polygon == null) {
            return true; // a footprint of an earlier input format is no obstacle
        }
        if (params.rules.throughBuildings == ru.lct.heatnet.reference.RuleOptions.ThroughBuildings.ANY) {
            return true; // the building of another OKS may be crossed
        }
        List<String> fed = new ArrayList<>();
        collectOks(e.down, fed);
        for (InputModel.Oks o : d.connected()) {
            if (fed.contains(o.id) && removed.ownBuildingId.equals(o.ownBuildingId)) {
                return true; // still feeds a connection point in the same building
            }
        }
        org.locationtech.jts.geom.Geometry building = params.rules.throughOwnParts ? removed.mainPart()
                : removed.polygon;
        return l.distance(building) >= clearance - MERGE_TOL_M;
    }

    /** Planner geometry keeps this much below the clearance a merged edge is checked against, metres. */
    private static final double MERGE_TOL_M = 0.02;

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

        /** Edges whose joins are left out: the excluded ones and everything below them. */
        private final Set<Integer> blocked;

        DraftAttach(Draft d, NetworkState state, InputModel model) {
            this(d, state, model, Collections.<Integer>emptySet());
        }

        /** Without the joins on the {@code excluded} edges and below them (they are neither goals nor distances). */
        DraftAttach(Draft d, NetworkState state, InputModel model, Set<Integer> excluded) {
            this.d = d;
            this.state = state;
            this.model = model;
            this.blocked = below(d, excluded);
        }


        /** Distances by point: the draft does not change while this OKS is searched for, the corners repeat. */
        private final Map<RoutingContext.EdgeKey, Double> distances = new HashMap<>();

        @Override
        public double distance(Coordinate v) {
            RoutingContext.EdgeKey key = new RoutingContext.EdgeKey(v, v);
            Double known = distances.get(key);
            if (known != null) {
                return known;
            }
            if (segments == null) {
                segments = new ArrayList<>();
                for (Draft.Edge e : d.edges.values()) {
                    if (blocked.contains(e.id)) {
                        continue;
                    }
                    for (int i = 0; i + 1 < e.coords.size(); i++) {
                        segments.add(new Coordinate[]{e.coords.get(i), e.coords.get(i + 1)});
                    }
                }
            }
            // the smallest distance to a segment; a segment whose envelope is farther than the best one found so far
            // cannot be the smallest (the margin covers the rounding of the distance)
            double best = Double.POSITIVE_INFINITY;
            for (Coordinate[] s : segments) {
                double dx = Math.max(0, Math.max(Math.min(s[0].x, s[1].x) - v.x, v.x - Math.max(s[0].x, s[1].x)));
                double dy = Math.max(0, Math.max(Math.min(s[0].y, s[1].y) - v.y, v.y - Math.max(s[0].y, s[1].y)));
                double lim = best + 1e-6;
                if (dx > lim || dy > lim || dx * dx + dy * dy > lim * lim) {
                    continue;
                }
                best = Math.min(best, Distance.pointToSegment(v, s[0], s[1]));
            }
            distances.put(key, best);
            return best;
        }

        /** Segments of the draft (it does not change while this OKS is searched for). */
        private List<Coordinate[]> segments;

        @Override
        public List<Coordinate[]> pieces() {
            distance(new Coordinate(0, 0)); // lays out the segments
            return segments;
        }

        @Override
        public double[] nearestPiece(Coordinate v) {
            distance(v); // lays out the segments
            double best = Double.POSITIVE_INFINITY;
            double[] out = null;
            for (Coordinate[] sg : segments) {
                double d = Distance.pointToSegment(v, sg[0], sg[1]);
                if (d < best && sg[0].distance(sg[1]) >= 1.0) {
                    best = d;
                    out = new double[]{sg[1].x - sg[0].x, sg[1].y - sg[0].y, d};
                }
            }
            return out;
        }

        @Override
        public List<TieOption> attachOptions(Coordinate v, PipeSpec pipe, double flow) {
            List<TieOption> out = new ArrayList<>();
            for (Draft.Edge e : d.edges.values()) {
                if (blocked.contains(e.id)) {
                    continue;
                }
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
                o.pipeDirs = TieOption.dirsAt(e.coords, bestPos);
                out.add(o);
            }
            for (Draft.Node n : d.nodes.values()) {
                if (n.kind == Draft.Kind.JUNCTION && !blocked.contains(n.up.id) && n.degree() < ref.getMaxChamberDegree()
                        && n.c.distance(v) <= params.attachSearchM) {
                    TieOption o = new TieOption(n.c, 0, 0, n.id, junctionScore(n, flow, pipe));
                    int dn = Math.max(grown(n.up, flow), pipe.getDn());
                    for (Draft.Edge e : n.down) {
                        dn = Math.max(dn, e.dn);
                    }
                    o.attachDn = dn;
                    o.pipeDirs = nodeDirs(n);
                    out.add(o);
                }
            }
            return out;
        }

        /** Directions of the edges at a junction, away from it. */
        private double[][] nodeDirs(Draft.Node n) {
            List<double[]> out = new ArrayList<>();
            if (n.up != null) {
                List<Coordinate> c = n.up.coords;
                Coordinate p = c.get(c.size() - 2);
                out.add(new double[]{p.x - n.c.x, p.y - n.c.y});
            }
            for (Draft.Edge e : n.down) {
                Coordinate q = e.coords.get(1);
                out.add(new double[]{q.x - n.c.x, q.y - n.c.y});
            }
            return out.toArray(new double[0][]);
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
            for (Variant.OksInfo o : v.oks) {
                if (o.connected && o.inside) {
                    v.metrics.entries++;
                    v.metrics.insideLengthM += o.insideM;
                    v.metrics.insideMinLengthM += o.insideMinM;
                    if (o.insideM > o.insideMinM + ENTRY_TOL_M) {
                        v.metrics.entriesNotNearest++;
                    }
                }
            }
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
        // A trial assembly gives the score only: the layer is not needed there (its diagnostics are dropped too).
        List<ReconstructionCalculator.Piece> recon = TRIAL.equals(variantId)
                ? Collections.<ReconstructionCalculator.Piece>emptyList()
                : new ReconstructionCalculator(model, ref).compute(loads, diag);
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
        int[] shape = shape(d, ref, params.oddJointTurns);
        v.metrics.shapeTurns = shape[0];
        v.metrics.oddJoints = shape[1];
        v.metrics.selectionScore = sm.score
                + params.shapeWeight * params.turnPenaltyScore * (shape[0] + params.oddJointTurns * shape[1]);
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
            List<Coordinate> oc = own.coords;
            int last = oc.size() - 1;
            // turns of the route outside the building; with the leg out of it the exit is no turn
            for (int k = 1; k < last - (cp.oks.connectionInside() ? 1 : 0); k++) {
                if (deflectionDeg(oc.get(k - 1), oc.get(k), oc.get(k + 1)) > 2) {
                    oi.turns++;
                }
            }
            if (cp.oks.connectionInside()) {
                oi.inside = true;
                oi.insideM = round(oc.get(last).distance(oc.get(last - 1)), 2);
                oi.insideMinM = round(BuildingEntry.exits(cp.oks, 1).get(0).insideM, 2);
                oi.entryLegM = last >= 2 && deflectionDeg(oc.get(last - 2), oc.get(last - 1), oc.get(last)) < 1
                        ? round(oc.get(last - 2).distance(oc.get(last - 1)), 2) : 0;
            }
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
