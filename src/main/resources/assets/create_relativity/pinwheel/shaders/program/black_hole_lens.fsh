#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene
#include create_relativity:vertex_lens_core

// Gravitational lensing, after the vertex lens (VertexLens) has drawn the world where its light appears, every image of
// it: this pass draws only the sky and the shadows. In every pixel it left empty the light followed back past every
// source (the light table, else the field lens: vertex_lens_core.glsl's relativityFieldLeg), black where a hole
// captures it (the shadow), else the sky it escapes to. What the vertex lens leaves empty stays sky: its own to fix.
// Then the nearest hole's accretion disk on top. Every hole is lensed as a non-spinning one.

uniform sampler2D DiffuseSampler0;
uniform sampler2D LensLookup;
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
    // The geometry is already where its light appears, every image of it.
    vec3 scene = original.rgb;
    // Where nothing is drawn, the light followed back: black where a hole captures it, else the sky it escapes to.
    if (sceneDist >= 1e29) {
        vec3 viewDir = normalize((VeilCamera.ViewMat * vec4(dir, 0.0)).xyz);
        vec3 escape;
        vec3 from = vec3(0.0);
        bool tableCaptured;
        bool tabled = relativityTableOn() && relativityTableEscape(viewDir, escape, tableCaptured);
        bool escaped = tabled ? !tableCaptured : relativityFieldLeg(viewDir, from, escape);
        scene = vec3(0.0);
        if (escaped) scene = skyAt(normalize((VeilCamera.IViewMat * vec4(escape, 0.0)).xyz));
    }
    // The nearest hole's disk.
    vec4 disk = vec4(0.0);
    if (HoleCount > 0 && (ProfileFlags & 256) == 0) disk = diskSample(dir, sceneDist);
    // Alpha marks what this pass made (lens_aa.fsh smooths and sharpens just those): 1 where the world's own pixel stands.
    fragColor = vec4(mix(scene, disk.rgb, disk.a), disk.a > 0.0 || scene != original.rgb ? 0.5 : 1.0);
}
