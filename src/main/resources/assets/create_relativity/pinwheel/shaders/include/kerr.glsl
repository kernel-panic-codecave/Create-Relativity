// Kerr light rays, shared by the Kerr table pass (kerr_table.fsh) and the lens pass (black_hole_lens.fsh).
//
// Kerr-Schild coordinates on a local frame L = (game z, game x, game y): spin axis L.z (game up), and positive spin
// turns L.x toward L.y (game z toward game x), matching the physics core. The inverse metric is
// g^uv = eta^uv - h l^u l^v, so with H = (eta^uv p_u p_v - h (l.p - p_t)^2) / 2 a ray needs only the scalar
// h (l.p - p_t)^2 and its gradient: dx/dlambda = p - h l (l.p - p_t), dp/dlambda = grad[h (l.p - p_t)^2] / 2.
// KerrSchildHamiltonianTest mirrors this code.

// Spinning holes: rays passing within KERR_REGION r_s are marched through the Kerr metric instead of the
// Schwarzschild table, starting at most KERR_START r_s out.
const float MIN_KERR_SPIN = 0.05;
const float KERR_REGION = 20.0;
const float KERR_START = 80.0;
const int KERR_STEPS = 140;
const float KERR_STEP = 0.1; // fraction of the radius per step

vec3 toLocal(vec3 game) { return vec3(game.z, game.x, game.y); }
vec3 toGame(vec3 local) { return vec3(local.y, local.z, local.x); }

// Spheroidal radius r, h = 2 M r^3 / (r^4 + a^2 z^2) and the null vector l at local point x.
float kerrProfile(vec3 x, float m, float a, out float h, out vec3 l) {
    float a2 = a * a;
    float w = dot(x, x) - a2;
    float r2 = 0.5 * w + sqrt(0.25 * w * w + a2 * x.z * x.z);
    float r = sqrt(max(r2, 1e-12));
    h = 2.0 * m * r * r2 / (r2 * r2 + a2 * x.z * x.z);
    float d = r2 + a2;
    l = vec3((r * x.x + a * x.y) / d, (r * x.y - a * x.x) / d, x.z / r);
    return r;
}

// Hamilton's equations, with dp = grad[h (l.p - p_t)^2] / 2 in closed form through r's implicit gradient
// grad r = (r^2 x + a^2 z e_z) / (r (2 r^2 - (|x|^2 - a^2))). Returns r.
float kerrDerivative(vec3 x, vec3 p, float pt, float m, float a, out vec3 dx, out vec3 dp) {
    float a2 = a * a;
    float w = dot(x, x) - a2;
    float s = sqrt(0.25 * w * w + a2 * x.z * x.z);
    float r2 = max(0.5 * w + s, 1e-12);
    float r = sqrt(r2);
    vec3 gradR = vec3(r2 * x.x, r2 * x.y, (r2 + a2) * x.z) / (2.0 * r * max(s, 1e-12));
    float q = r2 * r2 + a2 * x.z * x.z;
    float h = 2.0 * m * r * r2 / q;
    float dhdr = 2.0 * m * r2 * (3.0 * q - 4.0 * r2 * r2) / (q * q);
    vec3 gradH = gradR * dhdr + vec3(0.0, 0.0, -4.0 * m * r * r2 * a2 * x.z / (q * q));
    float d = r2 + a2;
    vec3 l = vec3((r * x.x + a * x.y) / d, (r * x.y - a * x.x) / d, x.z / r);
    float lp = dot(l, p) - pt;
    float along = x.x * p.x + x.y * p.y;
    float across = x.y * p.x - x.x * p.y;
    float dfdr = (along * d - (r * along + a * across) * 2.0 * r) / (d * d) - x.z * p.z / r2;
    vec3 gradF = vec3((r * p.x - a * p.y) / d, (r * p.y + a * p.x) / d, p.z / r) + gradR * dfdr;
    dx = p - h * lp * l;
    dp = 0.5 * lp * lp * gradH + h * lp * gradF;
    return r;
}

// The same, with the affine parameter running backwards: following light back in time from where it's seen.
float kerrBackward(vec3 x, vec3 p, float pt, float m, float a, out vec3 dx, out vec3 dp) {
    float r = kerrDerivative(x, p, pt, m, a, dx, dp);
    dx = -dx;
    dp = -dp;
    return r;
}

// Prograde Keplerian angular velocity (signed with the spin) of a thin disk at cylindrical radius rho.
float kerrDiskOmega(float rho, float m, float spin) {
    float absA = abs(spin) * m;
    float rbl = sqrt(max(rho * rho - absA * absA, 1e-6));
    float sq = sqrt(m);
    return sign(spin) * sq / (rbl * sqrt(rbl) + absA * sq);
}

// Null momentum (p, p_t) of a ray leaving local point x along coordinate direction d: u = (1, k d), g(u, u) = 0,
// then p = g u. Returns h there.
float kerrNullMomentum(vec3 x, vec3 d, float m, float a, out vec3 p, out float pt) {
    float h0;
    vec3 l0;
    kerrProfile(x, m, a, h0, l0);
    float ld = dot(l0, d);
    float qa = 1.0 + h0 * ld * ld;
    float qb = 2.0 * h0 * ld;
    float qc = h0 - 1.0;
    float k = (-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa);
    float lu = 1.0 + k * ld;
    pt = -1.0 + h0 * lu;
    p = k * d + h0 * l0 * lu;
    return h0;
}

// ---- General spacetime (M10): every source together --------------------------------------------------------------
// Sources add into the inverse metric, g^uv = eta^uv + sum dg^uv (physics-core ComposedSpacetime, MetricSource), so
// light's Hamiltonian H = g^uv p_u p_v / 2 is flat's plus one term per source. Coordinates are game axes relative to the
// camera, time t in blocks (0 now); state x4 = (x, t), p4 = (p, p_t). Each source is three vec4s in Sources:
//   [0] centre (camera-relative, at t = 0), type: 1 Kerr hole, 2 weak-field mass, 3 warp bubble
//   [1] Kerr: (m, a); mass: (m signed, softening); bubble: (radius, sigma, speed)
//   [2] bubble: direction of motion (unit, game axes)
const int MAX_SOURCES = 8;
uniform int SourceCount;
uniform vec4 Sources[3 * MAX_SOURCES];

const float SOURCE_KERR = 1.0;
const float SOURCE_MASS = 2.0;
const float SOURCE_BUBBLE = 3.0;

// Alcubierre's shape function f(r) and its slope f'(r).
float bubbleShape(float r, float radius, float sigma, out float slope) {
    float norm = 1.0 / (2.0 * tanh(sigma * radius));
    float u = sigma * (r + radius);
    float w = sigma * (r - radius);
    float cu = cosh(clamp(u, -40.0, 40.0));
    float cw = cosh(clamp(w, -40.0, 40.0));
    slope = sigma * (1.0 / (cu * cu) - 1.0 / (cw * cw)) * norm;
    return (tanh(u) - tanh(w)) * norm;
}

// Hamilton's equations for light at (x4, p4) through every source: dx4 = dH/dp4, dp4 = -dH/dx4. Forward in time.
void spacetimeDerivative(vec4 x4, vec4 p4, out vec4 dx4, out vec4 dp4) {
    vec3 x = x4.xyz;
    vec3 p = p4.xyz;
    float pt = p4.w;
    // Flat: H = (|p|^2 - p_t^2) / 2.
    vec3 dx = p;
    float dt = -pt;
    vec3 dp = vec3(0.0);
    float dpt = 0.0;
    for (int i = 0; i < SourceCount; i += 1) {
        vec4 s0 = Sources[3 * i];
        vec4 s1 = Sources[3 * i + 1];
        if (s0.w == SOURCE_KERR) {
            // dH = -h (l.p - p_t)^2 / 2 (kerrDerivative, on the hole's local axes).
            vec3 local = toLocal(x - s0.xyz);
            vec3 kdx;
            vec3 kdp;
            kerrDerivative(local, toLocal(p), pt, s1.x, s1.y, kdx, kdp);
            dx += toGame(kdx) - p; // kerrDerivative's dx includes flat's p
            dp += toGame(kdp);
            float h;
            vec3 l;
            kerrProfile(local, s1.x, s1.y, h, l);
            dt += h * (dot(l, toLocal(p)) - pt);
        } else if (s0.w == SOURCE_MASS) {
            // dH = phi (p_t^2 + |p|^2), phi = -m / sqrt(r^2 + eps^2).
            vec3 d = x - s0.xyz;
            float s2 = dot(d, d) + s1.y * s1.y;
            float sr = sqrt(s2);
            float phi = -s1.x / sr;
            vec3 gradPhi = s1.x * d / (s2 * sr);
            float pp = pt * pt + dot(p, p);
            dx += 2.0 * phi * p;
            dt += 2.0 * phi * pt;
            dp -= gradPhi * pp;
        } else if (s0.w == SOURCE_BUBBLE) {
            // dH = -v f (n.p) p_t - v^2 f^2 (n.p)^2 / 2, the bubble's centre moving at v n.
            vec3 n = Sources[3 * i + 2].xyz;
            float v = s1.z;
            vec3 d = x - (s0.xyz + v * n * x4.w);
            float r = length(d);
            float slope;
            float f = bubbleShape(r, s1.x, s1.y, slope);
            vec3 gradF = r > 1e-6 ? slope * d / r : vec3(0.0);
            float dtF = -v * dot(n, gradF);
            float np = dot(n, p);
            dx += (-v * f * pt - v * v * f * f * np) * n;
            dt += -v * f * np;
            float k = v * np * pt + v * v * f * np * np;
            dp += k * gradF;
            dpt += k * dtF;
        }
    }
    dx4 = vec4(dx, dt);
    dp4 = vec4(dp, dpt);
}

// The same, followed back in time (light traced from where it's seen).
void spacetimeBackward(vec4 x4, vec4 p4, out vec4 dx4, out vec4 dp4) {
    spacetimeDerivative(x4, p4, dx4, dp4);
    dx4 = -dx4;
    dp4 = -dp4;
}

// The inverse metric g^uv at x4 (indices: x, y, z, t), for setting up momenta.
mat4 spacetimeInverseMetric(vec4 x4) {
    mat4 g = mat4(1.0);
    g[3][3] = -1.0;
    for (int i = 0; i < SourceCount; i += 1) {
        vec4 s0 = Sources[3 * i];
        vec4 s1 = Sources[3 * i + 1];
        if (s0.w == SOURCE_KERR) {
            float h;
            vec3 l;
            kerrProfile(toLocal(x4.xyz - s0.xyz), s1.x, s1.y, h, l);
            vec4 lUp = vec4(toGame(l), -1.0);
            g -= h * outerProduct(lUp, lUp);
        } else if (s0.w == SOURCE_MASS) {
            vec3 d = x4.xyz - s0.xyz;
            float phi = -s1.x / sqrt(dot(d, d) + s1.y * s1.y);
            g += 2.0 * phi * mat4(1.0);
        } else if (s0.w == SOURCE_BUBBLE) {
            vec3 n = Sources[3 * i + 2].xyz;
            float v = s1.z;
            float slope;
            float f = bubbleShape(length(x4.xyz - (s0.xyz + v * n * x4.w)), s1.x, s1.y, slope);
            vec4 shift = vec4(-v * f * n, 0.0);
            vec4 time = vec4(0.0, 0.0, 0.0, 1.0);
            g += outerProduct(shift, time) + outerProduct(time, shift) - outerProduct(shift, shift);
        }
    }
    return g;
}

// The momentum of light at x4 whose coordinate velocity is along d (u = (k d, 1) null, p = g u): solves
// g_uv u^u u^v = 0 for the future root.
vec4 spacetimeNullMomentum(vec4 x4, vec3 d) {
    mat4 g = inverse(spacetimeInverseMetric(x4));
    vec4 dd = vec4(d, 0.0);
    vec4 tt = vec4(0.0, 0.0, 0.0, 1.0);
    float qa = dot(dd, g * dd);
    float qb = 2.0 * dot(dd, g * tt);
    float qc = dot(tt, g * tt);
    float k = (-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa);
    return g * (k * dd + tt);
}

// The light seen at x4 looking along dir, set up to be followed back in time: the photon arriving along -dir.
vec4 spacetimeArriving(vec4 x4, vec3 dir) {
    return spacetimeNullMomentum(x4, -dir);
}

struct KerrRay {
    int status;      // 0 escaped (exitPos/exitDir set), 1 captured, 2 not marched
    vec3 exitPos;    // camera-relative
    vec3 exitDir;
    float diskRho;   // radius of the first disk crossing (0: none)
    float diskG;     // its frequency shift, observed / emitted
    float diskAngle; // its azimuth, game atan(z, x)
    float diskDist;  // its distance from the camera
};

// Marches the view ray along dir past a hole (camera-relative centre toHole, Schwarzschild radius rs, signed spin):
// the photon arriving along -dir, followed back in time (kerrBackward). diskRadii (inner, outer; inner 0 for no disk) records the first equatorial crossing inside the
// disk, whatever is in front of it; the caller checks that against the scene.
KerrRay kerrMarch(vec3 toHole, float rs, float spin, vec3 dir, vec2 diskRadii) {
    KerrRay ray;
    ray.status = 2;
    ray.exitPos = vec3(0.0);
    ray.exitDir = dir;
    ray.diskRho = 0.0;
    ray.diskG = 0.0;
    ray.diskAngle = 0.0;
    ray.diskDist = 0.0;
    if (abs(spin) < MIN_KERR_SPIN || rs <= 0.0) return ray;
    float rc = length(toHole);
    float along = dot(toHole, dir);
    float impact = length(toHole - dir * along);
    if (impact > KERR_REGION * rs || (along < 0.0 && rc > KERR_REGION * rs)) return ray;

    float m = 0.5 * rs;
    float a = spin * m;
    float horizon = m + sqrt(max(m * m - a * a, 0.0));
    // Start at the camera, or where the straight ray enters the start sphere.
    float startRadius = KERR_START * rs;
    float t0 = 0.0;
    if (rc > startRadius) {
        float disc = along * along - (rc * rc - startRadius * startRadius);
        t0 = along - sqrt(max(disc, 0.0));
    }
    vec3 x = toLocal(dir * t0 - toHole);
    vec3 d = toLocal(dir);

    vec3 p;
    float pt;
    float hCamera = kerrNullMomentum(x, -d, m, a, p, pt);
    bool withDisk = diskRadii.x > 0.0;

    vec3 k1x;
    vec3 k1p;
    for (int s = 0; s < KERR_STEPS; s += 1) {
        float r = kerrBackward(x, p, pt, m, a, k1x, k1p);
        if (r < horizon * 1.02) {
            ray.status = 1;
            return ray;
        }
        if (dot(x, x) > startRadius * startRadius * 1.0201 && dot(x, k1x) > 0.0) break;
        float dl = KERR_STEP * max(r, 0.5 * m) / max(length(k1x), 1e-6);
        vec3 k2x;
        vec3 k2p;
        kerrBackward(x + 0.5 * dl * k1x, p + 0.5 * dl * k1p, pt, m, a, k2x, k2p);
        vec3 k3x;
        vec3 k3p;
        kerrBackward(x + 0.5 * dl * k2x, p + 0.5 * dl * k2p, pt, m, a, k3x, k3p);
        vec3 k4x;
        vec3 k4p;
        kerrBackward(x + dl * k3x, p + dl * k3p, pt, m, a, k4x, k4p);
        vec3 xNext = x + dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        vec3 pNext = p + dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);

        // Equatorial crossing inside the disk: the first one is what's seen.
        if (withDisk && ray.diskRho == 0.0 && (x.z > 0.0) != (xNext.z > 0.0)) {
            float f = x.z / (x.z - xNext.z);
            vec3 hit = mix(x, xNext, f);
            float rho = length(hit.xy);
            if (rho >= diskRadii.x && rho <= diskRadii.y) {
                float absA = abs(a);
                float rbl = sqrt(max(rho * rho - absA * absA, 1e-6));
                float sq = sqrt(m);
                float r32 = rbl * sqrt(rbl);
                float omega = kerrDiskOmega(rho, m, spin);
                float ut = (r32 + absA * sq) / (pow(rbl, 0.75) * sqrt(max(r32 - 3.0 * m * sqrt(rbl) + 2.0 * absA * sq, 1e-5)));
                // p is the arriving photon's own momentum: angular momentum per energy about the spin axis.
                vec3 pHit = mix(p, pNext, f);
                float lz = (hit.x * pHit.y - hit.y * pHit.x) / (-pt);
                ray.diskRho = rho;
                ray.diskG = 1.0 / (sqrt(max(1.0 - hCamera, 1e-4)) * ut * (1.0 - omega * lz));
                ray.diskAngle = atan(hit.x, hit.y); // game atan(z, x)
                ray.diskDist = length(toGame(hit) + toHole);
            }
        }
        x = xNext;
        p = pNext;
    }
    kerrBackward(x, p, pt, m, a, k1x, k1p);
    ray.status = 0;
    ray.exitPos = toGame(x) + toHole;
    ray.exitDir = normalize(toGame(k1x));
    return ray;
}
