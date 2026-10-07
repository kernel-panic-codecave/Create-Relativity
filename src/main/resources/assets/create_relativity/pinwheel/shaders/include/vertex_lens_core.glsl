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
// [11] x: Boyer-Lindquist r where the table's paths start coming in (the camera, or its start sphere); y: where they
//      end going out (the start sphere)
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

bool relativityUnmarched = false; // set when a lookup met a texel whose ray never came near the hole

// Path sample j (-7 .. 7: coming in, the closest approach at 0, going out) of the table's texel t.
vec2 relativityPathSample(ivec2 t, int j) {
    if (j == 0) return texelFetch(RelativityKerrPaths, ivec3(t, 0), 0).zw;
    int f = j < 0 ? -j - 1 : j + 6;
    vec4 v = texelFetch(RelativityKerrPaths, ivec3(t, 1 + f / 2), 0);
    return (f - 2 * (f / 2)) == 0 ? v.xy : v.zw;
}

// Where texel t's path is (seen from the hole) at Boyer-Lindquist radius rP, coming in or going out; ok false where
// the texel has no usable path. Cubic (Catmull-Rom) along the path's samples.
vec2 relativityPathAt(ivec2 t, float rP, bool outbound, out bool ok) {
    vec4 head = texelFetch(RelativityKerrPaths, ivec3(t, 0), 0);
    float rMin = head.x;
    int status = int(head.y + 0.5);
    ok = status == 1 || (status == 2 && !outbound);
    if (status == 0) relativityUnmarched = true;
    float rTop = outbound ? RelativityLensData[11].y : RelativityLensData[11].x;
    if (rTop <= rMin) ok = false;
    float u = 7.0 * sqrt(sqrt(clamp((rP - rMin) / max(rTop - rMin, 1e-6), 0.0, 1.0)));
    float v = outbound ? u : -u;
    float base = clamp(floor(v), -7.0, 6.0);
    float f = v - base;
    int j = int(base);
    vec2 p0 = relativityPathSample(t, max(j - 1, -7));
    vec2 p1 = relativityPathSample(t, j);
    vec2 p2 = relativityPathSample(t, j + 1);
    vec2 p3 = relativityPathSample(t, min(j + 2, 7));
    float f2 = f * f;
    float f3 = f2 * f;
    return 0.5 * (2.0 * p1 + (p2 - p0) * f + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 + (3.0 * p1 - p0 - 3.0 * p2 + p3) * f3);
}

// The path through table position s (texels) at radius rP, seen from the hole: bilinear between texels.
vec2 relativityPathImage(vec2 s, float rP, bool outbound, out bool ok) {
    vec2 size = RelativityLensData[7].zw;
    vec2 pos = s - 0.5;
    ivec2 b = ivec2(floor(pos));
    vec2 f = pos - vec2(b);
    ok = all(greaterThanEqual(b, ivec2(0))) && all(lessThan(b + 1, ivec2(size)));
    if (!ok) return vec2(0.0);
    bool o0;
    bool o1;
    bool o2;
    bool o3;
    vec2 a = relativityPathAt(b, rP, outbound, o0);
    vec2 c = relativityPathAt(b + ivec2(1, 0), rP, outbound, o1);
    vec2 d = relativityPathAt(b + ivec2(0, 1), rP, outbound, o2);
    vec2 e = relativityPathAt(b + ivec2(1, 1), rP, outbound, o3);
    ok = o0 && o1 && o2 && o3;
    return mix(mix(a, c, f.x), mix(d, e, f.x), f.y);
}

// Newton's method from table position s for the path that reaches radius rP (coming in, or going out) where the
// point seen from the hole as qP is. True if it converged, s then the image's table position.
bool relativityKerrSolve(inout vec2 s, vec2 qP, float rP, bool outbound) {
    for (int i = 0; i < 6; i += 1) {
        bool ok;
        vec2 miss = relativityPathImage(s, rP, outbound, ok) - qP;
        if (!ok) return false;
        if (dot(miss, miss) < 1e-8) return true;
        bool okX;
        bool okY;
        vec2 dx = (relativityPathImage(s + vec2(0.5, 0.0), rP, outbound, okX) - qP - miss) / 0.5;
        vec2 dy = (relativityPathImage(s + vec2(0.0, 0.5), rP, outbound, okY) - qP - miss) / 0.5;
        if (!okX || !okY) return false;
        mat2 jacobian = mat2(dx, dy);
        float det = determinant(jacobian);
        if (abs(det) < 1e-14) return false;
        vec2 step = inverse(jacobian) * miss;
        float length2 = dot(step, step);
        if (length2 > 64.0) step *= 8.0 / sqrt(length2);
        s -= step;
    }
    bool ok;
    vec2 miss = relativityPathImage(s, rP, outbound, ok) - qP;
    return ok && dot(miss, miss) < 1e-6;
}

// The view direction of p's image under a spinning hole ([6]), from Newton's method started at the view direction
// guess: 1 found; 0 its light doesn't come near the hole (the caller's Schwarzschild image stands: spin barely
// matters there); -1 not found (at the shadow's edge: hidden, like Schwarzschild's hidden vertices).
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
    // The direction from the hole in the table's equal-area coordinates round the hole-to-camera axis.
    vec3 e1 = normalize(-hole);
    vec3 e2 = cross(vec3(0.0, 1.0, 0.0), e1);
    if (dot(e2, e2) < 1e-6) e2 = cross(vec3(1.0, 0.0, 0.0), e1);
    e2 = normalize(e2);
    vec3 e3 = cross(e1, e2);
    vec3 n = normalize(d);
    vec2 qP = vec2(dot(n, e2), dot(n, e3)) / sqrt(max(0.5 * (1.0 + dot(n, e1)), 1e-6));
    vec2 tanXY = RelativityLensData[7].xy;
    vec2 size = RelativityLensData[7].zw;
    vec2 start = ((guess.xy / -guess.z) / tanXY * 0.5 + 0.5) * size;
    vec2 s = start;
    // The image whose light reaches p first along its path (coming in), else going out.
    relativityUnmarched = false;
    bool solved = rP <= RelativityLensData[11].x && relativityKerrSolve(s, qP, rP, false);
    if (!solved) {
        s = start;
        solved = relativityKerrSolve(s, qP, rP, true);
    }
    if (!solved) return relativityUnmarched ? 0 : -1;
    seen = normalize(vec3((s / size * 2.0 - 1.0) * tanXY, -1.0));
    return 1;
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
        if (found == 1) return proj * vec4(kerr * length(p), 1.0);
        if (found < 0 || RelativityLensData[1].w > 0.5) image.g = 0.0; // hidden (debug: everything not placed by Kerr)
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
