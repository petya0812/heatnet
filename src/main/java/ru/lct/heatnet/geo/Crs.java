package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Transformations between WGS 84 (EPSG:4326, input/output) and UTM 37N (EPSG:32637, all calculations).
 * Thread-safe: proj4j transforms are created per call.
 */
public final class Crs {

    public static final int WGS84 = 4326;
    public static final int UTM37N = 32637;

    public static final GeometryFactory WGS84_FACTORY = new GeometryFactory(new PrecisionModel(), WGS84);
    public static final GeometryFactory UTM_FACTORY = new GeometryFactory(new PrecisionModel(), UTM37N);

    private static final CoordinateReferenceSystem CRS_WGS84;
    private static final CoordinateReferenceSystem CRS_UTM;
    private static final CoordinateTransformFactory CTF = new CoordinateTransformFactory();

    static {
        CRSFactory f = new CRSFactory();
        CRS_WGS84 = f.createFromParameters("EPSG:4326", "+proj=longlat +datum=WGS84 +no_defs");
        CRS_UTM = f.createFromParameters("EPSG:32637", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
    }

    private Crs() {
    }

    public static Geometry toUtm(Geometry wgs84) {
        return transform(wgs84, CTF.createTransform(CRS_WGS84, CRS_UTM), UTM_FACTORY);
    }

    public static Geometry toWgs84(Geometry utm) {
        return transform(utm, CTF.createTransform(CRS_UTM, CRS_WGS84), WGS84_FACTORY);
    }

    public static Coordinate toWgs84(Coordinate utm) {
        CoordinateTransform t = CTF.createTransform(CRS_UTM, CRS_WGS84);
        ProjCoordinate out = new ProjCoordinate();
        t.transform(new ProjCoordinate(utm.x, utm.y), out);
        return new Coordinate(out.x, out.y);
    }

    /**
     * UTM → WGS 84 for many coordinates in a row: one transform, reused. Not thread-safe — one per use.
     */
    public static java.util.function.UnaryOperator<Coordinate> utmToWgs84() {
        CoordinateTransform t = CTF.createTransform(CRS_UTM, CRS_WGS84);
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        return c -> {
            src.x = c.x;
            src.y = c.y;
            t.transform(src, dst);
            return new Coordinate(dst.x, dst.y);
        };
    }

    private static Geometry transform(Geometry g, CoordinateTransform t, GeometryFactory target) {
        Geometry copy = target.createGeometry(g);
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                src.x = seq.getX(i);
                src.y = seq.getY(i);
                t.transform(src, dst);
                seq.setOrdinate(i, 0, dst.x);
                seq.setOrdinate(i, 1, dst.y);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        copy.geometryChanged();
        return copy;
    }
}
