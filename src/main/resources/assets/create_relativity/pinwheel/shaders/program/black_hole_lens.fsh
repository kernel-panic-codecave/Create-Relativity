#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// Gravitational lensing by black holes, after the vertex lens (VertexLens) has drawn the world where its light
// appears: the shadow over what lies beyond the hole, and in the sky the bent paths of view rays (lens_scene.glsl;
// spinning holes through Kerr, kerr.glsl) to the geometry round the hole (LensVoxels) or the sky: the Einstein ring
// and the images round it. From reduced-resolution tables (kerr_table.fsh, scene_table.fsh) where they're accurate,
// marched per pixel where not. Then the accretion disk on top.

uniform sampler2D DiffuseSampler0;
uniform sampler2D LensLookup;
uniform vec4 Disk;      // nearest hole's accretion disk: x brightness (0 = none), y/z inner/outer radius (blocks),
                        // w spin sense (+1 counter-clockwise from above, -1 clockwise)
uniform float DiskTime; // seconds x c (blocks of light travel), for the disk's rotating turbulence

uniform sampler2D KerrTable0; // kerr_table.fsh outputs, at reduced resolution
uniform sampler2D KerrTable1;
uniform sampler2D KerrTable2;
uniform int KerrTableEnabled; // 0: march every pixel, 1: use the Kerr table, 2: and paint re-marched pixels red,
                              // 3: output the table's error (main)
uniform sampler2D SceneTable; // scene_table.fsh output, at reduced resolution
uniform vec3 SkyColor;          // the sky overhead and at the horizon (fog), for paths that escape with nothing else
uniform sampler2D Lightmap;     // the game's lightmap (block light x sky light)
uniform vec4 WaterSurface;      // LensVoxels.waterSurface: water's surface (untinted RGB) and opacity
uniform vec4 FogColor;          // to show; also the terrain fog's colour (alpha its strength)
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
const float TABLE_TOLERANCE = 0.5;

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

// Largest second difference of a 4x4 neighbourhood (row-major, xyz only) along rows and columns through its inner
// 2x2; bilinear interpolation inside that 2x2 is off by about an eighth of it.
float secondDifference(vec3 v[16]) {
    float worst = 0.0;
    for (int k = 1; k <= 2; k += 1) {
        worst = max(worst, length(v[k * 4] - 2.0 * v[k * 4 + 1] + v[k * 4 + 2]));
        worst = max(worst, length(v[k * 4 + 1] - 2.0 * v[k * 4 + 2] + v[k * 4 + 3]));
        worst = max(worst, length(v[k] - 2.0 * v[k + 4] + v[k + 8]));
        worst = max(worst, length(v[k + 4] - 2.0 * v[k + 8] + v[k + 12]));
    }
    return worst;
}

bool sceneRemarched = false; // debug (LensMode 6)
bool kerrRemarched = false;

// The nearest hole's Kerr ray through this output pixel, interpolated from the table where that's accurate to about
// half a pixel: the inner 2x2 entries share a fate and a disk image, and the 4x4 around them is smooth enough
// (judged by second differences: the deflection's against a pixel's angle, the disk hit point's against its own
// change per texel). Elsewhere (shadow and disk edges, the photon ring) the ray is marched here.
KerrRay kerrNearest(vec3 dir, vec2 uv) {
    KerrRay ray;
    ray.status = 2;
    if (HoleCount == 0 || abs(HoleSpins[0].x) < MIN_KERR_SPIN) return ray;
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
                deflection[j * 4 + i] = texelFetch(KerrTable0, at, 0).xyz;
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
        float limit = 8.0 / (float(textureSize(DiffuseSampler0, 0).y) / float(size.y)) * TABLE_TOLERANCE;
        if (a0.w == 0.0) {
            vec3 exitA = viewRay((vec2(base) + 0.5) / vec2(size)) + a0.xyz;
            vec3 exitB = viewRay((vec2(base + ivec2(1, 0)) + 0.5) / vec2(size)) + b0.xyz;
            vec3 exitC = viewRay((vec2(base + ivec2(0, 1)) + 0.5) / vec2(size)) + c0.xyz;
            vec3 exitD = viewRay((vec2(base + ivec2(1, 1)) + 0.5) / vec2(size)) + d0.xyz;
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
    ivec2 size = textureSize(SceneTable, 0);
    vec2 pos = uv * vec2(size) - 0.5;
    ivec2 base = clamp(ivec2(floor(pos)), ivec2(1), size - 3);
    vec2 f = pos - vec2(base);
    vec4 a = texelFetch(SceneTable, base, 0);
    vec4 b = texelFetch(SceneTable, base + ivec2(1, 0), 0);
    vec4 c = texelFetch(SceneTable, base + ivec2(0, 1), 0);
    vec4 d = texelFetch(SceneTable, base + ivec2(1, 1), 0);
    int outcome = int(a.w + 0.5);
    bool agree = a.w == b.w && a.w == c.w && a.w == d.w && outcome != SCENE_UNBENT && outcome != 0;
    if (agree && outcome == SCENE_FELL_IN) return sceneHit(SCENE_FELL_IN, vec3(0.0));
    if (agree && outcome >= SCENE_VOXEL) {
        // Surface hits blend only within one face plane: across a step, the blend would float in the air beside it.
        int axis = ((outcome - SCENE_VOXEL) % 6) / 2;
        float plane = a.xyz[axis];
        agree = abs(b.xyz[axis] - plane) < 0.01 && abs(c.xyz[axis] - plane) < 0.01 && abs(d.xyz[axis] - plane) < 0.01;
    }
    if (agree) {
        vec2 frameSize = vec2(textureSize(DiffuseSampler0, 0));
        vec3 scale = vec3(1.0);
        if (outcome >= SCENE_VOXEL) scale = vec3(1.0 / max(length(a.xyz), 1e-3)); // positions, as angles from the camera
        vec3 v[16];
        bool same[16];
        for (int j = 0; j < 4; j += 1) {
            for (int i = 0; i < 4; i += 1) {
                vec4 entry = texelFetch(SceneTable, base + ivec2(i - 1, j - 1), 0);
                v[j * 4 + i] = entry.xyz * scale;
                same[j * 4 + i] = entry.w == a.w;
            }
        }
        // Outer entries with another outcome (across an edge just outside the 2x2) are extrapolated from the inner
        // ones, so only the inner 2x2 has to agree. (The corners aren't used.)
        for (int k = 1; k <= 2; k += 1) {
            if (!same[k * 4]) v[k * 4] = 2.0 * v[k * 4 + 1] - v[k * 4 + 2];
            if (!same[k * 4 + 3]) v[k * 4 + 3] = 2.0 * v[k * 4 + 2] - v[k * 4 + 1];
            if (!same[k]) v[k] = 2.0 * v[k + 4] - v[k + 8];
            if (!same[k + 12]) v[k + 12] = 2.0 * v[k + 8] - v[k + 4];
        }
        float pixelsPerTexel = frameSize.y / float(size.y);
        float natural = pixelsPerTexel * pixelAngle();
        float step = max(max(max(length(v[5] - v[6]), length(v[5] - v[9])), max(length(v[10] - v[6]), length(v[10] - v[9]))), natural);
        if (agree) agree = secondDifference(v) < 8.0 / pixelsPerTexel * TABLE_TOLERANCE * step;
    }
    if (!agree) {
        sceneRemarched = true;
        return marchScene(dir, sceneDist);
    }
    vec3 value = mix(mix(a.xyz, b.xyz, f.x), mix(c.xyz, d.xyz, f.x), f.y);
    if (outcome == SCENE_ESCAPED) value = normalize(value);
    return sceneHit(outcome, value);
}

// Vanilla's face shading for normal n: top 1, bottom 0.5, north/south 0.8, east/west 0.6, blended.
float faceShade(vec3 n) {
    float vertical = 0.5;
    if (n.y > 0.0) vertical = 1.0;
    return n.x * n.x * 0.6 + n.z * n.z * 0.8 + n.y * n.y * vertical;
}

// The lightmap's colour for sky and block light levels (0-15), sampled as vanilla's minecraft_sample_lightmap does.
vec3 lightmapColor(float sky, float block) {
    return texture(Lightmap, clamp(vec2(block, sky) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
}

// Light at camera-relative point p, from the light grid (linear, so smooth across cells); full sky outside it.
vec3 lightAt(vec3 p) {
    vec3 g = (p - VoxelOrigin) / float(VOXEL_SIZE);
    if (any(lessThan(g, vec3(0.0))) || any(greaterThan(g, vec3(1.0)))) return lightmapColor(15.0, 0.0);
    vec2 levels = texture(VoxelLight, g).rg * 15.0;
    return lightmapColor(levels.r, levels.g);
}

// Mip level for a texture seen at distance d: about a texel (16 per block) per pixel.
float mipFor(float d) {
    return max(log2(d * pixelAngle() * 16.0), 0.0);
}

// The colour of the quad at `at` (camera-relative), hit facing along `face`: the quad that contains the point in its
// cell (or the cell across the face; of quads drawn over one another, the later), its pixel there, shaded by its
// normal (by winding: its front) and lit by the light in front of it.
vec3 geometryColor(vec3 at, int face) {
    int axis = face / 2;
    vec3 n = vec3(0.0);
    n[axis] = -1.0;
    if (face - 2 * axis == 1) n[axis] = 1.0;
    float lod = mipFor(length(at));
    for (int side = 0; side < 2; side += 1) {
        vec3 g = at - VoxelOrigin - n * (0.002 - 0.004 * float(side));
        ivec3 cell = ivec3(floor(g));
        if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(VOXEL_SIZE)))) continue;
        uint run = texelFetch(Voxels, cell, 0).r;
        uvec2 tints = texelFetch(VoxelTint, cell, 0).rg;
        int start = int(run >> 8u);
        int count = min(int(run & 255u), MAX_CELL_QUADS);
        vec3 p = at - VoxelOrigin - vec3(cell);
        bool found = false;
        vec3 shown = vec3(0.0);
        for (int i = 0; i < count; i += 1) {
            int q = start + i;
            int base = q * QUAD_TEXELS;
            vec3 p0 = texelFetch(VoxelQuads, base).xyz;
            vec3 p1 = texelFetch(VoxelQuads, base + 1).xyz;
            vec3 p2 = texelFetch(VoxelQuads, base + 2).xyz;
            vec3 p3 = texelFetch(VoxelQuads, base + 3).xyz;
            for (int k = 0; k < 2; k += 1) {
                vec3 pb = p1;
                vec3 pc = p2;
                if (k == 1) {
                    pb = p2;
                    pc = p3;
                }
                vec3 e1 = pb - p0;
                vec3 e2 = pc - p0;
                vec3 normal = cross(e1, e2);
                float area = length(normal);
                if (area < 1e-9) continue;
                normal /= area;
                vec3 r = p - p0;
                if (abs(dot(r, normal)) > 0.01 || abs(dot(normal, n)) < 0.5) continue; // in the face hit, not an edge's other face
                if (int(texelFetch(VoxelQuads, base + 3).w + 0.5) == 2) continue; // water: seen through
                // Barycentrics of p projected on the triangle.
                float d00 = dot(e1, e1);
                float d01 = dot(e1, e2);
                float d11 = dot(e2, e2);
                float d20 = dot(r, e1);
                float d21 = dot(r, e2);
                float den = d00 * d11 - d01 * d01;
                vec2 bary = vec2(d11 * d20 - d01 * d21, d00 * d21 - d01 * d20) / den;
                if (bary.x < -0.01 || bary.y < -0.01 || bary.x + bary.y > 1.01) continue;
                vec3 color;
                if (!quadPixel(q, k == 1, clamp(bary, 0.0, 1.0), lod, tints, color)) continue;
                shown = color * faceShade(normal) * lightAt(at + normal * 0.5);
                found = true;
                // Under water (the cell in front holds a tinted fluid): its surface blended over, as the game draws it.
                vec3 front = at - VoxelOrigin + normal * 0.5;
                ivec3 frontCell = ivec3(floor(front));
                if (all(greaterThanEqual(frontCell, ivec3(0))) && all(lessThan(frontCell, ivec3(VOXEL_SIZE)))) {
                    uint fluid = texelFetch(VoxelTint, frontCell, 0).g;
                    // (The surface is lit as open water: full sky light.)
                    if (fluid != 0xFFFFFFFFu) shown = mix(shown, WaterSurface.rgb * unpackTint(fluid) * lightmapColor(15.0, 0.0), WaterSurface.a);
                }
            }
        }
        if (found) return shown;
    }
    return vec3(0.5);
}

// Vanilla's linear fog on a traced surface at camera-relative point p.
vec3 linearFog(vec3 color, vec3 p) {
    float distance = fogDistance(p);
    if (distance <= FogStart) return color;
    float fog = 1.0;
    if (distance < FogEnd) fog = smoothstep(FogStart, FogEnd, distance);
    return mix(color, FogColor.rgb, fog * FogColor.a);
}

// The colour a bent path ends on.
vec3 sceneColor(SceneHit hit, vec4 original) {
    if (hit.outcome >= SCENE_VOXEL) return linearFog(geometryColor(hit.value, hit.outcome - SCENE_VOXEL), hit.value);
    if (hit.outcome == SCENE_FELL_IN) return vec3(0.0);
    // The sky by direction (the frame's own sky pixels may hold hidden triangles drawn out at the far plane).
    if (hit.outcome == SCENE_ESCAPED) return mix(FogColor.rgb, SkyColor, smoothstep(0.0, 0.4, hit.value.y));
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
    if (HoleCount == 0 || LensMode == 4 || LensMode == 5) { fragColor = original; return; } // the frame as rendered (profiling)
    if (KerrTableEnabled == 3) {
        // The table's error against a full march of this pixel: red, the exit direction's (a quarter per sample
        // spacing: a pixel's angle, or the exit direction's change per pixel where the lens squeezes the background);
        // green, a different fate or disk image; blue, the disk hit point's (a quarter per percent of its radius).
        KerrRay table = kerrNearest(dir, texCoord);
        vec2 diskRadii = vec2(0.0);
        if (Disk.x > 0.0) diskRadii = Disk.yz;
        KerrRay marched = kerrMarch(Holes[0].xyz, Holes[0].w, HoleSpins[0].x, dir, diskRadii);
        float exitError = 0.0;
        float spacing = max(pixelAngle(), max(length(dFdx(marched.exitDir)), length(dFdy(marched.exitDir))));
        if (marched.status == 0 && table.status == 0) exitError = length(table.exitDir - marched.exitDir) / spacing; // chord, not acos: float precision
        float fate = 0.0;
        if (table.status != marched.status || (table.diskRho > 0.0) != (marched.diskRho > 0.0)) fate = 1.0;
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
    // The nearest hole, if spinning, is marched once for both its lens and its disk.
    KerrRay first = kerrNearest(dir, texCoord);
    vec3 scene;
    SceneHit hit = sceneHit(0, vec3(0.0));
    if (sceneDist < 1e29) {
        // Geometry is already where its light appears; only the shadow can hide it (if it lies beyond the hole).
        scene = original.rgb;
        if (inShadow(dir, first) && sceneDist > length(Holes[0].xyz)) scene = vec3(0.0);
    } else {
        hit = sceneNearest(dir, texCoord, sceneDist);
        scene = sceneColor(hit, original);
    }
    vec4 disk = kerrDisk(first, sceneDist);
    if (first.status == 2) disk = diskSample(dir, sceneDist);
    fragColor = vec4(mix(scene, disk.rgb, disk.a), 1.0);
    if (LensMode == 2) fragColor = vec4(vec3(float(marchSteps) / 64.0), 1.0);
    if (LensMode == 1) {
        // grey none (drawn geometry), green unbent, white escaped (cyan: out of steps), black fell in, yellow geometry
        vec3 outcome[7] = vec3[](vec3(0.5), vec3(0.0, 1.0, 0.0), vec3(0.5), vec3(0.5), vec3(1.0), vec3(0.0), vec3(1.0, 1.0, 0.0));
        fragColor = vec4(outcome[min(hit.outcome, SCENE_VOXEL)], 1.0);
        if (hit.outcome == SCENE_ESCAPED && marchSteps >= SCENE_STEPS) fragColor = vec4(0.0, 1.0, 1.0, 1.0); // out of steps: cyan
    }
    if (KerrTableEnabled == 2 && first.diskG < -0.5) fragColor = vec4(1.0, 0.0, 0.0, 1.0);
    if (LensMode == 6) fragColor = vec4(float(sceneRemarched), 0.0, float(kerrRemarched), 1.0);
}
