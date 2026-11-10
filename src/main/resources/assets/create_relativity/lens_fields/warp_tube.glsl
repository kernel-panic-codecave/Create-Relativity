// Warp tubes (lens WarpTubeField): Alcubierre's push of v along a spine (a maglev train's, round its carriages), the
// bubble's tanh wall on the distance rho to it (a capsule), the axis the nearest segment's, turning to the next across
// a joint's plane over the wall's width (as tube.glsl's): along it B = v f, C = -v^2 f^2 (WarpTube). Its observers ride
// with it (as a bubble's).
// Data (WarpTubeField): [0] x tubes, y points; [1 + j] tube j: x radius, y first point, z points, w v; [5 + i] point i:
// xyz (view space; each spine reversed: light followed arriving).

const int fieldMaxTubes = 4;
const int fieldPoints = 1 + fieldMaxTubes;
const float fieldSharpness = 8.0; // KrasnikovTube's sigma, times the radius

vec3 fieldPoint(int i) { return fieldData(fieldPoints + i).xyz; }

void fieldSample(vec3 x, inout LensFieldSample s) {
    int tubes = int(fieldData(0).x + 0.5);
    for (int j = 0; j < fieldMaxTubes && j < tubes; j += 1) {
        vec4 info = fieldData(1 + j);
        float radius = info.x;
        int first = int(info.y + 0.5);
        int count = int(info.z + 0.5);
        float sharp = fieldSharpness / radius;
        // The nearest segment (its closest point, how far along it).
        int best = -1;
        float bestD = 1e30;
        float bestU = 0.0;
        vec3 bestAt = vec3(0.0);
        for (int g = first; g < first + count - 1; g += 1) {
            vec3 a = fieldPoint(g);
            vec3 e = fieldPoint(g + 1) - a;
            // (Beyond the ball round the segment, farther than the nearest so far: skipped.)
            vec3 mid = a + 0.5 * e;
            if (length(x - mid) - 0.5 * length(e) > bestD) continue;
            float l2 = dot(e, e);
            float u = 0.0;
            if (l2 > 0.0) u = clamp(dot(x - a, e) / l2, 0.0, 1.0);
            vec3 at = a + e * u;
            float dist = length(x - at);
            if (dist < bestD) { bestD = dist; best = g; bestU = u; bestAt = at; }
        }
        if (best < 0) continue;
        float rho = bestD;
        // f and its slope.
        float tp = tanh(sharp * (rho + radius));
        float tm = tanh(sharp * (rho - radius));
        float norm = 1.0 / (2.0 * tanh(sharp * radius));
        float f = (tp - tm) * norm;
        float slope = sharp * ((1.0 - tp * tp) - (1.0 - tm * tm)) * norm;
        vec3 gRho = vec3(0.0);
        if (rho > 1e-6) gRho = (x - bestAt) / rho;
        // The axis, and across a joint's plane (within the wall's width of it) the next segment's.
        vec3 axis = normalize(fieldPoint(best + 1) - fieldPoint(best));
        vec3 turn = vec3(0.0);
        vec3 gTurn = vec3(0.0);
        float jointGap = 1e30;
        float width = 1.0 / sharp;
        if (best + 2 < first + count) {
            vec3 next = normalize(fieldPoint(best + 2) - fieldPoint(best + 1));
            vec3 m = normalize(axis + next);
            float dj = dot(x - fieldPoint(best + 1), m);
            jointGap = abs(dj);
            if (dj > -2.0 * width) {
                float w = smoothstep(-width, width, dj);
                float dw = 0.0;
                if (abs(dj) < width) { float q = (dj + width) / (2.0 * width); dw = 6.0 * q * (1.0 - q) / (2.0 * width); }
                turn = next - axis;
                gTurn = dw * m;
                axis = normalize(mix(axis, next, w));
            }
        }
        if (best > first) {
            vec3 previous = normalize(fieldPoint(best) - fieldPoint(best - 1));
            vec3 m = normalize(previous + axis);
            float dj = dot(x - fieldPoint(best), m);
            jointGap = min(jointGap, abs(dj));
            if (dj < 2.0 * width && length(turn) == 0.0) {
                float w = 1.0 - smoothstep(-width, width, dj);
                float dw = 0.0;
                if (abs(dj) < width) { float q = (dj + width) / (2.0 * width); dw = -6.0 * q * (1.0 - q) / (2.0 * width); }
                turn = previous - axis;
                gTurn = dw * m;
                axis = normalize(mix(axis, previous, w));
            }
        }
        float v = info.w;
        vec3 gF = slope * gRho;
        float b = v * f;
        if (f > 1e-6) lensFieldAddAxial(s, f, axis,
            0.0, vec3(0.0),
            b, v * gF,
            -b * b, (-2.0 * v * b) * gF,
            turn, gTurn,
            0.0, 1.0, b);
        // (Safe: clear of the wall's band, and of a joint's.)
        float wallGap = abs(rho - radius) - 3.0 * width;
        float safe = max(min(wallGap, jointGap - 2.0 * width), 0.02);
        lensFieldStep(s, safe);
    }
}

// x's distance from tube j's spine (its nearest segment).
float fieldSpineDistance(vec3 x, int j) {
    vec4 info = fieldData(1 + j);
    int first = int(info.y + 0.5);
    int count = int(info.z + 0.5);
    float best = 1e30;
    for (int g = first; g < first + count - 1; g += 1) {
        vec3 p = fieldPoint(g);
        vec3 e = fieldPoint(g + 1) - p;
        float l2 = dot(e, e);
        float u = 0.0;
        if (l2 > 0.0) u = clamp(dot(x - p, e) / l2, 0.0, 1.0);
        best = min(best, length(x - p - e * u));
    }
    return best;
}

// ---- Splitting (the tessellation stage): by a wall, whose bend three points of an edge can't follow ----
// An edge across a tube's wall band (its ends' and middle's distances from the spine reaching both into the band's
// inner half and its outer half: three walls' widths each side of the radius) is cut into pieces about fieldPixels on screen at its distance (no
// finer than a quarter of the wall's width), at most fieldMost: vertices where the wall's bend shows, each of them
// running the whole lens.
const float fieldMost = 16.0;
const float fieldPixels = 4.0;

float fieldPieces(vec3 a, vec3 b) {
    vec3 m = 0.5 * (a + b);
    float edge = length(b - a);
    float pixel = fieldData(0).w;
    float pieces = 1.0;
    int tubes = int(fieldData(0).x + 0.5);
    for (int j = 0; j < fieldMaxTubes && j < tubes; j += 1) {
        float radius = fieldData(1 + j).x;
        float width = radius / fieldSharpness;
        float da = fieldSpineDistance(a, j);
        float db = fieldSpineDistance(b, j);
        float dm = fieldSpineDistance(m, j);
        // (Not those merely near it: inside a tube the whole floor is, and cutting it all is ruinous.)
        float lo = min(da, min(db, dm));
        float hi = max(da, max(db, dm));
        if (lo <= radius + 3.0 * width && hi >= radius - 3.0 * width && hi - lo > 0.5 * width) {
            float piece = max(0.25 * width, fieldPixels * pixel * length(m));
            pieces = max(pieces, ceil(edge / piece));
        }
    }
    return min(pieces, fieldMost);
}

// Light runs straight through a warp tube's uniform inside and outside; its walls (fieldPieces) and the image stage's
// outline copies take the rest: no triangle's bend needs measuring for it.
bool fieldNear(vec3 a, vec3 b, vec3 c) {
    return false;
}
