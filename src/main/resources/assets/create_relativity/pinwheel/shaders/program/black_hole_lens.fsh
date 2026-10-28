#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene
#include create_relativity:vertex_lens_core

// Gravitational lensing, after the vertex lens (VertexLens) has drawn the world where its light appears: in every pixel
// it left empty the light followed back past every source by the field lens (vertex_lens_core.glsl's
// relativityFieldLeg), black where a hole captures it (the shadow), else the sky; and by the lens's asymptotes (the
// Einstein ring's caustic, the photon ring, the shadow's edge: where triangles can't follow the mapping) looked up in
// the scene capture, so what's there shows. With the light table, any pixel left empty whose light meets the level on
// its way shows the capture there (sceneCaptureTable): by the shadows a point has more images than the vertex lens can
// find. Everywhere else what the vertex lens leaves empty stays sky: its own to fix.
// Then the nearest hole's accretion disk on top. Every hole is lensed as a non-spinning one.

uniform sampler2D DiffuseSampler0;
uniform sampler2D LensLookup;
uniform sampler2D SceneCapture;      // SceneCapture: the level round the camera, unlensed, on a cube's six faces side by side
uniform sampler2D SceneCaptureDepth; // its depth (1: nothing drawn, sky)
uniform int SceneCaptureReady;       // which faces hold a capture (bit per face)
uniform vec4 SceneCaptureFaces[24];  // each face's projection times view rotation, by column (game axes, directions)
uniform vec2 SceneCaptureRange;      // the faces' near and far planes (blocks)
const int SCENE_CAPTURE_STEPS = 40;  // the march along a leg (sceneCaptureAlong): steps growing by a quarter
// The lens's asymptotes, by how it maps a pixel's square of sky (the escape directions' area over the view's): below
// the first (magnified past it: the Einstein ring's caustic) or above the second (squeezed: the photon ring).
const vec2 ASYMPTOTE_AREA = vec2(1.0 / 16.0, 16.0);
uniform vec4 Disk;      // nearest hole's accretion disk: x brightness (0 = none), y/z inner/outer radius (blocks),
                        // w spin sense (+1 counter-clockwise from above, -1 clockwise)
uniform float DiskTime; // seconds x c (blocks of light travel), for the disk's rotating turbulence
uniform int DiskScheme;        // RelativityConfig.DiskScheme: 0 realistic, 1 movie, 2 custom
uniform vec4 DiskGradient[8];  // the scheme's colours (movie's, or the custom ones), hottest first; w 1 where set
uniform sampler2D SkyProbe;   // sky_probe.fsh: the sky all round (latitude-longitude; alpha 0 where never seen)
uniform vec3 SkyColor;          // the sky overhead and at the horizon (fog), for paths that escape with nothing else
uniform int LensMode; // 0: the lens; 4 passes the frame through (debug)

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const vec2 LUT_SIZE = vec2(512.0, 1024.0); // BlackHoleLens.LUT_WIDTH / LUT_HEIGHT

const int DISK_STEPS = 320;
const float DISK_STEP = 0.03; // radians of orbit angle per step

// Table axes are sqrt(u0) and sqrt(psi / pi), sampled at texel centres.
vec2 lookup(float u0, float psi) {
    vec2 axes = vec2(sqrt(u0), sqrt(psi / PI));
    return texture(LensLookup, (axes * (LUT_SIZE - 1.0) + 0.5) / LUT_SIZE).rg;
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
const float DISK_MOVIE_BRIGHTNESS = 0.55; // the film look's brightness (its colours kept, not burned to white)
vec4 diskColor(float r, float rIn, float rOut, float g, float angle, float omega) {
    float flux = diskFlux(r, rIn);
    float turning = angle - omega * DiskTime;
    float swirl = 0.55 + 0.9 * turbulence(vec2(cos(turning), sin(turning)) * (2.0 + 6.0 * log(r / rIn + 1.0)));
    float heat = pow(flux, 0.25);
    float temperature = mix(2500.0, 11000.0, clamp(Disk.x, 0.0, 1.5) / 1.5) * heat;
    vec3 light = blackbody(temperature * g) * (flux * g * g * g * g) * swirl * (1.5 + 2.0 * Disk.x);
    // (Movie: warm by heat alone, a little brighter approaching; custom its gradient, shifted.)
    if (DiskScheme != 0) {
        int count = 0;
        for (int k = 0; k < 8; k += 1) {
            if (DiskGradient[k].w > 0.5) count = k + 1;
        }
        float t = DiskScheme == 1 ? 1.0 - heat : 1.0 - clamp(heat * g, 0.0, 1.0);
        float f = t * float(max(count - 1, 0));
        int i = min(int(floor(f)), max(count - 2, 0));
        vec3 base = mix(DiskGradient[i].rgb, DiskGradient[min(i + 1, count - 1)].rgb, f - float(i));
        float brightness = DiskScheme == 1 ? DISK_MOVIE_BRIGHTNESS * flux * g : flux * g * g * g * g;
        light = base * brightness * swirl * (1.5 + 2.0 * Disk.x);
    }
    vec3 color = vec3(1.0) - exp(-light);
    float alpha = clamp(flux * 3.0, 0.0, 1.0) * smoothstep(rOut, 0.8 * rOut, r);
    return vec4(color, alpha);
}

// The face of the scene capture that holds world direction d most squarely: where in the atlas, and the distance
// along d (blocks from the camera) to what it shows there (1e30 for sky); false if no face holding it is captured yet.
bool sceneCaptureFace(vec3 d, out vec2 at, out float dist) {
    at = vec2(0.0);
    dist = 1e30;
    // (The faces look along +x, -x, +y, -y, +z, -z: d's largest axis picks its face.)
    vec3 a = abs(d);
    int best = a.x >= a.y && a.x >= a.z ? (d.x > 0.0 ? 0 : 1) : (a.y >= a.z ? (d.y > 0.0 ? 2 : 3) : (d.z > 0.0 ? 4 : 5));
    if ((SceneCaptureReady & (1 << best)) == 0) return false;
    mat4 m = mat4(SceneCaptureFaces[4 * best], SceneCaptureFaces[4 * best + 1], SceneCaptureFaces[4 * best + 2], SceneCaptureFaces[4 * best + 3]);
    vec4 clip = m * vec4(d, 0.0);
    float bestW = clip.w;
    vec2 bestNdc = clamp(clip.xy / clip.w, -1.0, 1.0);
    vec2 cell = vec2(float(best - 3 * (best / 3)), float(best / 3));
    at = (cell + bestNdc * 0.5 + 0.5) / vec2(3.0, 2.0);
    float depth = texture(SceneCaptureDepth, at).r;
    if (depth < 1.0) {
        // (Depth to the face's view depth, then along d: clip.w is d's view depth, d being a unit vector.)
        float n = SceneCaptureRange.x;
        float f = SceneCaptureRange.y;
        float viewZ = 2.0 * n * f / ((f + n) - (2.0 * depth - 1.0) * (f - n));
        dist = viewZ / (bestW / length(d));
    }
    return true;
}

// What light reaching the camera along a last straight leg from o (world axes, camera-relative) heading e shows, by the
// scene capture: the first captured surface the leg passes behind (marched out from o, a coarse step then halved
// back); false if it meets none (sky) or the capture isn't ready. A leg from the camera itself is a single lookup.
bool sceneCaptureAlong(vec3 o, vec3 e, out vec3 color) {
    color = vec3(0.0);
    vec2 at;
    float dist;
    if (dot(o, o) < 1e-6) {
        if (!sceneCaptureFace(e, at, dist) || dist >= 1e29) return false;
        color = texture(SceneCapture, at).rgb;
        return true;
    }
    float t = 0.0;
    float stepLength = max(0.25, 0.02 * length(o));
    // (Whether the last sample lay in front of what the capture shows its way: only a leg crossing from in front to
    // behind a surface meets it; one already behind is passing behind something nearer the camera.)
    bool before = sceneCaptureFace(normalize(o), at, dist) && length(o) < dist;
    for (int i = 0; i < SCENE_CAPTURE_STEPS; i += 1) {
        float next = t + stepLength;
        vec3 x = o + e * next;
        float r = length(x);
        if (!sceneCaptureFace(x / r, at, dist)) return false;
        bool front = r < dist;
        if (before && !front) {
            // Crossed: found closer by halving, and a hit only if it lands on the surface (not on a silhouette's edge,
            // where what's shown jumps from near to far).
            float a = t;
            float b = next;
            for (int k = 0; k < 6; k += 1) {
                float m = 0.5 * (a + b);
                vec3 y = o + e * m;
                float ry = length(y);
                float dy;
                vec2 aty;
                if (sceneCaptureFace(y / ry, aty, dy) && ry >= dy) b = m; else a = m;
            }
            vec3 y = o + e * b;
            float ry = length(y);
            if (sceneCaptureFace(y / ry, at, dist) && abs(ry - dist) < max(0.5, 0.02 * ry) + (b - a)) {
                color = texture(SceneCapture, at).rgb;
                return true;
            }
        }
        before = front;
        t = next;
        stepLength *= 1.25;
    }
    return false;
}

// What the light seen along view direction d shows, by the light table and the scene capture: its path (the table's
// crossings of the spheres round the camera, cubic between, two samples a span) walked out from the camera until it
// passes behind a captured surface (then halved back to it, a hit only if it lands on the surface); false if it meets
// none before the table's light ends (captured, or out past the last sphere), or d is off the table. Where the vertex
// lens leaves a pixel empty but its light meets the level (the images by a shadow it can't find every one of).
const int TABLE_FILL_SAMPLES = 1; // a span between spheres
// Light bent less than this (radians) meets nothing the vertex lens didn't draw (its primary images): not walked.
const float TABLE_FILL_BEND = 0.05;
int fillWhy = 0; // debug (LensMode 5): how the walk ended (1 hit, 2 the table's light ended, 3 past the far plane,
                 // 4 a crossing rejected, 5 off the table or no capture, 6 to the last sphere)
bool sceneCaptureTable(vec3 d, out vec3 color) {
    color = vec3(0.0);
    vec2 uv;
    fillWhy = 5;
    if (SceneCaptureReady == 0 || !relativityTableUv(d, uv)) return false;
    fillWhy = 6;
    bool before = true;
    float last = relativityTableRadius(0.0);
    int samples = (relativityTableShells() - 1) * TABLE_FILL_SAMPLES;
    for (int i = 1; i <= samples; i += 1) {
        float r = relativityTableRadius(float(i) / float(TABLE_FILL_SAMPLES));
        // (Past the capture's far plane it shows nothing.)
        if (r > SceneCaptureRange.y) { fillWhy = 3; return false; }
        vec3 q;
        if (!relativityTablePoint(uv, r, q)) { fillWhy = 2; return false; }
        vec3 w = (VeilCamera.IViewMat * vec4(q, 0.0)).xyz;
        vec2 at;
        float dist;
        if (!sceneCaptureFace(w / length(w), at, dist)) return false;
        bool front = length(w) < dist;
        if (before && !front) {
            float a = last;
            float b = r;
            for (int k = 0; k < 8; k += 1) {
                float m = 0.5 * (a + b);
                vec3 y;
                if (!relativityTablePoint(uv, m, y)) break;
                vec3 wy = (VeilCamera.IViewMat * vec4(y, 0.0)).xyz;
                float dy;
                vec2 aty;
                if (sceneCaptureFace(wy / length(wy), aty, dy) && length(wy) >= dy) b = m; else a = m;
            }
            // A hit where the light goes into a surface, not where it passes behind something nearer the camera (a
            // post's silhouette: the light there is far behind what the capture shows, three times as far). Loose
            // otherwise: by the photon ring the table's path between spheres is a little off, and the crossing found
            // lands a little short of or past the surface.
            vec3 yb;
            if (relativityTablePoint(uv, b, yb)) {
                vec3 wb = (VeilCamera.IViewMat * vec4(yb, 0.0)).xyz;
                if (sceneCaptureFace(wb / length(wb), at, dist) && length(wb) - dist < max(2.0, 0.3 * length(wb))) {
                    color = texture(SceneCapture, at).rgb;
                    fillWhy = 1;
                    return true;
                }
            }
            fillWhy = 4;
        }
        before = front;
        last = r;
    }
    return false;
}

// What the light seen along view direction d shows, followed exactly: marched from the camera through every hole's
// orbit field (RK4, as relativityFieldMarch), each step's short straight piece checked against the scene capture (a
// crossing behind a captured surface halved back to it, a hit unless the light is far behind what it shows: a
// silhouette), then once it has left every hole heading away, the rest of its way straight (sceneCaptureAlong). False if
// a hole captures it before it meets anything, or it meets nothing. Sharp where the light table's steps would smear:
// inside the Einstein rings, where images are squeezed.
const int FILL_MARCH_LIMIT = 400;
bool sceneCaptureMarch(vec3 d, out vec3 color) {
    color = vec3(0.0);
    if (SceneCaptureReady == 0) return false;
    int group = (1 << relativityFieldCount()) - 1;
    vec3 x = vec3(0.0);
    vec3 v = d;
    bool before = true;
    int limit = FILL_MARCH_LIMIT + relativityRuntimeZero();
    for (int i = 0; i < limit; i += 1) {
        float nearest = 1e30;
        bool outside = true;
        for (int k = 0; k < relativityFieldCount(); k += 1) {
            vec3 off = x - relativityFieldCentre(k);
            float r = length(off);
            if (relativityFieldHole(k) && r <= 1.5 * relativityFieldParams(k).x && dot(off, v) < 0.0) return false;
            nearest = min(nearest, r);
            if (r < relativityFieldNear(k) || dot(off, v) <= 0.0) outside = false;
        }
        if (outside || length(x) > SceneCaptureRange.y) break;
        float h = 0.2 * nearest;
        vec3 a1 = relativityOrbitField(group, x, v);
        vec3 v2 = v + a1 * (0.5 * h);
        vec3 a2 = relativityOrbitField(group, x + v * (0.5 * h), v2);
        vec3 v3 = v + a2 * (0.5 * h);
        vec3 a3 = relativityOrbitField(group, x + v2 * (0.5 * h), v3);
        vec3 v4 = v + a3 * h;
        vec3 a4 = relativityOrbitField(group, x + v3 * h, v4);
        vec3 next = x + (v + 2.0 * v2 + 2.0 * v3 + v4) * (h / 6.0);
        vec3 w = (VeilCamera.IViewMat * vec4(next, 0.0)).xyz;
        vec2 at;
        float dist;
        bool front = !sceneCaptureFace(w / length(w), at, dist) || length(w) < dist;
        if (before && !front) {
            // (Halved back along the piece: short by a hole, so straight to well under a pixel.)
            vec3 a = x;
            vec3 b = next;
            for (int k = 0; k < 8; k += 1) {
                vec3 m = 0.5 * (a + b);
                vec3 wm = (VeilCamera.IViewMat * vec4(m, 0.0)).xyz;
                float dm;
                vec2 atm;
                if (sceneCaptureFace(wm / length(wm), atm, dm) && length(wm) >= dm) b = m; else a = m;
            }
            // A hit where the light goes into a surface, not where it passes behind a silhouette: a hair either side
            // of the crossing the light is on what the capture shows (within a texel's footprint, a block or so
            // across a floor seen glancing). Past a post's edge it's well in front of the floor beyond on one side
            // and well behind the post on the other.
            vec3 wa = (VeilCamera.IViewMat * vec4(a, 0.0)).xyz;
            vec3 wb = (VeilCamera.IViewMat * vec4(b, 0.0)).xyz;
            vec2 atA;
            float distA;
            if (sceneCaptureFace(wa / length(wa), atA, distA) && sceneCaptureFace(wb / length(wb), at, dist)
                    && distA - length(wa) < max(1.0, 0.08 * length(wa)) && length(wb) - dist < max(1.0, 0.08 * length(wb))) {
                color = texture(SceneCapture, at).rgb;
                return true;
            }
        }
        before = front;
        v += (a1 + 2.0 * a2 + 2.0 * a3 + a4) * (h / 6.0);
        x = next;
    }
    return sceneCaptureAlong((VeilCamera.IViewMat * vec4(x, 0.0)).xyz, normalize((VeilCamera.IViewMat * vec4(v, 0.0)).xyz), color);
}

// The sky in world direction d, as drawn: the frame's own where d shows sky on screen (the sky, clouds and sun as the
// game or a shader pack drew them, already through its tonemapping), else the sky probe's, else a plain gradient.
vec3 skyAt(vec3 d) {
    if ((ProfileFlags & 128) != 0) return mix(LensFogColor.rgb, SkyColor, smoothstep(0.0, 0.4, d.y)); // timing
    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(d, 0.0));
    if (clip.w > 0.0) {
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))) && texture(DiffuseDepthSampler, uv).r >= SKY_DEPTH) {
            return texture(DiffuseSampler0, uv).rgb;
        }
    }
    vec2 longLat = vec2(atan(d.z, d.x) / (2.0 * PI) + 0.5, asin(clamp(d.y, -1.0, 1.0)) / PI + 0.5);
    vec4 probe = texture(SkyProbe, longLat);
    if (probe.a > 0.5) return probe.rgb;
    // Never seen that way: the sky seen at about the same height (it changes mostly with height), weighted smoothly by
    // how far in height (stopping at the first height with any, it changed in steps: contour bands across bent sky),
    // and toward the plain gradient where little was seen near that height.
    vec4 sum = vec4(0.0);
    for (int ring = -12; ring <= 12; ring += 1) {
        float offset = float(ring) * 0.025;
        float weight = exp(-offset * offset / (2.0 * 0.06 * 0.06));
        for (int k = 0; k < 8; k += 1) {
            vec2 at = vec2(float(k) / 8.0 + 0.5 / 8.0, clamp(longLat.y + offset, 0.0, 1.0));
            vec4 seen = texture(SkyProbe, at);
            sum += vec4(seen.rgb * seen.a, seen.a) * weight;
        }
    }
    vec3 plain = mix(LensFogColor.rgb, SkyColor, smoothstep(0.0, 0.4, d.y));
    if (sum.a > 1e-4) return mix(plain, sum.rgb / sum.a, smoothstep(0.0, 2.0, sum.a));
    return mix(LensFogColor.rgb, SkyColor, smoothstep(0.0, 0.4, d.y));
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

void main() {
    vec3 dir = viewRay(texCoord);
    vec4 original = texture(DiffuseSampler0, texCoord);
    if ((relativityFieldCount() == 0 && !relativityWalls()) || LensMode == 4) { fragColor = original; return; }
    float sceneDist = sceneDistance(texCoord);
    // The geometry is already where its light appears. Where nothing is (the sky, and what the vertex lens couldn't
    // place): the light followed back past every source, its last leg looked up in the scene capture, else the sky;
    // black where a source captures it.
    vec3 scene = original.rgb;
    // Every pixel's light followed once (uniform control flow: neighbours' escape directions by screen-space
    // derivatives, for the asymptote test, cost no walks of their own).
    vec3 escape;
    vec3 from = vec3(0.0);
    vec3 viewDir = normalize((VeilCamera.ViewMat * vec4(dir, 0.0)).xyz);
    // (From the light table where it has this direction, else followed here.)
    bool tableCaptured;
    bool tabled = relativityTableOn() && relativityTableEscape(viewDir, escape, tableCaptured);
    bool escaped = tabled ? !tableCaptured : relativityFieldLeg(viewDir, from, escape);
    vec3 world = normalize((VeilCamera.IViewMat * vec4(escape, 0.0)).xyz);
    float captured = escaped ? 0.0 : 1.0;
    // By an asymptote: a neighbour captured where this isn't (or the other way: the shadow's edge), or the escape
    // directions' area over the view's far from 1 (the Einstein ring's caustic, the photon ring).
    float viewArea = length(cross(dFdx(dir), dFdy(dir)));
    float area = length(cross(dFdx(world), dFdy(world))) / max(viewArea, 1e-12);
    bool asymptote = abs(dFdx(captured)) + abs(dFdy(captured)) > 0.0 || area < ASYMPTOTE_AREA.x || area > ASYMPTOTE_AREA.y;
    if (sceneDist >= 1e29) {
        scene = vec3(0.0);
        // Light that meets the level on its way (by the table), though the vertex lens drew nothing here: the capture's.
        vec3 met;
        // (Also by a shadow's edge, where the table's escape lookup gives up: the walk ends where the light does.)
        // Only inside the Einstein rings (outside, the vertex lens draws what's there, and the rest is sky).
        bool onTable = relativityTableOn() && relativityInsideRing(viewDir, 1e30);
        bool meets = onTable && sceneCaptureMarch(viewDir, met);
        if (LensMode == 5) {
            vec3 codes[7] = vec3[](vec3(0.3), vec3(1.0), vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0), vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 1.0));
            if (!meets && fillWhy == 0) fillWhy = 0;
            fragColor = vec4(codes[fillWhy], 1.0);
            return;
        }
        if (meets) {
            scene = met;
        } else if (escaped) {
            scene = skyAt(world);
            vec3 seen;
            // (The leg's start for the capture's march: from the table's direction alone the camera's.)
            if (asymptote && tabled) relativityFieldLeg(viewDir, from, escape);
            if (asymptote && sceneCaptureAlong((VeilCamera.IViewMat * vec4(from, 0.0)).xyz, world, seen)) scene = seen;
        }
    }
    // The nearest hole's disk.
    vec4 disk = vec4(0.0);
    if (HoleCount > 0 && (ProfileFlags & 256) == 0) disk = diskSample(dir, sceneDist);
    // Alpha marks what this pass made (lens_aa.fsh smooths and sharpens just those): 1 where the world's own pixel stands.
    fragColor = vec4(mix(scene, disk.rgb, disk.a), disk.a > 0.0 || scene != original.rgb ? 0.5 : 1.0);
}
