// Kerr light paths for the vertex lens (KerrPaths.kt; read by vertex_lens_core.glsl): for each table texel, a view ray
// through the spinning hole, followed back in time (kerr.glsl, prepended), recorded as where it is seen from the hole
// at fixed points along it. A path is parameterised by sigma = +-sqrt(r - r_min) (Boyer-Lindquist r, its closest
// approach r_min; minus coming in, plus going out), smooth through the closest approach, sampled at
// sigma = sigma_max (k / 7)^2: on the way in from where the march starts (the camera, or the start sphere), on the
// way out to the start sphere. Each sample is the direction from the hole in equal-area coordinates round the
// hole-to-camera axis (relativityPathQ), continuous everywhere but straight behind the hole.

uniform vec3 HoleGame;    // camera-relative, game axes
uniform float HoleRs;
uniform float HoleSpin;
uniform mat3 ViewToGame;
uniform vec2 TableTan;    // tan of the table's half extents in view x and y

in vec2 texCoord;
layout(location = 0) out vec4 Path0; // r_min, status (0 unmarched, 1 escapes, 2 captured, 3 incomplete), closest approach
layout(location = 1) out vec4 Path1; // in 1, in 2
layout(location = 2) out vec4 Path2; // in 3, in 4
layout(location = 3) out vec4 Path3; // in 5, in 6
layout(location = 4) out vec4 Path4; // in 7 (the start), out 1
layout(location = 5) out vec4 Path5; // out 2, out 3
layout(location = 6) out vec4 Path6; // out 4, out 5
layout(location = 7) out vec4 Path7; // out 6, out 7 (the start sphere)

vec3 pathE1;
vec3 pathE2;
vec3 pathE3;

// Equal-area (Lambert) coordinates of unit direction n round pathE1: 2 sin(psi / 2) (cos chi, sin chi).
vec2 pathQ(vec3 n) {
    vec2 t = vec2(dot(n, pathE2), dot(n, pathE3));
    return t / sqrt(max(0.5 * (1.0 + dot(n, pathE1)), 1e-6));
}

vec2 qOfLocal(vec3 x) {
    return pathQ(normalize(toGame(x)));
}

// Largest root of the radial potential below r0 (the closest approach), or -1 if it stays positive to the horizon.
float closestApproach(vec3 x, vec3 p, float pt, float m, float a, float r0, float horizon) {
    float e = -pt;
    float l = x.x * p.y - x.y * p.x;
    float c = x.z / r0;
    float s = sqrt(max(1.0 - c * c, 1e-8));
    float pTheta = (x.x * p.x + x.y * p.y) * c / s - r0 * s * p.z;
    float q = pTheta * pTheta + c * c * (l * l / (s * s) - a * a * e * e);
    float rest = q + (l - a * e) * (l - a * e);
    float hi = r0;
    float ratio = pow(horizon * 1.02 / r0, 1.0 / 64.0);
    for (int i = 0; i < 64; i += 1) {
        float lo = hi * ratio;
        float k = e * (lo * lo + a * a) - a * l;
        if (k * k - (lo * lo - 2.0 * m * lo + a * a) * rest < 0.0) {
            float below = lo;
            float above = hi;
            for (int j = 0; j < 24; j += 1) {
                float mid = 0.5 * (below + above);
                float km = e * (mid * mid + a * a) - a * l;
                if (km * km - (mid * mid - 2.0 * m * mid + a * a) * rest < 0.0) below = mid; else above = mid;
            }
            return 0.5 * (below + above);
        }
        hi = lo;
    }
    return -1.0;
}

void main() {
    Path0 = vec4(0.0); Path1 = vec4(0.0); Path2 = vec4(0.0); Path3 = vec4(0.0);
    Path4 = vec4(0.0); Path5 = vec4(0.0); Path6 = vec4(0.0); Path7 = vec4(0.0);
    float rs = HoleRs;
    if (rs <= 0.0 || abs(HoleSpin) < MIN_KERR_SPIN) return;
    pathE1 = normalize(-HoleGame);
    pathE2 = cross(vec3(0.0, 1.0, 0.0), pathE1);
    if (dot(pathE2, pathE2) < 1e-6) pathE2 = cross(vec3(1.0, 0.0, 0.0), pathE1);
    pathE2 = normalize(pathE2);
    pathE3 = cross(pathE1, pathE2);

    vec3 dir = normalize(ViewToGame * vec3((texCoord * 2.0 - 1.0) * TableTan, -1.0));
    float rc = length(HoleGame);
    float along = dot(HoleGame, dir);
    float impact = length(HoleGame - dir * along);
    if (impact > KERR_REGION * rs || (along < 0.0 && rc > KERR_REGION * rs)) return;

    float m = 0.5 * rs;
    float a = HoleSpin * m;
    float horizon = m + sqrt(max(m * m - a * a, 0.0));
    float startRadius = KERR_START * rs;
    float t0 = 0.0;
    if (rc > startRadius) t0 = along - sqrt(max(along * along - (rc * rc - startRadius * startRadius), 0.0));
    vec3 x = toLocal(dir * t0 - HoleGame);
    vec3 p;
    float pt;
    kerrNullMomentum(x, -toLocal(dir), m, a, p, pt);

    vec3 k1x;
    vec3 k1p;
    float r = kerrBackward(x, p, pt, m, a, k1x, k1p);
    float r0 = r;
    bool inbound = dot(x, k1x) < 0.0;
    float rMin = r0;
    float status = 1.0;
    if (inbound) {
        rMin = closestApproach(x, p, pt, m, a, r0, horizon);
        if (rMin < 0.0) {
            status = 2.0;
            rMin = 1.05 * horizon;
        }
    }
    float rOut = startRadius;

    vec2 qIn[8];
    vec2 qOut[8];
    vec2 q = qOfLocal(x);
    for (int k = 0; k < 8; k += 1) {
        qIn[k] = q;
        qOut[k] = q;
    }
    int nextIn = 6; // samples in 7 .. 1 on the way in; 7 is the start
    int nextOut = 1;
    bool goingOut = !inbound;
    float rPrevious = r;
    vec2 qPrevious = q;
    bool done = false;
    for (int s = 0; s < 2 * KERR_STEPS && !done; s += 1) {
        if (r < horizon * 1.02) {
            if (status == 1.0) status = 3.0;
            break;
        }
        float dl = 0.5 * KERR_STEP * max(r, 0.5 * m) / max(length(k1x), 1e-6);
        vec3 k2x;
        vec3 k2p;
        kerrBackward(x + 0.5 * dl * k1x, p + 0.5 * dl * k1p, pt, m, a, k2x, k2p);
        vec3 k3x;
        vec3 k3p;
        kerrBackward(x + 0.5 * dl * k2x, p + 0.5 * dl * k2p, pt, m, a, k3x, k3p);
        vec3 k4x;
        vec3 k4p;
        kerrBackward(x + dl * k3x, p + dl * k3p, pt, m, a, k4x, k4p);
        x += dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        p += dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
        r = kerrBackward(x, p, pt, m, a, k1x, k1p);
        q = qOfLocal(x);
        if (!goingOut) {
            // Down through the inbound samples, then the closest approach where r turns.
            while (nextIn >= 1) {
                float target = rMin + (r0 - rMin) * pow(float(nextIn) / 7.0, 4.0);
                if (r > target) break;
                qIn[nextIn] = mix(qPrevious, q, (rPrevious - target) / max(rPrevious - r, 1e-9));
                nextIn -= 1;
            }
            if (r > rPrevious && status == 1.0) {
                goingOut = true;
                vec2 closest = rPrevious < r ? qPrevious : q;
                qIn[0] = closest;
                qOut[0] = closest;
                for (int k = nextIn; k >= 1; k -= 1) qIn[k] = closest; // passed within a step of r_min
                nextIn = 0;
            }
        }
        if (goingOut) {
            while (nextOut <= 7) {
                float target = rMin + (rOut - rMin) * pow(float(nextOut) / 7.0, 4.0);
                if (r < target) break;
                qOut[nextOut] = mix(qPrevious, q, (target - rPrevious) / max(r - rPrevious, 1e-9));
                nextOut += 1;
            }
            if (nextOut > 7) done = true;
        }
        rPrevious = r;
        qPrevious = q;
    }
    if (status == 1.0 && !done) status = 3.0;
    if (!inbound) {
        qIn[0] = qIn[7];
        qOut[0] = qIn[7];
    }
    if (status == 2.0) {
        // Falls in: only the way in exists; flat past its last sample, so interpolation near it stays on it.
        qIn[0] = qIn[1];
        for (int k = 0; k < 8; k += 1) qOut[k] = qIn[1];
    }
    Path0 = vec4(rMin, status, qIn[0]);
    Path1 = vec4(qIn[1], qIn[2]);
    Path2 = vec4(qIn[3], qIn[4]);
    Path3 = vec4(qIn[5], qIn[6]);
    Path4 = vec4(qIn[7], qOut[1]);
    Path5 = vec4(qOut[2], qOut[3]);
    Path6 = vec4(qOut[4], qOut[5]);
    Path7 = vec4(qOut[6], qOut[7]);
}
