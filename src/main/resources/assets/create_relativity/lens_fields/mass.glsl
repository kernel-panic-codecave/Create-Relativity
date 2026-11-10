// Masses (lens MassField): the weak field of a mass M (either sign), softened by e inside the body,
// Phi = -M / sqrt(r^2 + e^2): A = -1 + 2 Phi, D = 1 + 2 Phi (PointMass), each mass's added in.
// Data (MassField): [2i] mass i: xyz its centre (view space), w M (blocks); [2i + 1] x its softening e.

void fieldSample(vec3 x, inout LensFieldSample s) {
    int masses = fieldCount();
    for (int i = 0; i < masses; i += 1) {
        vec4 mass = fieldData(2 * i);
        float e = fieldData(2 * i + 1).x;
        vec3 rel = x - mass.xyz;
        float r2 = dot(rel, rel) + e * e;
        float r = sqrt(r2);
        float phi = -mass.w / r;
        vec3 gPhi = (mass.w / (r2 * r)) * rel;
        lensFieldAddIsotropic(s, 2.0 * phi, 2.0 * gPhi, 2.0 * phi, 2.0 * gPhi);
        lensFieldStep(s, max(0.2 * r, 0.02));
    }
}

// ---- Splitting (the tessellation stage) ----
// A mass M bends light as a hole of r_s = 2M does outside it (the weak field's Einstein ring is the same): near it
// (within 8 r_s, beyond its softening's core), and round the camera-mass axis of the strongest; none for a negative
// mass (it spreads light: no ring).
const float fieldMost = 32.0;

int fieldStrongest() {
    int strongest = -1;
    float best = 0.0;
    for (int i = 0; i < fieldCount(); i += 1) {
        float m = fieldData(2 * i).w;
        float pull = m / max(length(fieldData(2 * i).xyz), 1.0);
        if (m > 0.0 && pull > best) {
            best = pull;
            strongest = i;
        }
    }
    return strongest;
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
        float rs = 2.0 * fieldData(2 * i).w;
        if (rs <= 0.0) continue;
        float distance = max(length(m - fieldData(2 * i).xyz), fieldData(2 * i + 1).x);
        if (distance < 8.0 * rs) pieces = max(pieces, ceil(48.0 / max(distance, 1e-3)));
    }
    int k = fieldStrongest();
    if (k >= 0) {
        vec3 c = fieldData(2 * k).xyz;
        float rs = 2.0 * fieldData(2 * k).w;
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
        vec3 centre = fieldData(2 * i).xyz;
        float rs = 2.0 * fieldData(2 * i).w;
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
