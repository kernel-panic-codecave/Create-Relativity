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
// [11] x: the angle one screen pixel covers (radians; the pixel check); y: 1 turns the pixel check off (debug); z, w: the image map's inner and
//      outer radius (blocks)
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[12];
};
uniform sampler3D RelativityImages; // SchwarzschildImages.forFiltering over (sqrt u0, sqrt uP, phi / pi): r the
                                    // launch-angle correction, g whether there's an image (interpolated)

uniform sampler2DArray RelativityKerrPaths; // KerrPaths' table (kerr_paths.fsh): per texel, a view ray's path past a
                                            // spinning hole, as seen from the hole
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
int relativityRuntimeZero() { return int(min(RelativityLensData[1].w, 0.0)); }

const float RELATIVITY_AXIS_CUT = 2.0;  // blocks: larger than a block face's diagonal
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
// centre in its ray's plane, the offset out of it, the distance from the centre (unpacked: kerr_paths.fsh).
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

// Texel t's path (kerr_paths.fsh) at position v (-14 .. 14 along its samples: coming in, the closest approach at 0,
// going out): the angle round the centre in its ray's plane, the offset out of it and the distance from the centre
// (Catmull-Rom). ok false where the texel has no usable path there.
vec3 relativityPathAt(ivec2 t, float v, out bool ok) {
    vec4 head = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0);
    int status = int(floor(head.y / 8192.0));
    ok = status == 1 || (status == 2 && v <= 0.0);
    float base = clamp(floor(v), -14.0, 13.0);
    float f = v - base;
    int j = int(base);
    vec3 p0 = relativityPathSample(t, max(j - 1, -14));
    vec3 p1 = relativityPathSample(t, j);
    vec3 p2 = relativityPathSample(t, j + 1);
    vec3 p3 = relativityPathSample(t, min(j + 2, 14));
    float f2 = f * f;
    float f3 = f2 * f;
    return 0.5 * (2.0 * p1 + (p2 - p0) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f3);
}

// How far texel t's path at v misses the point at direction n (from the hole, game axes) and radius rP: the angle round
// the hole in its ray's plane (n's taken on the turn nearest the path's), the offset out of the plane, the radius / rP.
vec3 relativityPathMiss(ivec2 t, vec3 n, float rP, float v, out bool ok) {
    vec3 path = relativityPathAt(t, v, ok);
    vec3 dir = relativityTableDir(vec2(t) + 0.5, RelativityLensData[7].zw, RelativityLensData[6].xyz, RelativityLensData[7].x);
    vec3 e1 = normalize(-RelativityLensData[6].xyz);
    vec3 normal = cross(e1, dir);
    if (dot(normal, normal) < 1e-12) {
        ok = false;
        return vec3(0.0);
    }
    normal = normalize(normal);
    vec3 side = cross(normal, e1);
    float angle = atan(dot(n, side), dot(n, e1));
    angle += 6.28318530718 * floor((path.x - angle) / 6.28318530718 + 0.5);
    return vec3(path.x - angle, path.y - dot(n, normal), (path.z - rP) / rP);
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
    // At the path's closest approach (v near 0) the radius barely moves with v, so it can't fix v: no measure (0).
    if (abs(jacobian[2].z) < 1e-4) return 0.0;
    // Column c, row r: d miss_r / d x_c. Eliminate x.z through the radius row.
    vec2 cx = jacobian[0].xy - jacobian[2].xy * (jacobian[0].z / jacobian[2].z);
    vec2 cy = jacobian[1].xy - jacobian[2].xy * (jacobian[1].z / jacobian[2].z);
    float lensed = abs(cx.x * cy.y - cx.y * cy.x) * rP * rP;
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
int relativitySchwarzschildImage(vec3 p, out vec3 seen, out bool nearAxis) {
    seen = normalize(p);
    nearAxis = false;
    vec3 h = RelativityLensData[0].xyz;
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
    vec2 image = texture(RelativityImages, (axes * (size - 1.0) + 0.5) / size).rg;
    nearAxis = cosPhi < 0.0 && rp * sideLength < RELATIVITY_AXIS_CUT;
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
    vec4 m = texture(RelativityKerrMap, at);
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

// How many times faster than the point the image may move round it before the vertex is hidden (relativityFarImage).
const float RELATIVITY_STRETCH_CUT = 25.0;
const float RELATIVITY_NEAR_BLEND = 0.35;

// The table's image of view-space point p: 1 placed (seen its direction), 2 nothing to bend, -1 hidden (behind the hole,
// near its axis, or at a fold).
// probe: also hide p where its image moves far faster than it (the stretch cut, three more map lookups: per vertex; the
// pixel check, per pixel, leaves it out).
int relativityFarImage(vec3 p, out vec3 seen, bool probe) {
    seen = normalize(p);
    bool nearAxis = false;
    int image = 1;
    // The Schwarzschild image: alone for non-spinning holes; for anything else a hole's first guess and its axis cut.
    // Round another source (a mass, a bubble) the map alone places points; outside it they stand where they are.
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    if (holeCentre) {
        image = relativitySchwarzschildImage(p, seen, nearAxis);
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
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    float nearness = holeCentre ? smoothstep(RELATIVITY_NEAR * rc, RELATIVITY_NEAR_BLEND * rc, d) : 1.0;
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
const float RELATIVITY_PIXEL_SLACK = 3.0;

// Whether the lens bends draws made with projection proj (the level's, with lensing on): those carry the check.
bool relativityLensed(mat4 proj) {
    return RelativityLensData[0].w > 0.0 && RelativityLensData[1].x > 0.5 && RelativityLensData[1].y < 0.5 && relativityLevelProjection(proj);
}

// For the pixel showing view-space point unbent, drawn at view-space bent: whether it's in the wrong place.
bool relativityPixelWrong(vec3 unbent, vec3 bent) {
    if (RelativityLensData[11].y > 0.5) return false;
    float d = length(unbent);
    vec3 hole = RelativityLensData[0].xyz;
    bool holeCentre = RelativityLensData[1].z < 0.5 || RelativityLensData[7].y > 0.5;
    if (holeCentre && d < RELATIVITY_NEAR_BLEND * length(hole)) return false;
    vec3 seen;
    int image = relativityFarImage(unbent, seen, false);
    if (image == 2) return false;
    if (image < 0) return true;
    float angle = acos(clamp(dot(seen, normalize(bent)), -1.0, 1.0));
    return angle > RELATIVITY_PIXEL_SLACK * RelativityLensData[11].x;
}

// For shader packs' world programs (always the level's view; their projection may be jittered for TAA, which comes
// through the lens as a slightly moved point): bends with the level's projection from the block.
vec4 relativityLensLevel(vec4 clip) {
    if (RelativityLensData[1].x < 0.5) return clip;
    return relativityBend(clip, mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]));
}
