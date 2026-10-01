#veil:buffer veil:camera VeilCamera

// Gravitational lensing by Schwarzschild black holes.
//
// Each view ray is bent in its own orbital plane (hole, camera, ray) using a lookup table of where light seen at
// angle psi from the inward radial, by a camera at u0 = r_s / r_cam, came from (SchwarzschildLens).
//
// The bent ray resamples the camera's own frame (sharp). Only where the frame can't hold the answer is it traced
// through a colour+depth cube captured from the hole (LensEnvironment): rays bent off-screen (most of the photon
// ring points back past the camera), and rays whose frame sample is an object in front of where they bend, which
// they actually pass behind. For that tracing the bent ray is modelled as two straight legs meeting at its
// closest approach to the hole. Near the screen edges the two sources are blended so there's no seam.

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D LensLookup;
uniform sampler2D Environment;      // 3x2 cube-face colour atlas
uniform sampler2D EnvironmentDepth; // matching raw depth
uniform int EnvironmentReady;
uniform vec3 CaptureCenter;         // where the cube was captured, relative to the camera
uniform vec4 Disk;      // nearest hole's accretion disk: x brightness (0 = none), y/z inner/outer radius (blocks),
                        // w spin sense (+1 counter-clockwise from above, -1 clockwise)
uniform float DiskTime; // seconds x c (blocks of light travel), for the disk's rotating turbulence
uniform float DisplayTanHalfFov;    // > 0: the frame was rendered wider than the screen (overscan); this is the
                                    // screen's tan(vertical FOV / 2), and output pixels are cropped back to it

#define MAX_HOLES 8
uniform int HoleCount;
uniform vec4 Holes[MAX_HOLES]; // xyz: centre relative to the camera (blocks), w: Schwarzschild radius (blocks)
uniform vec4 HoleSpins[MAX_HOLES]; // x: spin a/M, signed (+ counter-clockwise about up, seen from above)
uniform int VertexLensing; // 1: world geometry was already bent in the vertex shaders (VertexLens); lens only the sky

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const vec2 LUT_SIZE = vec2(512.0, 1024.0); // BlackHoleLens.LUT_WIDTH / LUT_HEIGHT
const float FACE_SIZE = 256.0;             // LensEnvironment.FACE_SIZE
const float CAPTURE_NEAR = 0.05;           // LensEnvironment.NEAR
const float CAPTURE_FAR = 1024.0;          // LensEnvironment.FAR

// A frame sample nearer than the bend point by more than this many r_s is something the bent ray passes behind.
const float FOREGROUND_MARGIN = 2.0;
// Rays bent this far past the screen edge (in uv) fade from the edge-clamped frame to the capture.
const float EDGE_BLEND = 0.08;
const float SKY_DEPTH = 0.99999;
const int DISK_STEPS = 320;
const float DISK_STEP = 0.03; // radians of orbit angle per step
const int MARCH_STEPS = 32;

// Spinning holes: rays passing within KERR_REGION r_s are marched through the Kerr metric instead of the
// Schwarzschild table, starting at most KERR_START r_s out.
const float MIN_KERR_SPIN = 0.05;
const float KERR_REGION = 20.0;
const float KERR_START = 80.0;
const int KERR_STEPS = 140;
const float KERR_STEP = 0.1; // fraction of the radius per step

const vec3 FACE_FORWARD[6] = vec3[](vec3(1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0), vec3(0, 0, 1), vec3(0, 0, -1));
const vec3 FACE_UP[6] = vec3[](vec3(0, 1, 0), vec3(0, 1, 0), vec3(0, 0, 1), vec3(0, 0, -1), vec3(0, 1, 0), vec3(0, 1, 0));

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

// Distance to the surface at uv; the sky (nothing drawn) counts as infinitely far.
// Ray through an output pixel: the screen's own FOV when overscanning, otherwise the frame's.
vec3 displayRay(vec2 uv) {
    if (DisplayTanHalfFov <= 0.0) return viewRay(uv);
    float aspect = VeilCamera.ProjMat[1][1] / VeilCamera.ProjMat[0][0];
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 view = vec3(ndc.x * DisplayTanHalfFov * aspect, ndc.y * DisplayTanHalfFov, -1.0);
    return normalize((VeilCamera.IViewMat * vec4(view, 0.0)).xyz);
}

// Where a world direction lands in the (possibly overscanned) frame.
vec2 frameUv(vec3 dir) {
    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    return clip.xy / clip.w * 0.5 + 0.5;
}

float sceneDistance(vec2 uv) {
    float depth = texture(DiffuseDepthSampler, uv).r;
    if (depth >= SKY_DEPTH) return 1e30;
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(view.xyz / view.w);
}

// Table axes are sqrt(u0) and sqrt(psi / pi), sampled at texel centres.
vec2 lookup(float u0, float psi) {
    vec2 axes = vec2(sqrt(u0), sqrt(psi / PI));
    return texture(LensLookup, (axes * (LUT_SIZE - 1.0) + 0.5) / LUT_SIZE).rg;
}

// Cube atlas addressing; must mirror LensEnvironment: face f at column f % 3, row f / 3, image x along forward x up.
// Returns the atlas uv and, in z, the cosine between dir and the face's forward axis.
vec3 cubeUv(vec3 dir) {
    vec3 a = abs(dir);
    int face;
    if (a.x >= a.y && a.x >= a.z) {
        face = dir.x > 0.0 ? 0 : 1;
    } else if (a.y >= a.z) {
        face = dir.y > 0.0 ? 2 : 3;
    } else {
        face = dir.z > 0.0 ? 4 : 5;
    }
    vec3 forward = FACE_FORWARD[face];
    vec3 up = FACE_UP[face];
    float f = dot(dir, forward);
    vec2 uv = vec2(dot(dir, cross(forward, up)), dot(dir, up)) / f * 0.5 + 0.5;
    uv = clamp(uv, vec2(0.5 / FACE_SIZE), vec2(1.0 - 0.5 / FACE_SIZE)); // don't bleed into the neighbouring face
    return vec3((vec2(face % 3, face / 3) + uv) / vec2(3.0, 2.0), f);
}

// Distance from the capture centre to the first surface along unit direction dir.
float captureDistance(vec3 dir) {
    vec3 c = cubeUv(dir);
    float z = texture(EnvironmentDepth, c.xy).r * 2.0 - 1.0;
    float viewZ = 2.0 * CAPTURE_NEAR * CAPTURE_FAR / (CAPTURE_FAR + CAPTURE_NEAR - z * (CAPTURE_FAR - CAPTURE_NEAR));
    return viewZ / c.z; // depth is along the face axis; convert to distance along dir
}

// Colour seen along the ray origin + t*dir (camera-relative), traced through the captured cube.
// hit is false if the ray found no captured surface (sky, or beyond the capture's render distance).
vec3 traceEnvironment(vec3 origin, vec3 dir, out bool hit) {
    hit = true;
    vec3 start = origin - CaptureCenter;
    float t = 0.0;
    float step = 0.25;
    for (int i = 0; i < MARCH_STEPS; i++) {
        vec3 p = start + dir * t;
        float r = length(p);
        if (r > 1e-3 && r >= captureDistance(p / r)) {
            return texture(Environment, cubeUv(p / r).xy).rgb;
        }
        t += step;
        step *= 1.35; // geometric steps: fine near the hole, out to ~1000 blocks
    }
    hit = false;
    return texture(Environment, cubeUv(dir).xy).rgb; // sky at infinity
}

// Blackbody colour (approximate sRGB, brightest channel ~1) at [kelvin]; Tanner Helland's fit.
vec3 blackbody(float kelvin) {
    float t = clamp(kelvin, 1000.0, 40000.0) / 100.0;
    float r = 1.0;
    float g = clamp(0.39008158 * log(t) - 0.63184144, 0.0, 1.0);
    float b = 0.0;
    if (t > 66.0) {
        r = clamp(1.29293619 * pow(t - 60.0, -0.1332048), 0.0, 1.0);
        g = clamp(1.12989086 * pow(t - 60.0, -0.0755148), 0.0, 1.0);
    }
    if (t >= 66.0) {
        b = 1.0;
    } else if (t > 19.0) {
        b = clamp(0.54320679 * log(t - 10.0) - 1.19625409, 0.0, 1.0);
    }
    return vec3(r, g, b);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), w.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), w.x), w.y);
}

float turbulence(vec2 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i += 1) {
        sum += amplitude * valueNoise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return sum;
}

// Thin-disk flux shape (1 - sqrt(r_in/r)) (r_in/r)^3, normalised to peak at 1 (peak 0.05667 at r = 1.36 r_in).
float diskFlux(float r, float rIn) {
    float x = rIn / r;
    return max((1.0 - sqrt(x)) * x * x * x, 0.0) / 0.05667;
}

// Disk colour and opacity at radius r, with frequency shift g (observed / emitted), azimuth angle (for the
// turbulence) and signed angular velocity omega (radians per block of light travel).
vec4 diskColor(float r, float rIn, float rOut, float g, float angle, float omega) {
    float flux = diskFlux(r, rIn);
    float turning = angle - omega * DiskTime;
    float swirl = 0.55 + 0.9 * turbulence(vec2(cos(turning), sin(turning)) * (2.0 + 6.0 * log(r / rIn + 1.0)));
    float temperature = mix(2500.0, 11000.0, clamp(Disk.x, 0.0, 1.5) / 1.5) * pow(flux, 0.25);
    vec3 light = blackbody(temperature * g) * (flux * g * g * g * g) * swirl * (1.5 + 2.0 * Disk.x);
    vec3 color = vec3(1.0) - exp(-light);
    float alpha = clamp(flux * 3.0, 0.0, 1.0) * smoothstep(rOut, 0.8 * rOut, r);
    return vec4(color, alpha);
}

// ---- Kerr ----
// Kerr-Schild coordinates on a local frame L = (game z, game x, game y): spin axis L.z (game up), and positive spin
// turns L.x toward L.y (game z toward game x), matching the physics core. The inverse metric is
// g^uv = eta^uv - h l^u l^v, so with H = (eta^uv p_u p_v - h (l.p - p_t)^2) / 2 a ray needs only the scalar
// h (l.p - p_t)^2 and its gradient: dx/dlambda = p - h l (l.p - p_t), dp/dlambda = grad[h (l.p - p_t)^2] / 2.

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

float kerrPotential(vec3 x, vec3 p, float pt, float m, float a) {
    float h;
    vec3 l;
    kerrProfile(x, m, a, h, l);
    float lp = dot(l, p) - pt;
    return h * lp * lp;
}

void kerrDerivative(vec3 x, vec3 p, float pt, float m, float a, out vec3 dx, out vec3 dp) {
    float h;
    vec3 l;
    float r = kerrProfile(x, m, a, h, l);
    float lp = dot(l, p) - pt;
    dx = p - h * l * lp;
    float e = 1e-3 * max(r, m);
    vec3 ex = vec3(e, 0.0, 0.0);
    vec3 ey = vec3(0.0, e, 0.0);
    vec3 ez = vec3(0.0, 0.0, e);
    dp = vec3(
        kerrPotential(x + ex, p, pt, m, a) - kerrPotential(x - ex, p, pt, m, a),
        kerrPotential(x + ey, p, pt, m, a) - kerrPotential(x - ey, p, pt, m, a),
        kerrPotential(x + ez, p, pt, m, a) - kerrPotential(x - ez, p, pt, m, a)
    ) / (4.0 * e);
}

struct KerrRay {
    int status;     // 0 escaped (exitPos/exitDir set), 1 captured, 2 not marched
    vec3 exitPos;   // camera-relative
    vec3 exitDir;
    vec4 disk;      // disk colour and opacity where the ray crossed it (0 if not)
};

// Marches the view ray along dir past hole i (camera-relative centre, signed spin). The light reaching the camera is
// followed backwards; reversing time also reverses the spin, so the march runs in a hole of spin -a.
KerrRay kerrMarch(int i, vec3 dir, float sceneDist, bool withDisk) {
    KerrRay ray;
    ray.status = 2;
    ray.disk = vec4(0.0);
    float rs = Holes[i].w;
    float m = 0.5 * rs;
    float spin = HoleSpins[i].x;
    if (abs(spin) < MIN_KERR_SPIN) return ray;
    vec3 toHole = Holes[i].xyz;
    float rc = length(toHole);
    float along = dot(toHole, dir);
    float impact = length(toHole - dir * along);
    if (impact > KERR_REGION * rs || (along < 0.0 && rc > KERR_REGION * rs)) return ray;

    float aTrue = spin * m;
    float a = -aTrue;
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

    // Null momentum for coordinate direction d: u = (1, k d), g(u, u) = 0, then p = g u.
    float h0;
    vec3 l0;
    kerrProfile(x, m, a, h0, l0);
    float ld = dot(l0, d);
    float qa = 1.0 + h0 * ld * ld;
    float qb = 2.0 * h0 * ld;
    float qc = h0 - 1.0;
    float k = (-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa);
    float lu = 1.0 + k * ld;
    float pt = -1.0 + h0 * lu;
    vec3 p = k * d + h0 * l0 * lu;
    float hCamera = h0;

    float rOut = Disk.z;
    for (int s = 0; s < KERR_STEPS; s += 1) {
        float h;
        vec3 l;
        float r = kerrProfile(x, m, a, h, l);
        if (r < horizon * 1.02) {
            ray.status = 1;
            return ray;
        }
        vec3 k1x;
        vec3 k1p;
        kerrDerivative(x, p, pt, m, a, k1x, k1p);
        if (length(x) > startRadius * 1.01 && dot(x, k1x) > 0.0) break;
        float dl = KERR_STEP * max(r, 0.5 * m) / max(length(k1x), 1e-6);
        vec3 k2x;
        vec3 k2p;
        kerrDerivative(x + 0.5 * dl * k1x, p + 0.5 * dl * k1p, pt, m, a, k2x, k2p);
        vec3 k3x;
        vec3 k3p;
        kerrDerivative(x + 0.5 * dl * k2x, p + 0.5 * dl * k2p, pt, m, a, k3x, k3p);
        vec3 k4x;
        vec3 k4p;
        kerrDerivative(x + dl * k3x, p + dl * k3p, pt, m, a, k4x, k4p);
        vec3 xNext = x + dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        vec3 pNext = p + dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);

        // Equatorial crossing inside the disk: the first one is what's seen.
        if (withDisk && ray.disk.a == 0.0 && Disk.x > 0.0 && (x.z > 0.0) != (xNext.z > 0.0)) {
            float f = x.z / (x.z - xNext.z);
            vec3 hit = mix(x, xNext, f);
            float rho = length(hit.xy);
            if (rho >= Disk.y && rho <= rOut && sceneDist >= length(toGame(hit) + toHole) - 0.5) {
                float absA = abs(aTrue);
                float rbl = sqrt(max(rho * rho - absA * absA, 1e-6));
                float sq = sqrt(m);
                float r32 = rbl * sqrt(rbl);
                float sense = sign(spin);
                float omega = sense * sq / (r32 + absA * sq);
                float ut = (r32 + absA * sq) / (pow(rbl, 0.75) * sqrt(max(r32 - 3.0 * m * sqrt(rbl) + 2.0 * absA * sq, 1e-5)));
                // The light reaching the camera has the traced ray's spatial momentum reversed.
                vec3 pHit = mix(p, pNext, f);
                float lz = -(hit.x * pHit.y - hit.y * pHit.x) / (-pt);
                float g = 1.0 / (sqrt(max(1.0 - hCamera, 1e-4)) * ut * (1.0 - omega * lz));
                float angle = atan(hit.x, hit.y); // game atan(z, x)
                ray.disk = diskColor(rho, Disk.y, rOut, g, angle, omega);
            }
        }
        x = xNext;
        p = pNext;
    }
    vec3 dx;
    vec3 dp;
    kerrDerivative(x, p, pt, m, a, dx, dp);
    ray.status = 0;
    ray.exitPos = toGame(x) + toHole;
    ray.exitDir = normalize(toGame(dx));
    return ray;
}

// The frame as seen through every hole's lens (background bending, shadow, capture fallbacks).
vec3 lensedScene(vec3 dir, vec4 original, float sceneDist, KerrRay first) {
    vec3 origin = vec3(0.0); // start of the current straight leg, camera-relative
    bool bent = false;
    float bendDistance = 0.0; // distance from the camera to the last bend point
    float bendRs = 0.0;

    for (int i = 0; i < HoleCount; i++) {
        vec3 toHole = Holes[i].xyz - origin;
        float rs = Holes[i].w;
        float rc = length(toHole);
        if (rc <= rs) { return vec3(0.0); } // inside the horizon

        // Closest approach along the current leg (none if the leg points away from the hole).
        float tPeri = max(dot(toHole, dir), 0.0);
        if (!bent && sceneDist < tPeri - rs) continue; // the camera's ray hits something before reaching the hole

        // Spinning hole near this ray: the Kerr march replaces the table (the nearest one was marched already).
        if (abs(HoleSpins[i].x) >= MIN_KERR_SPIN && !bent) {
            KerrRay ray = first;
            if (i != 0) ray = kerrMarch(i, dir, sceneDist, false);
            if (ray.status == 1) { return vec3(0.0); }
            if (ray.status == 0) {
                origin = ray.exitPos;
                dir = ray.exitDir;
                bent = true;
                bendDistance = length(Holes[i].xyz);
                bendRs = rs;
                continue;
            }
        }

        vec3 e1 = -toHole / rc; // hole -> leg start
        float cosPsi = clamp(-dot(dir, e1), -1.0, 1.0);
        vec3 perp = dir + cosPsi * e1;
        float perpLen = length(perp);
        if (perpLen < 1e-6) {
            if (cosPsi > 0.0) { return vec3(0.0); }
            continue;
        }
        vec3 e2 = perp / perpLen;

        vec2 lens = lookup(rs / rc, acos(cosPsi));
        if (lens.g > 0.5) { return vec3(0.0); } // captured: the shadow
        origin += dir * tPeri;
        dir = cos(lens.r) * e1 + sin(lens.r) * e2;
        bent = true;
        bendDistance = length(origin);
        bendRs = rs;
    }

    if (!bent) { return original.rgb; }

    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    vec2 uv = clip.xy / abs(clip.w) * 0.5 + 0.5;
    bool onScreen = clip.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));

    if (EnvironmentReady == 0) {
        if (onScreen) {
            return texture(DiffuseSampler0, uv).rgb;
        }
        uv = 1.0 - abs(1.0 - mod(uv, 2.0)); // no capture yet: fold back into the frame, dimmed
        return texture(DiffuseSampler0, uv).rgb * 0.6;
    }

    // How far past the screen edge the bent ray lands (0 on screen; large when it points behind the camera).
    float overshoot = 1.0;
    if (clip.w > 0.0) {
        vec2 outside = max(-uv, uv - 1.0);
        overshoot = max(max(outside.x, outside.y), 0.0);
    }
    vec2 edgeUv = clamp(uv, vec2(0.0), vec2(1.0));
    vec3 frameColor = texture(DiffuseSampler0, edgeUv).rgb;
    bool foreground = sceneDistance(edgeUv) < bendDistance - FOREGROUND_MARGIN * bendRs;
    if (overshoot <= 0.0 && !foreground) {
        return frameColor;
    }

    bool hit;
    vec3 traced = traceEnvironment(origin, dir, hit);
    if (overshoot <= 0.0) {
        if (!hit) traced = frameColor; // the capture knows nothing better than the frame here
        return traced;
    }
    float toCapture = smoothstep(0.0, EDGE_BLEND, overshoot);
    if (foreground) toCapture = 1.0;
    return mix(frameColor, traced, toCapture);
}

// The nearest feeding hole's accretion disk along this view ray, lensed: the photon's orbit is marched back from
// the camera (u'' = -u + 1.5 r_s u^2, RK4 in the orbit angle) until it crosses the disk plane inside the disk, falls
// in, or escapes. Returns colour and opacity (0 where there's no disk).
vec4 diskSample(vec3 dir, float sceneDist) {
    if (HoleCount == 0 || Disk.x <= 0.0) return vec4(0.0);
    vec3 toHole = Holes[0].xyz;
    float rs = Holes[0].w;
    float rc = length(toHole);
    if (rc <= rs) return vec4(0.0);
    vec3 e1 = -toHole / rc;
    float cosPsi = clamp(-dot(dir, e1), -1.0, 1.0);
    vec3 perp = dir + cosPsi * e1;
    float sinPsi = length(perp);
    if (sinPsi < 1e-6) return vec4(0.0);
    vec3 e2 = perp / sinPsi;
    float rIn = Disk.y;
    float rOut = Disk.z;
    float impact = rc * sinPsi / sqrt(1.0 - rs / rc);
    if (rc > rOut && (cosPsi < 0.0 || impact > 1.5 * rOut)) return vec4(0.0); // can't reach the disk

    float u = 1.0 / rc;
    float invB2 = u * u * (1.0 - rs * u) / (sinPsi * sinPsi);
    float du = sqrt(max(invB2 - u * u * (1.0 - rs * u), 0.0));
    if (cosPsi < 0.0) du = -du;
    float phi = 0.0;
    float height = e1.y / u; // camera's height above the disk plane, relative to the hole
    for (int i = 0; i < DISK_STEPS; i += 1) {
        float k1u = du;
        float k1d = -u + 1.5 * rs * u * u;
        float u2 = u + 0.5 * DISK_STEP * k1u;
        float d2 = du + 0.5 * DISK_STEP * k1d;
        float k2u = d2;
        float k2d = -u2 + 1.5 * rs * u2 * u2;
        float u3 = u + 0.5 * DISK_STEP * k2u;
        float d3 = du + 0.5 * DISK_STEP * k2d;
        float k3u = d3;
        float k3d = -u3 + 1.5 * rs * u3 * u3;
        float u4 = u + DISK_STEP * k3u;
        float d4 = du + DISK_STEP * k3d;
        float k4u = d4;
        float k4d = -u4 + 1.5 * rs * u4 * u4;
        float uNext = u + DISK_STEP / 6.0 * (k1u + 2.0 * k2u + 2.0 * k3u + k4u);
        float duNext = du + DISK_STEP / 6.0 * (k1d + 2.0 * k2d + 2.0 * k3d + k4d);
        float phiNext = phi + DISK_STEP;
        if (uNext >= 1.0 / rs || uNext <= 0.0) return vec4(0.0); // fell in, or escaped, before reaching the disk
        vec3 p = (cos(phiNext) * e1 + sin(phiNext) * e2) / uNext;
        if ((p.y > 0.0) != (height > 0.0)) {
            float t = height / (height - p.y);
            float uc = mix(u, uNext, t);
            float phic = phi + DISK_STEP * t;
            vec3 hit = (cos(phic) * e1 + sin(phic) * e2) / uc;
            float r = length(hit);
            if (r >= rIn && r <= rOut) {
                // Something solid between the camera and this part of the disk hides it.
                if (sceneDist < length(hit + toHole) - 0.5) return vec4(0.0);
                // Observed / emitted frequency: static camera vs a Keplerian emitter, with the photon's angular
                // momentum about the disk axis (opposite to the traced direction, since light travels to the camera).
                float omega = sqrt(0.5 * rs / (r * r * r));
                float ut = inversesqrt(max(1.0 - 1.5 * rs / r, 1e-4));
                float lz = -impact * dot(cross(e1, e2), vec3(0.0, Disk.w, 0.0));
                float g = 1.0 / (sqrt(1.0 - rs / rc) * ut * (1.0 - omega * lz));
                return diskColor(r, rIn, rOut, g, atan(hit.z, hit.x), Disk.w * omega);
            }
        }
        height = p.y;
        u = uNext;
        du = duNext;
        phi = phiNext;
    }
    return vec4(0.0);
}

// Whether the view ray falls into the nearest hole (its shadow): the Kerr march if it ran, else the table.
bool inShadow(vec3 dir, KerrRay first) {
    if (first.status != 2) return first.status == 1;
    vec3 toHole = Holes[0].xyz;
    float rs = Holes[0].w;
    float rc = length(toHole);
    if (rc <= rs) return true;
    vec3 e1 = -toHole / rc;
    float cosPsi = clamp(-dot(dir, e1), -1.0, 1.0);
    if (cosPsi < 0.0) return false;
    return lookup(rs / rc, acos(cosPsi)).g > 0.5;
}

void main() {
    // With overscan the frame is wider than the screen: an output pixel's own content sits elsewhere in it.
    vec3 dir = displayRay(texCoord);
    vec2 ownUv = texCoord;
    if (DisplayTanHalfFov > 0.0) ownUv = frameUv(dir);
    vec4 original = texture(DiffuseSampler0, ownUv);
    if (HoleCount == 0) { fragColor = original; return; }
    float sceneDist = sceneDistance(ownUv);
    // The nearest hole, if spinning, is marched once for both its lens and its disk.
    KerrRay first = kerrMarch(0, dir, sceneDist, true);
    vec3 scene;
    if (VertexLensing == 1 && sceneDist < 1e29) {
        // Geometry is already where its light appears; only the shadow can hide it (if it lies beyond the hole).
        scene = original.rgb;
        if (inShadow(dir, first) && sceneDist > length(Holes[0].xyz)) scene = vec3(0.0);
    } else {
        scene = lensedScene(dir, original, sceneDist, first);
    }
    vec4 disk = first.disk;
    if (first.status == 2) disk = diskSample(dir, sceneDist);
    fragColor = vec4(mix(scene, disk.rgb, disk.a), 1.0);
}
