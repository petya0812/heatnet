#!/usr/bin/env python3
"""Test data generator for the heat network case (input format: technical appendix, section 1, plus the earlier
objects oks_future, oks_existing and upstream_object_id that the service still reads).

Geometry is designed in metres of UTM 37N (EPSG:32637) around a Moscow-like origin and written in
EPSG:4326. No third-party packages.

  python3 tools/gen_testdata.py small  -o test-data/small.geojson
  python3 tools/gen_testdata.py chain  -o test-data/chain.geojson
  python3 tools/gen_testdata.py star   -o test-data/star.geojson
  python3 tools/gen_testdata.py expensive -o test-data/expensive.geojson
  python3 tools/gen_testdata.py chambers  -o test-data/chambers.geojson
  python3 tools/gen_testdata.py dirty  -o test-data/dirty.geojson
  python3 tools/gen_testdata.py dense  -o test-data/dense.geojson --seed 5 --oks 40
  python3 tools/gen_testdata.py dense  -o test-data/dense-big.geojson --seed 11 --size 5000 --oks 180
  python3 tools/gen_testdata.py random -o test-data/random-42.geojson --seed 42
  python3 tools/gen_testdata.py large  -o test-data/generated/large.geojson --bytes 3000000000
"""
import argparse
import json
import math
import random
import sys

ORIGIN_E = 413000.0
ORIGIN_N = 6179000.0


def utm37_to_lonlat(e, n):
    """Inverse transverse Mercator (Snyder), WGS 84, zone 37N. Accuracy well below 1 cm here."""
    a = 6378137.0
    f = 1 / 298.257223563
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    k0 = 0.9996
    x = e - 500000.0
    m = n / k0
    mu = m / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    s1, c1, t1 = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    cc = ep2 * c1 ** 2
    tt = t1 ** 2
    nn = a / math.sqrt(1 - e2 * s1 ** 2)
    rr = a * (1 - e2) / (1 - e2 * s1 ** 2) ** 1.5
    d = x / (nn * k0)
    lat = phi1 - (nn * t1 / rr) * (d ** 2 / 2 - (5 + 3 * tt + 10 * cc - 4 * cc ** 2 - 9 * ep2) * d ** 4 / 24
                                   + (61 + 90 * tt + 298 * cc + 45 * tt ** 2 - 252 * ep2 - 3 * cc ** 2) * d ** 6 / 720)
    lon = math.radians(39.0) + (d - (1 + 2 * tt + cc) * d ** 3 / 6
                                + (5 - 2 * cc + 28 * tt - 3 * cc ** 2 + 8 * ep2 + 24 * tt ** 2) * d ** 5 / 120) / c1
    return round(math.degrees(lon), 9), round(math.degrees(lat), 9)


def ll(x, y):
    return list(utm37_to_lonlat(ORIGIN_E + x, ORIGIN_N + y))


def point(x, y):
    return {"type": "Point", "coordinates": ll(x, y)}


def line(pts):
    return {"type": "LineString", "coordinates": [ll(x, y) for x, y in pts]}


def ring(pts):
    pts = list(pts)
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    return [ll(x, y) for x, y in pts]


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def polygon(outer, *holes):
    return {"type": "Polygon", "coordinates": [ring(outer)] + [ring(h) for h in holes]}


def feature(geom, **props):
    return {"type": "Feature", "geometry": geom, "properties": props}


class Builder:
    def __init__(self):
        self.features = []

    def add(self, geom, **props):
        self.features.append(feature(geom, **props))

    def source(self, fid, x, y):
        self.add(point(x, y), id=fid, object_type="source")

    def segment(self, fid, pts, dn, flow, up):
        self.add(line(pts), id=fid, object_type="heat_network", diameter=dn, flow_tph=flow, upstream_object_id=up)

    def chamber(self, fid, x, y, dn, up):
        self.add(point(x, y), id=fid, object_type="heat_chamber", diameter=dn, upstream_object_id=up)

    def oks(self, fid, outer, flow, cp):
        self.add(polygon(outer), id=fid, object_type="oks_future", flow_tph=flow, heat_load=round(flow / 16.0, 4))
        self.add(point(*cp), id=fid + "-cp", object_type="oks_connection_point", oks_id=fid)

    def building(self, fid, outer):
        self.add(polygon(outer), id=fid, object_type="oks_existing")

    def restriction(self, fid, rtype, geom):
        self.add(geom, id=fid, object_type="restriction", restriction_type=rtype)

    def dump(self, path):
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"type": "FeatureCollection", "features": self.features}, f, ensure_ascii=False)


def small():
    """Hand-made scenario: every restriction type of table 2 except the railway and the typical cases of the rules.

    Situations the data exercises (the optimiser chooses the concrete tie-ins):
      m1 (from the source) is loaded close to its capacity: every connection overloads it, so the
         informational layer always reports it short of capacity; m4 is drawn downstream-to-upstream;
      oks-1  north, crosses gas and road, passes between two buildings;
      oks-2  next to a park and branch b1 (b1 runs out of capacity as well);
      oks-3  south, crosses tram tracks, next to chamber k1;
      oks-4  large flow (DN 250 > DN of the nearby chambers) -> a wide new chamber, chain short of capacity;
      oks-5  enclosed by water -> no route (unconnected, asserted);
      oks-6  far from the network with a small flow -> the length limit forces a larger DN;
      metro-1 unknown restriction type -> diagnostics warning (asserted).
    """
    b = Builder()
    b.source("src", 0, 0)
    b.segment("m1", [(0, 0), (200, 0)], 400, 900.0, "src")
    b.chamber("k1", 200, 0, 400, "m1")
    b.segment("m2", [(200, 0), (400, 0)], 300, 350.0, "k1")
    b.chamber("k2", 400, 0, 300, "m2")
    b.segment("m3", [(400, 0), (600, 0)], 200, 140.0, "k2")
    b.chamber("k3", 600, 0, 200, "m3")
    # m4 is drawn from its downstream end to test orientation detection
    b.segment("m4", [(800, 0), (600, 0)], 150, 50.0, "k3")
    b.segment("b1", [(400, 0), (400, 300)], 150, 60.0, "k2")

    b.restriction("road-1", "road", polygon(rect(-50, 20, 380, 35)))
    b.restriction("gas-1", "gas_pipeline", line([(-50, 55), (380, 55)]))
    b.restriction("tram-1", "tram_tracks", polygon(rect(150, -45, 450, -35)))
    b.restriction("cable-1", "power_cable", line([(520, -150), (520, 60)]))
    b.restriction("park-1", "park", polygon(rect(425, 190, 460, 250)))
    b.restriction("social-1", "social_area", polygon(rect(620, 40, 700, 120)))
    b.restriction("prohibited-1", "prohibited_site", polygon(rect(-40, -150, 40, -100)))
    b.restriction("water-1", "water", polygon(rect(660, 160, 780, 280), rect(690, 190, 750, 250)))
    b.restriction("metro-1", "metro", polygon(rect(850, -50, 900, 50)))

    b.building("bld-1", rect(60, 70, 100, 100))
    b.building("bld-2", rect(135, 70, 175, 100))
    b.building("bld-3", rect(300, 100, 340, 140))

    b.oks("oks-1", rect(100, 120, 140, 160), 10.0, (120, 120))
    b.oks("oks-2", rect(480, 200, 530, 240), 30.0, (480, 220))
    b.oks("oks-3", rect(180, -120, 230, -80), 5.0, (205, -80))
    b.oks("oks-4", rect(560, -60, 610, -30), 160.0, (596, -30))
    b.oks("oks-5", rect(705, 205, 735, 235), 8.0, (720, 205))
    b.oks("oks-6", rect(-60, 250, -20, 290), 2.0, (-40, 250))
    return b


def _trunk(b):
    """Straight trunk from the source along y = 0 with chambers every 250 m."""
    b.source("src", 0, 0)
    prev = "src"
    flow = 900.0
    for i in range(4):
        sid = f"t{i + 1}"
        b.segment(sid, [(i * 250, 0), ((i + 1) * 250, 0)], 500, flow, prev)
        cid = f"k{i + 1}"
        b.chamber(cid, (i + 1) * 250, 0, 500, sid)
        prev = cid
        flow -= 150


def chain():
    """Five small OKS in a row 350 m from the network: one shared trunk is far cheaper than five routes.
    12 t/h each: alone an OKS needs DN 80 (limit 327 m); the length limit raises it to DN 100 (419 m), which covers
    the route (appendix 2.3: the minimal DN that meets both the flow and the length limit).

    Asserted: the best variant is joint, its score is below the separate one.
    """
    b = Builder()
    _trunk(b)
    b.restriction("road-1", "road", polygon(rect(-50, 150, 1050, 165)))
    for i in range(5):
        x = 300 + i * 80
        b.oks(f"oks-{i + 1}", rect(x, 350, x + 40, 390), 12.0, (x + 20, 350))
    b.building("bld-1", rect(420, 220, 470, 260))
    return b


def star():
    """Six OKS around one spot 400 m from the network, connection points facing the centre: a single branching
    chamber would get 7 segments, the limit of 4 (R-NET-5) must split them. 15 t/h each: DN 100 alone, one nomenclature
    up (DN 125, 554 m) covers the farthest route. Asserted: 0 rule violations."""
    b = Builder()
    _trunk(b)
    cx, cy = 500, 450
    for i in range(6):
        ang = 2 * math.pi * i / 6
        ox, oy = cx + 70 * math.cos(ang), cy + 70 * math.sin(ang)
        # square 30x30 whose side facing the centre holds the connection point
        ux, uy = math.cos(ang), math.sin(ang)
        px, py = -uy, ux
        c0 = (ox - 15 * ux - 15 * px, oy - 15 * uy - 15 * py)
        c1 = (ox - 15 * ux + 15 * px, oy - 15 * uy + 15 * py)
        c2 = (ox + 15 * ux + 15 * px, oy + 15 * uy + 15 * py)
        c3 = (ox + 15 * ux - 15 * px, oy + 15 * uy - 15 * py)
        cp = (ox - 15 * ux, oy - 15 * uy)
        b.oks(f"oks-{i + 1}", [c0, c1, c2, c3], 15.0, cp)
    return b


def expensive():
    """Appendix 2.5, clarification 15: an OKS is connected even when its route costs more than the penalty.

    oks-near is 60 m from the trunk; oks-far (40 t/h) is 650 m away: DN 150 (raised from DN 125 by the length
    limit), about 79 million with the attachment — more than the penalty of 120 million would add to S, yet it is
    connected. Asserted: both are connected.
    """
    b = Builder()
    _trunk(b)
    b.oks("oks-near", rect(80, 60, 120, 100), 10.0, (100, 60))
    b.oks("oks-far", rect(580, 650, 620, 690), 40.0, (600, 650))
    return b


def chambers():
    """Appendix 2.4, clarification 11: a chamber within 10 m of the attachment point with a free branch is used.

    kA and kB are 7 m apart on a DN 300 line; the OKS lies closer to kB. Both are existing chambers, a tie-in
    into either costs the same. Asserted: the network is joined in an existing chamber, not through a new one.
    """
    b = Builder()
    b.source("src", 0, 0)
    b.segment("s1", [(0, 0), (200, 0)], 300, 100.0, "src")
    b.chamber("kA", 200, 0, 300, "s1")
    b.segment("s2", [(200, 0), (207, 0)], 300, 100.0, "kA")
    b.chamber("kB", 207, 0, 100, "s2")
    b.segment("s3", [(207, 0), (400, 0)], 300, 100.0, "kB")
    b.oks("oks-1", rect(185, 100, 225, 140), 5.0, (205, 100))
    return b


def dirty():
    """Edge cases of real data in one file. Asserted: no crash, every case in diagnostics, 0 rule violations.

      m2 passes through chamber k2 without a break (k2 lies inside m2), m3 starts at k2;
      0.3–1.5 m gaps between segments and chambers; a segment with a non-standard DN 350;
      a cycle of two segments detached from the source; a heat_network with Point geometry;
      oks-in: connection point 2 m inside the building; oks-off: 3 m outside the boundary;
      oks-big: flow above the largest DN; oks-nocp: no connection point; an orphan connection point;
      a road multipolygon with a hole, overlapping park and social area, point supports, a bow-tie building,
      a duplicate id.
    """
    b = Builder()
    b.source("src", 0, 0)
    b.segment("m1", [(0, 0), (199.6, 0)], 400, 500.0, "src")             # 0.4 m short of k1
    b.chamber("k1", 200, 0, 400, "m1")
    b.segment("m2", [(200.3, 0), (600, 0)], 350, 300.0, "k1")            # passes through k2 at x=400
    b.chamber("k2", 400, 0.2, 350, "m2")
    b.segment("m3", [(401.5, 0.2), (401.5, 300)], 150, 40.0, "k2")      # starts 1.5 m from k2
    b.segment("x1", [(900, 500), (1000, 500)], 100, 5.0, "x2")          # cycle, detached from the source
    b.segment("x2", [(1000, 500), (900, 500)], 100, 5.0, "x1")
    b.add(point(50, 50), id="bad-geom", object_type="heat_network", diameter=100, flow_tph=1.0,
          upstream_object_id="src")
    # restrictions
    road = {"type": "MultiPolygon", "coordinates": [
        [ring(rect(-50, 30, 700, 45))],
        [ring(rect(250, 100, 420, 200)), ring(rect(290, 130, 380, 170))]]}
    b.restriction("road-mp", "road", road)
    b.restriction("park-1", "park", polygon(rect(460, 60, 560, 140)))
    b.restriction("social-1", "social_area", polygon(rect(520, 100, 600, 180)))
    b.add({"type": "MultiPoint", "coordinates": [ll(150, 70), ll(170, 70), ll(190, 70)]},
          id="supports", object_type="restriction", restriction_type="support")
    b.restriction("support-1", "support", point(230, 60))
    b.add({"type": "Polygon", "coordinates": [ring([(600, 250), (650, 300), (650, 250), (600, 300)])]},
          id="bowtie", object_type="oks_existing")
    b.building("bld-1", rect(60, 80, 100, 120))
    b.building("bld-1", rect(700, 80, 740, 120))                          # duplicate id
    # OKS
    b.oks("oks-ok", rect(120, 100, 160, 140), 10.0, (140, 100))
    b.add(polygon(rect(300, 250, 340, 290)), id="oks-in", object_type="oks_future", flow_tph=12.0, heat_load=0.7)
    b.add(point(320, 252), id="oks-in-cp", object_type="oks_connection_point", oks_id="oks-in")
    b.add(polygon(rect(480, 250, 520, 290)), id="oks-off", object_type="oks_future", flow_tph=6.0, heat_load=0.4)
    b.add(point(500, 247), id="oks-off-cp", object_type="oks_connection_point", oks_id="oks-off")
    b.oks("oks-big", rect(40, -120, 80, -80), 30000.0, (60, -80))
    b.add(polygon(rect(200, -120, 240, -80)), id="oks-nocp", object_type="oks_future", flow_tph=5.0, heat_load=0.3)
    b.add(point(999, 999), id="orphan-cp", object_type="oks_connection_point", oks_id="no-such-oks")
    b.oks("oks-k2", rect(385, -90, 425, -50), 8.0, (405, -50))             # near k2 (chamber inside m2)
    return b


def _inside_quad(q, x, y):
    """Point in a convex quad (counter-clockwise or clockwise)."""
    sign = 0
    for i in range(4):
        ax, ay = q[i]
        bx, by = q[(i + 1) % 4]
        c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        if c != 0:
            if sign == 0:
                sign = 1 if c > 0 else -1
            elif (c > 0) != (sign > 0):
                return False
    return True


def _inset(q, d):
    """Shrink a convex quad towards its centroid by roughly d metres."""
    cx = sum(p[0] for p in q) / 4
    cy = sum(p[1] for p in q) / 4
    out = []
    for x, y in q:
        l = math.hypot(x - cx, y - cy)
        k = max(0.0, (l - d * 1.42) / l)
        out.append((cx + (x - cx) * k, cy + (y - cy) * k))
    return out


def _street(a, b, w):
    """Street polygon of width w along the centreline a-b, extended by w/2 at both ends."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    l = math.hypot(dx, dy)
    ux, uy = dx / l, dy / l
    px, py = -uy * w / 2, ux * w / 2
    a2 = (a[0] - ux * w / 2, a[1] - uy * w / 2)
    b2 = (b[0] + ux * w / 2, b[1] + uy * w / 2)
    return [(a2[0] + px, a2[1] + py), (b2[0] + px, b2[1] + py), (b2[0] - px, b2[1] - py), (a2[0] - px, a2[1] - py)]


def _rot_rect(cx, cy, w, h, ang):
    c, s_ = math.cos(ang), math.sin(ang)
    pts = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
    return [(cx + x * c - y * s_, cy + x * s_ + y * c) for x, y in pts]


def dense_city(seed, size_m=2000, n_oks=40, step=150):
    """Dense city without downloads: jittered street grid (widths 12-26 m, one street as a multipolygon),
    rotated buildings of different sizes, tram along a main street, a railway corridor (forbidden, table 2),
    a river, gas and cables along streets, a heat network tree along sidewalks with chambers at crossings."""
    rnd = random.Random(seed)
    n = size_m // step
    nodes = {}
    for i in range(n + 1):
        for j in range(n + 1):
            jit = 0 if i in (0, n) or j in (0, n) else step * 0.12
            nodes[(i, j)] = (i * step + rnd.uniform(-jit, jit), j * step + rnd.uniform(-jit, jit))
    width = {}
    b = Builder()
    main = set(range(0, n + 1, 4))
    street_polys = []
    for i in range(n + 1):
        for j in range(n + 1):
            for di, dj in ((1, 0), (0, 1)):
                k = (i + di, j + dj)
                if k not in nodes:
                    continue
                w = 26 if (dj == 0 and j in main) or (di == 0 and i in main) else rnd.choice([12, 14, 16])
                width[((i, j), k)] = w
                street_polys.append(((i, j), k, _street(nodes[(i, j)], nodes[k], w)))
    for idx, (a, k, poly) in enumerate(street_polys):
        if idx < len(street_polys) - 3:
            b.restriction(f"road-{idx}", "road", polygon(poly))
    # the last three street pieces as one multipolygon
    b.restriction("road-mp", "road", {"type": "MultiPolygon",
                                      "coordinates": [[ring(p)] for _, _, p in street_polys[-3:]]})
    # tram along the main horizontal street j = 4, narrower, inside the road
    tj = 4 if n > 4 else n // 2
    for i in range(n):
        b.restriction(f"tram-{i}", "tram_tracks", polygon(_street(nodes[(i, tj)], nodes[(i + 1, tj)], 7)))
    # railway corridor (forbidden, table 2) along the vertical street i = n - 2 shifted into the blocks
    ri = max(1, n - 3)
    strips = []  # (a, b, half width) where no building may stand
    for j in range(n):
        a, c = nodes[(ri, j)], nodes[(ri, j + 1)]
        a, c = (a[0] + 45, a[1]), (c[0] + 45, c[1])
        b.restriction(f"rail-{j}", "railway", polygon(_street(a, c, 18)))
        strips.append((a, c, 9 + 12))
    # river across the south part
    ry = step * 1.5
    river = [(-30, ry + 20), (size_m * 0.3, ry - 25), (size_m * 0.6, ry + 30), (size_m + 30, ry)]
    for q in range(len(river) - 1):
        b.restriction(f"water-{q}", "water", polygon(_street(river[q], river[q + 1], 40)))
        strips.append((river[q], river[q + 1], 20 + 12))

    def near_strip(x, y):
        for (ax, ay), (bx, by), hw in strips:
            dx, dy = bx - ax, by - ay
            t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
            if math.hypot(x - ax - t * dx, y - ay - t * dy) < hw:
                return True
        return False
    # sidewalk offset: network on one side, gas on the other
    s = 26 / 2 + 4
    # heat network: BFS spanning tree over the grid from the source at the west main street
    src = (0, tj)
    b.source("src", nodes[src][0] + s, nodes[src][1] + s)
    parent = {src: None}
    order = [src]
    q = [src]
    while q:
        cur = q.pop(0)
        nb = [(cur[0] + 1, cur[1]), (cur[0], cur[1] + 1), (cur[0], cur[1] - 1), (cur[0] - 1, cur[1])]
        for k in nb:
            if k in nodes and k not in parent:
                parent[k] = cur
                order.append(k)
                q.append(k)
    children = {k: 0 for k in nodes}
    for k, p_ in parent.items():
        if p_ is not None:
            children[p_] += 1
    # flows: accumulate a fake downstream consumption so that DN decreases away from the source
    load = {k: 0.0 for k in nodes}
    for k in reversed(order):
        load[k] += rnd.uniform(3, 12)
        if parent[k] is not None:
            load[parent[k]] += load[k]
    caps = [(50, 3.5), (65, 8.3), (80, 13.2), (100, 22.3), (125, 40.2), (150, 65.1), (200, 152.3), (250, 274.9),
            (300, 437.4), (400, 943.1), (500, 1663.4), (600, 2627.7), (700, 3735.1)]

    def dn_for(f):
        for dn, cap in caps:
            if cap >= f * 1.25:
                return dn
        return 800
    for k in order[1:]:
        p_ = parent[k]
        seg = f"n{k[0]}_{k[1]}"
        up = "src" if p_ == src else f"c{p_[0]}_{p_[1]}"
        a = (nodes[p_][0] + s, nodes[p_][1] + s)
        c = (nodes[k][0] + s, nodes[k][1] + s)
        f = round(load[k] * 0.8, 1)
        b.segment(seg, [a, c], dn_for(load[k]), f, up)
        b.chamber(f"c{k[0]}_{k[1]}", c[0], c[1], dn_for(load[k]), seg)
    # gas along every third horizontal street, cables along every fourth vertical one (opposite sidewalk)
    for j in range(1, n, 3):
        b.restriction(f"gas-{j}", "gas_pipeline", line([(nodes[(i, j)][0] - s, nodes[(i, j)][1] - s) for i in range(n + 1)]))
    for i in range(2, n, 4):
        b.restriction(f"cable-{i}", "power_cable", line([(nodes[(i, j)][0] - s + 3, nodes[(i, j)][1] - s + 3)
                                                        for j in range(n + 1)]))
    # blocks: buildings, parks, social areas
    blds = []
    for i in range(n):
        for j in range(n):
            quad = [nodes[(i, j)], nodes[(i + 1, j)], nodes[(i + 1, j + 1)], nodes[(i, j + 1)]]
            inner = _inset(quad, 30)
            roll = rnd.random()
            if roll < 0.05:
                b.restriction(f"park-{i}-{j}", "park", polygon(inner))
                continue
            school = None
            if roll < 0.08:
                school = _inset(quad, 45)
                b.restriction(f"school-{i}-{j}", "social_area", polygon(school))
            placed = []
            for _ in range(40):
                w, h = rnd.uniform(14, 40), rnd.uniform(12, 30)
                ang = rnd.uniform(-0.3, 0.3) + rnd.choice([0, math.pi / 2])
                cx = rnd.uniform(min(p[0] for p in inner), max(p[0] for p in inner))
                cy = rnd.uniform(min(p[1] for p in inner), max(p[1] for p in inner))
                pts = _rot_rect(cx, cy, w, h, ang)
                if not all(_inside_quad(inner, x, y) for x, y in pts):
                    continue
                if any(near_strip(x, y) for x, y in pts + [(cx, cy)]):
                    continue
                if school and any(_inside_quad(_inset(school, -12), x, y) for x, y in pts + [(cx, cy)]):
                    continue
                r = math.hypot(w, h) / 2
                if any(math.hypot(cx - ox, cy - oy) < r + orr + 8 for ox, oy, orr in placed):
                    continue
                placed.append((cx, cy, r))
                blds.append((pts, quad))
    rnd.shuffle(blds)
    for idx, (pts, quad) in enumerate(blds):
        if idx < n_oks:
            # connection point in the middle of the side closest to the block edge (the street)
            best = None
            for e in range(4):
                mx = (pts[e][0] + pts[(e + 1) % 4][0]) / 2
                my = (pts[e][1] + pts[(e + 1) % 4][1]) / 2
                dq = min(math.hypot(mx - (quad[t][0] + quad[(t + 1) % 4][0]) / 2,
                                    my - (quad[t][1] + quad[(t + 1) % 4][1]) / 2) for t in range(4))
                if best is None or dq < best[0]:
                    best = (dq, (mx, my))
            flow = round(rnd.choice([rnd.uniform(1, 8), rnd.uniform(8, 40), rnd.uniform(40, 150)]), 2)
            b.oks(f"oks-{idx + 1}", pts, flow, best[1])
        else:
            b.building(f"bld-{idx + 1}", pts)
    return b


def random_city(seed, size_m=2000, n_oks=30):
    """Grid city: roads every 200 m, blocks with buildings, a tree network along the streets."""
    rnd = random.Random(seed)
    b = Builder()
    step = 200
    road_w = 14
    # roads: horizontal and vertical strips
    for i in range(0, size_m // step + 1):
        c = i * step
        b.restriction(f"road-h{i}", "road", polygon(rect(-20, c - road_w / 2, size_m + 20, c + road_w / 2)))
        b.restriction(f"road-v{i}", "road", polygon(rect(c - road_w / 2, -20, c + road_w / 2, size_m + 20)))
    # network: trunk along the middle street (y = mid + 12), branches along vertical streets (x + 12)
    mid = (size_m // step // 2) * step
    ty = mid + 12
    b.source("src", 0, ty)
    prev = "src"
    xs = list(range(0, size_m + 1, step))
    trunk_flow = 1500.0
    seg_n = 0
    ch_n = 0
    for i in range(len(xs) - 1):
        x0 = xs[i] + (12 if i > 0 else 0)
        x1 = xs[i + 1] + 12
        seg_n += 1
        sid = f"t{seg_n}"
        dn = 500 if i < 3 else 400 if i < 6 else 300
        b.segment(sid, [(x0, ty), (x1, ty)], dn, round(trunk_flow, 1), prev)
        trunk_flow *= 0.8
        ch_n += 1
        cid = f"k{ch_n}"
        b.chamber(cid, x1, ty, dn, sid)
        prev = cid
        # branches north and south from the chamber along the vertical street
        for direction in (1, -1):
            if rnd.random() < 0.6:
                length = rnd.choice([200, 400, 600])
                y_end = ty + direction * length
                y_end = max(30, min(size_m - 30, y_end))
                seg_n += 1
                bid = f"b{seg_n}"
                bdn = rnd.choice([150, 200, 250])
                cap = {150: 65.1, 200: 152.3, 250: 274.9}[bdn]
                b.segment(bid, [(x1, ty), (x1, y_end)], bdn, round(cap * rnd.uniform(0.5, 0.95), 1), cid)
    # utilities along some streets (offset inside the block edge)
    for i in range(1, size_m // step, 3):
        c = i * step
        b.restriction(f"gas-{i}", "gas_pipeline", line([(-20, c - 30), (size_m + 20, c - 30)]))
    for i in range(2, size_m // step, 4):
        c = i * step
        b.restriction(f"cable-{i}", "power_cable", line([(c - 30, -20), (c - 30, size_m + 20)]))
    b.restriction("tram-1", "tram_tracks", polygon(rect(-20, mid - step - 5, size_m + 20, mid - step + 5)))
    # blocks: buildings, parks, some OKS
    blocks = [(bx, by) for bx in range(0, size_m, step) for by in range(0, size_m, step)]
    rnd.shuffle(blocks)
    oks_blocks = set(blocks[:n_oks])
    park_blocks = set(blocks[n_oks:n_oks + 3])
    social_blocks = set(blocks[n_oks + 3:n_oks + 5])
    water_block = blocks[n_oks + 5]
    bld_n = 0
    oks_n = 0
    for bx, by in blocks:
        inner = (bx + 25, by + 25, bx + step - 25, by + step - 25)
        if (bx, by) in park_blocks:
            b.restriction(f"park-{bx}-{by}", "park", polygon(rect(*inner)))
            continue
        if (bx, by) in social_blocks:
            b.restriction(f"social-{bx}-{by}", "social_area", polygon(rect(bx + 40, by + 40, bx + 120, by + 120)))
        if (bx, by) == water_block:
            b.restriction("water-1", "water", polygon(rect(bx + 30, by + 60, bx + 170, by + 110)))
            continue
        # 2x2 grid of lots, one lot may host the OKS
        lots = [(bx + 30 + i * 75, by + 30 + j * 75) for i in range(2) for j in range(2)]
        oks_lot = rnd.randrange(4) if (bx, by) in oks_blocks else -1
        for li, (lx, ly) in enumerate(lots):
            w = rnd.uniform(30, 55)
            h = rnd.uniform(30, 55)
            if li == oks_lot:
                oks_n += 1
                outer = rect(lx, ly, lx + w, ly + h)
                side = rnd.choice(["s", "n", "w", "e"])
                cp = {"s": (lx + w / 2, ly), "n": (lx + w / 2, ly + h), "w": (lx, ly + h / 2),
                      "e": (lx + w, ly + h / 2)}[side]
                flow = round(rnd.choice([rnd.uniform(1, 8), rnd.uniform(8, 40), rnd.uniform(40, 120)]), 2)
                b.oks(f"oks-{oks_n}", outer, flow, cp)
            elif rnd.random() < 0.8:
                bld_n += 1
                b.building(f"bld-{bld_n}", rect(lx, ly, lx + w, ly + h))
    return b


def large(path, target_bytes, seed=7):
    """Streams a big file: the small scenario plus millions of far-away building footprints."""
    rnd = random.Random(seed)
    base = small().features
    written = 0
    with open(path, "w", encoding="utf-8") as f:
        head = '{"type":"FeatureCollection","features":['
        f.write(head)
        written += len(head)
        first = True
        for ft in base:
            s = json.dumps(ft, ensure_ascii=False)
            f.write(("" if first else ",") + s)
            first = False
            written += len(s) + 1
        i = 0
        # buildings on a 60 m grid starting 5 km east of the scenario
        per_row = 400
        while written < target_bytes:
            gx = 5000 + (i % per_row) * 60
            gy = -12000 + (i // per_row) * 60
            w = 20 + rnd.random() * 25
            h = 20 + rnd.random() * 25
            s = json.dumps(feature(polygon(rect(gx, gy, gx + w, gy + h)), id=f"bulk-{i}",
                                   object_type="oks_existing"), ensure_ascii=False)
            f.write("," + s)
            written += len(s) + 1
            i += 1
        f.write("]}")
    return i


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["small", "chain", "star", "expensive", "chambers", "dirty", "dense", "random",
                                     "large"])
    ap.add_argument("--size", type=int, default=2000)
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--oks", type=int, default=30)
    ap.add_argument("--bytes", type=int, default=3_000_000_000)
    args = ap.parse_args()
    if args.kind == "small":
        small().dump(args.out)
    elif args.kind == "chain":
        chain().dump(args.out)
    elif args.kind == "star":
        star().dump(args.out)
    elif args.kind == "expensive":
        expensive().dump(args.out)
    elif args.kind == "chambers":
        chambers().dump(args.out)
    elif args.kind == "dirty":
        dirty().dump(args.out)
    elif args.kind == "dense":
        dense_city(args.seed, size_m=args.size, n_oks=args.oks).dump(args.out)
    elif args.kind == "random":
        random_city(args.seed, n_oks=args.oks).dump(args.out)
    else:
        n = large(args.out, args.bytes)
        print(f"bulk features: {n}", file=sys.stderr)


if __name__ == "__main__":
    main()
