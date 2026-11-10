// Black holes (lens HoleField): Schwarzschild in isotropic coordinates round each, u = M / (2r):
// ds^2 = -((1 - u) / (1 + u))^2 dt^2 + (1 + u)^4 |dx|^2, so A = -((1 + u) / (1 - u))^2, D = (1 + u)^-4, each hole's
// added in (superposed). Its horizon is r = M / 2 (u = 1): light inside it is lost. Spin left out (spin 0).
// Data (HoleField): [i] hole i: xyz its centre (view space), w its mass M (blocks).

void fieldSample(vec3 x, inout LensFieldSample s) {
    int holes = fieldCount();
    for (int i = 0; i < holes; i += 1) {
        vec4 hole = fieldData(i);
        float m = hole.w;
        vec3 rel = x - hole.xyz;
        float r = max(length(rel), 1e-6);
        float u = 0.5 * m / r;
        if (u > 0.95) lensFieldAbsorb(s);
        u = min(u, 0.95);
        vec3 er = rel / r;
        float onePlus = 1.0 + u;
        float oneMinus = 1.0 - u;
        float a = -(onePlus * onePlus) / (oneMinus * oneMinus);
        float d = 1.0 / (onePlus * onePlus * onePlus * onePlus);
        // (dA/dr = 4u (1 + u) / (r (1 - u)^3); dD/dr = 4u / (r (1 + u)^5).)
        float daDr = 4.0 * u * onePlus / (r * oneMinus * oneMinus * oneMinus);
        float ddDr = 4.0 * u / (r * onePlus * onePlus * onePlus * onePlus * onePlus);
        lensFieldAddIsotropic(s, a + 1.0, daDr * er, d - 1.0, ddDr * er);
        // (A tenth of the way to the horizon: the bend is gentle on that scale, sharp near it.)
        lensFieldStep(s, max(0.1 * (r - 0.5 * m), 0.02));
    }
}

// ---- Splitting (the tessellation stage): where an edge's curve can't be told from three of its points ----
// Near a hole (within 8 r_s of it: 48 / distance pieces, by its shadow's edge), and round the nearest hole's
// camera-hole axis, both ways (the primary images' Einstein ring behind the hole, the secondary images' behind the
// camera): pieces small against the distance from the axis, K = 33 (2 r_s / D)^(1/4), within K / 2 blocks of it.
const float fieldMost = 32.0;

// The nearest hole (to the camera), -1 none.
int fieldNearest() {
    int nearest = -1;
    float best = 1e30;
    for (int i = 0; i < fieldCount(); i += 1) {
        float d = length(fieldData(i).xyz);
        if (d < best) {
            best = d;
            nearest = i;
        }
    }
    return nearest;
}

// The least distance from the centre of the line of sight to view-space point p (or of p itself from it).
float fieldSightline(vec3 p, vec3 centre) {
    vec3 d = normalize(p);
    return min(length(centre - d * max(dot(centre, d), 0.0)), length(p - centre));
}

// About how far (radians) the image of edge a, b strays off straight, bent by the source of r_s rs at centre: its bend
// 2 r_s / b (b by fieldSightline) falls as 1 / theta, theta the angle off the centre, so an edge spanning angle L strays
// by about bend (L / theta)^2 / 4; twice that, to be safe.
const float fieldMeasured = 0.125; // pixels: beyond this the bend's measured (fieldNear)
float fieldCurve(vec3 a, vec3 b, vec3 centre, float rs) {
    float least = max(min(fieldSightline(a, centre), fieldSightline(b, centre)), rs);
    float span = acos(clamp(dot(normalize(a), normalize(b)), -1.0, 1.0));
    float theta = least / max(length(centre), rs);
    return 0.5 * (2.0 * rs / least) * (span / theta) * (span / theta);
}

float fieldPieces(vec3 a, vec3 b) {
    vec3 m = 0.5 * (a + b);
    float edge = length(b - a);
    float pieces = 1.0;
    for (int i = 0; i < fieldCount(); i += 1) {
        float rs = 2.0 * fieldData(i).w;
        float distance = length(m - fieldData(i).xyz);
        if (distance < 8.0 * rs) pieces = max(pieces, ceil(48.0 / max(distance, 1e-3)));
    }
    int k = fieldNearest();
    if (k >= 0) {
        vec3 c = fieldData(k).xyz;
        float rs = 2.0 * fieldData(k).w;
        float D = max(length(c), rs);
        float K = 33.0 * sqrt(sqrt(min(2.0 * rs / D, 1.0)));
        vec3 u = c / max(length(c), 1e-6);
        float t = dot(m - c, u);
        if (t > -(D + 1e4) && t < 1e4) {
            float off = length(m - c - t * u);
            if (off < 0.5 * K) pieces = max(pieces, ceil(K * edge / max(off, 1e-3)));
        }
    }
    return min(pieces, fieldMost);
}

// Whether the tessellation stage must measure triangle a, b, c's bend (lens its edges' midpoints): an edge's image
// straying more than fieldMeasured pixels off straight by fieldCurve, or the triangle magnified by the Einstein ring.
// (Bending alone needs no more vertices, only its change across the triangle.)
bool fieldNear(vec3 a, vec3 b, vec3 c) {
    float pixel = RelativityLensData[11].x;
    for (int i = 0; i < fieldCount(); i += 1) {
        vec3 centre = fieldData(i).xyz;
        float rs = 2.0 * fieldData(i).w;
        if (rs <= 0.0) continue;
        if (max(fieldCurve(a, b, centre, rs), max(fieldCurve(b, c, centre, rs), fieldCurve(c, a, centre, rs))) > fieldMeasured * pixel) return true;
        // (Near the Einstein ring, where the triangle's magnified 1 / |1 - (theta_E / theta)^4| (theta_E^2 at most
        // 2 r_s / D): measured if it could come out 8 pixels across.)
        float least = min(fieldSightline(a, centre), min(fieldSightline(b, centre), fieldSightline(c, centre)));
        float span = max(acos(clamp(dot(normalize(a), normalize(b)), -1.0, 1.0)), max(acos(clamp(dot(normalize(b), normalize(c)), -1.0, 1.0)), acos(clamp(dot(normalize(c), normalize(a)), -1.0, 1.0))));
        float far = max(length(centre), rs);
        float theta = max(least, rs) / far;
        float ring = 2.0 * rs / far / (theta * theta);
        if (span / max(abs(1.0 - ring * ring), 1e-3) > 8.0 * pixel) return true;
    }
    return false;
}
