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
// [7] the Kerr path table (KerrPaths): tan of its half extents in view x and y, its size in texels
// [8..10] view to game axes (a rotation), by column
// [11] x: Boyer-Lindquist r where the table's paths start coming in (the camera, or their sphere); y: where they end
//      going out (their sphere, past everything drawn)
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[12];
};
uniform sampler3D RelativityImages; // SchwarzschildImages.forFiltering over (sqrt u0, sqrt uP, phi / pi): r the
                                    // launch-angle correction, g whether there's an image (interpolated)

uniform sampler2DArray RelativityKerrPaths; // KerrPaths' table (kerr_paths.fsh): per texel, a view ray's path past a
                                            // spinning hole, as seen from the hole

const float RELATIVITY_AXIS_CUT = 2.0;  // blocks: larger than a block face's diagonal
const float RELATIVITY_BEYOND = 1e4;   // NDC depth of hidden vertices: far past the far plane

bool relativityLevelProjection(mat4 proj) {
    mat4 d = proj - mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]);
    float diff = abs(d[0][0]) + abs(d[1][1]) + abs(d[2][2]) + abs(d[3][2]) + abs(d[2][3]) + abs(d[2][0]) + abs(d[2][1]);
    return diff < 1e-4;
}

// ---- Spinning holes: the image found on the Kerr path table (kerr_paths.fsh) by Newton's method ----

vec2 relativityLastSolve = vec2(-1.0); // debug (KerrSolveCheck): the last found solve's start (0..2) and path position v
int relativityLastFound = 2; // debug (KerrSolveCheck): the last Kerr solve's outcome (2: none ran)

// Path sample j (-7 .. 7: coming in, the closest approach at 0, going out) of the table's texel t.
vec2 relativityPathSample(ivec2 t, int j) {
    if (j == 0) return texelFetch(RelativityKerrPaths, ivec3(t, 0), 0).zw;
    int f = j < 0 ? -j - 1 : j + 6;
    vec4 v = texelFetch(RelativityKerrPaths, ivec3(t, 1 + f / 2), 0);
    return (f - 2 * (f / 2)) == 0 ? v.xy : v.zw;
}

// Texel t's path (kerr_paths.fsh) at position v (-7 .. 7 along its samples: coming in, the closest approach at 0,
// going out): the angle round the hole in its ray's plane and the offset out of it (Catmull-Rom), and the
// Boyer-Lindquist radius there, r_min + (r_top - r_min) (|v| / 7)^4 (smooth through 0). ok false where the texel has
// no usable path there.
vec3 relativityPathAt(ivec2 t, float v, out bool ok) {
    vec4 head = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0);
    int status = int(head.y + 0.5);
    ok = status == 1 || (status == 2 && v <= 0.0);
    float base = clamp(floor(v), -7.0, 6.0);
    float f = v - base;
    int j = int(base);
    vec2 p0 = relativityPathSample(t, max(j - 1, -7));
    vec2 p1 = relativityPathSample(t, j);
    vec2 p2 = relativityPathSample(t, j + 1);
    vec2 p3 = relativityPathSample(t, min(j + 2, 7));
    float f2 = f * f;
    float f3 = f2 * f;
    vec2 at = 0.5 * (2.0 * p1 + (p2 - p0) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f3);
    float rTop = v < 0.0 ? RelativityLensData[11].x : RelativityLensData[11].y;
    float w = abs(v) / 7.0;
    return vec3(at, head.x + (rTop - head.x) * w * w * w * w);
}

// How far texel t's path at v misses the point at direction n (from the hole, game axes) and radius rP: the angle round
// the hole in its ray's plane (n's taken on the turn nearest the path's), the offset out of the plane, the radius / rP.
vec3 relativityPathMiss(ivec2 t, vec3 n, float rP, float v, out bool ok) {
    vec3 path = relativityPathAt(t, v, ok);
    vec2 tanXY = RelativityLensData[7].xy;
    vec2 size = RelativityLensData[7].zw;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 dir = normalize(viewToGame * vec3(((vec2(t) + 0.5) / size * 2.0 - 1.0) * tanXY, -1.0));
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

// The miss at x = (table position in texels, path position v), bilinear between texels.
vec3 relativityPathImage(vec3 x, vec3 n, float rP, out bool ok) {
    vec2 size = RelativityLensData[7].zw;
    vec2 pos = x.xy - 0.5;
    ivec2 b = ivec2(floor(pos));
    vec2 f = pos - vec2(b);
    ok = all(greaterThanEqual(b, ivec2(0))) && all(lessThan(b + 1, ivec2(size)));
    if (!ok) return vec3(0.0);
    bool o0;
    bool o1;
    bool o2;
    bool o3;
    vec3 a = relativityPathMiss(b, n, rP, x.z, o0);
    vec3 c = relativityPathMiss(b + ivec2(1, 0), n, rP, x.z, o1);
    vec3 d = relativityPathMiss(b + ivec2(0, 1), n, rP, x.z, o2);
    vec3 e = relativityPathMiss(b + ivec2(1, 1), n, rP, x.z, o3);
    ok = o0 && o1 && o2 && o3;
    return mix(mix(a, c, f.x), mix(d, e, f.x), f.y);
}

// Newton's method (backtracking) on x = (table position, path position) for the path through the point at direction n
// and radius rP. True if it converged; x then holds the image's table position.
bool relativityKerrSolve(inout vec3 x, vec3 n, float rP) {
    vec3 steps = vec3(0.5, 0.5, 0.02);
    for (int i = 0; i < 10; i += 1) {
        bool ok;
        vec3 miss = relativityPathImage(x, n, rP, ok);
        if (!ok) return false;
        float current = dot(miss, miss);
        if (current < 1e-8) return true;
        mat3 jacobian;
        for (int c = 0; c < 3; c += 1) {
            vec3 shifted = x;
            shifted[c] += steps[c];
            bool okShift;
            jacobian[c] = (relativityPathImage(shifted, n, rP, okShift) - miss) / steps[c];
            if (!okShift) return false;
        }
        if (abs(determinant(jacobian)) < 1e-18) return false;
        vec3 step = inverse(jacobian) * miss;
        float length2 = dot(step.xy, step.xy);
        if (length2 > 64.0) step.xy *= 8.0 / sqrt(length2); // (near the ring, images far from the guess stay unfound: hidden)
        step.z = clamp(step.z, -2.0, 2.0);
        // Halve a step that makes the miss worse.
        float scale = 1.0;
        vec3 next = x;
        for (int k = 0; k < 5; k += 1) {
            next = vec3(x.xy - step.xy * scale, clamp(x.z - step.z * scale, -7.0, 7.0));
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
float relativityKerrMagnification(vec3 x, vec3 n, float rP, float d) {
    bool ok;
    vec3 miss = relativityPathImage(x, n, rP, ok);
    vec3 steps = vec3(0.5, 0.5, 0.02);
    mat3 jacobian;
    for (int c = 0; c < 3; c += 1) {
        vec3 shifted = x;
        shifted[c] += steps[c];
        bool okShift;
        jacobian[c] = (relativityPathImage(shifted, n, rP, okShift) - miss) / steps[c];
        if (!okShift) return 0.0;
    }
    // At the path's closest approach (v near 0) the radius barely moves with v, so it can't fix v: no measure (0).
    if (abs(jacobian[2].z) < 1e-4) return 0.0;
    // Column c, row r: d miss_r / d x_c. Eliminate x.z through the radius row.
    vec2 cx = jacobian[0].xy - jacobian[2].xy * (jacobian[0].z / jacobian[2].z);
    vec2 cy = jacobian[1].xy - jacobian[2].xy * (jacobian[1].z / jacobian[2].z);
    float lensed = abs(cx.x * cy.y - cx.y * cy.x) * rP * rP;
    float texel = 2.0 * RelativityLensData[7].x / RelativityLensData[7].z * d;
    return texel * texel / max(lensed, 1e-30);
}

// Where along texel t's path radius rP would be, coming in (v < 0) or going out: a starting point for the solve.
float relativityPathPosition(ivec2 t, float rP, bool outbound) {
    float rMin = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0).x;
    float rTop = outbound ? RelativityLensData[11].y : RelativityLensData[11].x;
    float u = 7.0 * sqrt(sqrt(clamp((rP - rMin) / max(rTop - rMin, 1e-6), 0.0, 1.0)));
    return outbound ? u : -u;
}

// The view direction of p's image under a spinning hole ([6]), by Newton's method on the table and along its paths,
// started at the view direction guess (the Schwarzschild image): 1 found; 0 not found but the guess is near the
// table's edge (off screen: the caller's Schwarzschild image stands, so a triangle reaching on screen keeps its
// corner); -1 not found (near the line behind the hole, where images crowd the Einstein ring: hidden, like
// Schwarzschild's near-axis vertices, and left to the post pass).
// Magnification past which a Kerr image counts as on a fold (healthy images beside one stay under about 40).
#define RELATIVITY_FOLD_CUT 80.0

int relativityKerrImage(vec3 p, vec3 guess, out vec3 seen) {
    seen = guess;
    if (guess.z > -1e-3) return 0;
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 hole = RelativityLensData[6].xyz;
    float a = RelativityLensData[6].w * 0.5 * RelativityLensData[0].w;
    vec3 d = viewToGame * p - hole;
    vec3 local = vec3(d.z, d.x, d.y);
    float w = dot(local, local) - a * a;
    float rP = sqrt(max(0.5 * w + sqrt(0.25 * w * w + a * a * local.z * local.z), 1e-12));
    vec3 n = normalize(d);
    vec2 tanXY = RelativityLensData[7].xy;
    vec2 size = RelativityLensData[7].zw;
    vec2 start = ((guess.xy / -guess.z) / tanXY * 0.5 + 0.5) * size;
    bool nearEdge = any(lessThan(start, vec2(32.0))) || any(greaterThan(start, size - 32.0));
    if (any(lessThan(start, vec2(1.0))) || any(greaterThan(start, size - 1.0))) return 0;
    ivec2 at = ivec2(start);
    // Coming in first (the image whose light reaches p first along its path), then going out, then from the closest
    // approach.
    float tries[3];
    tries[0] = rP <= RelativityLensData[11].x ? relativityPathPosition(at, rP, false) : relativityPathPosition(at, rP, true);
    tries[1] = relativityPathPosition(at, rP, true);
    tries[2] = 0.0;
    // The image on the same side of the hole (on screen) as the guess: the primary, not the one across the ring.
    vec3 holeView = transpose(viewToGame) * hole;
    vec2 holeAt = ((holeView.xy / -min(holeView.z, -1e-3)) / tanXY * 0.5 + 0.5) * size;
    for (int k = 0; k < 3; k += 1) {
        vec3 x = vec3(start, tries[k]);
        if (relativityKerrSolve(x, n, rP) && dot(start - holeAt, x.xy - holeAt) >= 0.0) {
            seen = normalize(vec3((x.xy / size * 2.0 - 1.0) * tanXY, -1.0));
            float magnification = relativityKerrMagnification(x, n, rP, length(p));
            relativityLastSolve = vec2(float(k), magnification);
            // At a fold the solve lands between the two merging images, and a triangle reaching such a vertex is
            // stretched across both: hidden, and the strip left to the post pass (as Schwarzschild's near-axis cut).
            if (magnification > RELATIVITY_FOLD_CUT) { relativityLastSolve.x = 5.0; return -1; }
            if (RelativityLensData[1].w > 1.5 && RelativityLensData[1].w < 2.5 && x.z > 0.0) return -1; // debug 2: hide going out
            if (RelativityLensData[1].w > 2.5 && RelativityLensData[1].w < 3.5 && x.z <= 0.0) return -1; // debug 3: hide coming in
            return 1;
        }
    }
    return nearEdge ? 0 : -1;
}

// Works in view space (camera at the origin): the projection alone recovers that from the clip position, whatever
// space the draw's own vertices and model-view matrix use (chunk-relative, world, posed).
// The bend itself, whether or not the level is being drawn with lensing on (the post pass uses it after).
vec4 relativityBend(vec4 clip, mat4 proj) {
    if (RelativityLensData[0].w <= 0.0) return clip;
    vec4 view = inverse(proj) * clip;
    vec3 p = view.xyz / view.w;
    vec3 h = RelativityLensData[0].xyz;
    float rs = RelativityLensData[0].w;
    float rc = length(h);
    vec3 fromHole = p - h;
    float rp = length(fromHole);
    if (rc <= rs || rp <= rs) return clip;
    float u0 = max(rs / rc, 1e-4);
    float uP = max(rs / rp, 1e-4);
    if (u0 < 2e-3 && uP < 2e-3) return clip; // bending under a few milliradians
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
    if (RelativityLensData[1].y > 0.5) return proj * vec4(p, 1.0);
    // No primary image (hidden behind the hole), or near the camera-hole line behind the hole (images round the
    // Einstein ring at the azimuth of their side of the line, so a triangle across it would be stretched over the whole
    // ring): such a vertex gets a depth far past the far plane (toward the hole on screen), so a triangle reaching it
    // passes the far plane within a hair of its other vertices: clipped there, or with depth clamping on, drawn at
    // the far plane, which the post pass takes for sky and redraws (the ring) from the capture.
    bool nearAxis = cosPhi < 0.0 && rp * sideLength < RELATIVITY_AXIS_CUT;
    vec3 seen = normalize(p);
    if (image.g >= 0.5) {
        float rcU = 1.0 / u0;
        float rpU = 1.0 / uP;
        float local = atan(rpU * sin(phi), rcU - rpU * cos(phi)) + image.r;
        // The table's angle is in a static observer's frame (radial lengths stretched by 1 / sqrt(1 - u0)); the
        // camera's directions are coordinate directions, like the rest of the lens.
        float psi = atan(sin(local), cos(local) * sqrt(1.0 - u0));
        seen = -cos(psi) * e1 + sin(psi) * e2;
    }
    // A spinning hole: the Kerr image, found from the Schwarzschild one.
    if (RelativityLensData[1].z > 0.5 && !nearAxis) {
        vec3 kerr;
        int found = relativityKerrImage(p, seen, kerr);
        relativityLastFound = found;
        if (found == 1) return proj * vec4(kerr * length(p), 1.0);
        if (RelativityLensData[1].w > 3.5) found = 0; // debug 4: failures drawn at the Schwarzschild image instead
        if (found < 0 || (RelativityLensData[1].w > 0.5 && RelativityLensData[1].w < 1.5)) image.g = 0.0; // hidden (debug 1: all not placed by Kerr)
    }
    if (image.g < 0.5 || nearAxis) {
        vec4 hidden = proj * vec4(-e1 * length(p), 1.0);
        hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
        return hidden;
    }
    return proj * vec4(seen * length(p), 1.0);
}

// The lens for draws that may not use the level's projection (vanilla, Sodium, Flywheel: the hand, GUI items...).
vec4 relativityLens(vec4 clip, mat4 proj) {
    if (RelativityLensData[1].x < 0.5 || !relativityLevelProjection(proj)) return clip;
    return relativityBend(clip, proj);
}

// For shader packs' world programs (always the level's view; their projection may be jittered for TAA, which comes
// through the lens as a slightly moved point): bends with the level's projection from the block.
vec4 relativityLensLevel(vec4 clip) {
    if (RelativityLensData[1].x < 0.5) return clip;
    return relativityBend(clip, mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]));
}
