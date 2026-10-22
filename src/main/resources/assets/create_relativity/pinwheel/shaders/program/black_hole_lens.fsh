#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene
#include create_relativity:vertex_lens_core

// Gravitational lensing by black holes, after the vertex lens (VertexLens) has drawn the world where its light
// appears: the shadow over what lies beyond the hole, and in the sky the bent paths of view rays (lens_scene.glsl;
// spinning holes through Kerr, kerr.glsl) to the sky: the Einstein ring and the images round it (the world's own
// geometry the vertex lens draws, never this pass). From reduced-resolution tables (kerr_table.fsh, scene_table.fsh) where they're accurate,
// marched per pixel where not. Then the accretion disk on top.

uniform sampler2D DiffuseSampler0;
uniform sampler2D LensLookup;
uniform vec4 Disk;      // nearest hole's accretion disk: x brightness (0 = none), y/z inner/outer radius (blocks),
                        // w spin sense (+1 counter-clockwise from above, -1 clockwise)
uniform float DiskTime; // seconds x c (blocks of light travel), for the disk's rotating turbulence
uniform int ShadowDrawn; // 1 while the shadow is drawn as geometry (HorizonMesh): this pass draws none
uniform int DiskScheme;        // RelativityConfig.DiskScheme: 0 realistic, 1 movie, 2 custom (DiskMesh.shade)
uniform vec4 DiskGradient[8];  // the scheme's colours (movie's, or the custom ones), hottest first; w 1 where set
uniform int DiskFillOnly; // 1 while the disk is drawn as geometry (DiskMesh): here only in sky pixels, its images' holes

uniform sampler2D KerrTable0; // kerr_table.fsh outputs, at reduced resolution
uniform sampler2D KerrTable1;
uniform sampler2D KerrTable2;
uniform int KerrTableEnabled; // 0: march every pixel, 1: use the Kerr table, 2: and paint re-marched pixels red,
                              // 3: output the table's error (main)
uniform sampler2D SceneTable2; // scene_refine.fsh output: the paths at half resolution
uniform sampler2D SkyProbe;   // sky_probe.fsh: the sky all round (latitude-longitude; alpha 0 where never seen)
uniform vec3 SkyColor;          // the sky overhead and at the horizon (fog), for paths that escape with nothing else
uniform int LensMode; // 0: the lens; debug: 1 paints each sky pixel by its path's outcome, 2 by its full-resolution
                      // march's step count (every pixel marched), 3 marches every pixel, 4 passes the frame through,
                      // 5 also skips the tables; 6 paints pixels re-marched at full resolution (red: scene, blue: Kerr)

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const vec2 LUT_SIZE = vec2(512.0, 1024.0); // BlackHoleLens.LUT_WIDTH / LUT_HEIGHT

const int DISK_STEPS = 320;
const float DISK_STEP = 0.03; // radians of orbit angle per step

// Kerr table (kerr_table.fsh, at reduced resolution): pixels it would place off by more than about this many sample
// spacings are marched at full resolution instead.

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
const float DISK_MOVIE_BRIGHTNESS = 0.55; // DiskMesh.MOVIE_BRIGHTNESS
vec4 diskColor(float r, float rIn, float rOut, float g, float angle, float omega) {
    float flux = diskFlux(r, rIn);
    float turning = angle - omega * DiskTime;
    float swirl = 0.55 + 0.9 * turbulence(vec2(cos(turning), sin(turning)) * (2.0 + 6.0 * log(r / rIn + 1.0)));
    float heat = pow(flux, 0.25);
    float temperature = mix(2500.0, 11000.0, clamp(Disk.x, 0.0, 1.5) / 1.5) * heat;
    vec3 light = blackbody(temperature * g) * (flux * g * g * g * g) * swirl * (1.5 + 2.0 * Disk.x);
    // (As DiskMesh.shade: movie warm by heat alone, a little brighter approaching; custom its gradient, shifted.)
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

// ---- Kerr ----

bool sceneRemarched = false; // debug (LensMode 6)
bool kerrRemarched = false;

// The nearest hole's Kerr ray through this output pixel, interpolated from the table where that's accurate to about
// half a pixel: the inner 2x2 entries share a fate and a disk image, and the 4x4 around them is smooth enough
// (judged by second differences: the deflection's against a pixel's angle, the disk hit point's against its own
// change per texel). Elsewhere (shadow and disk edges, the photon ring) the ray is marched here.
KerrRay kerrNearest(vec3 dir, vec2 uv) {
    // (Every field set, even when nothing's marched: what's left unset is whatever the registers held, and that changed
    // with unrelated code: frames came out black round spinning holes.)
    KerrRay ray;
    ray.status = 2;
    ray.exitPos = vec3(0.0);
    ray.exitDir = dir;
    ray.diskRho = 0.0;
    ray.diskG = 0.0;
    ray.diskAngle = 0.0;
    ray.diskDist = 0.0;
    if (HoleCount == 0 || abs(HoleSpins[0].x) < MIN_KERR_SPIN) return ray;
    // Only near the hole can a pixel be in its shadow or show its disk: further out (past three r_s's apparent size,
    // or the disk's lensed extent, with room to spare) there's nothing for the table to say.
    float rc = length(Holes[0].xyz);
    float reach = 3.0 * Holes[0].w;
    if (Disk.x > 0.0) reach = max(reach, 1.5 * Disk.z);
    if (rc > reach && dot(dir, Holes[0].xyz / rc) < cos(asin(reach / rc) + 0.1)) return ray;
    ivec2 size = textureSize(KerrTable0, 0);
    vec2 pos = uv * vec2(size) - 0.5;
    ivec2 base = clamp(ivec2(floor(pos)), ivec2(1), size - 3);
    vec2 f = pos - vec2(base); // past [0, 1] in the outermost texels: extrapolated, which the smooth edges allow
    vec4 a0 = texelFetch(KerrTable0, base, 0);
    vec4 b0 = texelFetch(KerrTable0, base + ivec2(1, 0), 0);
    vec4 c0 = texelFetch(KerrTable0, base + ivec2(0, 1), 0);
    vec4 d0 = texelFetch(KerrTable0, base + ivec2(1, 1), 0);
    bool agree = KerrTableEnabled != 0 && a0.w == b0.w && a0.w == c0.w && a0.w == d0.w;
    if (agree && a0.w == 2.0) return ray;
    vec4 a1 = texelFetch(KerrTable1, base, 0);
    vec4 b1 = texelFetch(KerrTable1, base + ivec2(1, 0), 0);
    vec4 c1 = texelFetch(KerrTable1, base + ivec2(0, 1), 0);
    vec4 d1 = texelFetch(KerrTable1, base + ivec2(1, 1), 0);
    vec4 distances = vec4(a1.w, b1.w, c1.w, d1.w);
    bool allDisk = all(greaterThan(distances, vec4(0.0)));
    if (agree && any(greaterThan(distances, vec4(0.0))) != allDisk) agree = false;
    vec4 a2 = texelFetch(KerrTable2, base, 0);
    vec4 b2 = texelFetch(KerrTable2, base + ivec2(1, 0), 0);
    vec4 c2 = texelFetch(KerrTable2, base + ivec2(0, 1), 0);
    vec4 d2 = texelFetch(KerrTable2, base + ivec2(1, 1), 0);

    if (agree) {
        vec3 deflection[16];
        vec3 diskPoint[16];
        for (int j = 0; j < 4; j += 1) {
            for (int i = 0; i < 4; i += 1) {
                ivec2 at = base + ivec2(i - 1, j - 1);
                deflection[j * 4 + i] = KerrTableEnabled == 3 ? texelFetch(KerrTable0, at, 0).xyz : vec3(0.0);
                vec4 disk = texelFetch(KerrTable2, at, 0);
                diskPoint[j * 4 + i] = vec3(disk.x * disk.zw, 0.0);
            }
        }
        // Error is measured against how far apart neighbouring pixels sample the background, i.e. the change in exit
        // direction per pixel: a pixel's angle where the view is barely bent, less where the lens magnifies (errors
        // show more), more where it squeezes the background (near the photon ring), so there an error that can't show
        // isn't paid for with a march. Interpolation is off by an eighth of the second difference, and a texel's
        // change spans several pixels; so the error, in units of the change per pixel, is
        // (D2 / 8) / (step / pixelsPerTexel).
        float limit = 8.0 / (float(textureSize(DiffuseSampler0, 0).y) * TableScale / float(size.y)) * TABLE_TOLERANCE;
        // (The exit direction isn't checked: nothing here reads it. The shadow takes the fate, the disk its hit, and the
        // bent sky comes from the scene table; marching where only it is curved, round the photon ring, was most of
        // the cost of a spinning hole while moving. Its accuracy shows only in the table error view.)
        if (a0.w == 0.0 && KerrTableEnabled == 3) {
            vec3 exitA = tableRay((vec2(base) + 0.5) / vec2(size)) + a0.xyz;
            vec3 exitB = tableRay((vec2(base + ivec2(1, 0)) + 0.5) / vec2(size)) + b0.xyz;
            vec3 exitC = tableRay((vec2(base + ivec2(0, 1)) + 0.5) / vec2(size)) + c0.xyz;
            vec3 exitD = tableRay((vec2(base + ivec2(1, 1)) + 0.5) / vec2(size)) + d0.xyz;
            float step = max(max(length(exitA - exitB), length(exitA - exitC)), max(length(exitD - exitB), length(exitD - exitC)));
            agree = secondDifference(deflection) < limit * step;
        }
        if (agree && allDisk) {
            float step = max(max(length(diskPoint[5] - diskPoint[6]), length(diskPoint[5] - diskPoint[9])),
                max(length(diskPoint[10] - diskPoint[6]), length(diskPoint[10] - diskPoint[9])));
            agree = secondDifference(diskPoint) < limit * step;
        }
    }
    if (!agree) {
        vec2 diskRadii = vec2(0.0);
        if (Disk.x > 0.0) diskRadii = Disk.yz;
        ray = kerrMarch(Holes[0].xyz, Holes[0].w, HoleSpins[0].x, dir, diskRadii);
        kerrRemarched = true;
        if (KerrTableEnabled == 2) ray.diskG = -1.0; // flags the pixel for main()
        return ray;
    }
    ray.status = int(a0.w + 0.5);
    // The deflection interpolates well where the exit direction itself doesn't (even unbent, it isn't linear on screen).
    ray.exitDir = normalize(dir + mix(mix(a0.xyz, b0.xyz, f.x), mix(c0.xyz, d0.xyz, f.x), f.y));
    vec4 t1 = mix(mix(a1, b1, f.x), mix(c1, d1, f.x), f.y);
    vec4 t2 = mix(mix(a2, b2, f.x), mix(c2, d2, f.x), f.y);
    ray.exitPos = t1.xyz;
    ray.diskDist = 0.0;
    ray.diskRho = 0.0;
    ray.diskG = 0.0;
    ray.diskAngle = 0.0;
    if (allDisk) {
        ray.diskDist = t1.w;
        ray.diskRho = t2.x;
        ray.diskG = t2.y;
        ray.diskAngle = atan(t2.w, t2.z);
    }
    return ray;
}

// The disk image a Kerr ray recorded, unless the scene is in front of it.
vec4 kerrDisk(KerrRay ray, float sceneDist) {
    if (ray.diskRho <= 0.0 || sceneDist < ray.diskDist - 0.5) return vec4(0.0);
    float omega = kerrDiskOmega(ray.diskRho, 0.5 * Holes[0].w, HoleSpins[0].x);
    return diskColor(ray.diskRho, Disk.y, Disk.z, ray.diskG, ray.diskAngle, omega);
}

// ---- Scene table ----

// The bent path of this pixel's view ray: interpolated from the scene table where its inner 2x2 entries share an
// outcome and the 4x4 around them is smooth (second differences against the change per texel, as for the Kerr table,
// in radians); otherwise marched here.
SceneHit sceneNearest(vec3 dir, vec2 uv, float sceneDist) {
    if (sceneUnbent(dir, sceneDist)) return sceneHit(SCENE_UNBENT, vec3(0.0));
    if (LensMode == 3 || LensMode == 2 || LensMode == 1) return marchScene(dir, sceneDist); // debug views: every pixel marched
    SceneHit hit;
    SceneHit nearest;
    if (sceneTableAt(SceneTable2, uv, hit, nearest)) return hit;
    // Neighbours that disagree (an edge in what the paths meet): the nearest one's, as the half-resolution table
    // was marched there; at most a pixel off. (Marching here instead costs most of the pass: on a GPU, a few such pixels
    // make every pixel near them wait as long.)
    sceneRemarched = true;
    if (nearest.outcome == 0 || (ProfileFlags & 512) != 0) return marchScene(dir, sceneDist);
    return nearest;
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

// The colour a bent path ends on.
vec3 sceneColor(SceneHit hit, vec4 original) {
    // (Where the shadow is drawn as geometry, its edge is that mesh's: a coarser table's fall-in past it shows sky.)
    if (hit.outcome == SCENE_FELL_IN) return ShadowDrawn == 1 ? skyAt(hit.value) : vec3(0.0);
    if (hit.outcome == SCENE_ESCAPED) return skyAt(hit.value);
    return original.rgb;
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
    vec3 dir = viewRay(texCoord);
    vec4 original = texture(DiffuseSampler0, texCoord);
    if ((HoleCount == 0 && SourceCount == 0 && !relativityWalls()) || LensMode == 4 || LensMode == 5) { fragColor = original; return; } // the frame as rendered (profiling)
    if (KerrTableEnabled == 3) {
        // The table's error against a full march of this pixel: red, the exit direction's (a quarter per sample
        // spacing: a pixel's angle, or the exit direction's change per pixel where the lens squeezes the background);
        // green, a different fate or disk image; blue, the disk hit point's (a quarter per percent of its radius).
        KerrRay table = kerrNearest(dir, tableUv(dir));
        vec2 diskRadii = vec2(0.0);
        if (Disk.x > 0.0) diskRadii = Disk.yz;
        KerrRay marched = kerrMarch(Holes[0].xyz, Holes[0].w, HoleSpins[0].x, dir, diskRadii);
        float exitError = 0.0;
        float spacing = max(pixelAngle(), max(length(dFdx(marched.exitDir)), length(dFdy(marched.exitDir))));
        if (marched.status == 0 && table.status == 0) exitError = length(table.exitDir - marched.exitDir) / spacing; // chord, not acos: float precision
        float fate = 0.0;
        // (Status 2 from the table: outside the shadow's and disk's reach, where the lens pass doesn't read it.)
        if (table.status != 2 && (table.status != marched.status || (table.diskRho > 0.0) != (marched.diskRho > 0.0))) fate = 1.0;
        float diskError = 0.0;
        if (table.diskRho > 0.0 && marched.diskRho > 0.0) {
            vec2 a = table.diskRho * vec2(cos(table.diskAngle), sin(table.diskAngle));
            vec2 b = marched.diskRho * vec2(cos(marched.diskAngle), sin(marched.diskAngle));
            diskError = 100.0 * length(a - b) / marched.diskRho;
        }
        fragColor = vec4(clamp(exitError / 4.0, 0.0, 1.0), fate, clamp(diskError / 4.0, 0.0, 1.0), 1.0);
        return;
    }
    float sceneDist = sceneDistance(texCoord);
    // Sources the field lens has modules for (vertex_lens_core.glsl), any number: the geometry is already where its
    // light appears; the sky the light leaving each sky pixel ends on, or the shadow where a source captures it (exact
    // to the horizon: it closes round a camera falling in). Nothing marched, no tables built.
    if (relativityFieldCount() >= 1 || relativityWalls()) {
        vec3 scene = original.rgb;
        if (sceneDist >= 1e29) {
            vec3 escape;
            vec3 viewDir = normalize((VeilCamera.ViewMat * vec4(dir, 0.0)).xyz);
            if (relativityFieldSky(viewDir, escape)) scene = skyAt(normalize((VeilCamera.IViewMat * vec4(escape, 0.0)).xyz));
            else scene = vec3(0.0);
        }
        // (The nearest hole's disk as before, for now.)
        vec4 fieldDisk = vec4(0.0);
        if (HoleCount > 0 && (ProfileFlags & 256) == 0) fieldDisk = diskSample(dir, sceneDist);
        if (DiskFillOnly == 1 && sceneDist < 1e29) fieldDisk = vec4(0.0);
        fragColor = vec4(mix(scene, fieldDisk.rgb, fieldDisk.a), fieldDisk.a > 0.0 || scene != original.rgb ? 0.5 : 1.0);
        return;
    }
    // (timing: 2048 leaves drawn pixels as drawn, 4096 sky pixels)
    if ((sceneDist < 1e29 && (ProfileFlags & 2048) != 0) || (sceneDist >= 1e29 && (ProfileFlags & 4096) != 0)) {
        fragColor = original;
        return;
    }
    // The nearest hole, if spinning, is marched once for both its lens and its disk.
    KerrRay first = kerrNearest(dir, tableUv(dir));
    vec3 scene;
    SceneHit hit = sceneHit(0, vec3(0.0));
    if (sceneDist < 1e29) {
        // Geometry is already where its light appears; only the shadow can hide it (if it lies beyond the hole).
        scene = original.rgb;
        if (ShadowDrawn == 0 && HoleCount > 0 && inShadow(dir, first) && sceneDist > length(Holes[0].xyz)) scene = vec3(0.0);
    } else {
        hit = sceneNearest(dir, tableUv(dir), sceneDist);
        scene = sceneColor(hit, original);
    }
    vec4 disk = kerrDisk(first, sceneDist);
    // (A spinning hole's disk is the Kerr ray's, wherever it ends, falling in included: none past the table's reach.)
    bool spinning = HoleCount > 0 && abs(HoleSpins[0].x) >= MIN_KERR_SPIN;
    if (!spinning && (ProfileFlags & 256) == 0) disk = diskSample(dir, sceneDist);
    if (DiskFillOnly == 1 && sceneDist < 1e29) disk = vec4(0.0);
    // (Round a spinning hole the mesh is placed by the approximated lens and this by the coarse Kerr table: they don't
    // meet, and what's filled here shows as speckle where rays graze the disk's plane and as arcs of images the mesh
    // doesn't draw. While the mesh draws the disk this pass adds none of it there.)
    if (DiskFillOnly == 1 && spinning) disk = vec4(0.0);
    // Alpha marks what this pass made (lens_aa.fsh smooths and sharpens just those): 1 where the world's own pixel stands.
    bool made = disk.a > 0.0 || (sceneDist >= 1e29 && hit.outcome != SCENE_UNBENT && hit.outcome != 0) || scene != original.rgb;
    fragColor = vec4(mix(scene, disk.rgb, disk.a), made ? 0.5 : 1.0);
    if (LensMode == 2) fragColor = vec4(vec3(float(marchSteps) / 64.0), 1.0);
    if (LensMode == 1) {
        // grey none (drawn geometry), green unbent, white escaped (cyan: out of steps), black fell in
        vec3 outcome[6] = vec3[](vec3(0.5), vec3(0.0, 1.0, 0.0), vec3(0.5), vec3(0.5), vec3(1.0), vec3(0.0));
        fragColor = vec4(outcome[min(hit.outcome, 5)], 1.0);
        if (sceneDist < 1e29) fragColor = vec4(vec3(clamp(sceneDist / 256.0, 0.0, 1.0)), 1.0); // drawn: grey by distance
        if (hit.outcome == SCENE_ESCAPED && marchSteps >= SCENE_STEPS) fragColor = vec4(0.0, 1.0, 1.0, 1.0); // out of steps: cyan
    }
    if (KerrTableEnabled == 2 && first.diskG < -0.5) fragColor = vec4(1.0, 0.0, 0.0, 1.0);
    if (LensMode == 6) fragColor = vec4(float(sceneRemarched), 0.0, float(kerrRemarched), 1.0);
}
