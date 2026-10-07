// Light paths through the scene around black holes, shared by the scene table pass (scene_table.fsh) and the lens
// pass (black_hole_lens.fsh), for the pixels where the vertex lens (VertexLens) drew nothing: the sky, where bent
// light shows the shadow, the Einstein ring and the images round it. Needs VeilCamera.

#include create_relativity:kerr

uniform sampler2D DiffuseDepthSampler;
uniform float RenderDistance; // blocks (paths count as escaped past twice this)
uniform int ViewChunks;       // the level renderer's view distance in chunks: which chunks it draws
uniform vec2 CameraInChunk;   // the camera's x and z within its chunk (0 .. 16)
uniform float LensFogStart;   // the level's terrain fog this frame (vanilla's linear fog). Not vanilla's uniform names:
uniform float LensFogEnd;     // Veil sets those from the render state when binding, and by the post pass there's no fog
uniform int LensFogShape;     // 0 sphere, 1 cylinder
uniform vec4 LensFogColor;    // the terrain fog's colour (alpha its strength)
uniform int ProfileFlags;     // profiling only (0 normally): 2 skips geometry, 4 uses Euler for bending, 8 ignores the
                              // render distance; 16 draws fogged geometry as drawn; 32 (LensMode 1) paints traced hits' fog distance
uniform int UnseenSource; // what bent paths can meet: 0 nothing (the sky), 1 the geometry round the hole (LensVoxels)
uniform usampler3D Voxels;      // LensVoxels' fine grid: per one-block cell, its quads as (start << 8) | count
uniform samplerBuffer VoxelQuads; // its quads, 6 texels each (LensVoxels.writeQuad), relative to their cell
uniform usampler3D VoxelTint;  // LensVoxels' tint grid: block (r) and fluid (g) tint per fine cell, RGB bits
uniform sampler3D VoxelLight;   // LensVoxels' light grid: sky (r) and block (g) light per fine cell, 0-15 as 0-1
uniform vec3 VoxelOrigin;       // the grid's minimum corner, relative to the camera
uniform usampler3D VoxelsBehind;    // the same grids past the hole, away from the camera (LensVoxels.behind)
uniform usampler3D VoxelTintBehind;
uniform sampler3D VoxelLightBehind;
uniform vec3 VoxelOriginBehind;
uniform int VoxelBehindReady;       // 1 once filled where it is
uniform sampler2D VoxelTex0;    // the textures quads sample (LensVoxels.textureSlots)
uniform sampler2D VoxelTex1;
uniform sampler2D VoxelTex2;
uniform sampler2D VoxelTex3;
uniform sampler2D VoxelTex4;
uniform sampler2D VoxelTex5;
uniform sampler2D VoxelTex6;
uniform sampler2D VoxelTex7;

#define MAX_HOLES 8
uniform int HoleCount;
uniform vec4 Holes[MAX_HOLES]; // xyz: centre relative to the camera (blocks), w: Schwarzschild radius (blocks)
uniform vec4 HoleSpins[MAX_HOLES]; // x: spin a/M, signed (+ counter-clockwise about up, seen from above); nearest first

const float SKY_DEPTH = 0.99999;

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

// Distance to the surface drawn at uv; the sky (nothing drawn) counts as infinitely far.
float sceneDistance(vec2 uv) {
    float depth = texture(DiffuseDepthSampler, uv).r;
    if (depth >= SKY_DEPTH) return 1e30;
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float distance = length(view.xyz / view.w);
    // Wholly in the fog, drawn geometry looks just like sky, and near a fold the vertex lens can leave a gap in nearer
    // ground through which far ground shows (its own images there hidden): such pixels are traced like sky, so the
    // nearer ground the light really meets wins.
    if (distance >= LensFogEnd && LensFogColor.a > 0.0 && (ProfileFlags & 16) == 0) return 1e30;
    return distance;
}

// Angle between neighbouring output pixels (radians).
float pixelAngle() {
    return 2.0 / VeilCamera.ProjMat[1][1] / float(textureSize(DiffuseDepthSampler, 0).y);
}

// ---- Light paths through the scene ----
// The view ray is followed along its bent path, d^2x/ds^2 = -(3/2) r_s h^2 x / r^5 per hole (h = |x * dx/ds|, the
// Schwarzschild photon orbit in Cartesian form, superposed), and stopped at the first surface it reaches in the
// geometry round the hole. If the nearest hole spins, near it the path follows the Kerr-Schild Hamiltonian instead
// (kerr.glsl; that hole alone).

const int SCENE_STEPS = 160;
// Path length past which a path counts as escaped: twice the render distance (it can curve back), at least 64 blocks.
float sceneFar() {
    return max(2.0 * RenderDistance, 64.0);
}

// Whether the level renderer draws the chunk holding camera-relative point p (ChunkTrackingView.isWithinDistance, as
// its section graph uses it), so traced geometry ends where the drawn world does.
bool drawnChunk(vec3 p) {
    ivec2 offset = ivec2(floor((CameraInChunk + p.xz) / 16.0));
    int i = max(0, abs(offset.x) - 1);
    int j = max(0, abs(offset.y) - 1);
    int far = max(i, j);
    int near = min(i, j);
    return near * near + far * far < ViewChunks * ViewChunks;
}

// Vanilla's fog distance of camera-relative point p.
float fogDistance(vec3 p) {
    if (LensFogShape == 0) return length(p);
    return max(length(p.xz), abs(p.y));
}
const float SETTLED = 60.0;       // r_s from every hole, heading away: bending is done, steps grow freely

vec3 lightBend(vec3 x, vec3 v) {
    vec3 a = vec3(0.0);
    for (int i = 0; i < HoleCount; i += 1) {
        vec3 d = x - Holes[i].xyz;
        float r2 = dot(d, d);
        vec3 hv = cross(d, v);
        a -= 1.5 * Holes[i].w * dot(hv, hv) * d / (r2 * r2 * sqrt(r2));
    }
    return a;
}

// The angle (radians, as seen from the camera) by which the holes shift what the straight view ray hits at distance
// dist (1e30: the sky), to first order: each hole's accumulated transverse deflection rs/b [s(t) - s(0)], with
// s(t) = (t - tP) / sqrt((t - tP)^2 + b^2), integrated to the hit and divided by its distance.
float weakShift(vec3 dir, float dist) {
    float total = 0.0;
    for (int i = 0; i < HoleCount; i += 1) {
        vec3 c = Holes[i].xyz;
        float rs = Holes[i].w;
        float tP = dot(c, dir);
        float b = max(length(c - dir * tP), 1e-3);
        float r0 = sqrt(tP * tP + b * b);
        float s0 = -tP / r0;
        if (dist > 1e29) {
            total += rs / b * (1.0 - s0);
        } else {
            float rD = sqrt((dist - tP) * (dist - tP) + b * b);
            total += rs / b * (rD - r0 - dist * s0) / max(dist, 1e-3);
        }
    }
    return total;
}

// The bent path of view ray dir at distance t along it, to first order (see weakShift): position and direction.
void weakPath(vec3 dir, float t, out vec3 x, out vec3 v) {
    x = dir * t;
    v = dir;
    for (int i = 0; i < HoleCount; i += 1) {
        vec3 c = Holes[i].xyz;
        float rs = Holes[i].w;
        float tP = dot(c, dir);
        vec3 toward = c - dir * tP;
        float b = max(length(toward), 1e-3);
        vec3 n = toward / b;
        float r0 = sqrt(tP * tP + b * b);
        float rT = sqrt((t - tP) * (t - tP) + b * b);
        float s0 = -tP / r0;
        x += n * (rs / b * (rT - r0 - t * s0));
        v += n * (rs / b * ((t - tP) / rT - s0));
    }
    v = normalize(v);
}

// Outcomes of a view ray's bent path (SceneHit).
const int SCENE_UNBENT = 1;  // the bend can't show: the original pixel
const int SCENE_ESCAPED = 4;
const int SCENE_FELL_IN = 5;
const int SCENE_VOXEL = 6;   // 6..11: a quad of the grid, facing along a face (2 axis, + 1 for +axis)
const int SCENE_VOXEL_BEHIND = 12; // 12..17: the same, in the grid past the hole

// ---- Geometry round the hole (LensVoxels) ----
const int VOXEL_SIZE = 128;   // LensVoxels.SIZE
const int VOXEL_STEPS = 48;   // most cells walked per path step
const int QUAD_TEXELS = 6;    // LensVoxels.QUAD_TEXELS
const int MAX_CELL_QUADS = 64;

vec4 voxelTexel(int slot, vec2 uv, float lod) {
    if (slot == 0) return textureLod(VoxelTex0, uv, lod);
    if (slot == 1) return textureLod(VoxelTex1, uv, lod);
    if (slot == 2) return textureLod(VoxelTex2, uv, lod);
    if (slot == 3) return textureLod(VoxelTex3, uv, lod);
    if (slot == 4) return textureLod(VoxelTex4, uv, lod);
    if (slot == 5) return textureLod(VoxelTex5, uv, lod);
    if (slot == 6) return textureLod(VoxelTex6, uv, lod);
    if (slot == 7) return textureLod(VoxelTex7, uv, lod);
    return vec4(1.0);
}

vec4 unpackColor(float bits) {
    uint c = floatBitsToUint(bits);
    return vec4(float((c >> 16u) & 255u), float((c >> 8u) & 255u), float(c & 255u), float(c >> 24u)) / 255.0;
}

// Ray o + t d (cell-local) against triangle p0 p1 p2 (Moller-Trumbore): t and barycentrics (u for p1, v for p2).
bool triangleHit(vec3 o, vec3 d, vec3 p0, vec3 p1, vec3 p2, out float t, out vec2 bary) {
    vec3 e1 = p1 - p0;
    vec3 e2 = p2 - p0;
    vec3 h = cross(d, e2);
    float det = dot(e1, h);
    if (abs(det) < 1e-12) return false;
    float f = 1.0 / det;
    vec3 s = o - p0;
    float u = f * dot(s, h);
    if (u < 0.0 || u > 1.0) return false;
    vec3 q = cross(s, e1);
    float v = f * dot(d, q);
    if (v < 0.0 || u + v > 1.0) return false;
    t = f * dot(e2, q);
    bary = vec2(u, v);
    return true;
}

vec3 unpackTint(uint c) {
    return vec3(float((c >> 16u) & 255u), float((c >> 8u) & 255u), float(c & 255u)) / 255.0;
}

// Quad q's texture coordinates at barycentrics bary of its first (second) triangle, and whether the pixel there shows
// (cutout pixels don't, judged at mip level 0); colour the pixel (at mip level lod) times the vertex colour and, for a
// tinted quad, its cell's tint (tints: block, fluid), or the flat colour without a texture.
bool quadPixel(int q, bool second, vec2 bary, float lod, uvec2 tints, out vec3 color) {
    int base = q * QUAD_TEXELS;
    vec4 c0 = texelFetch(VoxelQuads, base);
    vec4 c1 = texelFetch(VoxelQuads, base + 1);
    vec4 c2 = texelFetch(VoxelQuads, base + 2);
    int tint = int(texelFetch(VoxelQuads, base + 3).w + 0.5);
    vec3 tinted = vec3(1.0);
    if (tint == 1) tinted = unpackTint(tints.x);
    if (tint == 2) tinted = unpackTint(tints.y);
    vec4 uv01 = texelFetch(VoxelQuads, base + 4);
    vec4 uv23 = texelFetch(VoxelQuads, base + 5);
    int slot = int(c0.w);
    if (slot < 0) {
        vec4 plain = unpackColor(c2.w);
        color = plain.rgb * tinted;
        return plain.a > 0.5;
    }
    // Triangles (0, 1, 2) and (0, 2, 3).
    vec2 uvA = uv01.zw;
    vec2 uvB = uv23.xy;
    if (second) {
        uvA = uv23.xy;
        uvB = uv23.zw;
    }
    vec2 uv = uv01.xy * (1.0 - bary.x - bary.y) + uvA * bary.x + uvB * bary.y;
    if (voxelTexel(slot, uv, 0.0).a <= 0.1) return false;
    color = voxelTexel(slot, uv, lod).rgb * unpackColor(c1.w).rgb * tinted;
    return true;
}

const float COPLANAR_T = 1e-4; // hits this close count as the same surface: the later-drawn quad (an overlay) wins

// The nearest shown quad of cell run `run` hit by o + t d (cell-local) for t in [t0, t1]: t, the quad and triangle, and
// the face normal turned toward the ray. Of quads drawn over one another, the later one (as the game draws them).
bool cellHit(uint run, vec3 o, vec3 d, float t0, float t1, out float tHit, out vec3 normal) {
    int start = int(run >> 8u);
    int count = min(int(run & 255u), MAX_CELL_QUADS);
    tHit = t1;
    bool found = false;
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
            float t;
            vec2 bary;
            if (!triangleHit(o, d, p0, pb, pc, t, bary) || t < t0 || t >= t1) continue;
            if (found && t > tHit + COPLANAR_T) continue;
            if (int(texelFetch(VoxelQuads, base + 3).w + 0.5) == 2) continue; // tinted fluid (water): seen through
            vec3 color;
            if (!quadPixel(q, k == 1, bary, 0.0, uvec2(0u), color)) continue;
            tHit = t;
            normal = normalize(cross(pb - p0, pc - p0));
            if (dot(normal, d) > 0.0) normal = -normal;
            found = true;
        }
    }
    return found;
}

// Grid walk state over cells of size 1 in a grid's own coordinates (DDA).
struct Walk {
    ivec3 cell;
    ivec3 stepDir;
    vec3 next;
    vec3 delta;
    float t;
};

// Starts a walk along g0 + t d (grid coordinates) over [0, size)^3 for t in [0, 1]; false if it misses the grid.
bool walkStart(vec3 g0, vec3 d, int size, out Walk w, out float exit) {
    vec3 safe = d;
    for (int k = 0; k < 3; k += 1) {
        if (abs(safe[k]) < 1e-9) safe[k] = 1e-9;
    }
    vec3 inv = 1.0 / safe;
    vec3 tA = -g0 * inv;
    vec3 tB = (vec3(float(size)) - g0) * inv;
    vec3 tNear = min(tA, tB);
    vec3 tFar = max(tA, tB);
    float enter = max(max(max(tNear.x, tNear.y), tNear.z), 0.0);
    exit = min(min(min(tFar.x, tFar.y), tFar.z), 1.0);
    if (enter >= exit) return false;
    w.cell = clamp(ivec3(floor(g0 + d * enter)), ivec3(0), ivec3(size - 1));
    w.stepDir = ivec3(sign(safe));
    w.next = (vec3(w.cell) + max(vec3(w.stepDir), vec3(0.0)) - g0) * inv;
    w.delta = abs(inv);
    w.t = enter;
    return true;
}

// The walk's exit from its current cell, then on to the next; false once outside [0, size)^3.
float walkExit(Walk w) {
    return min(min(w.next.x, w.next.y), w.next.z);
}

bool walkStep(inout Walk w, int size) {
    if (w.next.x <= w.next.y && w.next.x <= w.next.z) {
        w.t = w.next.x;
        w.next.x += w.delta.x;
        w.cell.x += w.stepDir.x;
    } else if (w.next.y <= w.next.z) {
        w.t = w.next.y;
        w.next.y += w.delta.y;
        w.cell.y += w.stepDir.y;
    } else {
        w.t = w.next.z;
        w.next.z += w.delta.z;
        w.cell.z += w.stepDir.z;
    }
    return all(greaterThanEqual(w.cell, ivec3(0))) && all(lessThan(w.cell, ivec3(size)));
}

// Face index (2 axis, + 1 if the normal points along +axis) of the dominant axis of normal n.
int faceOf(vec3 n) {
    vec3 a = abs(n);
    int axis = 0;
    if (a.y >= a.x && a.y >= a.z) axis = 1;
    if (a.z >= a.x && a.z >= a.y) axis = 2;
    int face = 2 * axis;
    if (n[axis] > 0.0) face += 1;
    return face;
}

// The first shown quad of one grid's cells (minimum corner origin) that segment a -> a + d meets: t along it (0 .. 1)
// and its face normal (toward the ray).
bool gridHit(usampler3D cells, vec3 origin, vec3 a, vec3 d, out float tHit, out vec3 normal) {
    Walk w;
    float exit;
    if (!walkStart(a - origin, d, VOXEL_SIZE, w, exit)) return false;
    for (int n = 0; n < VOXEL_STEPS; n += 1) {
        uint run = texelFetch(cells, w.cell, 0).r;
        float cellExit = min(walkExit(w), exit);
        if (run != 0u) {
            vec3 o = a - origin - vec3(w.cell);
            if (cellHit(run, o, d, w.t - 1e-5, cellExit + 1e-5, tHit, normal)) return true;
        }
        if (cellExit >= exit || !walkStep(w, VOXEL_SIZE)) break;
    }
    return false;
}

// What segment a -> b (camera-relative) first meets of the geometry round the hole (the grids' quads, round it and
// past it): where (at), and the outcome (SCENE_VOXEL, or SCENE_VOXEL_BEHIND, plus the face).
bool geometryHit(vec3 a, vec3 b, out vec3 at, out int outcome) {
    vec3 d = b - a;
    float tMain;
    vec3 nMain;
    float tBehind;
    vec3 nBehind;
    bool main = gridHit(Voxels, VoxelOrigin, a, d, tMain, nMain);
    // The grids overlap (and hold the same blocks there): the one past the hole is walked only for a segment leaving
    // the main one's box, and only up to the main one's hit.
    vec3 ra = a - VoxelOrigin;
    vec3 rb = b - VoxelOrigin;
    bool insideMain = all(greaterThanEqual(min(ra, rb), vec3(0.0))) && all(lessThan(max(ra, rb), vec3(float(VOXEL_SIZE))));
    bool behind = false;
    if (VoxelBehindReady == 1 && !insideMain) {
        float reach = main ? tMain : 1.0;
        behind = gridHit(VoxelsBehind, VoxelOriginBehind, a, d * reach, tBehind, nBehind);
        tBehind *= reach;
    }
    if (!main && !behind) return false;
    if (main && (!behind || tMain <= tBehind)) {
        at = a + d * tMain;
        outcome = SCENE_VOXEL + faceOf(nMain);
    } else {
        at = a + d * tBehind;
        outcome = SCENE_VOXEL_BEHIND + faceOf(nBehind);
    }
    return true;
}

// Where the bent path of view ray dir (camera-relative) ends up.
struct SceneHit {
    int outcome;  // SCENE_UNBENT, SCENE_ESCAPED, SCENE_FELL_IN, or SCENE_VOXEL + face
    vec3 value;   // escaped: final direction; geometry: the point hit, camera-relative
};

SceneHit sceneHit(int outcome, vec3 value) {
    SceneHit hit;
    hit.outcome = outcome;
    hit.value = value;
    return hit;
}

// Whether the holes shift what the straight view ray hits (at sceneDist) by too little to show.
bool sceneUnbent(vec3 dir, float sceneDist) {
    return weakShift(dir, sceneDist) < 0.25 * pixelAngle();
}

int marchSteps = 0; // debug (LensMode 2)

SceneHit marchScene(vec3 dir, float sceneDist) {
    marchSteps = 0;
    for (int i = 0; i < HoleCount; i += 1) {
        if (length(Holes[i].xyz) <= Holes[i].w) return sceneHit(SCENE_FELL_IN, vec3(0.0)); // inside a horizon
    }
    if (sceneUnbent(dir, sceneDist)) return sceneHit(SCENE_UNBENT, vec3(0.0));

    // Up to where the path has drifted a tenth of a pixel from the straight view ray (found by bisection; there's no
    // surface before it, as the straight ray's own hit is farther), the first-order path is exact enough to start from.
    float tight = 0.1 * pixelAngle();
    float lo = 0.0;
    float hi = min(sceneDist, sceneFar());
    if (weakShift(dir, hi) < tight) lo = hi;
    for (int k = 0; k < 12 && lo < hi; k += 1) {
        float mid = 0.5 * (lo + hi);
        if (weakShift(dir, mid) < tight) lo = mid; else hi = mid;
    }
    vec3 x;
    vec3 v;
    weakPath(dir, lo, x, v);
    float traveled = lo;

    // Kerr state, started where the path first comes under the nearest hole's influence: local position, momentum.
    bool kerr = abs(HoleSpins[0].x) >= MIN_KERR_SPIN;
    bool kerrLive = false;
    float kerrM = 0.5 * Holes[0].w;
    float kerrA = HoleSpins[0].x * kerrM;
    float kerrHorizon = kerrM + sqrt(max(kerrM * kerrM - kerrA * kerrA, 0.0));
    vec3 kx = vec3(0.0);
    vec3 kp = vec3(0.0);
    float kpt = 0.0;
    for (int n = 0; n < SCENE_STEPS; n += 1) {
        marchSteps = n + 1;
        // Nearest hole, in r_s, and whether every hole is settled (far, and the path leaving it).
        float near = 1e30;
        float nearRs = 1.0;
        float nearHorizon = 1.0;
        bool settled = true;
        for (int i = 0; i < HoleCount; i += 1) {
            vec3 d = x - Holes[i].xyz;
            float r = length(d);
            if (r < near) {
                near = r;
                nearRs = Holes[i].w;
                nearHorizon = Holes[i].w;
                if (i == 0 && kerr) nearHorizon = kerrHorizon; // inside r_s when spinning
            }
            if (r < SETTLED * Holes[i].w || dot(d, v) < 0.0) settled = false;
        }
        // Steps grow with distance travelled (hits are refined by bisection), and near a hole with the height above
        // its horizon, but no smaller than 15% of the radius (so a ray grazing the photon sphere takes ~40 steps an
        // orbit) unless that's more than half the height.
        float ds = 0.1 * traveled + 0.3;
        float above = max(near - nearHorizon, 0.0);
        if (!settled) ds = min(ds, max(max(0.25 * above, min(0.15 * near, 0.5 * above)), 0.01 * nearRs));
        vec3 xNext;
        vec3 vNext;
        if (settled) {
            xNext = x + v * ds;
            vNext = v;
        } else if (kerr) {
            if (!kerrLive) {
                kx = toLocal(x - Holes[0].xyz);
                kerrNullMomentum(kx, -toLocal(v), kerrM, kerrA, kp, kpt); // the photon arriving along the path
                kerrLive = true;
            }
            vec3 k1x;
            vec3 k1p;
            kerrBackward(kx, kp, kpt, kerrM, kerrA, k1x, k1p);
            float dl = ds / max(length(k1x), 1e-6);
            vec3 k2x;
            vec3 k2p;
            kerrBackward(kx + 0.5 * dl * k1x, kp + 0.5 * dl * k1p, kpt, kerrM, kerrA, k2x, k2p);
            vec3 k3x;
            vec3 k3p;
            kerrBackward(kx + 0.5 * dl * k2x, kp + 0.5 * dl * k2p, kpt, kerrM, kerrA, k3x, k3p);
            vec3 k4x;
            vec3 k4p;
            kerrBackward(kx + dl * k3x, kp + dl * k3p, kpt, kerrM, kerrA, k4x, k4p);
            kx += dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
            kp += dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
            float h;
            vec3 l;
            if (kerrProfile(kx, kerrM, kerrA, h, l) < 1.02 * kerrHorizon) return sceneHit(SCENE_FELL_IN, vec3(0.0));
            xNext = toGame(kx) + Holes[0].xyz;
            vNext = normalize(toGame(k4x));
        } else {
            vec3 k1v = lightBend(x, v);
            if ((ProfileFlags & 4) != 0) {
                xNext = x + ds * v;
                vNext = normalize(v + ds * k1v);
            } else {
            vec3 k2v = lightBend(x + 0.5 * ds * v, v + 0.5 * ds * k1v);
            vec3 k3v = lightBend(x + 0.5 * ds * (v + 0.5 * ds * k1v), v + 0.5 * ds * k2v);
            vec3 k4v = lightBend(x + ds * (v + 0.5 * ds * k2v), v + ds * k3v);
            xNext = x + ds * (v + ds / 6.0 * (k1v + k2v + k3v));
            vNext = normalize(v + ds / 6.0 * (k1v + 2.0 * k2v + 2.0 * k3v + k4v));
            }
        }
        for (int i = 0; i < HoleCount; i += 1) {
            if ((i > 0 || !kerr) && length(xNext - Holes[i].xyz) < 1.01 * Holes[i].w) return sceneHit(SCENE_FELL_IN, vec3(0.0));
        }
        if (UnseenSource == 1 && (ProfileFlags & 2) == 0) {
            vec3 at;
            int outcome;
            // Past the render distance there's nothing drawn, so nothing to meet.
            if (geometryHit(x, xNext, at, outcome) && (drawnChunk(at) || (ProfileFlags & 8) != 0)) {
                return sceneHit(outcome, at);
            }
        }
        x = xNext;
        v = vNext;
        traveled += ds;
        if (traveled > sceneFar()) break;
    }

    return sceneHit(SCENE_ESCAPED, v); // the sky, or anything beyond reach, in the final direction
}

