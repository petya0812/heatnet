package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.TestSupport.REF;

/** Routes stay within the area of the data ({@link PlanParams#dataBoundaryM}). */
class DataBoundaryTest {

    /**
     * random-7: the area is the convex hull of the data with the margin and holds the network and the OKS; the routes
     * stay inside. (Its routes without the boundary go round in a concave bay of the data — inside the hull too: the
     * boundary catches routes round the outside of the whole dataset, see docs/algorithm.md.)
     */
    @Test
    void routesStayInsideTheData() throws Exception {
        LoadedInput in = LoadedInput.read(Paths.get("test-data", "random-7.geojson"), REF);
        InputModel model = in.getModel();
        PlanParams params = new PlanParams();
        DataBoundary boundary = DataBoundary.build(model, in.obstacleSource(), params);
        PreparedGeometry area = PreparedGeometryFactory.prepare(boundary.getArea());
        for (InputModel.Segment s : model.getSegments().values()) {
            assertTrue(area.covers(s.line.buffer(params.dataBoundaryM - 1.5)), "the network with the margin: " + s.id);
        }
        for (InputModel.Oks o : model.getOks()) {
            assertTrue(area.covers(o.connectionPoint), o.id);
        }

        params.threads = 1;
        assertEquals(0, outside(new Planner(REF, params).plan(model, in.obstacleSource(), new Diagnostics()), area));
        assertEquals(boundary.getArea().getArea(), boundary.getArea().convexHull().getArea(),
                boundary.getArea().getArea() * 1e-6, "a convex area");
    }

    private static int outside(List<Variant> variants, PreparedGeometry area) {
        int n = 0;
        for (Variant v : variants) {
            for (Variant.NewSegment s : v.segments) {
                if (!area.covers(s.line)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void offWithZeroMargin() throws Exception {
        LoadedInput in = LoadedInput.read(Paths.get("test-data", "small.geojson"), REF);
        PlanParams params = new PlanParams();
        params.dataBoundaryM = 0;
        assertNull(DataBoundary.build(in.getModel(), in.obstacleSource(), params));
    }
}
