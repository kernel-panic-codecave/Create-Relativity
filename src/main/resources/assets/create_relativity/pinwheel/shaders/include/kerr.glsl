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
