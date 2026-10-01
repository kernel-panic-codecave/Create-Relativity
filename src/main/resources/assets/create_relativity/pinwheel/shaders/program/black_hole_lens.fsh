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

#define MAX_HOLES 8
uniform int HoleCount;
uniform vec4 Holes[MAX_HOLES]; // xyz: centre relative to the camera (blocks), w: Schwarzschild radius (blocks)

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
const int MARCH_STEPS = 32;

const vec3 FACE_FORWARD[6] = vec3[](vec3(1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0), vec3(0, 0, 1), vec3(0, 0, -1));
const vec3 FACE_UP[6] = vec3[](vec3(0, 1, 0), vec3(0, 1, 0), vec3(0, 0, 1), vec3(0, 0, -1), vec3(0, 1, 0), vec3(0, 1, 0));

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

// Distance to the surface at uv; the sky (nothing drawn) counts as infinitely far.
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

void main() {
    vec4 original = texture(DiffuseSampler0, texCoord);
    if (HoleCount == 0) { fragColor = original; return; }
    vec3 dir = viewRay(texCoord);
    float sceneDist = sceneDistance(texCoord);
    vec3 origin = vec3(0.0); // start of the current straight leg, camera-relative
    bool bent = false;
    float bendDistance = 0.0; // distance from the camera to the last bend point
    float bendRs = 0.0;

    for (int i = 0; i < HoleCount; i++) {
        vec3 toHole = Holes[i].xyz - origin;
        float rs = Holes[i].w;
        float rc = length(toHole);
        if (rc <= rs) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; } // inside the horizon

        // Closest approach along the current leg (none if the leg points away from the hole).
        float tPeri = max(dot(toHole, dir), 0.0);
        if (!bent && sceneDist < tPeri - rs) continue; // the camera's ray hits something before reaching the hole

        vec3 e1 = -toHole / rc; // hole -> leg start
        float cosPsi = clamp(-dot(dir, e1), -1.0, 1.0);
        vec3 perp = dir + cosPsi * e1;
        float perpLen = length(perp);
        if (perpLen < 1e-6) {
            if (cosPsi > 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
            continue;
        }
        vec3 e2 = perp / perpLen;

        vec2 lens = lookup(rs / rc, acos(cosPsi));
        if (lens.g > 0.5) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; } // captured: the shadow
        origin += dir * tPeri;
        dir = cos(lens.r) * e1 + sin(lens.r) * e2;
        bent = true;
        bendDistance = length(origin);
        bendRs = rs;
    }

    if (!bent) { fragColor = original; return; }

    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    vec2 uv = clip.xy / abs(clip.w) * 0.5 + 0.5;
    bool onScreen = clip.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));

    if (EnvironmentReady == 0) {
        if (onScreen) {
            fragColor = vec4(texture(DiffuseSampler0, uv).rgb, 1.0);
        } else {
            uv = 1.0 - abs(1.0 - mod(uv, 2.0)); // no capture yet: fold back into the frame, dimmed
            fragColor = vec4(texture(DiffuseSampler0, uv).rgb * 0.6, 1.0);
        }
        return;
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
        fragColor = vec4(frameColor, 1.0);
        return;
    }

    bool hit;
    vec3 traced = traceEnvironment(origin, dir, hit);
    if (overshoot <= 0.0) {
        if (!hit) traced = frameColor; // the capture knows nothing better than the frame here
        fragColor = vec4(traced, 1.0);
        return;
    }
    float toCapture = smoothstep(0.0, EDGE_BLEND, overshoot);
    if (foreground) toCapture = 1.0;
    fragColor = vec4(mix(frameColor, traced, toCapture), 1.0);
}
