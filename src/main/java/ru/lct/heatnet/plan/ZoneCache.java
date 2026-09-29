package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Geometry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Buffers and prepared zones of obstacles, reused between the route searches of one calculation: an obstacle keeps
 * its geometry, so its buffer depends only on the clearance, and a clearance only on the DN. On real data (buildings
 * with hundreds of vertices) this is what a search spends its time on. Shared by the planning threads, so everything
 * put here must be immutable and fully initialised.
 */
final class ZoneCache {

    private final Map<String, Geometry> buffers = new ConcurrentHashMap<>();
    private final Map<String, Object> objects = new ConcurrentHashMap<>();

    /** Objects whose geometry changes during planning (routes built for this variant) are not cached. */
    static boolean cacheable(String objectId) {
        return objectId != null && !objectId.startsWith("new:");
    }

    Geometry buffer(String objectId, String kind, double distance, Supplier<Geometry> compute) {
        if (!cacheable(objectId)) {
            return compute.get();
        }
        return buffers.computeIfAbsent(key(objectId, kind, distance), k -> compute.get());
    }

    /** A prepared zone or special of an object for one clearance; they are immutable and shared by the threads. */
    @SuppressWarnings("unchecked")
    <T> T object(String objectId, String kind, double distance, Supplier<T> compute) {
        if (!cacheable(objectId)) {
            return compute.get();
        }
        return (T) objects.computeIfAbsent(key(objectId, kind, distance), k -> compute.get());
    }

    private static String key(String objectId, String kind, double distance) {
        // the exact distance: clearances of neighbouring DN differ by millimetres and must not share a zone
        return objectId + '|' + kind + '|' + Double.doubleToLongBits(distance);
    }
}
