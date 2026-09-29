package ru.lct.heatnet.input;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;

/** Serves existing buildings and restrictions intersecting an area (UTM). */
public interface ObstacleSource {

    List<Obstacle> intersecting(Geometry area);

    /** In-memory implementation for files that fit into memory (tests, small datasets). */
    final class InMemory implements ObstacleSource {
        private final STRtree index = new STRtree();

        public InMemory(List<Obstacle> obstacles) {
            for (Obstacle o : obstacles) {
                // the geometries are shared by the planning threads: their envelope is computed here, in one thread
                o.getGeometry().getEnvelopeInternal();
                index.insert(o.getGeometry().getEnvelopeInternal(), o);
            }
            index.build();
        }

        @Override
        public List<Obstacle> intersecting(Geometry area) {
            List<Obstacle> out = new ArrayList<>();
            for (Object o : index.query(area.getEnvelopeInternal())) {
                Obstacle ob = (Obstacle) o;
                if (ob.getGeometry().intersects(area)) {
                    out.add(ob);
                }
            }
            // same order as the database source (ORDER BY fid): identical results from file and from PostGIS
            out.sort(java.util.Comparator.comparing(Obstacle::getId));
            return out;
        }
    }
}
