package ru.lct.heatnet.input;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orientation of the existing network towards the source derived from geometry, for datasets where
 * {@code upstream_object_id} is not given (the contest dataset): segment ends closer than
 * {@link InputModel#TOPOLOGY_TOLERANCE_M} are one node, chambers sit in nodes, and the network is walked
 * from the node of the source outwards. Every object gets the object through which the source is reached.
 */
final class Topology {

    private Topology() {
    }

    /** @return upstream object id for every segment and chamber the walk reached */
    static Map<String, String> derive(Map<String, ParsedFeature> segments, Map<String, ParsedFeature> chambers,
                                      ParsedFeature source, Diagnostics diag) {
        Map<String, String> upstream = new LinkedHashMap<>();
        if (source == null || segments.isEmpty()) {
            return upstream;
        }
        List<Coordinate> nodes = new ArrayList<>();
        Grid index = new Grid(InputModel.CHAMBER_GAP_M);
        Map<String, int[]> segNodes = new HashMap<>(); // segment id → [start node, end node]
        for (ParsedFeature f : segments.values()) {
            LineString line = (LineString) f.getUtm();
            segNodes.put(f.getId(), new int[]{node(nodes, index, line.getCoordinateN(0)),
                    node(nodes, index, line.getCoordinateN(line.getNumPoints() - 1))});
        }
        Map<Integer, List<String>> atNode = new HashMap<>();
        for (Map.Entry<String, int[]> e : segNodes.entrySet()) {
            for (int n : e.getValue()) {
                atNode.computeIfAbsent(n, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<Integer, String> chamberAt = new HashMap<>();
        for (ParsedFeature f : chambers.values()) {
            Coordinate c = ((Point) f.getUtm()).getCoordinate();
            int n = nearest(nodes, index, c, InputModel.CHAMBER_GAP_M);
            if (n >= 0) {
                chamberAt.putIfAbsent(n, f.getId());
            }
        }
        Coordinate sc = ((Point) source.getUtm()).getCoordinate();
        int root = nearest(nodes, index, sc, InputModel.CHAMBER_GAP_M);
        if (root < 0) {
            root = nearest(nodes, index, sc, Double.MAX_VALUE);
            diag.warning("network.source_not_on_network", source.getId(), String.format(
                    "The source is %.1f m from the nearest segment end; the network is oriented from that end",
                    nodes.get(root).distance(sc)));
        }
        // the object that the walk came through into a node: the source at the root, otherwise a chamber or a segment
        Map<Integer, String> arrivedBy = new HashMap<>();
        arrivedBy.put(root, source.getId());
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            int n = queue.poll();
            String into = arrivedBy.get(n);
            String chamber = chamberAt.get(n);
            if (chamber != null && !upstream.containsKey(chamber)) {
                upstream.put(chamber, into);
                into = chamber; // segments below the chamber hang on the chamber, not on the segment above it
            }
            for (String sid : atNode.getOrDefault(n, java.util.Collections.emptyList())) {
                if (!visited.add(sid)) {
                    continue;
                }
                upstream.put(sid, into);
                int[] ends = segNodes.get(sid);
                int other = ends[0] == n ? ends[1] : ends[0];
                if (!arrivedBy.containsKey(other)) {
                    arrivedBy.put(other, sid);
                    queue.add(other);
                }
            }
        }
        for (ParsedFeature f : chambers.values()) {
            if (!upstream.containsKey(f.getId())) {
                Coordinate c = ((Point) f.getUtm()).getCoordinate();
                int n = nearest(nodes, index, c, InputModel.CHAMBER_GAP_M);
                String into = n >= 0 ? arrivedBy.get(n) : null;
                if (into != null) {
                    upstream.put(f.getId(), into);
                }
            }
        }
        int unreachable = segments.size() - visited.size();
        if (unreachable > 0) {
            diag.warning("network.detached_part", null, unreachable
                    + " segments are not connected to the source; no tie-ins are made there");
        }
        diag.info("network.topology_derived", null, "Orientation towards the source derived from geometry for "
                + upstream.size() + " objects: upstream_object_id is not in the data");
        return upstream;
    }

    private static int node(List<Coordinate> nodes, Grid index, Coordinate c) {
        int found = index.nearest(nodes, c, InputModel.TOPOLOGY_TOLERANCE_M);
        if (found >= 0) {
            return found;
        }
        nodes.add(c);
        index.add(c, nodes.size() - 1);
        return nodes.size() - 1;
    }

    private static int nearest(List<Coordinate> nodes, Grid index, Coordinate c, double radius) {
        if (radius == Double.MAX_VALUE) {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < nodes.size(); i++) {
                double d = nodes.get(i).distance(c);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return best;
        }
        return index.nearest(nodes, c, radius);
    }

    /** Point index on a square grid: the cell is as large as the largest search radius. */
    private static final class Grid {
        private final double cell;
        private final Map<Long, List<Integer>> cells = new HashMap<>();

        Grid(double cell) {
            this.cell = cell;
        }

        private long key(double x, double y) {
            return (((long) Math.floor(x / cell)) << 32) ^ (((long) Math.floor(y / cell)) & 0xffffffffL);
        }

        void add(Coordinate c, int id) {
            cells.computeIfAbsent(key(c.x, c.y), k -> new ArrayList<>()).add(id);
        }

        int nearest(List<Coordinate> nodes, Coordinate c, double radius) {
            int best = -1;
            double bestDist = radius;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int i : cells.getOrDefault(key(c.x + dx * cell, c.y + dy * cell),
                            java.util.Collections.<Integer>emptyList())) {
                        double d = nodes.get(i).distance(c);
                        if (d <= bestDist) {
                            bestDist = d;
                            best = i;
                        }
                    }
                }
            }
            return best;
        }
    }
}
