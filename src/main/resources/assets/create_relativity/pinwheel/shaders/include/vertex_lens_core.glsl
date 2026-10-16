// High-quality lens: each world vertex moves to where its light appears (the primary image), keeping its true distance,
// so depth testing sorts what's in front. Injected at the end of vanilla world vertex shaders (see vertex_lens.json).
// Only draws using the level's own projection are lensed: not the hand, GUI items or other level views.

// One array, not named members: Veil lists a program's active uniforms and warns about block members it can't locate,
// except arrays (which it takes for another mod's uniform block). Same std140 layout as the members would have:
// [0] the hole: xyz centre relative to the camera in view space (blocks), w Schwarzschild radius (0 = off)
// [1] x: 1 while the level is drawn with lensing on; y: 1 skips the image table (debug); z: 1 for a spinning hole;
//     w: 1 hides vertices the Kerr table didn't place (debug)
// [2..5] the level's projection this frame, by column
// [6] spinning hole (Kerr; [1].z = 1): xyz centre relative to the camera in game axes, w spin a / M
// [7] the Kerr path table (KerrPaths): zw its size in texels (azimuth, angle from the hole; see relativityTableDir)
// [8..10] view to game axes (a rotation), by column
// [11] x: the angle one screen pixel covers (radians; the pixel check); y: bit 1 turns the pixel check off (debug), bit 2
//      places vertices at their secondary images (VertexLens.drawSecondImages); z, w: the image map's inner and outer
//      radius (blocks)
// [12..27] an approximated lone spinning hole's exact shadow edge (HorizonMesh.edges): its angle from the hole at 64
//      azimuths round it (relativityShadowEdge), all 0 when there's none
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[28];
};
uniform sampler3D RelativityImages; // SchwarzschildImages.forFiltering over (sqrt u0, sqrt uP, phi / pi): r the
                                    // launch-angle correction, g whether there's an image (interpolated)

uniform sampler3D RelativitySecondImages; // SchwarzschildImages.buildSecondaryTable, as RelativityImages: r ln(psi - psi_c)
                                          // of the secondary image (the long way round), g whether there's one
uniform sampler2DArray RelativityKerrPaths; // KerrPaths' table (kerr_paths.fsh): per texel, a view ray's path past a
                                            // spinning hole, as seen from the hole
uniform sampler3D RelativityKerrSecondMap; // KerrSlots' secondary-image map (as RelativityKerrMap: the light the long way
                                           // round), for spinning holes' secondary images
uniform sampler3D RelativityKerrMap; // KerrImageMap (kerr_image_map.fsh): per point round a spinning hole, how its image
                                     // is turned from the straight line of sight per block of its distance (rgb, game
                                     // axes), a 1 where it has one

// The Kerr image map's texels: azimuth round the spin axis (game y), cos of the angle from it, and radius
// (logarithmic from [11].z to [11].w). Texel (i, j, k) is the point at azimuth ((i + 0.5) / W - 0.5) 2 pi (as
// atan(z, x)), cos j / (H - 1) * 2 - 1, radius [11].z ([11].w / [11].z)^(k / (D - 1)).
const vec3 RELATIVITY_MAP_SIZE = vec3(128.0, 64.0, 48.0); // KerrImageMap.WIDTH, HEIGHT, DEPTH

// Loop counts read from the block (kerrDebug, never negative, so this is 0), not constants: a constant-count loop gets
// unrolled, and with the light steps and solves inlined into it the driver took tens of seconds to compile the programs
// using them (35 s for the image map's).
// Whether this draw places vertices at their secondary images ([11].y bit 2).
// (Or relativityForceSecond: the image map solving secondary images, kerr_image_map.fsh.)
bool relativityForceSecond = false;
bool relativitySecond() { return relativityForceSecond || mod(RelativityLensData[11].y, 4.0) > 1.5; }

int relativityRuntimeZero() { return int(min(RelativityLensData[1].w, 0.0)); }

const float RELATIVITY_AXIS_CUT = 2.0;  // blocks: larger than a block face's diagonal (draws without the pixel check)

// The axis cut for this draw ([11].y above bit 2, in hundredths of a block): small where the pixel check trims what
// wraps round the ring and terrain by the axis is cut fine (QuadSubdivision's axis tube), the full RELATIVITY_AXIS_CUT
// where nothing trims it (Flywheel's shaders, which carry no pixel check).
float relativityAxisCut() {
    // (Round a spinning hole the full cut: its ring isn't centred on the straight camera-hole axis the cut and the tube
    // are measured from, and letting its images by that axis through put 1.6% of a side view wrong instead of 0.4%.)
    // (Codes from 1000 up: the fine cut even there, for fine geometry, DiskMesh's.)
    float code = floor(RelativityLensData[11].y / 4.0);
    if (code >= 1000.0) return (code - 1000.0) / 100.0;
    // (An approximated spinning hole is lensed as a non-spinning one is, round its moved centre: the same fine cut.)
    if (RelativityLensData[1].z > 0.5 && RelativityLensData[7].y < 2.5) return RELATIVITY_AXIS_CUT;
    return code > 0.5 ? code / 100.0 : RELATIVITY_AXIS_CUT;
}
const float RELATIVITY_BEYOND = 1e4;   // NDC depth of hidden vertices: far past the far plane

bool relativityLevelProjection(mat4 proj) {
    mat4 d = proj - mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]);
    float diff = abs(d[0][0]) + abs(d[1][1]) + abs(d[2][2]) + abs(d[3][2]) + abs(d[2][3]) + abs(d[2][0]) + abs(d[2][1]);
    return diff < 1e-4;
}

// ---- Spinning holes: the image found on the Kerr path table (kerr_paths.fsh) by Newton's method ----

// ---- The Kerr path table's directions ----
// The table covers every direction from the camera, independent of where it looks: texel column x (x + 0.5 at
// centres, wrapping round) is the azimuth 2 pi x / W round the camera-to-hole line, from game up's side; row y is the
// angle from that line, alpha(y / H), with RELATIVITY_TABLE_KNEE of the rows within the knee angle (four shadow
// radii: where images change fastest) and the rest spread out to pi.
const float RELATIVITY_TABLE_KNEE = 0.6;

float relativityKneeAngle(vec3 hole, float rs) {
    return min(4.0 * asin(min(2.6 * rs / max(length(hole), 1e-3), 1.0)), 0.6 * 3.14159265);
}

float relativityAlpha(float u, float knee) {
    if (u <= RELATIVITY_TABLE_KNEE) return u / RELATIVITY_TABLE_KNEE * knee;
    return knee + (u - RELATIVITY_TABLE_KNEE) / (1.0 - RELATIVITY_TABLE_KNEE) * (3.14159265 - knee);
}

// d alpha / d u.
float relativityAlphaRate(float u, float knee) {
    return u <= RELATIVITY_TABLE_KNEE ? knee / RELATIVITY_TABLE_KNEE : (3.14159265 - knee) / (1.0 - RELATIVITY_TABLE_KNEE);
}

float relativityAlphaU(float alpha, float knee) {
    if (alpha <= knee) return alpha / knee * RELATIVITY_TABLE_KNEE;
    return RELATIVITY_TABLE_KNEE + (alpha - knee) / (3.14159265 - knee) * (1.0 - RELATIVITY_TABLE_KNEE);
}

// The table's frame for a hole at hole (camera-relative, game axes): the line to the hole, and the azimuth's zero
// (game up, made square to it) and quarter turn.
void relativityTableFrame(vec3 hole, out vec3 axis, out vec3 zero, out vec3 quarter) {
    axis = normalize(hole);
    vec3 up = abs(axis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    zero = normalize(up - dot(up, axis) * axis);
    quarter = cross(axis, zero);
}

// The direction (game axes) at table position at (texels) of a table size texels, for a hole at hole with radius rs.
vec3 relativityTableDir(vec2 at, vec2 size, vec3 hole, float rs) {
    vec3 axis;
    vec3 zero;
    vec3 quarter;
    relativityTableFrame(hole, axis, zero, quarter);
    float azimuth = at.x / size.x * 6.28318530718;
    float alpha = relativityAlpha(clamp(at.y / size.y, 0.0, 1.0), relativityKneeAngle(hole, rs));
    return cos(alpha) * axis + sin(alpha) * (cos(azimuth) * zero + sin(azimuth) * quarter);
}

// The table position (texels; x in [0, W)) of direction dir (game axes).
vec2 relativityTableAt(vec3 dir, vec2 size, vec3 hole, float rs) {
    vec3 axis;
    vec3 zero;
    vec3 quarter;
    relativityTableFrame(hole, axis, zero, quarter);
    float alpha = acos(clamp(dot(dir, axis), -1.0, 1.0));
    float azimuth = atan(dot(dir, quarter), dot(dir, zero));
    if (azimuth < 0.0) azimuth += 6.28318530718;
    return vec2(azimuth / 6.28318530718 * size.x, relativityAlphaU(alpha, relativityKneeAngle(hole, rs)) * size.y);
}

// The path table's readers and the solves on it: only in the programs that build or invert the table (GLSL 3.30,
// which defines RELATIVITY_PATHS), not the world's vertex shaders (1.50: no bit casts), which only read the map.
vec2 relativityLastSolve = vec2(-1.0); // debug (KerrSolveCheck): the last found solve's start (0..2) and path position v
int relativityLastFound = 2; // debug (KerrSolveCheck): the last Kerr solve's outcome (2: none ran)

#ifdef RELATIVITY_PATHS
// Half-precision packing by hand (packHalf2x16 is GLSL 4.20): round to nearest, values under 6e-5 flushed to zero.
uint relativityHalf(float v) {
    uint b = floatBitsToUint(v);
    uint sign = (b >> 16) & 0x8000u;
    int e = int((b >> 23) & 0xffu) - 112;
    if (e <= 0) return sign;
    if (e >= 31) return sign | 0x7c00u;
    uint h = sign | (uint(e) << 10) | ((b & 0x7fffffu) >> 13);
    if ((b & 0x1000u) != 0u) h += 1u;
    return h;
}

float relativityUnhalf(uint h) {
    uint e = (h >> 10) & 0x1fu;
    if (e == 0u) return 0.0;
    return uintBitsToFloat(((h & 0x8000u) << 16) | ((e + 112u) << 23) | ((h & 0x3ffu) << 13));
}

// Two values in one float's bits: b in the high half (kept positive and normal, so the float is too: a render target
// keeps it exactly), a in the low.
float relativityPackPair(float a, float b) {
    return uintBitsToFloat((relativityHalf(b) << 16) | relativityHalf(a));
}

vec2 relativityUnpackPair(float pair) {
    uint u = floatBitsToUint(pair);
    return vec2(relativityUnhalf(u & 0xffffu), relativityUnhalf(u >> 16));
}

// Path sample j (-14 .. 14: coming in, the closest approach at 0, going out) of the table's texel t: the angle round the
// centre in its ray's plane, the elevation out of it (an angle), the distance from the centre (unpacked: kerr_paths.fsh).
vec3 relativityPathSample(ivec2 t, int j) {
    vec2 raw;
    if (j == 0) {
        raw = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0).zw;
    } else {
        // (kerr_paths.fsh's layout: 1-7 out to |j| = 7 in layers 1-7, the rest in layers 8-14.)
        int aj = abs(j);
        int f;
        int base;
        if (aj <= 7) {
            f = j < 0 ? aj - 1 : j + 6;
            base = 1;
        } else {
            f = j < 0 ? aj - 8 : j - 1;
            base = 8;
        }
        vec4 v = texelFetch(RelativityKerrPaths, ivec3(t, base + f / 2), 0);
        raw = (f - 2 * (f / 2)) == 0 ? v.xy : v.zw;
    }
    return vec3(raw.x, relativityUnpackPair(raw.y));
}

// A path sample (relativityPathSample) as a point (from the centre, game axes), in its ray's frame: e1 centre to camera,
// side in the ray's plane, normal out of it.
vec3 relativityPathPoint(vec3 s, vec3 e1, vec3 side, vec3 normal) {
    return s.z * (cos(s.y) * (cos(s.x) * e1 + sin(s.x) * side) + sin(s.y) * normal);
}

// Texel t's path (kerr_paths.fsh) at position v (-14 .. 14 along its samples: coming in, the closest approach at 0,
// going out): the point (from the centre, game axes; Catmull-Rom through the samples as points: through the angles, the
// pole of the ray's plane, where a spinning hole can swing light, would wrap them round). ok false where the texel has
// no usable path there.
vec3 relativityPathAt(ivec2 t, float v, vec3 e1, vec3 side, vec3 normal, out bool ok) {
    vec4 head = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0);
    int status = int(floor(head.y / 8192.0));
    ok = status == 1 || (status == 2 && v <= 0.0);
    float base = clamp(floor(v), -14.0, 13.0);
    float f = v - base;
    int j = int(base);
    vec3 p0 = relativityPathPoint(relativityPathSample(t, max(j - 1, -14)), e1, side, normal);
    vec3 p1 = relativityPathPoint(relativityPathSample(t, j), e1, side, normal);
    vec3 p2 = relativityPathPoint(relativityPathSample(t, j + 1), e1, side, normal);
    vec3 p3 = relativityPathPoint(relativityPathSample(t, min(j + 2, 14)), e1, side, normal);
    float f2 = f * f;
    float f3 = f2 * f;
    return 0.5 * (2.0 * p1 + (p2 - p0) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f3);
}

// How far texel t's path at v misses the point at direction n (from the hole, game axes) and radius rP: the gap between
// them in space, per unit of rP (game axes). (Not the radius and the angles round the hole apart: where the light passes
// the point moving round the hole, its radius barely changes along it, and matching the radius first put the crossing a
// tenth of a block or more along the path from a path only a hundredth of a block off: images ten pixels astray.)
vec3 relativityPathMiss(ivec2 t, vec3 n, float rP, float v, out bool ok) {
    vec3 dir = relativityTableDir(vec2(t) + 0.5, RelativityLensData[7].zw, RelativityLensData[6].xyz, RelativityLensData[7].x);
    vec3 e1 = normalize(-RelativityLensData[6].xyz);
    vec3 normal = cross(e1, dir);
    if (dot(normal, normal) < 1e-12) {
        ok = false;
        return vec3(0.0);
    }
    normal = normalize(normal);
    vec3 side = cross(normal, e1);
    vec3 at = relativityPathAt(t, v, e1, side, normal, ok);
    return (at - rP * n) / rP;
}

// The miss at x = (table position in texels, path position v), bilinear between texels (wrapping round in azimuth).
vec3 relativityPathImage(vec3 x, vec3 n, float rP, out bool ok) {
    ivec2 size = ivec2(RelativityLensData[7].zw);
    vec2 pos = x.xy - 0.5;
    ivec2 b = ivec2(floor(pos));
    vec2 f = pos - vec2(b);
    ok = b.y >= 0 && b.y + 1 < size.y;
    if (!ok) return vec3(0.0);
    // (Not %: with a negative operand GLSL leaves it undefined, and just past the seam b.x is -1.)
    int x0 = b.x - size.x * int(floor(float(b.x) / float(size.x)));
    int x1 = (x0 + 1) % size.x;
    bool o0;
    bool o1;
    bool o2;
    bool o3;
    vec3 a = relativityPathMiss(ivec2(x0, b.y), n, rP, x.z, o0);
    vec3 c = relativityPathMiss(ivec2(x1, b.y), n, rP, x.z, o1);
    vec3 d = relativityPathMiss(ivec2(x0, b.y + 1), n, rP, x.z, o2);
    vec3 e = relativityPathMiss(ivec2(x1, b.y + 1), n, rP, x.z, o3);
    ok = o0 && o1 && o2 && o3;
    return mix(mix(a, c, f.x), mix(d, e, f.x), f.y);
}

// Newton's method (backtracking) on x = (table position, path position) for the path through the point at direction n
// and radius rP. True if it converged; x then holds the image's table position, and jacobian (if measured) the last
// step's Jacobian (columns: the miss's change per unit of x's components).
bool relativityKerrSolve(inout vec3 x, vec3 n, float rP, out mat3 jacobian, out bool measured) {
    vec3 steps = vec3(0.5, 0.5, 0.02);
    measured = false;
    jacobian = mat3(0.0);
    for (int i = 0; i < 10 + relativityRuntimeZero(); i += 1) {
        bool ok;
        vec3 miss = relativityPathImage(x, n, rP, ok);
        if (!ok) return false;
        float current = dot(miss, miss);
        if (current < 1e-8) return true;
        for (int c = 0; c < 3 + relativityRuntimeZero(); c += 1) {
            vec3 shifted = x;
            shifted[c] += steps[c];
            bool okShift;
            jacobian[c] = (relativityPathImage(shifted, n, rP, okShift) - miss) / steps[c];
            if (!okShift) return false;
        }
        measured = true;
        if (abs(determinant(jacobian)) < 1e-18) return false;
        vec3 step = inverse(jacobian) * miss;
        float length2 = dot(step.xy, step.xy);
        if (length2 > 64.0) step.xy *= 8.0 / sqrt(length2); // (near the ring, images far from the guess stay unfound: hidden)
        step.z = clamp(step.z, -2.0, 2.0);
        // Already there to within float precision (a warm start usually is): the miss's floor isn't always under the
        // tolerance above, and iterating on would only spin.
        if (dot(step.xy, step.xy) < 1e-6 && abs(step.z) < 1e-4) {
            x -= step;
            break;
        }
        // Halve a step that makes the miss worse.
        float scale = 1.0;
        vec3 next = x;
        for (int k = 0; k < 5 + relativityRuntimeZero(); k += 1) {
            next = vec3(x.xy - step.xy * scale, clamp(x.z - step.z * scale, -14.0, 14.0));
            bool okNext;
            vec3 m = relativityPathImage(next, n, rP, okNext);
            if (okNext && dot(m, m) < current) break;
            scale *= 0.5;
        }
        x = next;
    }
    bool ok;
    vec3 miss = relativityPathImage(x, n, rP, ok);
    return ok && dot(miss, miss) < 1e-6;
}


// The image's magnification at solution x (for the point at direction n, radius rP from the hole, camera distance
// d): the point's area per texel squared unlensed over lensed. The solve's Jacobian, with the path position held to
// the radius (the third residual), maps texels to directions round the hole; near a fold (where two images of the
// ground meet and merge) it goes singular and the magnification blows up. 0 where it can't be measured.
float relativityKerrMagnificationOf(mat3 jacobian, vec2 at, float rP, float d) {
    // Column c: d miss / d x_c (the gap per unit of rP). The path position's column is the path's own direction at the
    // point: the table position's columns, with that taken out, span the patch of space their texel's light covers there.
    vec3 along = jacobian[2];
    if (dot(along, along) < 1e-12) return 0.0;
    vec3 cx = jacobian[0] - along * (dot(jacobian[0], along) / dot(along, along));
    vec3 cy = jacobian[1] - along * (dot(jacobian[1], along) / dot(along, along));
    float lensed = length(cross(cx, cy)) * rP * rP;
    // The texel's solid angle: its angle step times its azimuth step (both in radians).
    vec2 size = RelativityLensData[7].zw;
    float knee = relativityKneeAngle(RelativityLensData[6].xyz, RelativityLensData[7].x);
    float u = clamp(at.y / size.y, 0.0, 1.0);
    float area = relativityAlphaRate(u, knee) / size.y * sin(relativityAlpha(u, knee)) * 6.28318530718 / size.x;
    return area * d * d / max(lensed, 1e-30);
}

float relativityKerrMagnification(vec3 x, vec3 n, float rP, float d) {
    bool ok;
    vec3 miss = relativityPathImage(x, n, rP, ok);
    vec3 steps = vec3(0.5, 0.5, 0.02);
    mat3 jacobian;
    for (int c = 0; c < 3 + relativityRuntimeZero(); c += 1) {
        vec3 shifted = x;
        shifted[c] += steps[c];
        bool okShift;
        jacobian[c] = (relativityPathImage(shifted, n, rP, okShift) - miss) / steps[c];
        if (!okShift) return 0.0;
    }
    return relativityKerrMagnificationOf(jacobian, x.xy, rP, d);
}

// Where along texel t's path the distance rP from the centre is first met coming in (v < 0, searched from the start)
// or going out (from the closest approach): a starting point for the solve. Between the samples that bracket it,
// linearly; the closest approach (0) if none do, coming in; the end (7) going out.
float relativityPathPosition(ivec2 t, float rP, bool outbound) {
    int first = -14;
    if (outbound) first = 0;
    float last = relativityPathSample(t, first).z;
    for (int k = 1; k <= 14 + relativityRuntimeZero(); k += 1) {
        int j = outbound ? k : k - 14;
        float r = relativityPathSample(t, j).z;
        if ((r - rP) * (last - rP) <= 0.0) {
            float f = (rP - last) / (abs(r - last) > 1e-9 ? r - last : 1e-9);
            return float(j - 1) + clamp(f, 0.0, 1.0);
        }
        last = r;
    }
    return outbound ? 14.0 : 0.0;
}



// The view direction of p's image under a spinning hole ([6]), by Newton's method on the table and along its paths,
// started at the view direction guess (the Schwarzschild image): 1 found; 0 not found but the guess is near the
// table's edge (off screen: the caller's Schwarzschild image stands, so a triangle reaching on screen keeps its
// corner); -1 not found (near the line behind the hole, where images crowd the Einstein ring: hidden, like
// Schwarzschild's near-axis vertices, and left to the post pass).
// Magnification past which a Kerr image counts as on a fold (healthy images beside one stay under about 40).
#define RELATIVITY_FOLD_CUT 80.0

// warmV: where along its path the guess's image was last time (|warmV| <= 14; larger for none), tried first.
// solvedV: where along its path the image found is.
int relativityKerrImage(vec3 p, vec3 guess, float warmV, out vec3 seen, out float solvedV) {
    seen = guess;
    solvedV = 99.0;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 hole = RelativityLensData[6].xyz; // the lens centre
    vec3 d = viewToGame * p - hole;
    float rP = length(d);
    vec3 n = normalize(d);
    vec2 size = RelativityLensData[7].zw;
    float rs = RelativityLensData[7].x;
    vec2 start = relativityTableAt(viewToGame * guess, size, hole, rs);
    // Straight away from the hole (the table's far edge), there's hardly any bending: the guess stands.
    if (start.y > size.y - 2.0) return 0;
    start.y = max(start.y, 1.0);
    ivec2 at = ivec2(start);
    // Coming in first (the image whose light reaches p first along its path), then going out, then from the closest
    // approach.
    float tries[3];
    tries[0] = rP <= relativityPathSample(at, -14).z ? relativityPathPosition(at, rP, false) : relativityPathPosition(at, rP, true);
    if (abs(warmV) <= 14.0) tries[0] = warmV;
    tries[1] = relativityPathPosition(at, rP, true);
    tries[2] = 0.0;
    // The image on the same side of the hole as the guess (within a quarter turn round it): the primary, not the one
    // across the ring.
    for (int k = 0; k < 3 + relativityRuntimeZero(); k += 1) {
        vec3 x = vec3(start, tries[k]);
        mat3 jacobian;
        bool measured;
        if (relativityKerrSolve(x, n, rP, jacobian, measured) && cos((x.x - start.x) / size.x * 6.28318530718) >= 0.0) {
            seen = transpose(viewToGame) * relativityTableDir(x.xy, size, hole, rs);
            solvedV = x.z;
            // (The last step's Jacobian is close enough to measure it with; a guess already at the answer has none.)
            float magnification = measured ? relativityKerrMagnificationOf(jacobian, x.xy, rP, length(p)) : relativityKerrMagnification(x, n, rP, length(p));
            relativityLastSolve = vec2(float(k), magnification);
            // At a fold the solve lands between the two merging images, and a triangle reaching such a vertex is
            // stretched across both: hidden, and the strip left to the post pass (as Schwarzschild's near-axis cut).
            if (magnification > RELATIVITY_FOLD_CUT) { relativityLastSolve.x = 5.0; return -1; }
            if (RelativityLensData[1].w > 1.5 && RelativityLensData[1].w < 2.5 && x.z > 0.0) return -1; // debug 2: hide going out
            if (RelativityLensData[1].w > 2.5 && RelativityLensData[1].w < 3.5 && x.z <= 0.0) return -1; // debug 3: hide coming in
            return 1;
        }
    }
    return -1;
}



#endif

// The Schwarzschild image of view-space point p: 1 with seen its view direction; 2 where there's nothing to bend
// (no hole, too weak, or inside the radius: seen is p's own direction); -1 with no primary image (hidden behind the
// hole). nearAxis: near the camera-hole line behind the hole (images round the Einstein ring at the azimuth of their
// side of the line, so a triangle across it would be stretched over the whole ring).
const float RELATIVITY_KERR_CUT_K = 0.12;

// Whether view-space point p is within the axis cut of the camera-hole line (behindOnly: past the hole).
// (hole: the lens's centre the image is worked out round: an approximated spinning hole's is moved, and so is its axis.)
bool relativityNearAxis(vec3 p, bool behindOnly, vec3 hole) {
    vec3 axis = hole;
    vec3 e1 = -normalize(axis); // hole to camera
    vec3 from = p - axis;
    float along = dot(from, e1);
    if (behindOnly && along >= 0.0) return false;
    float cut = relativityAxisCut();
    // Round a spinning hole: a fraction of the Einstein radius at the point's distance behind the hole (where images
    // are stretched more than about 1 / RELATIVITY_KERR_CUT_K times), the way the stretch grows: with the square root of
    // the hole's size and of the distance behind it. (Not a fixed width: wider than needed by the hole, narrower far out.)
    // (An approximated one is lensed as a non-spinning one is, round its moved centre: the fine cut, as for that.)
    if (RelativityLensData[1].z > 0.5 && RelativityLensData[7].y < 2.5) {
        float rc = length(RelativityLensData[0].xyz);
        float behind = max(-along, 0.0);
        float einstein = sqrt(2.0 * RelativityLensData[0].w * behind * rc / (rc + behind));
        cut = max(0.25, RELATIVITY_KERR_CUT_K * einstein);
    }
    return length(from - along * e1) < cut;
}

// Where the lens's Einstein-ring axis runs through: the hole, or round an approximated spinning hole the centre it's
// lensed round there (relativityKerrApprox: moved by shift, grown by about RELATIVITY_CAUSTIC_SHIFT by the ring).
const float RELATIVITY_CAUSTIC_SHIFT = 1.2; // QuadSubdivision.CAUSTIC_SHIFT
vec3 relativityCausticCentre() {
    vec3 hole = RelativityLensData[0].xyz;
    if (RelativityLensData[1].z < 0.5 || RelativityLensData[7].y < 2.5) return hole;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 eGame = normalize(RelativityLensData[6].xyz);
    vec3 shift = transpose(viewToGame) * (-RelativityLensData[6].w * 0.5 * RelativityLensData[0].w * cross(vec3(0.0, 1.0, 0.0), eGame));
    return hole + RELATIVITY_CAUSTIC_SHIFT * shift;
}

// Whether view-space point p lies within reach (blocks) of the Einstein-ring axis behind the hole.
bool relativityByAxis(vec3 p, float reach) {
    vec3 centre = relativityCausticCentre();
    vec3 e1 = -normalize(centre);
    vec3 from = p - centre;
    float along = dot(from, e1);
    return along < 0.0 && length(from - along * e1) < reach;
}

int relativitySchwarzschildImageAt(vec3 p, vec3 h, out vec3 seen, out bool nearAxis) {
    seen = normalize(p);
    nearAxis = false;
    float rs = RelativityLensData[0].w;
    float rc = length(h);
    vec3 fromHole = p - h;
    float rp = length(fromHole);
    if (rs <= 0.0 || rc <= rs || rp <= rs) return 2;
    float u0 = max(rs / rc, 1e-4);
    float uP = max(rs / rp, 1e-4);
    if (u0 < 2e-3 && uP < 2e-3) return 2; // bending under a few milliradians
    vec3 e1 = -h / rc; // hole to camera
    vec3 radial = fromHole / rp;
    float cosPhi = clamp(dot(radial, e1), -1.0, 1.0);
    vec3 side = radial - cosPhi * e1;
    float sideLength = length(side);
    vec3 e2 = side / max(sideLength, 1e-6);
    float phi = acos(cosPhi);

    vec3 size = vec3(64.0, 64.0, 128.0);
    vec3 axes = vec3(sqrt(u0), sqrt(uP), phi / 3.14159265);
    if (relativitySecond()) {
        // The secondary image: launched just outside the critical angle, toward the far side from p's, sweeping
        // 2 pi - phi. Near the camera-hole line on either side its images run all round the ring, as the primary's
        // do behind the hole.
        nearAxis = relativityNearAxis(p, false, h);
        if (u0 >= 2.0 / 3.0) return -1; // inside the photon sphere: none tabled
        vec2 second = texture(RelativitySecondImages, (axes * (size - 1.0) + 0.5) / size).rg;
        if (second.g < 0.999) return -1;
        float critical = asin(min(2.59807621 * u0 * sqrt(1.0 - u0), 1.0));
        float launch = critical + exp(second.r);
        float psi2 = atan(sin(launch), cos(launch) * sqrt(1.0 - u0));
        seen = -cos(psi2) * e1 - sin(psi2) * e2;
        return 1;
    }
    vec2 image = texture(RelativityImages, (axes * (size - 1.0) + 0.5) / size).rg;
    nearAxis = relativityNearAxis(p, true, h);
    if (image.g < 0.5) return -1;
    float rcU = 1.0 / u0;
    float rpU = 1.0 / uP;
    float local = atan(rpU * sin(phi), rcU - rpU * cos(phi)) + image.r;
    // The table's angle is in a static observer's frame (radial lengths stretched by 1 / sqrt(1 - u0)); the camera's
    // directions are coordinate directions, like the rest of the lens.
    float psi = atan(sin(local), cos(local) * sqrt(1.0 - u0));
    seen = -cos(psi) * e1 + sin(psi) * e2;
    return 1;
}

int relativitySchwarzschildImage(vec3 p, out vec3 seen, out bool nearAxis) {
    return relativitySchwarzschildImageAt(p, RelativityLensData[0].xyz, seen, nearAxis);
}

// A lone spinning hole's image (primary or, with relativitySecond, secondary), approximated from the non-spinning one,
// with no solving: what's seen of a spinning hole is, to a good approximation, a non-spinning hole's view of the point
// with the hole moved sideways by -a (up x e) (e camera to hole, a = spin M: far out a Kerr lens is a Schwarzschild
// lens displaced so), turned round the hole by frame dragging: tau = -RELATIVITY_TWIST (a / M) cos i x^-(n + m u0)
// (i the spin axis's angle from the line of sight, x = tan alpha / tan alpha_c the image's distance from the hole in
// shadow radii, u0 = r_s / r_c; n, m RELATIVITY_TWIST_FALLOFF). Fitted against the exact closed-form images (KerrRay)
// over spins 0.3-0.9, view angles 15-90 degrees and camera distances 7.5-40 m: past a third of a shadow radius out the
// error is a few px (median 8 at 0.3-1 shadow radii out for a camera 7.5 r_s away, 2-3 farther out); in the thin band
// by the shadow's edge (light that has wound round) tens to hundreds, the photon ring's sliver. It follows the camera
// at once.
// The exact shadow's edge (HorizonMesh.edges) at game-space direction d from the camera, e the direction to the hole:
// its angle from e at d's azimuth round e (from up's projection, HorizonMesh.frame), interpolated; 0 if there's none.
float relativityShadowEdge(vec3 d, vec3 e) {
    vec3 up = abs(e.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 zero = normalize(up - dot(up, e) * e);
    vec3 quarter = cross(e, zero);
    float azimuth = atan(dot(d, quarter), dot(d, zero));
    if (azimuth < 0.0) azimuth += 6.28318531;
    float f = azimuth / 6.28318531 * 64.0;
    int i0 = int(floor(f));
    float t = f - floor(f);
    int i1 = i0 + 1;
    if (i0 > 63) i0 -= 64;
    if (i1 > 63) i1 -= 64;
    float a0 = RelativityLensData[12 + i0 / 4][i0 - 4 * (i0 / 4)];
    float a1 = RelativityLensData[12 + i1 / 4][i1 - 4 * (i1 / 4)];
    if (a0 <= 0.0 || a1 <= 0.0) return 0.0;
    return mix(a0, a1, t);
}
// How far out (in shadow radii past the approximation's own edge) what's drawn is pulled onto the exact edge: the pull
// falls as exp(-q / RELATIVITY_EDGE_REACH).
const float RELATIVITY_EDGE_REACH = 0.08;
const float RELATIVITY_TWIST = 0.386;
const vec2 RELATIVITY_TWIST_FALLOFF = vec2(2.73, -4.93);
const float RELATIVITY_SHIFT_GROWTH = 0.65;
bool relativityKerrApproximated() { return RelativityLensData[1].z > 0.5 && RelativityLensData[7].y > 2.5; }
int relativityKerrApprox(vec3 p, out vec3 seen, out bool nearAxis) {
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 eGame = normalize(RelativityLensData[6].xyz);
    float spin = RelativityLensData[6].w;
    float rs = RelativityLensData[0].w;
    vec3 shift = transpose(viewToGame) * (-spin * 0.5 * rs * cross(vec3(0.0, 1.0, 0.0), eGame));
    int image = relativitySchwarzschildImageAt(p, RelativityLensData[0].xyz + shift, seen, nearAxis);
    if (image != 1) return image;
    vec3 e = normalize(RelativityLensData[0].xyz);
    float u0 = min(rs / length(RelativityLensData[0].xyz), 0.999);
    float critical = asin(min(2.59807621 * u0 * sqrt(1.0 - u0), 1.0));
    float alphaC = atan(sin(critical), cos(critical) * sqrt(1.0 - u0));
    float alpha = acos(clamp(dot(seen, e), -1.0, 1.0));
    // Toward the ring the displacement grows (the spinning shadow sits about 2 a sin i over, twice the far-field lens's):
    // 1 + 0.65 (alpha_c / alpha)^n, n = 1.5 + 9 r_s / r_c: fitted to the best displacement per band against the exact
    // images over spins 0.3-0.9, camera distances 4-20 r_s and view angles 0.7-1.5 rad (0.65 the same for all of them;
    // the fall-off slower the farther the camera); the image once more with it.
    float falloff = 1.5 + 9.0 * rs / length(RelativityLensData[0].xyz);
    float grow = RELATIVITY_SHIFT_GROWTH * pow(alphaC / max(alpha, alphaC), falloff);
    if (grow > 0.01) {
        vec3 again;
        bool nearAgain;
        if (relativitySchwarzschildImageAt(p, RelativityLensData[0].xyz + (1.0 + grow) * shift, again, nearAgain) == 1) {
            seen = again;
            nearAxis = nearAgain;
            alpha = acos(clamp(dot(seen, e), -1.0, 1.0));
        }
    }
    float x = max(tan(min(alpha, 1.5)) / tan(alphaC), 1.15);
    float tau = -RELATIVITY_TWIST * spin * (-eGame.y) * pow(x, -(RELATIVITY_TWIST_FALLOFF.x + RELATIVITY_TWIST_FALLOFF.y * u0));
    // (Rodrigues: seen turned by tau round e.)
    seen = normalize(seen * cos(tau) + cross(e, seen) * sin(tau) + e * dot(e, seen) * (1.0 - cos(tau)));
    // By the edge: the spinning shadow is flattened on its approaching side, the non-spinning one moved and turned is a
    // circle. What's drawn there is pulled radially (round the hole) so the approximation's own edge falls on the exact
    // one (HorizonMesh's, the shadow drawn), fading out away from it.
    // (Only near the hole's direction, where the pull is anything at all: the tangent plane it works on can't hold
    // directions a right angle or more away, and rebuilt from it they'd flip forward.)
    float edge = relativityShadowEdge(viewToGame * seen, eGame);
    if (edge > 0.0 && edge < 1.5 && dot(seen, e) > 0.2 && alpha < 3.0 * edge) {
        // The approximation's edge along this azimuth: the circle of the shadow's radius round its lens's centre at the
        // edge (the hole moved by (1 + RELATIVITY_SHIFT_GROWTH) shift, turned by the twist there), on the tangent plane.
        float tauEdge = -RELATIVITY_TWIST * spin * (-eGame.y) * pow(1.15, -(RELATIVITY_TWIST_FALLOFF.x + RELATIVITY_TWIST_FALLOFF.y * u0));
        vec3 lens = normalize(RelativityLensData[0].xyz + (1.0 + RELATIVITY_SHIFT_GROWTH) * shift);
        lens = normalize(lens * cos(tauEdge) + cross(e, lens) * sin(tauEdge) + e * dot(e, lens) * (1.0 - cos(tauEdge)));
        vec3 c = lens / dot(lens, e) - e;
        vec3 radial = seen - e * dot(seen, e);
        float rl = length(radial);
        if (rl > 1e-6) {
            radial /= rl;
            float uc = dot(radial, c);
            float r = tan(alphaC);
            float own = uc + sqrt(max(uc * uc - dot(c, c) + r * r, 0.0));
            float here = rl / max(dot(seen, e), 1e-3);
            float q = here / own - 1.0;
            float pull = exp(-max(q, 0.0) / RELATIVITY_EDGE_REACH);
            float moved = max(here + (tan(edge) - own) * pull, 0.0);
            seen = normalize(e + moved * radial);
        }
    }
    return 1;
}

// ---- Closed-form Kerr light paths (physics-core KerrRay; Gralla & Lupsasca 2020), in the hole's Kerr-Schild Cartesian
// chart with the spin along +z ("physics" axes: game (x, y, z) is physics (y, z, x)); lengths in blocks. ----

// Carlson's symmetric elliptic integrals (duplication; single precision: stop at 1e-4 relative, then the series).
float relativityRF(float x, float y, float z) {
    for (int n = 0; n < 12 + relativityRuntimeZero(); n += 1) {
        float sx = sqrt(x);
        float sy = sqrt(y);
        float sz = sqrt(z);
        float lam = sx * (sy + sz) + sy * sz;
        x = 0.25 * (x + lam);
        y = 0.25 * (y + lam);
        z = 0.25 * (z + lam);
        float mu = (x + y + z) / 3.0;
        if (max(abs(x - mu), max(abs(y - mu), abs(z - mu))) < 1e-4 * mu) break;
    }
    float mu = (x + y + z) / 3.0;
    float dx = (mu - x) / mu;
    float dy = (mu - y) / mu;
    float dz = (mu - z) / mu;
    float e2 = dx * dy - dz * dz;
    float e3 = dx * dy * dz;
    return (1.0 + (e2 / 24.0 - 0.1 - 3.0 * e3 / 44.0) * e2 + e3 / 14.0) / sqrt(mu);
}

float relativityRC(float x, float y) {
    float w = 1.0;
    if (y < 0.0) {
        w = sqrt(x / (x - y));
        x = x - y;
        y = -y;
    }
    if (y <= 0.0) return 1e30;
    for (int n = 0; n < 12 + relativityRuntimeZero(); n += 1) {
        float lam = 2.0 * sqrt(x) * sqrt(y) + y;
        x = 0.25 * (x + lam);
        y = 0.25 * (y + lam);
        float mu = (x + 2.0 * y) / 3.0;
        if (abs((y - mu) / mu) < 1e-4) break;
    }
    float mu = (x + 2.0 * y) / 3.0;
    float s = (y - mu) / mu;
    return w * (1.0 + s * s * (0.3 + s * (1.0 / 7.0 + s * (0.375 + s * 9.0 / 22.0)))) / sqrt(mu);
}

float relativityRJ(float x, float y, float z, float p) {
    float sum = 0.0;
    float fac = 1.0;
    for (int n = 0; n < 12 + relativityRuntimeZero(); n += 1) {
        float sx = sqrt(x);
        float sy = sqrt(y);
        float sz = sqrt(z);
        float lam = sx * (sy + sz) + sy * sz;
        float alpha = p * (sx + sy + sz) + sx * sy * sz;
        float beta = p * (p + lam) * (p + lam);
        sum += fac * relativityRC(alpha * alpha, beta);
        fac *= 0.25;
        x = 0.25 * (x + lam);
        y = 0.25 * (y + lam);
        z = 0.25 * (z + lam);
        p = 0.25 * (p + lam);
        float mu = (x + y + z + 2.0 * p) / 5.0;
        if (max(max(abs(x - mu), abs(y - mu)), max(abs(z - mu), abs(p - mu))) < 1e-4 * mu) break;
    }
    float mu = (x + y + z + 2.0 * p) / 5.0;
    float dx = (mu - x) / mu;
    float dy = (mu - y) / mu;
    float dz = (mu - z) / mu;
    float dp = (mu - p) / mu;
    float ea = dx * (dy + dz) + dy * dz;
    float eb = dx * dy * dz;
    float ec = dp * dp;
    float e2 = ea - 3.0 * ec;
    float e3 = eb + 2.0 * dp * (ea - ec);
    float e4 = (2.0 * eb + dp * (ea - 3.0 * ec)) * dp;
    float e5 = eb * dp * dp;
    return 3.0 * sum + fac * (1.0 - 3.0 * e2 / 14.0 + e3 / 6.0 + 9.0 * e2 * e2 / 88.0 - 3.0 * e4 / 22.0 - 9.0 * e2 * e3 / 52.0 + 3.0 * e5 / 26.0) / (mu * sqrt(mu));
}

// F(phi | m) and Pi(n; phi | m) for any real phi (periodic extension), m < 1, n < 1.
float relativityEllipticF(float phi, float m) {
    float j = floor(phi / 3.14159265 + 0.5);
    float rest = phi - j * 3.14159265;
    float s = sin(rest);
    float c = cos(rest);
    float part = s * relativityRF(c * c, 1.0 - m * s * s, 1.0);
    if (j != 0.0) part += 2.0 * j * relativityRF(0.0, 1.0 - m, 1.0);
    return part;
}

float relativityEllipticPi(float n, float phi, float m) {
    float j = floor(phi / 3.14159265 + 0.5);
    float rest = phi - j * 3.14159265;
    float s = sin(rest);
    float c = cos(rest);
    float part = s * relativityRF(c * c, 1.0 - m * s * s, 1.0) + n / 3.0 * s * s * s * relativityRJ(c * c, 1.0 - m * s * s, 1.0, 1.0 - n * s * s);
    if (j != 0.0) part += 2.0 * j * (relativityRF(0.0, 1.0 - m, 1.0) + n / 3.0 * relativityRJ(0.0, 1.0 - m, 1.0, 1.0 - n));
    return part;
}

// The Jacobi amplitude am(x | m), 0 <= m < 1, by the AGM (descending Landen), x first reduced by whole periods.
float relativityAmplitudeAgm(float x, float m) {
    float quarter = relativityRF(0.0, 1.0 - m, 1.0);
    float j = floor(x / (2.0 * quarter) + 0.5);
    x -= 2.0 * quarter * j;
    float aa[10];
    float cc[10];
    aa[0] = 1.0;
    cc[0] = sqrt(m);
    float b = sqrt(1.0 - m);
    int n = 0;
    for (int i = 0; i < 9 + relativityRuntimeZero(); i += 1) {
        if (abs(cc[n]) < 1e-7) break;
        aa[n + 1] = 0.5 * (aa[n] + b);
        cc[n + 1] = 0.5 * (aa[n] - b);
        b = sqrt(aa[n] * b);
        n += 1;
    }
    float phi = exp2(float(n)) * aa[n] * x;
    for (int i = n; i >= 1; i -= 1) phi = 0.5 * (phi + asin(clamp(cc[i] / aa[i] * sin(phi), -1.0, 1.0)));
    return phi + j * 3.14159265;
}

// am(x | m) for any m < 1 (a negative parameter through the imaginary-modulus transformation).
float relativityAmplitude(float x, float m) {
    if (m >= 0.0) return relativityAmplitudeAgm(x, m);
    float k = 1.0 - m;
    float mu = -m / k;
    float phi = relativityAmplitudeAgm(x * sqrt(k), mu);
    float sp = sin(phi);
    float dn = sqrt(1.0 - mu * sp * sp);
    float sn = sp / (dn * sqrt(k));
    float cn = cos(phi) / dn;
    return atan(sn, cn) + 6.28318530718 * floor((phi + 3.14159265) / 6.28318530718);
}

// A ray seen from the camera at cam (physics axes, from the hole) along direction d: its constants and starting state,
// packed: ray0 (lambda, eta, r0, theta0), ray1 (phiKs0, radial sign, polar sign, turning point r4 or -1), roots (r1..r4).
// False when it can't be followed in closed form here (no turning point outside the horizon, or vortical: eta <= 0).
bool relativityKerrRay(vec3 cam, vec3 d, float m, float a, out vec4 ray0, out vec4 ray1, out vec4 roots) {
    ray0 = vec4(0.0);
    ray1 = vec4(0.0);
    roots = vec4(0.0);
    float w0 = dot(cam, cam) - a * a;
    float r0 = sqrt(0.5 * w0 + sqrt(0.25 * w0 * w0 + a * a * cam.z * cam.z));
    float cth = clamp(cam.z / r0, -1.0, 1.0);
    float sth = sqrt(max(1.0 - cth * cth, 1e-12));
    // The arriving photon (spatial direction -d): its momentum covector p = g u.
    vec3 dd = -d;
    float r2 = r0 * r0;
    float h = 2.0 * m * r0 * r2 / (r2 * r2 + a * a * cam.z * cam.z);
    vec4 l = vec4(1.0, (r0 * cam.x + a * cam.y) / (r2 + a * a), (r0 * cam.y - a * cam.x) / (r2 + a * a), cam.z / r0);
    float ld = dot(l.yzw, dd);
    // g_00 = -1 + h, g_0i = h l_i, g_ij = delta + h l_i l_j.
    float qa = dot(dd, dd) + h * ld * ld;
    float qb = h * ld;
    float qc = -1.0 + h;
    float k = (-qb + sqrt(max(qb * qb - qa * qc, 0.0))) / qa;
    vec4 u = vec4(1.0, k * dd);
    // p_mu = g_mu nu u^nu = eta_mu nu u^nu + h l_mu (l_nu u^nu), l_0 = 1.
    float lnu = u.x + dot(l.yzw, u.yzw);
    vec4 p = vec4(-u.x, u.yzw) + h * l * lnu;
    float e = -p.x;
    float lz = cam.x * p.z - cam.y * p.y;
    float pTheta = (cth / sth) * (cam.x * p.y + cam.y * p.z) - r0 * sth * p.w;
    float q = pTheta * pTheta + cth * cth * (lz * lz / (sth * sth) - a * a * e * e);
    float lambda = lz / e;
    float eta = q / (e * e);
    // Along +d: which way r and theta go.
    vec3 xs = cam + 1e-3 * r0 * d;
    float ws = dot(xs, xs) - a * a;
    float rs = sqrt(0.5 * ws + sqrt(0.25 * ws * ws + a * a * xs.z * xs.z));
    float radialSign = rs >= r0 ? 1.0 : -1.0;
    float polarSign = acos(clamp(xs.z / rs, -1.0, 1.0)) >= acos(cth) ? 1.0 : -1.0;
    // R(r) = r^4 + A r^2 + B r + C: its roots (Gralla & Lupsasca eqs. 95-96).
    float aa = a * a - eta - lambda * lambda;
    float bb = 2.0 * m * (eta + (lambda - a) * (lambda - a));
    float cc = -a * a * eta;
    float pp = -aa * aa / 12.0 - cc;
    float qq = -aa / 3.0 * ((aa / 6.0) * (aa / 6.0) - cc) - bb * bb / 8.0;
    float disc = (pp / 3.0) * (pp / 3.0) * (pp / 3.0) + (qq / 2.0) * (qq / 2.0);
    float sum;
    if (disc >= 0.0) {
        float sd = sqrt(disc);
        float c1 = -qq / 2.0 + sd;
        float c2 = -qq / 2.0 - sd;
        sum = sign(c1) * pow(abs(c1), 1.0 / 3.0) + sign(c2) * pow(abs(c2), 1.0 / 3.0);
    } else {
        float rho = sqrt(-(pp / 3.0) * (pp / 3.0) * (pp / 3.0));
        float angle = acos(clamp(-qq / 2.0 / rho, -1.0, 1.0));
        sum = 2.0 * pow(rho, 1.0 / 3.0) * cos(angle / 3.0);
    }
    float z = sqrt(max((sum - aa / 3.0) / 2.0, 1e-12));
    float s1 = -aa / 2.0 - z * z + bb / (4.0 * z);
    float s2 = -aa / 2.0 - z * z - bb / (4.0 * z);
    roots = vec4(-z - sqrt(max(s1, 0.0)), -z + sqrt(max(s1, 0.0)), z - sqrt(max(s2, 0.0)), z + sqrt(max(s2, 0.0)));
    float rPlus = m + sqrt(max(m * m - a * a, 0.0));
    float turning = (s2 >= 0.0 && roots.w > rPlus && radialSign < 0.0) ? roots.w : -1.0;
    ray0 = vec4(lambda, eta, r0, acos(cth));
    ray1 = vec4(atan(cam.y, cam.x) - atan(a, r0), radialSign, polarSign, turning);
    return turning > 0.0 && eta > 0.0;
}

// Where that ray is (physics axes, from the hole) when it reaches radius r: on the way in (outbound false) or, past its
// turning point, on the way out. False if it doesn't. (Light that passes the hole: Mino time and phi's radial part from
// the turning point in closed form; the polar motion exactly.)
bool relativityKerrRayAt(vec4 ray0, vec4 ray1, vec4 roots, float m, float a, float r, bool outbound, out vec3 at) {
    at = vec3(0.0);
    float lambda = ray0.x;
    float eta = ray0.y;
    float r0 = ray0.z;
    float turning = ray1.w;
    if (turning <= 0.0 || r < turning) return false;
    if (!outbound && r > r0) return false;
    float r1 = roots.x;
    float r3 = roots.z;
    float r4 = roots.w;
    float r31 = r3 - r1;
    float r41 = r4 - r1;
    float r32 = r3 - roots.y;
    float r42 = r4 - roots.y;
    float r43 = r4 - r3;
    float k = r32 * r41 / (r31 * r42);
    float scale = 2.0 / sqrt(r31 * r42);
    float rPlus = m + sqrt(max(m * m - a * a, 0.0));
    float rMinus = m - sqrt(max(m * m - a * a, 0.0));
    float aPlus = (2.0 * m * rPlus - a * lambda) / max(rPlus - rMinus, 1e-6);
    float aMinus = -(2.0 * m * rMinus - a * lambda) / max(rPlus - rMinus, 1e-6);
    // From the turning point out to the camera's radius and to r: Mino time and phi's radial part. (Loops over the radii
    // and the horizons, each integral called from one place: the drivers inline every call.)
    vec2 legs[2];
    float radii[2] = float[](r0, r);
    float horizons[2] = float[](rPlus, rMinus);
    float weights[2] = float[](aPlus, aMinus);
    for (int i = 0; i < 2 + relativityRuntimeZero(); i += 1) {
        float rr = radii[i];
        float amplitude = asin(min(sqrt(max((rr - r4) * r31 / ((rr - r3) * r41), 0.0)), 1.0));
        float f = relativityEllipticF(amplitude, k);
        float phiPart = 0.0;
        if (rPlus - rMinus > 1e-6) {
            for (int j = 0; j < 2 + relativityRuntimeZero(); j += 1) {
                float rh = horizons[j];
                float n = r41 * (r3 - rh) / (r31 * (r4 - rh));
                phiPart += weights[j] * (f / (r3 - rh) - r43 / ((r4 - rh) * (r3 - rh)) * relativityEllipticPi(n, amplitude, k));
            }
            phiPart *= a * scale;
        }
        legs[i] = vec2(scale * f, phiPart);
    }
    vec2 total = outbound ? legs[0] + legs[1] : legs[0] - legs[1];
    float tau = total.x;
    // The polar motion: cos theta = u+ sn(X | u+^2 / u-^2), X = X0 - (theta's direction) rate tau.
    float a2 = max(a * a, 1e-8);
    float dth = 0.5 * (1.0 - (eta + lambda * lambda) / a2);
    float root = sqrt(dth * dth + eta / a2);
    float uPlus2 = min(dth + root, 1.0 - 1e-6);
    float uMinus2 = dth - root;
    float uPlus = sqrt(max(uPlus2, 0.0));
    float mm = uPlus2 / uMinus2;
    float rate = sqrt(a2 * -uMinus2);
    float x0 = relativityEllipticF(asin(clamp(cos(ray0.w) / uPlus, -1.0, 1.0)), mm);
    float x = x0 - ray1.z * rate * tau;
    // (At the camera and at r: one loop, each call in one place.)
    float ends[2] = float[](x0, x);
    float amplitudes[2];
    float pis[2];
    for (int i = 0; i < 2 + relativityRuntimeZero(); i += 1) {
        amplitudes[i] = relativityAmplitude(ends[i], mm);
        pis[i] = relativityEllipticPi(uPlus2, amplitudes[i], mm);
    }
    float u = uPlus * sin(amplitudes[1]);
    float polar = (pis[0] - pis[1]) / (ray1.z * rate);
    // Back along the path Mino time runs backwards: phi changes by minus the integrals.
    float phiBl = -(total.y + lambda * polar);
    float kPhi = 0.0;
    if (rPlus - rMinus > 1e-6) kPhi = a / (rPlus - rMinus) * (log(abs((r - rPlus) / (r - rMinus))) - log(abs((r0 - rPlus) / (r0 - rMinus))));
    float phiKs = ray1.x + phiBl + kPhi;
    float theta = acos(clamp(u, -1.0, 1.0));
    float st = sin(theta);
    at = vec3(st * (r * cos(phiKs) - a * sin(phiKs)), st * (r * sin(phiKs) + a * cos(phiKs)), r * cos(theta));
    return true;
}

// Where the ray along d lands on its leg at radius (the miss from target); false if it doesn't reach it there, miss.x
// then -1 if its light falls in (or can't be followed), else its turning point (it turns back short of radius).
bool relativityKerrLanding(vec3 cam, vec3 d, float m, float a, float radius, bool outbound, vec3 target, out vec3 miss) {
    vec4 ray0;
    vec4 ray1;
    vec4 roots;
    vec3 hit;
    miss = vec3(-1.0);
    if (!relativityKerrRay(cam, d, m, a, ray0, ray1, roots)) return false;
    miss = vec3(ray1.w);
    if (!relativityKerrRayAt(ray0, ray1, roots, m, a, radius, outbound, hit)) return false;
    miss = hit - target;
    return true;
}

// The exact image (unit view direction) of view-space point p round the lone spinning hole, by Gauss-Newton on the
// closed-form path from start (the approximation's image, view space), on the leg the start lands nearer on, then the
// other: true if it lands within a hundredth of a block (or a thousandth of its radius), seen then the image; false
// leaves seen as start. One loop with a single call to the closed form (relativityKerrLanding), each pass evaluating
// one direction as the state says: the drivers inline every call, and the closed form at each step of a solve written
// out took them far too long to compile.
// Closed-form evaluations a vertex may spend (most converge in about a dozen; the rest keep the approximation).
const int RELATIVITY_EXACT_PASSES = 24;

// Debug (GameTests): why the last relativityKerrExactImage gave up: 1 neither leg reached from the start, 10 + state
// where both legs ran out (the state a leg was last in), 3 out of passes.
float relativityKerrExactWhy = 0.0;
bool relativityKerrExactImage(vec3 p, vec3 start, out vec3 seen) {
    relativityKerrExactWhy = 0.0;
    seen = start;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    float m = 0.5 * RelativityLensData[0].w;
    float a = RelativityLensData[6].w * m;
    vec3 camGame = -RelativityLensData[6].xyz;
    vec3 cam = vec3(camGame.z, camGame.x, camGame.y);
    vec3 pg = viewToGame * p - RelativityLensData[6].xyz;
    vec3 target = vec3(pg.z, pg.x, pg.y);
    float wt = dot(target, target) - a * a;
    float radius = sqrt(0.5 * wt + sqrt(0.25 * wt * wt + a * a * target.z * target.z));
    vec3 sg = viewToGame * start;
    vec3 dir0 = normalize(vec3(sg.z, sg.x, sg.y));
    float tolerance = max(0.01, 1e-3 * radius);
    float eps = 2e-4;
    // States: 0, 1 the start on each leg (choosing the first); 2 the current direction; 3, 4 the probes along e1, e2
    // (5, 6 the same on the other side when one falls in); 7 a trial step (halved while it doesn't help).
    int state = 0;
    bool outbound = false;
    bool first = false;
    int legsTried = 0;
    float missIn = 1e9;
    vec3 dir = dir0;
    vec3 residual = vec3(1e9);
    vec3 e1 = vec3(0.0);
    vec3 e2 = vec3(0.0);
    vec3 ja = vec3(0.0);
    vec3 jb = vec3(0.0);
    vec2 step = vec2(0.0);
    int halvings = 0;
    // Levenberg-Marquardt: the normal equations kept, damped further each time a step doesn't help.
    vec3 normal = vec3(0.0);
    vec2 gradient = vec2(0.0);
    float damping = 1e-3;
    int iterations = 0;
    // The start nudged (its angle from the hole) while neither leg reaches: out if its light falls in, in if it turns
    // back short of the point.
    vec3 toHole = normalize(-cam);
    int nudges = 0;
    float fellOrTurned = 0.0;
    for (int pass = 0; pass < int(RelativityLensData[7].w) + relativityRuntimeZero(); pass += 1) {
        // The direction and leg this pass evaluates.
        vec3 d = dir;
        bool leg = outbound;
        if (state == 0) { d = dir0; leg = false; }
        else if (state == 1) { d = dir0; leg = true; }
        else if (state == 3) d = normalize(dir + eps * e1);
        else if (state == 4) d = normalize(dir + eps * e2);
        else if (state == 5) d = normalize(dir - eps * e1);
        else if (state == 6) d = normalize(dir - eps * e2);
        else if (state == 7) d = normalize(dir + step.x * e1 + step.y * e2);
        vec3 miss;
        bool ok = relativityKerrLanding(cam, d, m, a, radius, leg, target, miss);
        if (state == 0) {
            missIn = ok ? length(miss) : 1e9;
            fellOrTurned = ok ? 0.0 : miss.x;
            state = 1;
        } else if (state == 1) {
            float missOut = ok ? length(miss) : 1e9;
            if (missIn >= 1e8 && missOut >= 1e8) {
                if (nudges >= 12) { relativityKerrExactWhy = 1.0; return false; }
                nudges += 1;
                // Out (falls in) or in (turns back early), keeping its side of the hole.
                float alpha = acos(clamp(dot(dir0, toHole), -1.0, 1.0));
                vec3 side = normalize(dir0 - dot(dir0, toHole) * toHole);
                alpha *= fellOrTurned < 0.0 ? 1.03 : 0.97;
                dir0 = normalize(cos(alpha) * toHole + sin(alpha) * side);
                state = 0;
                continue;
            }
            first = missOut < missIn;
            outbound = first;
            legsTried = 1;
            dir = dir0;
            state = 2;
        } else {
            bool restart = false;
            if (state == 2) {
                if (!ok) restart = true;
                else {
                    residual = miss;
                    if (length(residual) < tolerance) {
                        seen = normalize(transpose(viewToGame) * vec3(dir.y, dir.z, dir.x));
                        return true;
                    }
                    vec3 helper = abs(dir.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
                    e1 = normalize(cross(dir, helper));
                    e2 = cross(dir, e1);
                    state = 3;
                }
            } else if (state == 3 || state == 5) {
                if (ok) { ja = state == 3 ? (miss - residual) / eps : (residual - miss) / eps; state = 4; }
                else if (state == 3) state = 5;
                else restart = true;
            } else if (state == 4 || state == 6) {
                if (ok) {
                    jb = state == 4 ? (miss - residual) / eps : (residual - miss) / eps;
                    normal = vec3(dot(ja, ja), dot(ja, jb), dot(jb, jb));
                    gradient = vec2(dot(ja, residual), dot(jb, residual));
                    float aa = normal.x * (1.0 + damping);
                    float bb = normal.z * (1.0 + damping);
                    float det = aa * bb - normal.y * normal.y;
                    if (abs(det) < 1e-30) restart = true;
                    else {
                        step = vec2(-(bb * gradient.x - normal.y * gradient.y), -(aa * gradient.y - normal.y * gradient.x)) / det;
                        halvings = 0;
                        state = 7;
                    }
                } else if (state == 4) state = 6;
                else restart = true;
            } else if (state == 7) {
                if (ok && length(miss) < length(residual)) {
                    dir = d;
                    residual = miss;
                    iterations += 1;
                    damping = max(damping * 0.25, 1e-6);
                    if (length(residual) < tolerance) {
                        seen = normalize(transpose(viewToGame) * vec3(dir.y, dir.z, dir.x));
                        return true;
                    }
                    if (iterations >= 12) restart = true;
                    else {
                        vec3 helper = abs(dir.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
                        e1 = normalize(cross(dir, helper));
                        e2 = cross(dir, e1);
                        state = 3;
                    }
                } else {
                    // Damped more (toward steepest descent, shorter), from the same normal equations.
                    halvings += 1;
                    damping *= 8.0;
                    float aa = normal.x * (1.0 + damping);
                    float bb = normal.z * (1.0 + damping);
                    float det = aa * bb - normal.y * normal.y;
                    step = vec2(-(bb * gradient.x - normal.y * gradient.y), -(aa * gradient.y - normal.y * gradient.x)) / det;
                    if (halvings >= 8) restart = true;
                }
            }
            // A leg that went nowhere: the other leg from the start, once.
            if (restart) {
                relativityKerrExactWhy = relativityKerrExactWhy * 10.0 + 10.0 + float(state);
                if (legsTried >= 2) return false;
                legsTried = 2;
                outbound = !first;
                dir = dir0;
                iterations = 0;
                damping = 1e-3;
                state = 2;
            }
        }
    }
    relativityKerrExactWhy = 3.0;
    return false;
}

// The exact solve's correction to the approximation at the last vertex (exact - approximate image, view space; 0 where
// none), which the vertex shaders hand to the pixel check; and the pixel check's own: it adds the interpolated one.
vec3 relativityLastCorrection = vec3(0.0);
bool relativityPixelStage = false;
vec3 relativityPixelCorrection = vec3(0.0);

// How far out (in shadow radii) the exact solve refines the approximation.
const float RELATIVITY_EXACT_REACH = 1.6; // (past it the approximation errs by a few px at most)
bool relativityExactWanted(vec3 seen) {
    vec3 e = normalize(RelativityLensData[0].xyz);
    float rs = RelativityLensData[0].w;
    float u0 = min(rs / length(RelativityLensData[0].xyz), 0.999);
    float critical = asin(min(2.59807621 * u0 * sqrt(1.0 - u0), 1.0));
    float alphaC = atan(sin(critical), cos(critical) * sqrt(1.0 - u0));
    return acos(clamp(dot(seen, e), -1.0, 1.0)) < RelativityLensData[7].z * alphaC;
}

// The Kerr image of view-space point p from the image map (KerrImageMap): 1 with seen its view direction; -1 with
// none there (hidden: behind the hole, or at a fold, left to the post pass); 0 outside the map (the Schwarzschild
// image stands: far out, where spin hardly shows).
int relativityKerrMapImage(vec3 p, out vec3 seen) {
    seen = normalize(p);
    float rIn = RelativityLensData[11].z;
    float rOut = RelativityLensData[11].w;
    if (rOut <= rIn) return 0;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 game = viewToGame * p;
    vec3 d = game - RelativityLensData[6].xyz;
    float r = length(d);
    if (r < rIn) return -1;
    float s = log(r / rIn) / log(rOut / rIn);
    if (s > 1.0) return 0;
    vec3 size = RELATIVITY_MAP_SIZE;
    vec3 at = vec3(atan(d.z, d.x) / 6.28318530718 + 0.5, ((d.y / r * 0.5 + 0.5) * (size.y - 1.0) + 0.5) / size.y,
        (s * (size.z - 1.0) + 0.5) / size.z);
    vec4 m = relativitySecond() ? texture(RelativityKerrSecondMap, at) : texture(RelativityKerrMap, at);
    // (Every texel round p must have an image: averaging over those that do misplaces points by the shadow's edge,
    // where neighbouring texels sit either side of capture.)
    if (m.a < 0.999) return -1;
    seen = transpose(viewToGame) * normalize(normalize(game) + m.rgb * length(game)); // (stored per block of distance)
    return 1;
}

// The bending acceleration of light at x heading v (unit), round a hole at hole of radius rs: the photon orbit equation
// in Cartesian form (as lightBend in lens_scene).
vec3 relativityLightBend(vec3 x, vec3 v, vec3 hole, float rs) {
    vec3 d = x - hole;
    float r2 = dot(d, d);
    vec3 hv = cross(d, v);
    return -1.5 * rs * dot(hv, hv) * d / (r2 * r2 * sqrt(r2));
}

// Kerr light in Kerr-Schild coordinates on the hole's local axes (game z, x, y): kerr.glsl's kerrDerivative and
// kerrNullMomentum, under names of their own (some programs include both files).
vec3 relativityLocal(vec3 game) { return vec3(game.z, game.x, game.y); }
vec3 relativityGame(vec3 local) { return vec3(local.y, local.z, local.x); }

void relativityKerrStep(vec3 x, vec3 p, float pt, float m, float a, out vec3 dx, out vec3 dp) {
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
    // Followed back in time (from where the light is seen).
    dx = -(p - h * lp * l);
    dp = -(0.5 * lp * lp * gradH + h * lp * gradF);
}

void relativityKerrMomentum(vec3 x, vec3 d, float m, float a, out vec3 p, out float pt) {
    float a2 = a * a;
    float w = dot(x, x) - a2;
    float r2 = 0.5 * w + sqrt(0.25 * w * w + a2 * x.z * x.z);
    float r = sqrt(max(r2, 1e-12));
    float h0 = 2.0 * m * r * r2 / (r2 * r2 + a2 * x.z * x.z);
    float dd = r2 + a2;
    vec3 l0 = vec3((r * x.x + a * x.y) / dd, (r * x.y - a * x.x) / dd, x.z / r);
    float ld = dot(l0, d);
    float qa = 1.0 + h0 * ld * ld;
    float qb = 2.0 * h0 * ld;
    float qc = h0 - 1.0;
    float k = (-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa);
    float lu = 1.0 + k * ld;
    pt = -1.0 + h0 * lu;
    p = k * d + h0 * l0 * lu;
}

// Where the light seen along view-space direction e is after coordinate distance dist, round a spinning hole ([6]:
// game position relative to the camera, spin): four RK4 steps in Kerr-Schild coordinates. View space.
vec3 relativityKerrNearEnd(vec3 e, float dist) {
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    float m = 0.5 * RelativityLensData[0].w;
    float a = RelativityLensData[6].w * m;
    vec3 x = relativityLocal(-RelativityLensData[6].xyz); // the camera, from the hole
    vec3 start = x;
    vec3 p;
    float pt;
    relativityKerrMomentum(x, -relativityLocal(viewToGame * e), m, a, p, pt); // the photon arriving along e
    int steps = 4 + relativityRuntimeZero();
    for (int k = 0; k < steps; k += 1) {
        float h = 0.25 * dist;
        vec3 k1x;
        vec3 k1p;
        relativityKerrStep(x, p, pt, m, a, k1x, k1p);
        float dl = h / max(length(k1x), 1e-6);
        vec3 k2x;
        vec3 k2p;
        relativityKerrStep(x + 0.5 * dl * k1x, p + 0.5 * dl * k1p, pt, m, a, k2x, k2p);
        vec3 k3x;
        vec3 k3p;
        relativityKerrStep(x + 0.5 * dl * k2x, p + 0.5 * dl * k2p, pt, m, a, k3x, k3p);
        vec3 k4x;
        vec3 k4p;
        relativityKerrStep(x + dl * k3x, p + dl * k3p, pt, m, a, k4x, k4p);
        x += dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
        p += dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
    }
    return transpose(viewToGame) * relativityGame(x - start);
}

// Near the camera, the tables (centred on the hole) are coarse, so light is followed there directly: from the camera
// along e, four RK4 steps to p's distance, and e corrected by where that lands short of p; three rounds (a short path's
// landing point moves with e almost one for one). The view direction of view-space point p.
vec3 relativityNearImage(vec3 p, vec3 hole, float rs) {
    float d = length(p);
    vec3 e = p / d;
    float h = 0.25 * d;
    // A spinning hole: Kerr light (frame dragging shows even this close).
    bool kerr = RelativityLensData[1].z > 0.5 && RelativityLensData[7].y > 0.5 && abs(RelativityLensData[6].w) > 0.0;
    int rounds = 3 + relativityRuntimeZero();
    for (int round = 0; round < rounds; round += 1) {
        if (kerr) {
            e = normalize(e + (p - relativityKerrNearEnd(e, d)) / d);
            continue;
        }
        vec3 x = vec3(0.0);
        vec3 v = e;
        int steps = 4 + relativityRuntimeZero();
        for (int k = 0; k < steps; k += 1) {
            vec3 a1 = relativityLightBend(x, v, hole, rs);
            vec3 a2 = relativityLightBend(x + 0.5 * h * v, v + 0.5 * h * a1, hole, rs);
            vec3 a3 = relativityLightBend(x + 0.5 * h * v + 0.25 * h * h * a1, v + 0.5 * h * a2, hole, rs);
            vec3 a4 = relativityLightBend(x + h * v + 0.5 * h * h * a2, v + h * a3, hole, rs);
            x += h * v + h * h / 6.0 * (a1 + a2 + a3);
            v = normalize(v + h / 6.0 * (a1 + 2.0 * a2 + 2.0 * a3 + a4));
        }
        e = normalize(e + (p - x) / d);
    }
    return e;
}

// Points this close to the camera (a fraction of its distance from the hole) take the near image, blending into the
// tables' over the next stretch.
const float RELATIVITY_NEAR = 0.25;

// Round a spinning hole, points nearer than it whose straight line of sight passes it wider than this many r_s are
// followed directly too (relativityNearImage): their light is weakly bent, and the image map covers that stretch on
// the camera's side poorly (its shells meet the light edge on there).
const float RELATIVITY_WEAK_MISS = 3.0;

// How much of view-space point p's place the near march gives (relativityBend), 0 to 1: a lone spinning hole's points
// nearer than it whose light passes it wide. Ramped, not switched: past either edge the far image takes over, and the
// two differ by pixels round a spinning hole (the far one approximated), so a hard switch put a seam (and the pixel
// check, holes) along the ground where the camera's distance is the hole's.
float relativityWeakNearWeight(vec3 p) {
    if (RelativityLensData[1].z < 0.5 || RelativityLensData[7].y < 1.5) return 0.0; // (a lone hole: [7].y 2)
    vec3 hole = RelativityLensData[0].xyz;
    float rc = length(hole);
    float d = length(p);
    if (d >= rc) return 0.0;
    float t = clamp(dot(hole, p) / (d * d), 0.0, 1.0);
    float miss = length(hole - t * p) / (RELATIVITY_WEAK_MISS * RelativityLensData[0].w);
    return (1.0 - smoothstep(0.85 * rc, rc, d)) * smoothstep(1.0, 1.3, miss);
}
bool relativityWeakNear(vec3 p) { return relativityWeakNearWeight(p) > 0.0; }

// How many times faster than the point the image may move round it before the vertex is hidden (relativityFarImage).
const float RELATIVITY_STRETCH_CUT = 25.0;
const float RELATIVITY_NEAR_BLEND = 0.35;

// The table's image of view-space point p: 1 placed (seen its direction), 2 nothing to bend, -1 hidden (behind the hole,
// near its axis, or at a fold).
// probe: also hide p where its image moves far faster than it (the stretch cut, three more map lookups: per vertex; the
// pixel check, per pixel, leaves it out).
int relativityFarImage(vec3 p, out vec3 seen, bool probe) {
    seen = normalize(p);
    relativityLastCorrection = vec3(0.0);
    bool nearAxis = false;
    int image = 1;
    // The Schwarzschild image: alone for non-spinning holes; for anything else a hole's first guess and its axis cut.
    // Round another source (a mass, a bubble) the map alone places points; outside it they stand where they are.
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    if (relativityKerrApproximated()) {
        image = relativityKerrApprox(p, seen, nearAxis);
        // (Inside r_s, for the Schwarzschild image "nothing to bend": round a spinning hole such points are the horizon
        // sphere's, hidden.)
        if (image == 2 && length(p - RelativityLensData[0].xyz) <= RelativityLensData[0].w) return -1;
        if (nearAxis) return -1;
        // Within RELATIVITY_EXACT_REACH shadow radii of the hole the approximation errs by pixels to tens of them: the
        // exact image there, solved from it in closed form (relativityKerrExactImage; where that fails, the
        // approximation stands).
        // (The pixel check doesn't solve per pixel: it takes the approximation plus the correction its triangle's
        // vertices found, interpolated.)
        if (relativityPixelStage) seen = normalize(seen + relativityPixelCorrection);
        else if (image == 1 && RelativityLensData[7].y > 3.5 && !relativitySecond() && relativityExactWanted(seen)) {
            vec3 exact;
            if (relativityKerrExactImage(p, seen, exact)) {
                relativityLastCorrection = exact - seen;
                seen = exact;
            }
        }
        return image;
    }
    if (relativitySecond()) {
        // Round a spinning hole, from its secondary-image map (only drawn while the slots blend one: VertexLens).
        if (RelativityLensData[1].z > 0.5) {
            vec3 kerr;
            if (relativityKerrMapImage(p, kerr) != 1) return -1;
            seen = kerr;
            return 1;
        }
        image = relativitySchwarzschildImage(p, seen, nearAxis);
        if (image == 2 || nearAxis) return -1;
        return image;
    }
    if (holeCentre) {
        image = relativitySchwarzschildImage(p, seen, nearAxis);
        // (Inside r_s, for the Schwarzschild image "nothing to bend": round a spinning hole, whose horizon is smaller,
        // such points, the horizon sphere's (HorizonMesh), are the map's, and without an image there they're hidden.)
        bool insideRs = length(p - RelativityLensData[0].xyz) <= RelativityLensData[0].w;
        if (image == 2 && RelativityLensData[1].z > 0.5 && insideRs) image = -1;
        if (image == 2) return 2;
    }
    // Through every source: the image from the map.
    if (RelativityLensData[1].z > 0.5 && !nearAxis) {
        vec3 kerr;
        int found = relativityKerrMapImage(p, kerr);
        relativityLastFound = found;
        if (found == 1 && !probe) {
            seen = kerr;
            return 1;
        }
        if (found == 1) {
            // Where the image moves far more than the point does round p (a seam between texels on different branches
            // of the image, either side of the Einstein ring, or a stretch so steep straight edges can't follow it), a
            // triangle reaching p would be a slab across the sky: hidden, and the post pass draws what's there.
            float step = 0.01 * length(p);
            vec3 axes[3] = vec3[](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));
            for (int k = 0; k < 3; k += 1) {
                vec3 near;
                if (relativityKerrMapImage(p + step * axes[k], near) != 1) continue;
                // The image's turn against the point's own (its angle seen unlensed: about step / |p|, at most).
                if (acos(clamp(dot(near, kerr), -1.0, 1.0)) > RELATIVITY_STRETCH_CUT * 0.01) {
                    relativityLastFound = -1;
                    return -1;
                }
            }
            seen = kerr;
            return 1;
        }
        if (found < 0) image = -1;
        if (found == 0 && !holeCentre) return 2;
    }
    if (nearAxis) return -1;
    return image;
}

// Works in view space (camera at the origin): the projection alone recovers that from the clip position, whatever
// space the draw's own vertices and model-view matrix use (chunk-relative, world, posed).
// The bend itself, whether or not the level is being drawn with lensing on (the post pass uses it after).
vec4 relativityBend(vec4 clip, mat4 proj) {
    if (RelativityLensData[0].w <= 0.0) return clip;
    vec4 view = inverse(proj) * clip;
    vec3 p = view.xyz / view.w;
    if (RelativityLensData[1].y > 0.5) return proj * vec4(p, 1.0);
    float d = length(p);
    vec3 hole = RelativityLensData[0].xyz;
    float rc = length(hole);
    if (relativitySecond()) {
        // The secondary image at the length of the light's way round (at least the way via the hole), so the primary
        // image of whatever is in front along the same ray wins the depth test. Every point drawn has one (out to the
        // render distance: far ones are slivers by the photon ring, cheap to draw; measured ~2 ms for all of them).
        float rp = length(p - hole);
        vec3 seen;
        // (Round a spinning hole, only what lies beyond the hole: nearer things' secondary images are slivers by the
        // photon ring, under a pixel, and the per-frame map solves them poorly: a spray of shards round the shadow.)
        // (Not round an approximated one, lensed as a non-spinning one is: its secondary images are as good as those.)
        bool spinNear = RelativityLensData[1].z > 0.5 && RelativityLensData[7].y < 2.5 && d < rc;
        if (spinNear || relativityFarImage(p, seen, false) != 1) {
            vec4 hidden = proj * vec4(normalize(hole) * d, 1.0);
            hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
            return hidden;
        }
        return proj * vec4(seen * (rc + rp), 1.0);
    }
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    float nearness = holeCentre ? smoothstep(RELATIVITY_NEAR * rc, RELATIVITY_NEAR_BLEND * rc, d) : 1.0;
    nearness = min(nearness, 1.0 - relativityWeakNearWeight(p));
    vec3 seen;
    int image = 1;
    if (nearness > 0.0) {
        image = relativityFarImage(p, seen, true);
        if (image == 2 && nearness >= 1.0) return clip;
        if (image == 2) seen = normalize(p);
    }
    if (nearness < 1.0) {
        vec3 near = relativityNearImage(p, hole, RelativityLensData[0].w);
        // (The tables hiding a point this close is their coarseness, not a missing image.)
        seen = image < 0 || nearness <= 0.0 ? near : normalize(mix(near, seen, nearness));
        image = 1;
    }
    // No primary image, or near the axis: such a vertex gets a depth far past the far plane (toward the hole on
    // screen), so a triangle reaching it passes the far plane within a hair of its other vertices: clipped there, or
    // with depth clamping on, drawn at the far plane, which the post pass takes for sky and redraws.
    if (image < 0) {
        vec4 hidden = proj * vec4(normalize(hole) * d, 1.0);
        hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
        return hidden;
    }
    return proj * vec4(seen * d, 1.0);
}

// The lens for draws that may not use the level's projection (vanilla, Sodium, Flywheel: the hand, GUI items...).
vec4 relativityLens(vec4 clip, mat4 proj) {
    if (RelativityLensData[1].x < 0.5 || !relativityLevelProjection(proj)) return clip;
    return relativityBend(clip, proj);
}

// ---- The pixel check (world fragment shaders) ----
// A triangle is drawn with straight edges between its bent corners, but near the Einstein ring a block's image is a long
// arc: a triangle there is stretched across the sky though each corner is right. So each pixel checks itself: the point
// it shows (its corners' unbent positions, interpolated) must appear where it's drawn (the bent ones, interpolated), to
// within RELATIVITY_PIXEL_SLACK pixels; if not, or if that point has no image, it's discarded, and what's truly there
// shows through (the geometry behind, or the post pass). Close to the camera the corners are placed directly (the near
// march) and the check is skipped: too costly per pixel, and not needed there.
const float RELATIVITY_PIXEL_SLACK = 6.0;
const float RELATIVITY_POLAR_REACH = 0.75; // QuadSubdivision.POLAR_REACH
const float RELATIVITY_AXIS_SLACK = 40.0;

// Whether the lens bends draws made with projection proj (the level's, with lensing on): those carry the check.
bool relativityLensed(mat4 proj) {
    return RelativityLensData[0].w > 0.0 && RelativityLensData[1].x > 0.5 && RelativityLensData[1].y < 0.5 && relativityLevelProjection(proj);
}

// For the pixel showing view-space point unbent, drawn at view-space bent: whether it's in the wrong place.
bool relativityPixelWrong(vec3 unbent, vec3 bent) {
    if (mod(RelativityLensData[11].y, 2.0) > 0.5) return false;
    float d = length(unbent);
    vec3 hole = RelativityLensData[0].xyz;
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    // (Close to the camera the primary images are placed by the near march, not checked; the secondary ones always are:
    // the player's own body, round the camera, is all triangles across the ring.)
    if (holeCentre && d < RELATIVITY_NEAR_BLEND * length(hole) && !relativitySecond()) return false;
    if (!relativitySecond() && relativityWeakNear(unbent)) return false;
    // By the axis behind a non-spinning hole the image moves round the Einstein ring far faster than the point does,
    // and a pixel's own image is touchy: checked as closely as elsewhere it threw away a ring of what's there (the faces
    // there are split round the axis, QuadSubdivision.POLAR_REACH, so their edges follow the ring anyway). Checked
    // loosely, which still catches a face straddling the axis (with the camera moving, before the split catches up):
    // drawn straight across the ring, off by hundreds of pixels.
    bool schwarzschildLike = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 2.5;
    float slack = schwarzschildLike && relativityByAxis(unbent, RELATIVITY_POLAR_REACH) ? RELATIVITY_AXIS_SLACK : RELATIVITY_PIXEL_SLACK;
    vec3 seen;
    int image = relativityFarImage(unbent, seen, false);
    if (image == 2) return false;
    if (image < 0) return true;
    float angle = acos(clamp(dot(seen, normalize(bent)), -1.0, 1.0));
    return angle > slack * RelativityLensData[11].x;
}

// For shader packs' world programs (always the level's view; their projection may be jittered for TAA, which comes
// through the lens as a slightly moved point): bends with the level's projection from the block.
vec4 relativityLensLevel(vec4 clip) {
    if (RelativityLensData[1].x < 0.5) return clip;
    return relativityBend(clip, mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]));
}
