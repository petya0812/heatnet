package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.AbstractNode;
import org.locationtech.jts.index.strtree.Boundable;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A built STR tree laid out in arrays, queried by an envelope or by a straight segment. A query returns the items
 * {@code STRtree.query(envelope)} returns, in the same order; the query by a segment skips those whose envelope the
 * segment itself misses. A long diagonal edge has a large envelope; most items in it are far from the edge, and every
 * item of the router lies within its envelope, so it cannot meet the edge either. Immutable once made: shared by the
 * planning threads.
 */
final class SegmentQuery {

    /** Envelopes are widened by this much for the test against the segment line, metres: the test only skips. */
    private static final double MARGIN_M = 1e-3;

    private final double[] minX;
    private final double[] maxX;
    private final double[] minY;
    private final double[] maxY;
    /** First child of a node; -1 for an item. */
    private final int[] first;
    private final int[] count;
    private final Object[] items;

    /** The tree must be built (or queried once) and not changed afterwards. */
    SegmentQuery(STRtree tree) {
        List<Boundable> all = new ArrayList<>();
        if (!tree.isEmpty()) {
            all.add(tree.getRoot());
        }
        // children of a node take consecutive places: the order of the children is kept
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Boundable b = all.get(i);
            if (b instanceof AbstractNode) {
                List<?> children = ((AbstractNode) b).getChildBoundables();
                spans.add(new int[]{all.size(), children.size()});
                for (Object c : children) {
                    all.add((Boundable) c);
                }
            } else {
                spans.add(new int[]{-1, 0});
            }
        }
        int n = all.size();
        minX = new double[n];
        maxX = new double[n];
        minY = new double[n];
        maxY = new double[n];
        first = new int[n];
        count = new int[n];
        items = new Object[n];
        for (int i = 0; i < n; i++) {
            Boundable b = all.get(i);
            Envelope e = (Envelope) b.getBounds();
            if (e == null || e.isNull()) {
                // an empty node meets nothing
                minX[i] = Double.POSITIVE_INFINITY;
                maxX[i] = Double.NEGATIVE_INFINITY;
                minY[i] = Double.POSITIVE_INFINITY;
                maxY[i] = Double.NEGATIVE_INFINITY;
            } else {
                minX[i] = e.getMinX();
                maxX[i] = e.getMaxX();
                minY[i] = e.getMinY();
                maxY[i] = e.getMaxY();
            }
            first[i] = spans.get(i)[0];
            count[i] = spans.get(i)[1];
            items[i] = b instanceof ItemBoundable ? ((ItemBoundable) b).getItem() : null;
        }
    }

    /**
     * Calls {@code action} with the items whose envelope meets {@code env}, in the order of the tree, until it
     * returns false. Returns false if it stopped.
     */
    @SuppressWarnings("unchecked")
    <T> boolean forEach(Envelope env, Predicate<T> action) {
        if (items.length == 0 || env.isNull() || !meets(0, env)) {
            return true;
        }
        return visit(0, env, false, 0, 0, 0, 0, (Predicate<Object>) action);
    }

    /**
     * Calls {@code action} with the items whose envelope meets the envelope {@code env} of a→b and the segment
     * itself, in the order of the tree, until it returns false. Returns false if it stopped.
     */
    @SuppressWarnings("unchecked")
    <T> boolean forEach(Coordinate a, Coordinate b, Envelope env, Predicate<T> action) {
        if (items.length == 0 || !meets(0, env)) {
            return true;
        }
        return visit(0, env, true, a.x, a.y, b.x - a.x, b.y - a.y, (Predicate<Object>) action);
    }

    private boolean meets(int i, Envelope env) {
        return !(env.getMinX() > maxX[i] || env.getMaxX() < minX[i] || env.getMinY() > maxY[i]
                || env.getMaxY() < minY[i]);
    }

    private boolean visit(int node, Envelope env, boolean along, double ax, double ay, double dx, double dy,
                          Predicate<Object> action) {
        double eMinX = env.getMinX();
        double eMaxX = env.getMaxX();
        double eMinY = env.getMinY();
        double eMaxY = env.getMaxY();
        int end = first[node] + count[node];
        for (int c = first[node]; c < end; c++) {
            if (eMinX > maxX[c] || eMaxX < minX[c] || eMinY > maxY[c] || eMaxY < minY[c]
                    || along && misses(c, ax, ay, dx, dy)) {
                continue;
            }
            if (first[c] >= 0) {
                if (!visit(c, env, along, ax, ay, dx, dy, action)) {
                    return false;
                }
            } else if (!action.test(items[c])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the line through a with direction d leaves the widened envelope on one side: all its corners strictly
     * on the same side. The envelopes already overlap, so otherwise the segment may meet it.
     */
    private boolean misses(int i, double ax, double ay, double dx, double dy) {
        double x0 = minX[i] - MARGIN_M - ax;
        double x1 = maxX[i] + MARGIN_M - ax;
        double y0 = minY[i] - MARGIN_M - ay;
        double y1 = maxY[i] + MARGIN_M - ay;
        double s00 = dx * y0 - dy * x0;
        double s01 = dx * y1 - dy * x0;
        double s10 = dx * y0 - dy * x1;
        double s11 = dx * y1 - dy * x1;
        return s00 > 0 && s01 > 0 && s10 > 0 && s11 > 0 || s00 < 0 && s01 < 0 && s10 < 0 && s11 < 0;
    }
}
