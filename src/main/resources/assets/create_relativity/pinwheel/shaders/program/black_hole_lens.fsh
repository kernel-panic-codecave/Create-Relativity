#include veil:camera

// Gravitational lensing by Schwarzschild black holes.
// Each view ray is bent in its own orbital plane (hole, camera, ray) using a lookup table of where light
// seen at angle psi from the inward radial, by a camera at u0 = r_s / r_cam, came from. See SchwarzschildLens.

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D LensLookup;

#define MAX_HOLES 8
uniform int HoleCount;
uniform vec4 Holes[MAX_HOLES]; // xyz: centre relative to the camera (blocks), w: Schwarzschild radius (blocks)

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const vec2 LUT_SIZE = vec2(512.0, 1024.0); // BlackHoleLens.LUT_WIDTH / LUT_HEIGHT
const float MAX_SWEEP = 6.0 * PI; // SchwarzschildLens.MAX_SWEEP

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

float sceneDistance(vec2 uv) {
    float depth = texture(DiffuseDepthSampler, uv).r;
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return length(view.xyz / view.w);
}

// Table axes are sqrt(u0) and sqrt(psi / pi), sampled at texel centres.
vec2 lookup(float u0, float psi) {
    vec2 axes = vec2(sqrt(u0), sqrt(psi / PI));
    return texture(LensLookup, (axes * (LUT_SIZE - 1.0) + 0.5) / LUT_SIZE).rg;
}

void main() {
    vec4 original = texture(DiffuseSampler0, texCoord);
    vec3 dir = viewRay(texCoord);
    float sceneDist = sceneDistance(texCoord);
    bool bent = false;

    for (int i = 0; i < HoleCount; i++) {
        vec3 toHole = Holes[i].xyz;
        float rs = Holes[i].w;
        float rc = length(toHole);
        if (rc <= rs) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; } // inside the horizon
        if (sceneDist < rc - 3.0 * rs) continue; // something solid sits in front of this hole

        vec3 e1 = -toHole / rc; // hole -> camera
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
        dir = cos(lens.r) * e1 + sin(lens.r) * e2;
        bent = true;
    }

    if (!bent) { fragColor = original; return; }

    // Where the bent ray points on screen. Rays bent off-screen (often back past the camera) have nothing
    // rendered to sample until the environment capture exists; fold them back in, dimmed.
    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    vec2 uv = clip.xy / abs(clip.w) * 0.5 + 0.5;
    if (clip.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)))) {
        fragColor = vec4(texture(DiffuseSampler0, uv).rgb, 1.0);
    } else {
        uv = 1.0 - abs(1.0 - mod(uv, 2.0));
        fragColor = vec4(texture(DiffuseSampler0, uv).rgb * 0.6, 1.0);
    }
}
