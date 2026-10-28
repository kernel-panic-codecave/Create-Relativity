// High-quality lens: each world vertex moves to where its light appears (the primary image), keeping its true distance,
// so depth testing sorts what's in front. Injected at the end of vanilla world vertex shaders (see vertex_lens.json).
// Only draws using the level's own projection are lensed: not the hand, GUI items or other level views.

// One array, not named members: Veil lists a program's active uniforms and warns about block members it can't locate,
// except arrays (which it takes for another mod's uniform block). Same std140 layout as the members would have:
// [0] the hole: xyz centre relative to the camera in view space (blocks), w Schwarzschild radius (0 = off)
// [1] x: 1 while the level is drawn with lensing on; y: 1 skips the image table (debug); z, w unused
// [2..5] the level's projection this frame, by column
// [6] the light table (LightTable, relativityTable*): x, y the half-tangents across and up its view, z, w its nearest and
//     farthest spheres (blocks); [7] x, y its size (texels), z its spheres, w 1 while it's on
// [8..10] unused (all 0)
// [11] x: the angle one screen pixel covers (radians; the pixel check); y: bit 1 turns the pixel check off (debug), bit 2
//      places vertices at their secondary images (VertexLens.drawSecondImages); z the light table's slot this pass draws
//      (0 its primary signature); w unused
// [12..27] unused (all 0)
// [28..44] the field lens's sources (relativityFieldCount and on)
// [45..121] the field lens's tubes (relativityTubeCount and on)
// [122..150] the field lens's warp bubbles (relativityBubbleCount and on)
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[151];
};
uniform sampler3D RelativityImages; // SchwarzschildImages.forFiltering over (sqrt u0, sqrt uP, phi / pi): r the
                                    // launch-angle correction, g whether there's an image (interpolated)

uniform sampler3D RelativitySecondImages; // SchwarzschildImages.buildSecondaryTable, as RelativityImages: r ln(psi - psi_c)
                                          // of the secondary image (the long way round), g whether there's one
uniform usampler2DArray RelativityLightSeeds; // LightTable's inverse, per kept signature (slot s: layers s n to s n +
                                              // n - 1): per straight-line direction and sphere, the quad of rays (j W
                                              // + i: rays (i, j) to (i + 1, j + 1)) of that signature covering it there,
                                              // the least bent kept (bend << 20 | index), all ones where none; layer
                                              // 4 n, texels (s, 0): slot s's signature (all ones: none)
uniform sampler2DArray RelativityLightTable; // LightTable: per view direction, where its light first crosses each sphere
                                             // round the camera (layer 2k: xyz, w 1 if it does) and its heading there
                                             // (2k + 1); layer 2 n: where it ends heading (w 1 out, 0 captured)
uniform sampler2D RelativityEscape; // SchwarzschildLens' escape table (BlackHoleLens's LUT): (sqrt u0, sqrt(psi / pi)) to
                                    // the escape angle and whether the light falls in; the field lens's holes
// Whether this draw places vertices at their secondary images ([11].y bit 2).
bool relativitySecond() { return mod(RelativityLensData[11].y, 4.0) > 1.5; }

// 0, read from the block ([1].w, never negative), not a constant, for loop counts: a constant-count loop gets unrolled,
// and with the light steps inlined into it the driver took tens of seconds to compile the programs using them.
int relativityRuntimeZero() { return int(min(RelativityLensData[1].w, 0.0)); }

const float RELATIVITY_AXIS_CUT = 2.0;  // blocks: larger than a block face's diagonal (draws without the pixel check)

// The axis cut for this draw ([11].y above bit 2, in hundredths of a block): small where the pixel check trims what
// wraps round the ring and terrain by the axis is cut fine (QuadSubdivision's axis tube), the full RELATIVITY_AXIS_CUT
// where nothing trims it (Flywheel's shaders, which carry no pixel check).
float relativityAxisCut() {
    float code = floor(RelativityLensData[11].y / 4.0);
    if (code >= 1000.0) return (code - 1000.0) / 100.0;
    return code > 0.5 ? code / 100.0 : RELATIVITY_AXIS_CUT;
}
const float RELATIVITY_BEYOND = 1e4;   // NDC depth of hidden vertices: far past the far plane

bool relativityLevelProjection(mat4 proj) {
    mat4 d = proj - mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]);
    float diff = abs(d[0][0]) + abs(d[1][1]) + abs(d[2][2]) + abs(d[3][2]) + abs(d[2][3]) + abs(d[2][0]) + abs(d[2][1]);
    return diff < 1e-4;
}


// The Schwarzschild image of view-space point p: 1 with seen its view direction; 2 where there's nothing to bend
// (no hole, too weak, or inside the radius: seen is p's own direction); -1 with no primary image (hidden behind the
// hole). nearAxis: near the camera-hole line behind the hole (images round the Einstein ring at the azimuth of their
// side of the line, so a triangle across it would be stretched over the whole ring).
// Whether view-space point p is within the axis cut of the camera-hole line (behindOnly: past the hole).
// (hole: the lens's centre the image is worked out round.)
bool relativityNearAxis(vec3 p, bool behindOnly, vec3 hole) {
    vec3 axis = hole;
    vec3 e1 = -normalize(axis); // hole to camera
    vec3 from = p - axis;
    float along = dot(from, e1);
    if (behindOnly && along >= 0.0) return false;
    return length(from - along * e1) < relativityAxisCut();
}

// Where the lens's Einstein-ring axis runs through for view-space point p: the hole.
vec3 relativityCausticCentre(vec3 p) { return RelativityLensData[0].xyz; }

// Whether view-space point p lies within reach (blocks) of the Einstein-ring axis behind the hole (either way for
// secondary images).
bool relativityByAxis(vec3 p, float reach) {
    vec3 centre = relativityCausticCentre(p);
    vec3 e1 = -normalize(centre);
    vec3 from = p - centre;
    float along = dot(from, e1);
    // (Drawing secondary images, the camera's side too: their Einstein ring is round the axis there.)
    return (along < 0.0 || relativitySecond()) && length(from - along * e1) < reach;
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

// The bending acceleration of light at x heading v (unit), round a hole at hole of radius rs: the photon orbit equation
// in Cartesian form (as lightBend in lens_scene).
vec3 relativityLightBend(vec3 x, vec3 v, vec3 hole, float rs) {
    vec3 d = x - hole;
    float r2 = dot(d, d);
    vec3 hv = cross(d, v);
    return -1.5 * rs * dot(hv, hv) * d / (r2 * r2 * sqrt(r2));
}

// Near the camera, the tables (centred on the hole) are coarse, so light is followed there directly: from the camera
// along e, four RK4 steps to p's distance, and e corrected by where that lands short of p; three rounds (a short path's
// landing point moves with e almost one for one). The view direction of view-space point p.
vec3 relativityNearImage(vec3 p, vec3 hole, float rs) {
    float d = length(p);
    vec3 e = p / d;
    float h = 0.25 * d;
    int rounds = 3 + relativityRuntimeZero();
    for (int round = 0; round < rounds; round += 1) {
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

const float RELATIVITY_NEAR_BLEND = 0.35;

// ---- The field lens: every source composed along the light (physics-core's FieldLens, which this mirrors) ----
// Light runs straight between sources. Each source is a module: passed exactly from the stretch where the light
// reaches it (a hole: its orbit, from the escape table RelativityEscape), and acting weakly (closed form) on the
// stretches before; when a later source turns the light, those already passed have their weak tails moved onto the
// new line. A point's image: Newton's method on that composition, the point's nearest source aiming the last stretch
// exactly (a hole: its image tables). No marching, nothing built per frame. View space; directions are coordinate
// tangents of the light's path (the camera's own directions, as the rest of the lens takes them).
// [28] x: how many sources, y 1 if the post pass fills from the scene capture (relativityCaptureFills); [29 + 2k]: source k's centre (view space) and type (1 hole, 2 mass); [30 + 2k]: its
// parameters (hole: x r_s; mass: x mass, y softening). VertexLens.uploadField.
const int RELATIVITY_MAX_FIELD = 8; // VertexLens.MAX_FIELD_SOURCES
const float RELATIVITY_FAR = 1e7;   // blocks: "out past everything"

// (Not clamped to RELATIVITY_MAX_FIELD here, VertexLens never uploads more: with the bound known, the compiler unrolled
// every loop over the sources, nested ones included, and the world's shaders took tens of seconds to compile.)
int relativityFieldCount() { return int(RelativityLensData[28].x + 0.5); }

// Whether the post pass fills the lens's asymptotes (the Einstein ring, the photon ring) from the scene capture
// (SceneCapture), following each pixel's light: then nothing need be let through loosely there for want of anything
// else to show.
bool relativityCaptureFills() { return RelativityLensData[28].y > 0.5; }
vec3 relativityFieldCentre(int k) { return RelativityLensData[29 + 2 * k].xyz; }
bool relativityFieldHole(int k) { return RelativityLensData[29 + 2 * k].w < 1.5; }
vec4 relativityFieldParams(int k) { return RelativityLensData[30 + 2 * k]; }
float relativityFieldScale(int k) { return relativityFieldHole(k) ? relativityFieldParams(k).x : max(2.0 * abs(relativityFieldParams(k).x), 1e-9); }

// The weak field of a mass m at c softened by s (Plummer: over a whole line 4 m b / (b^2 + s^2)) on light running
// straight from a to b: adds its turn of the light's direction, and its move of the light at b, both toward c.
// (No bare `return;` in void functions: Veil's GLSL processor crashes on one, and every vanilla shader then goes unlensed.)
void relativityPointField(vec3 c, float m, float s, vec3 a, vec3 b, inout vec3 turn, inout vec3 move) {
    vec3 span = b - a;
    float len = max(length(span), 1e-6);
    vec3 d = span / len;
    float tc = dot(c - a, d);
    vec3 offset = a + d * tc - c;
    // (A line through the centre, or none: nothing.)
    float impact = length(offset);
    float k = impact < 1e-6 || length(span) < 1e-6 ? 0.0 : 2.0 * m * impact;
    impact = max(impact, 1e-6);
    float q = impact * impact + s * s;
    float u0 = -tc;
    float u1 = len - tc;
    float r0 = sqrt(u0 * u0 + q);
    float r1 = sqrt(u1 * u1 + q);
    vec3 toward = -offset / impact;
    turn += toward * (k / q * (u1 / r1 - u0 / r0));
    // (The turn at each point times the way left to b.)
    move += toward * (k * ((len - tc) / q * (u1 / r1 - u0 / r0) + 1.0 / r1 - 1.0 / r0));
}

void relativityFieldWeak(int k, vec3 a, vec3 b, inout vec3 turn, inout vec3 move) {
    // (One call either way: each call site is inlined whole.)
    vec4 params = relativityFieldParams(k);
    bool hole = relativityFieldHole(k);
    relativityPointField(relativityFieldCentre(k), hole ? 0.5 * params.x : params.x, hole ? 0.0 : params.y, a, b, turn, move);
}

// Where light coming along (oi, di) and leaving along (oo, dOut) turned: the point of the outgoing line nearest the
// incoming one, no earlier than oi.
vec3 relativityMeet(vec3 oi, vec3 di, vec3 oo, vec3 dOut) {
    vec3 w = oo - oi;
    float b = dot(di, dOut);
    float denom = 1.0 - b * b;
    if (denom < 1e-9) return oo;
    float d = dot(di, w);
    float e = dot(dOut, w);
    float s = (d - b * e) / denom;
    float t = (b * d - e) / denom;
    return s < 0.0 ? oo + dOut * dot(oi - oo, dOut) : oo + dOut * t;
}

// A hole of r_s rs at c passing light from o along (coordinate) d: the line it leaves on (from where it turned), or
// false if it falls in. Exact: the escape angle of its orbit (SchwarzschildLens's table, as the post pass reads it).
bool relativityHolePass(vec3 c, float rs, inout vec3 o, inout vec3 d) {
    vec3 rel = o - c;
    float r = length(rel);
    if (r <= rs) return false;
    vec3 e1 = rel / r;
    float u0 = rs / r;
    // A static observer's direction there (radial lengths stretched by 1 / sqrt(1 - u0)).
    float radial = dot(d, e1);
    vec3 seen = normalize(d + e1 * (radial * (1.0 / sqrt(1.0 - u0) - 1.0)));
    float inward = -dot(seen, e1);
    vec3 side = seen + e1 * inward;
    if (length(side) < 1e-6) return inward <= 0.0;
    vec3 e2 = normalize(side);
    float psi = acos(clamp(inward, -1.0, 1.0));
    vec2 lutSize = vec2(textureSize(RelativityEscape, 0));
    vec2 at = vec2(sqrt(u0), sqrt(psi / 3.14159265));
    vec2 escape = texture(RelativityEscape, (at * (lutSize - 1.0) + 0.5) / lutSize).rg;
    if (escape.g > 0.5) return false;
    vec3 out_ = e1 * cos(escape.r) + e2 * sin(escape.r);
    float b = r * sin(psi) / sqrt(1.0 - u0);
    vec3 nearest = c + (e1 * sin(escape.r) - e2 * cos(escape.r)) * b;
    o = relativityMeet(o, d, nearest, out_);
    d = out_;
    return true;
}

// A source passing light from o along d (see relativityHolePass); a mass turns it once, where the line comes nearest,
// by its whole deflection from o on.
bool relativityFieldPass(int k, inout vec3 o, inout vec3 d) {
    vec3 c = relativityFieldCentre(k);
    if (relativityFieldHole(k)) return relativityHolePass(c, relativityFieldParams(k).x, o, d);
    float tc = dot(c - o, d);
    vec3 turn = vec3(0.0);
    vec3 move = vec3(0.0);
    relativityFieldWeak(k, o, o + d * (max(tc, 0.0) + RELATIVITY_FAR), turn, move);
    if (tc > 0.0) o += d * tc;
    d = normalize(d + turn);
    return true;
}

// The direction light must leave `from` along (a coordinate tangent) to reach `target`, through the hole of r_s rs at
// c alone: its primary image or, with `secondary`, its secondary one (the image tables, as relativitySchwarzschildImageAt
// reads them from the camera); false if there's none.
bool relativityHoleAim(vec3 from, vec3 c, float rs, vec3 target, bool secondary, out vec3 d) {
    vec3 h = c - from;
    float rc = length(h);
    vec3 fromHole = target - c;
    float rp = length(fromHole);
    d = normalize(target - from);
    if (rc <= rs || rp <= rs) return false;
    float u0 = max(rs / rc, 1e-4);
    float uP = max(rs / rp, 1e-4);
    vec3 e1 = -h / rc;
    vec3 radial = fromHole / rp;
    float cosPhi = clamp(dot(radial, e1), -1.0, 1.0);
    vec3 side = radial - cosPhi * e1;
    vec3 e2 = side / max(length(side), 1e-6);
    float phi = acos(cosPhi);
    vec3 size = vec3(64.0, 64.0, 128.0);
    vec3 axes = vec3(sqrt(u0), sqrt(uP), phi / 3.14159265);
    if (secondary) {
        if (u0 >= 2.0 / 3.0) return false;
        vec2 second = texture(RelativitySecondImages, (axes * (size - 1.0) + 0.5) / size).rg;
        if (second.g < 0.999) return false;
        float critical = asin(min(2.59807621 * u0 * sqrt(1.0 - u0), 1.0));
        float launch = critical + exp(second.r);
        float psi2 = atan(sin(launch), cos(launch) * sqrt(1.0 - u0));
        d = -cos(psi2) * e1 - sin(psi2) * e2;
        return true;
    }
    vec2 image = texture(RelativityImages, (axes * (size - 1.0) + 0.5) / size).rg;
    if (image.g < 0.5) return false;
    float rcU = 1.0 / u0;
    float rpU = 1.0 / uP;
    float local = atan(rpU * sin(phi), rcU - rpU * cos(phi)) + image.r;
    float psi = atan(sin(local), cos(local) * sqrt(1.0 - u0));
    d = -cos(psi) * e1 + sin(psi) * e2;
    return true;
}

// How far round source k its field isn't weak: RELATIVITY_FIELD_NEAR of a hole's r_s (its exact pass starts there),
// none round a mass (FieldLens.near).
const float RELATIVITY_FIELD_NEAR = 20.0; // FieldLens.NEAR
float relativityFieldNear(int k) { return relativityFieldHole(k) ? RELATIVITY_FIELD_NEAR * relativityFieldParams(k).x : 0.0; }

// Source k's weak field on light running straight from a to b, outside its near sphere (FieldLens.fieldOutside): adds
// the turn, and the move at b (a turn before the sphere carries the light on round it). (One call site for the
// field: each is inlined whole.)
void relativityFieldWeakOutside(int k, vec3 a, vec3 b, inout vec3 turn, inout vec3 move) {
    vec3 span = b - a;
    float len = max(length(span), 1e-6);
    vec3 d = span / len;
    vec3 c = relativityFieldCentre(k);
    float tc = dot(c - a, d);
    float impact = length(a + d * tc - c);
    float radius = relativityFieldNear(k);
    float h = impact < radius ? sqrt(radius * radius - impact * impact) : 0.0;
    // The stretches outside: [0, tc - h] and [tc + h, len] (the whole when the line misses the sphere).
    for (int part = 0; part < 2; part += 1) {
        float from = part == 0 ? 0.0 : (h > 0.0 ? max(tc + h, 0.0) : len);
        float to = part == 0 ? (h > 0.0 ? min(tc - h, len) : len) : len;
        if (to > from) {
            vec3 t = vec3(0.0);
            vec3 m = vec3(0.0);
            relativityFieldWeak(k, a + d * from, a + d * to, t, m);
            turn += t;
            move += m + t * (len - to);
        }
    }
}

// The weak fields of the sources in `mask` (bit k: source k), each outside its near sphere, on the line (o, d) up to
// `until` along: the line the light is on there, turned, and through where they've moved it to.
void relativityFieldWeakly(inout vec3 o, inout vec3 d, float until, int mask) {
    if (until > 0.0) {
        vec3 end = o + d * until;
        vec3 turn = vec3(0.0);
        vec3 move = vec3(0.0);
        for (int k = 0; k < relativityFieldCount(); k += 1) {
            if ((mask & (1 << k)) != 0) relativityFieldWeakOutside(k, o, end, turn, move);
        }
        d = normalize(d + turn);
        o = end + move - d * until;
    }
}

// The light's path as relativityFieldPropagate follows it, measured against a point while relativityMissTracking is set:
// how near its pieces come (straight from one source's closest approach to the next's, or a march's steps; the last on
// without end; up to where it's captured, if it is: what's seen is the point's light, wherever it goes after), and the
// point less the nearest place on them (relativityMissVector). FieldLens.miss.
bool relativityMissTracking = false;
vec3 relativityMissTarget = vec3(0.0);
float relativityMissBest = 1e30;
vec3 relativityMissVector = vec3(0.0);

// The piece of the line (o, d) from `from` to `to` along it, against the tracked point.
void relativityMissPiece(vec3 o, vec3 d, float from, float to) {
    if (relativityMissTracking && to > from) {
        vec3 near = o + d * clamp(dot(relativityMissTarget - o, d), from, to);
        vec3 miss = relativityMissTarget - near;
        if (length(miss) < relativityMissBest) { relativityMissBest = length(miss); relativityMissVector = miss; }
    }
}

// Whether holes a and b bend light hard at once: their near spheres overlap (FieldLens.coupled).
bool relativityFieldCoupled(int a, int b) {
    return a != b && relativityFieldHole(a) && relativityFieldHole(b)
        && length(relativityFieldCentre(a) - relativityFieldCentre(b)) < relativityFieldNear(a) + relativityFieldNear(b);
}

// The holes among `left` coupled to hole k, directly or through others (bit per source), k's own bit included.
int relativityFieldGroup(int k, int left) {
    int n = relativityFieldCount();
    int group = 1 << k;
    for (int round = 0; round < n; round += 1) {
        for (int a = 0; a < n; a += 1) {
            if ((group & (1 << a)) == 0) continue;
            for (int b = 0; b < n; b += 1) {
                if ((left & (1 << b)) != 0 && (group & (1 << b)) == 0 && relativityFieldCoupled(a, b)) group |= 1 << b;
            }
        }
    }
    return group;
}

// Whether any two sources are coupled (then images are solved on the composition: relativityFieldImage).
bool relativityFieldAnyCoupled() {
    int n = relativityFieldCount();
    for (int a = 0; a < n; a += 1) {
        for (int b = a + 1; b < n; b += 1) {
            if (relativityFieldCoupled(a, b)) return true;
        }
    }
    return false;
}

// The superposed orbit field of the holes in `group` (OrbitField): light at x heading v (any length) accelerates by
// each hole's photon orbit's pull in Cartesian form, -3/2 r_s h^2 x / r^5 (h = |x × v|), added.
vec3 relativityOrbitField(int group, vec3 x, vec3 v) {
    vec3 a = vec3(0.0);
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if ((group & (1 << k)) == 0) continue;
        vec3 d = x - relativityFieldCentre(k);
        float r2 = dot(d, d);
        vec3 hv = cross(d, v);
        a += d * (-1.5 * relativityFieldParams(k).x * dot(hv, hv) / (r2 * r2 * sqrt(r2)));
    }
    return a;
}

const float RELATIVITY_MARCH_STEP = 0.2; // FieldLens.MARCH_STEP: of the distance to the nearest centre
const int RELATIVITY_MARCH_LIMIT = 400;

// Light from x heading v marched through the group's superposed orbit field (FieldLens.march): RK4, steps
// RELATIVITY_MARCH_STEP of the distance to the nearest centre, until it has left every one's near sphere heading away
// from each (x, v where it is then); false if it falls in (inside a photon sphere heading in) or doesn't get out.
bool relativityFieldMarch(int group, inout vec3 x, inout vec3 v) {
    int limit = RELATIVITY_MARCH_LIMIT + relativityRuntimeZero();
    for (int i = 0; i < limit; i += 1) {
        float nearest = 1e30;
        bool outside = true;
        for (int k = 0; k < relativityFieldCount(); k += 1) {
            if ((group & (1 << k)) == 0) continue;
            vec3 d = x - relativityFieldCentre(k);
            float r = length(d);
            if (r <= 1.5 * relativityFieldParams(k).x && dot(d, v) < 0.0) return false;
            nearest = min(nearest, r);
            if (r < relativityFieldNear(k) || dot(d, v) <= 0.0) outside = false;
        }
        if (outside) return true;
        float h = RELATIVITY_MARCH_STEP * nearest;
        vec3 a1 = relativityOrbitField(group, x, v);
        vec3 v2 = v + a1 * (0.5 * h);
        vec3 a2 = relativityOrbitField(group, x + v * (0.5 * h), v2);
        vec3 v3 = v + a2 * (0.5 * h);
        vec3 a3 = relativityOrbitField(group, x + v2 * (0.5 * h), v3);
        vec3 v4 = v + a3 * h;
        vec3 a4 = relativityOrbitField(group, x + v3 * h, v4);
        vec3 next = x + (v + 2.0 * v2 + 2.0 * v3 + v4) * (h / 6.0);
        relativityMissPiece(x, normalize(next - x), 0.0, length(next - x));
        v += (a1 + 2.0 * a2 + 2.0 * a3 + a4) * (h / 6.0);
        x = next;
    }
    return false;
}

// Light along (o, d) past the sources in the order it meets them (FieldLens.propagate): false if one captures it.
// Each is passed exactly from where the light enters its near sphere (back behind where it is, if that's inside, but
// not past where it set out: started level with an earlier source beside it, its orbit began by its photon sphere,
// and the weak field standing in before that is no good there: the order showed, a straight cut through a shadow).
bool relativityFieldPropagate(inout vec3 o, inout vec3 d) {
    int n = relativityFieldCount();
    vec3 start = o;
    vec3 piece = o; // where the light's current straight piece starts (relativityMissPiece)
    int left = (1 << n) - 1;
    int passed = 0;
    // (Bounded by the uniform count, not a constant: a constant bound is unrolled, and this is inlined at every call.)
    for (int i = 0; i < n; i += 1) {
        int next = -1;
        float nextAt = 1e30;
        for (int k = 0; k < n; k += 1) {
            if ((left & (1 << k)) == 0) continue;
            float t = dot(relativityFieldCentre(k) - o, d);
            if (t < nextAt) { nextAt = t; next = k; }
        }
        if (next < 0) break;
        vec3 c = relativityFieldCentre(next);
        // Holes coupled to it (near spheres overlapping): marched together, from where the light first enters any of
        // their near spheres to where it has left them all heading away; then each one's exact pass finishes its bend.
        int group = relativityFieldHole(next) ? relativityFieldGroup(next, left) : (1 << next);
        float groupEntry = 1e30;
        if (group != (1 << next)) {
            for (int k = 0; k < n; k += 1) {
                if ((group & (1 << k)) == 0) continue;
                float tk = dot(relativityFieldCentre(k) - o, d);
                float bk = length(o + d * tk - relativityFieldCentre(k));
                float rk = relativityFieldNear(k);
                if (bk < rk) groupEntry = min(groupEntry, max(tk - sqrt(rk * rk - bk * bk), dot(start - o, d)));
            }
        }
        if (groupEntry < 1e29) {
            if (groupEntry > 0.0) relativityFieldWeakly(o, d, groupEntry, left);
            else o += d * groupEntry;
            vec3 from = groupEntry > 0.0 ? o + d * groupEntry : o;
            relativityMissPiece(o, d, dot(piece - o, d), dot(from - o, d));
            left &= ~group;
            vec3 before = d;
            vec3 x = from;
            vec3 v = d;
            if (!relativityFieldMarch(group, x, v)) return false;
            // (Each one's exact pass from where the march left off, nearest first, the light kept where it is.)
            vec3 heading = normalize(v);
            int done = 0;
            for (int j = 0; j < n; j += 1) {
                int pick = -1;
                float closest = 1e30;
                for (int k = 0; k < n; k += 1) {
                    if ((group & (1 << k)) == 0 || (done & (1 << k)) != 0) continue;
                    float r = length(x - relativityFieldCentre(k));
                    if (r < closest) { closest = r; pick = k; }
                }
                if (pick < 0) break;
                done |= 1 << pick;
                vec3 at = x;
                if (!relativityFieldPass(pick, at, heading)) return false;
            }
            o = x;
            vec3 tails = vec3(0.0);
            for (int j = 0; j < 2 * n; j += 1) {
                int k = j / 2;
                if ((passed & (1 << k)) == 0) continue;
                bool old = j - 2 * k == 1;
                vec3 tail = vec3(0.0);
                vec3 unused = vec3(0.0);
                relativityFieldWeak(k, o, o + (old ? before : heading) * RELATIVITY_FAR, tail, unused);
                tails += old ? -tail : tail;
            }
            d = normalize(heading + tails);
            passed |= group;
            piece = o;
            continue;
        }
        float impact = length(o + d * nextAt - c);
        float radius = relativityFieldNear(next);
        float entry = impact < radius ? max(nextAt - sqrt(radius * radius - impact * impact), dot(start - o, d)) : 0.0;
        if (entry > 0.0) relativityFieldWeakly(o, d, entry, left);
        else o += d * entry;
        left &= ~(1 << next);
        vec3 before = d;
        // (The piece in, up to its closest approach.)
        relativityMissPiece(o, d, dot(piece - o, d), dot(c - o, d));
        if (!relativityFieldPass(next, o, d)) return false;
        // Those already passed counted their tails along the old line: from the turn on, the light runs along the new.
        // (Both tails at one call site: each is inlined whole.)
        vec3 tails = vec3(0.0);
        for (int j = 0; j < 2 * n; j += 1) {
            int k = j / 2;
            if ((passed & (1 << k)) == 0) continue;
            bool old = j - 2 * k == 1;
            vec3 tail = vec3(0.0);
            vec3 unused = vec3(0.0);
            relativityFieldWeak(k, o, o + (old ? before : d) * RELATIVITY_FAR, tail, unused);
            tails += old ? -tail : tail;
        }
        d = normalize(d + tails);
        passed |= 1 << next;
        // (The piece out starts at its closest approach.)
        piece = o + d * dot(c - o, d);
    }
    relativityMissPiece(o, d, dot(piece - o, d), RELATIVITY_FAR);
    return true;
}

// How far light leaving the camera along view direction d passes from `target`, past every source (FieldLens.miss):
// the target less the nearest point of its path.
vec3 relativityFieldMiss(vec3 d, vec3 target) {
    relativityMissTracking = true;
    relativityMissTarget = target;
    relativityMissBest = 1e30;
    relativityMissVector = vec3(1e9);
    vec3 o = vec3(0.0);
    vec3 e = d;
    relativityFieldPropagate(o, e);
    relativityMissTracking = false;
    return relativityMissVector;
}

// The view direction whose light passes through `target`, from `guess` (FieldLens.solve): Gauss-Newton on the miss,
// finite differences across the direction; false if it doesn't get within half a pixel's angle at the target.
const int RELATIVITY_SOLVE_STEPS = 4;
float relativitySolveResidual = -1.0; // debug (LensCheck): the last solve's miss, in pixels' angle at the target
const float RELATIVITY_SOLVE_STEP = 5e-4; // radians: the finite differences' step
bool relativityFieldSolve(vec3 target, vec3 guess, out vec3 seen) {
    vec3 d = normalize(guess);
    seen = d;
    float tolerance = 0.5 * RelativityLensData[11].x * length(target);
    vec3 e1 = vec3(1.0, 0.0, 0.0);
    vec3 e2 = vec3(0.0, 1.0, 0.0);
    vec3 r = vec3(0.0);
    vec3 j1 = vec3(0.0);
    // Each round three walks (the direction, and a step across it each way), from one call site: it's inlined whole.
    // The last round's first walk only checks the last step.
    int walks = 3 * RELATIVITY_SOLVE_STEPS + 1 + relativityRuntimeZero();
    for (int w = 0; w < walks; w += 1) {
        int part = w - 3 * (w / 3);
        if (part == 0) {
            e1 = normalize(abs(d.y) < 0.9 ? cross(d, vec3(0.0, 1.0, 0.0)) : cross(d, vec3(1.0, 0.0, 0.0)));
            e2 = cross(d, e1);
        }
        vec3 probe = part == 0 ? d : normalize(d + (part == 1 ? e1 : e2) * RELATIVITY_SOLVE_STEP);
        vec3 m = relativityFieldMiss(probe, target);
        if (part == 0) {
            r = m;
            relativitySolveResidual = length(r) / (RelativityLensData[11].x * length(target));
            if (length(r) < tolerance) { seen = d; return true; }
        } else if (part == 1) {
            j1 = (m - r) / RELATIVITY_SOLVE_STEP;
        } else {
            vec3 j2 = (m - r) / RELATIVITY_SOLVE_STEP;
            // (Least squares on the 3 x 2 Jacobian: (J^T J) step = -J^T r.)
            float a = dot(j1, j1);
            float b = dot(j1, j2);
            float c = dot(j2, j2);
            float det = a * c - b * b;
            if (abs(det) < 1e-20) break;
            vec2 step = vec2(c * -dot(j1, r) - b * -dot(j2, r), a * -dot(j2, r) - b * -dot(j1, r)) / det;
            // (At most a tenth of a radian a step: far from the image the map is anything but linear.)
            float size = length(step);
            if (size > 0.1) step *= 0.1 / size;
            d = normalize(d + e1 * step.x + e2 * step.y);
        }
    }
    seen = d;
    return false;
}

// ---- The light table (LightTable: light_table.comp fills it each frame) ----
// A grid of view directions over (a little past) the screen, each one's light marched the whole way through the
// superposed field (every hole's photon orbit, every mass weakly: physics-core's OrbitField), and where it first crosses
// each of n spheres round the camera (radii logarithmic from [6].z to [6].w) and its heading there. A point's image is
// the table inverted: Newton across the grid for the direction whose light reaches the point at its distance, cubic
// (Hermite) between the spheres round it. Exact up to the table's interpolation (physics-core FieldLensTest:
// lightTableImages), a few reads a step. View space.
bool relativityTableOn() { return RelativityLensData[7].w > 0.5; }
vec2 relativityTableSize() { return RelativityLensData[7].xy; }
int relativityTableShells() { return int(RelativityLensData[7].z + 0.5); }
float relativityTableRadius(float k) {
    return RelativityLensData[6].z * exp(log(RelativityLensData[6].w / RelativityLensData[6].z) * k / float(relativityTableShells() - 1));
}

// The view direction through table position uv (texel units, centres at half-integers).
vec3 relativityTableDirection(vec2 uv) {
    vec2 t = (2.0 * uv / relativityTableSize() - 1.0) * RelativityLensData[6].xy;
    return normalize(vec3(t, -1.0));
}

// The table position seeing straight along view direction d; false if it's behind or off the table.
bool relativityTableUv(vec3 d, out vec2 uv) {
    uv = vec2(0.0);
    if (d.z >= -1e-6) return false;
    uv = ((d.xy / -d.z) / RelativityLensData[6].xy + 1.0) * 0.5 * relativityTableSize();
    return all(greaterThanEqual(uv, vec2(0.5))) && all(lessThanEqual(uv, relativityTableSize() - 0.5));
}

vec4 relativityTableRead(vec2 uv, int layer) {
    return texture(RelativityLightTable, vec3(uv / relativityTableSize(), float(layer)));
}

// Where the light through uv is at distance r from the camera; false where the table has none there.
bool relativityTablePoint(vec2 uv, float r, out vec3 p) {
    p = vec3(0.0);
    float s = log(r / RelativityLensData[6].z) / log(RelativityLensData[6].w / RelativityLensData[6].z) * float(relativityTableShells() - 1);
    int k = int(clamp(floor(s), 0.0, float(relativityTableShells() - 2)));
    vec4 a = relativityTableRead(uv, 2 * k);
    vec4 b = relativityTableRead(uv, 2 * k + 2);
    if (a.w < 0.999 || b.w < 0.999) return false;
    vec3 ta = normalize(relativityTableRead(uv, 2 * k + 1).xyz);
    vec3 tb = normalize(relativityTableRead(uv, 2 * k + 3).xyz);
    float span = length(b.xyz - a.xyz);
    // (The parameter whose point is at distance r: from the radii's ratio, then Newton.)
    float t = clamp((r - length(a.xyz)) / max(length(b.xyz) - length(a.xyz), 1e-6), 0.0, 1.0);
    for (int i = 0; i < 2; i += 1) {
        float t2 = t * t;
        float t3 = t2 * t;
        vec3 q = a.xyz * (2.0 * t3 - 3.0 * t2 + 1.0) + ta * ((t3 - 2.0 * t2 + t) * span) + b.xyz * (-2.0 * t3 + 3.0 * t2) + tb * ((t3 - t2) * span);
        vec3 dq = a.xyz * (6.0 * t2 - 6.0 * t) + ta * ((3.0 * t2 - 4.0 * t + 1.0) * span) + b.xyz * (-6.0 * t2 + 6.0 * t) + tb * ((3.0 * t2 - 2.0 * t) * span);
        float slope = dot(q, dq) / max(length(q), 1e-6);
        if (abs(slope) > 1e-9) t = clamp(t - (length(q) - r) / slope, 0.0, 1.0);
    }
    float t2 = t * t;
    float t3 = t2 * t;
    p = a.xyz * (2.0 * t3 - 3.0 * t2 + 1.0) + ta * ((t3 - 2.0 * t2 + t) * span) + b.xyz * (-2.0 * t3 + 3.0 * t2) + tb * ((t3 - t2) * span);
    return true;
}

// Where the camera sees view-space point p by the table: Newton across it from `guess` (a view direction: the seed
// map's ray through p, relativityTableImage); false if it doesn't get within half a pixel's angle (by a shadow's edge,
// or light that comes back: a second image).
const int RELATIVITY_TABLE_STEPS = 12;
vec4 relativityTableWhy = vec4(0.0); // debug (LensCheck): how the last inversion ended (x: 1 off the table, 2 no data, 3 no
                                     // data a step over, 4 degenerate, 5 out of steps, 6 found), y steps, zw uv
bool relativityTableImageFrom(vec3 p, vec3 guess, out vec3 seen) {
    float r = length(p);
    seen = normalize(guess);
    vec2 uv;
    relativityTableWhy = vec4(1.0, 0.0, 0.0, 0.0);
    if (!relativityTableUv(seen, uv)) return false;
    float tolerance = 0.5 * RelativityLensData[11].x * r;
    int steps = RELATIVITY_TABLE_STEPS + relativityRuntimeZero();
    for (int i = 0; i < steps; i += 1) {
        vec3 q;
        relativityTableWhy = vec4(2.0, float(i), uv);
        if (!relativityTablePoint(uv, r, q)) return false;
        vec3 miss = p - q;
        if (length(miss) < tolerance) { relativityTableWhy = vec4(6.0, float(i), uv); seen = relativityTableDirection(uv); return true; }
        vec3 qu;
        vec3 qv;
        relativityTableWhy = vec4(3.0, float(i), uv);
        if (!relativityTablePoint(uv + vec2(0.25, 0.0), r, qu) || !relativityTablePoint(uv + vec2(0.0, 0.25), r, qv)) return false;
        vec3 ju = (qu - q) * 4.0;
        vec3 jv = (qv - q) * 4.0;
        float a = dot(ju, ju);
        float b = dot(ju, jv);
        float c = dot(jv, jv);
        float det = a * c - b * b;
        relativityTableWhy = vec4(4.0, float(i), uv);
        if (abs(det) < 1e-20) return false;
        relativityTableWhy = vec4(5.0, float(i), uv);
        vec2 step = vec2(c * dot(ju, miss) - b * dot(jv, miss), a * dot(jv, miss) - b * dot(ju, miss)) / det;
        // (At most a dozen texels a step: far from the image the table's map is anything but linear.)
        float size = length(step);
        if (size > 12.0) step *= 12.0 / size;
        uv = clamp(uv + step, vec2(0.5), relativityTableSize() - 0.5);
    }
    return false;
}


// An image's signature (LightTable.signature): bit k set if its light, seen along view direction d, passes hole k on the
// other side from view-space point p, as seen from the camera: (c × d) · (c × p) < 0. 0 the primary image; within one
// signature the map from points to images is continuous (broken only on a hole's camera-hole axis, an asymptote).
uint relativityTableSignature(vec3 d, vec3 p) {
    uint bits = 0u;
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (!relativityFieldHole(k)) continue;
        vec3 c = relativityFieldCentre(k);
        if (dot(cross(c, d), cross(c, p)) < 0.0) bits |= 1u << uint(k);
    }
    return bits;
}

// The light table's slots (one geometry pass each: VertexLens.drawSecondImages); this pass's ([11].z) and its signature.
const int RELATIVITY_TABLE_SLOTS = 4; // LightTable.SLOTS
int relativityTableSlot() { return int(RelativityLensData[11].z + 0.5); }
uint relativityTableSlotSignature(int slot) {
    return texelFetch(RelativityLightSeeds, ivec3(slot, 0, RELATIVITY_TABLE_SLOTS * relativityTableShells()), 0).r;
}

// Where the camera sees view-space point p by the table in this pass's signature: seeded from that signature's inverse
// map (the quad of rays covering p's straight-line direction at its sphere, or a neighbour's), refined by Newton, kept
// only if the image found has the signature.
bool relativityTableImage(vec3 p, out vec3 seen) {
    seen = normalize(p);
    int slot = relativityTableSlot();
    uint signature = relativityTableSlotSignature(slot);
    if (signature == 0xFFFFFFFFu) return false;
    vec2 uv;
    if (!relativityTableUv(seen, uv)) return false;
    float r = length(p);
    float s = log(r / RelativityLensData[6].z) / log(RelativityLensData[6].w / RelativityLensData[6].z) * float(relativityTableShells() - 1);
    int k = int(clamp(floor(s + 0.5), 0.0, float(relativityTableShells() - 1)));
    ivec2 cell = ivec2(uv);
    uint best = 0xFFFFFFFFu;
    for (int n = 0; n < 9; n += 1) {
        ivec2 at = clamp(cell + ivec2(n % 3 - 1, n / 3 - 1), ivec2(0), ivec2(relativityTableSize()) - 1);
        best = min(best, texelFetch(RelativityLightSeeds, ivec3(at, slot * relativityTableShells() + k), 0).r);
    }
    if (best == 0xFFFFFFFFu) return false;
    int index = int(best & 0xFFFFFu);
    int width = int(relativityTableSize().x);
    vec2 seed = vec2(float(index % width), float(index / width)) + 1.0;
    if (!relativityTableImageFrom(p, relativityTableDirection(seed), seen)) return false;
    return relativityTableSignature(seen, p) == signature;
}

// Where the light seen along view direction d ends heading, by the table: false if d is off it, or by a shadow's edge
// (the texels round it disagree on capture: there the table's steps would show, so the light is followed per pixel
// instead); captured if a source took it.
bool relativityTableEscape(vec3 d, out vec3 escape, out bool captured) {
    escape = d;
    captured = false;
    vec2 uv;
    if (!relativityTableUv(d, uv)) return false;
    vec4 e = relativityTableRead(uv, 2 * relativityTableShells());
    if (e.w > 0.001 && e.w < 0.999) return false;
    captured = e.w < 0.5;
    escape = normalize(e.xyz);
    return true;
}

// ---- Tubes (physics-core's TubeModule, which this mirrors): a field uniform inside a tube of thin walls round a
// polyline (a Krasnikov tube's tipped light cones, a warp tube's push) ----
// Light runs straight but at the walls and the joints between segments, where it keeps its energy at infinity and its
// momentum along the wall and solves for the part across: refracted, or reflected, or if neither captured (a horizon).
// Inside, its direction is the one the inside's observers see. Followed the way the camera sees light, arriving: time
// reversal turns a tube's push round, so VertexLens uploads each spine reversed and these follow light forward.
// [45] x: how many tubes, y how many spine points in all, z the walk's most events, w the aim's most steps; [46 + 3j]:
// tube j's radius ([122].y: the aim's most traces); [47 + 3j]: its inside's g^tt, g^ts, g^ss and its observers' alpha; [48 + 3j]: their beta, gamma
// (TubeModule.Interior); [58 + i]: spine point i (view space) and its tube. VertexLens.uploadTubes.
const int RELATIVITY_MAX_TUBES = 4;        // VertexLens.MAX_TUBES
const int RELATIVITY_MAX_TUBE_POINTS = 64; // VertexLens.MAX_TUBE_POINTS
const float RELATIVITY_TUBE_EPS = 1e-3;    // blocks: off a wall, past a joint

int relativityTubeCount() { return int(RelativityLensData[45].x + 0.5); }
int relativityTubePoints() { return int(RelativityLensData[45].y + 0.5); }
int relativityTubeEvents() { return int(RelativityLensData[45].z + 0.5); }
int relativityTubeSteps() { return int(RelativityLensData[45].w + 0.5); }
vec3 relativityTubePoint(int i) { return RelativityLensData[58 + i].xyz; }
int relativityTubeOf(int i) { return int(RelativityLensData[58 + i].w + 0.5); }

// Warp bubbles (physics-core's BubbleModule, which these mirror): the same walk, the same crossings, an ellipsoidal wall
// round a uniform warp push, frozen where it is. [122] x: how many; [123 + 7e]: bubble e's centre (view space); [124 +
// 7e ..]: its three semi-axes ([122].y: the walls' aim's most traces) (unit, then length); [127 + 7e]: its inside's g^tt, g^ts, g^ss, alpha; [128 + 7e]: beta,
// gamma; [129 + 7e]: its push's direction (reversed, as the tubes' spines: light followed arriving). VertexLens.uploadBubbles.
// Bubble e is region RELATIVITY_BUBBLE_REGION + e.
const int RELATIVITY_MAX_BUBBLES = 4;        // VertexLens.MAX_BUBBLES
const int RELATIVITY_BUBBLE_REGION = 1000;

int relativityBubbleCount() { return int(RelativityLensData[122].x + 0.5); }
bool relativityIsBubble(int region) { return region >= RELATIVITY_BUBBLE_REGION; }
vec3 relativityBubbleCentre(int e) { return RelativityLensData[123 + 7 * e].xyz; }

// Whether any tube or bubble is in view.
bool relativityWalls() { return relativityTubeCount() > 0 || relativityBubbleCount() > 0; }

// x in bubble e's semi-axes (1 on its wall).
vec3 relativityBubbleLocal(vec3 x, int e) {
    vec3 r = x - relativityBubbleCentre(e);
    vec4 a0 = RelativityLensData[124 + 7 * e];
    vec4 a1 = RelativityLensData[125 + 7 * e];
    vec4 a2 = RelativityLensData[126 + 7 * e];
    return vec3(dot(r, a0.xyz) / a0.w, dot(r, a1.xyz) / a1.w, dot(r, a2.xyz) / a2.w);
}

// Bubble e's wall's outward normal at (or by) x.
vec3 relativityBubbleNormal(vec3 x, int e) {
    vec3 u = relativityBubbleLocal(x, e);
    vec4 a0 = RelativityLensData[124 + 7 * e];
    vec4 a1 = RelativityLensData[125 + 7 * e];
    vec4 a2 = RelativityLensData[126 + 7 * e];
    return normalize(a0.xyz * (u.x / a0.w) + a1.xyz * (u.y / a1.w) + a2.xyz * (u.z / a2.w));
}

// Where the line x + w t is inside bubble e: range, false if nowhere.
bool relativityBubbleSpan(vec3 x, vec3 w, int e, out vec2 range) {
    vec3 u = relativityBubbleLocal(x, e);
    vec3 du = relativityBubbleLocal(x + w, e) - u;
    float qa = dot(du, du);
    float qb = 2.0 * dot(u, du);
    float qc = dot(u, u) - 1.0;
    float disc = qb * qb - 4.0 * qa * qc;
    range = vec2(1.0, 0.0);
    if (qa > 1e-20 && disc >= 0.0) range = vec2(-qb - sqrt(disc), -qb + sqrt(disc)) / (2.0 * qa);
    return range.x <= range.y;
}

// Whether spine points g and g + 1 make a segment (are the same tube's). Segment g is region g; -1 is outside.
bool relativityTubeSegment(int g) { return g >= 0 && g + 1 < relativityTubePoints() && relativityTubeOf(g) == relativityTubeOf(g + 1); }
float relativityTubeRadius(int g) { return RelativityLensData[46 + 3 * relativityTubeOf(g)].x; }

// Region g's inside (outside: flat): g^tt, g^ts, g^ss and alpha; beta and gamma; its direction t.
vec4 relativityTubeMetric(int g) {
    vec4 inside = vec4(-1.0, 0.0, 1.0, 0.0);
    if (relativityIsBubble(g)) inside = RelativityLensData[127 + 7 * (g - RELATIVITY_BUBBLE_REGION)];
    else if (g >= 0) inside = RelativityLensData[47 + 3 * relativityTubeOf(g)];
    return inside;
}
vec2 relativityTubeObservers(int g) {
    vec2 inside = vec2(1.0, 0.0);
    if (relativityIsBubble(g)) inside = RelativityLensData[128 + 7 * (g - RELATIVITY_BUBBLE_REGION)].xy;
    else if (g >= 0) inside = RelativityLensData[48 + 3 * relativityTubeOf(g)].xy;
    return inside;
}
vec3 relativityTubeAxis(int g) {
    vec3 axis = vec3(1.0, 0.0, 0.0);
    if (relativityIsBubble(g)) axis = RelativityLensData[129 + 7 * (g - RELATIVITY_BUBBLE_REGION)].xyz;
    else if (g >= 0) axis = normalize(relativityTubePoint(g + 1) - relativityTubePoint(g));
    return axis;
}

vec3 relativityTubeClosest(vec3 x, int g) {
    vec3 a = relativityTubePoint(g);
    vec3 d = relativityTubePoint(g + 1) - a;
    float l2 = dot(d, d);
    float u = 0.0;
    if (l2 > 0.0) u = clamp(dot(x - a, d) / l2, 0.0, 1.0);
    return a + d * u;
}

// A wall's outward normal at x, region g's (a tube's segment, or a bubble).
vec3 relativityWallOutward(vec3 x, int g) {
    vec3 n = vec3(0.0, 1.0, 0.0);
    if (relativityIsBubble(g)) n = relativityBubbleNormal(x, g - RELATIVITY_BUBBLE_REGION);
    else n = normalize(x - relativityTubeClosest(x, g));
    return n;
}

// How big the walls are, for the aim's search: the first tube's radius, or the first bubble's longest semi-axis.
float relativityWallSize() {
    float size = 1.0;
    if (relativityBubbleCount() > 0) size = max(RelativityLensData[124].w, max(RelativityLensData[125].w, RelativityLensData[126].w));
    if (relativityTubeCount() > 0) size = relativityTubeRadius(0);
    return size;
}

// Between segments g and g + 1: the normal (toward g + 1) of the plane through their joint halving the turn.
vec3 relativityTubeJoint(int g) { return normalize(relativityTubeAxis(g) + relativityTubeAxis(g + 1)); }


// The region x is in: a bubble it's inside, else the nearest segment within its tube's radius (then across its joints'
// planes), else -1.
int relativityTubeRegion(vec3 x) {
    int n = relativityTubePoints();
    int bubble = -1;
    for (int e = 0; e < relativityBubbleCount(); e += 1) {
        if (bubble < 0 && length(relativityBubbleLocal(x, e)) < 1.0) bubble = RELATIVITY_BUBBLE_REGION + e;
    }
    int best = -1;
    float bestD = 1e30;
    for (int g = 0; g < n; g += 1) {
        if (!relativityTubeSegment(g)) continue;
        float d = length(x - relativityTubeClosest(x, g));
        if (d < relativityTubeRadius(g) && d < bestD) { bestD = d; best = g; }
    }
    int k = best;
    if (k >= 0) {
        for (int i = 0; i < n; i += 1) {
            if (relativityTubeSegment(k + 1) && dot(x - relativityTubePoint(k + 1), relativityTubeJoint(k)) > 0.0) k += 1;
            else break;
        }
        for (int i = 0; i < n; i += 1) {
            if (relativityTubeSegment(k - 1) && dot(x - relativityTubePoint(k), relativityTubeJoint(k - 1)) < 0.0) k -= 1;
            else break;
        }
    }
    if (bubble >= 0) k = bubble;
    return k;
}

// Widens range (lo, hi) to take in where the line o + w t is inside the sphere of radius r round the origin.
void relativityTubeSphere(vec3 o, vec3 w, float r, inout vec2 range) {
    float qa = dot(w, w);
    float qb = 2.0 * dot(o, w);
    float qc = dot(o, o) - r * r;
    float disc = qb * qb - 4.0 * qa * qc;
    if (disc >= 0.0) {
        float s = sqrt(disc);
        range = vec2(min(range.x, (-qb - s) / (2.0 * qa)), max(range.y, (-qb + s) / (2.0 * qa)));
    }
}

// Where the line x + w t is inside segment g's capsule: range (it's convex), false if nowhere.
bool relativityTubeCapsule(vec3 x, vec3 w, int g, out vec2 range) {
    vec3 a = relativityTubePoint(g);
    vec3 b = relativityTubePoint(g + 1);
    float radius = relativityTubeRadius(g);
    range = vec2(1e30, -1e30);
    relativityTubeSphere(x - a, w, radius, range);
    relativityTubeSphere(x - b, w, radius, range);
    // The cylinder's side, within the slab between the ends.
    vec3 axis = normalize(b - a);
    float len = length(b - a);
    vec3 o = x - a;
    vec3 wp = w - axis * dot(w, axis);
    vec3 op = o - axis * dot(o, axis);
    float qa = dot(wp, wp);
    vec2 side = vec2(-1e30, 1e30);
    if (qa < 1e-12) {
        if (dot(op, op) > radius * radius) side = vec2(1.0, 0.0);
    } else {
        float qb = 2.0 * dot(op, wp);
        float qc = dot(op, op) - radius * radius;
        float disc = qb * qb - 4.0 * qa * qc;
        side = vec2(1.0, 0.0);
        if (disc >= 0.0) side = vec2(-qb - sqrt(disc), -qb + sqrt(disc)) / (2.0 * qa);
    }
    if (side.x <= side.y) {
        float wa = dot(w, axis);
        float oa = dot(o, axis);
        if (abs(wa) < 1e-12) {
            if (oa < 0.0 || oa > len) side = vec2(1.0, 0.0);
        } else {
            float s0 = -oa / wa;
            float s1 = (len - oa) / wa;
            side = vec2(max(side.x, min(s0, s1)), min(side.y, max(s0, s1)));
        }
        if (side.x <= side.y) range = vec2(min(range.x, side.x), max(range.y, side.y));
    }
    return range.x <= range.y;
}

// The momentum across a wall of normal n for light of energy at infinity pt and momentum pT along it, in region g,
// going along n (sense 1) or against it (-1), future-directed: the smaller if two; false if none.
bool relativityTubeSolve(float pt, vec3 pT, vec3 n, int g, float sense, out float q) {
    vec4 f = relativityTubeMetric(g);
    vec3 t = relativityTubeAxis(g);
    // p = pT + q n, p_s = a0 + b0 q; null: g^tt pt^2 + 2 g^ts pt p_s + g^ss p_s^2 + |p|^2 - p_s^2 = 0.
    float a0 = dot(pT, t);
    float b0 = dot(n, t);
    float qa = 1.0 + (f.z - 1.0) * b0 * b0;
    float qb = 2.0 * (f.z - 1.0) * a0 * b0 + 2.0 * f.y * pt * b0;
    float qc = (f.z - 1.0) * a0 * a0 + 2.0 * f.y * pt * a0 + f.x * pt * pt + dot(pT, pT);
    vec2 roots = vec2(0.0);
    int count = 0;
    if (abs(qa) < 1e-6) {
        if (abs(qb) > 1e-9) { roots.x = -qc / qb; count = 1; }
    } else {
        float disc = qb * qb - 4.0 * qa * qc;
        if (disc >= 0.0) {
            roots = vec2(-qb - sqrt(disc), -qb + sqrt(disc)) / (2.0 * qa);
            count = 2;
        }
    }
    bool found = false;
    q = 0.0;
    for (int i = 0; i < count; i += 1) {
        float r = roots.x;
        if (i == 1) r = roots.y;
        vec3 p = pT + n * r;
        float ps = dot(p, t);
        float td = f.x * pt + f.y * ps;
        float vn = (r + b0 * ((f.z - 1.0) * ps + f.y * pt)) / td;
        if (td > 1e-6 && vn * sense > 0.0 && (!found || abs(r) < abs(q))) { q = r; found = true; }
    }
    return found;
}

// Light's coordinate velocity (per unit coordinate time) in region g.
vec3 relativityTubeVelocity(vec3 p, float pt, int g) {
    vec4 f = relativityTubeMetric(g);
    vec3 t = relativityTubeAxis(g);
    float ps = dot(p, t);
    return (p + t * ((f.z - 1.0) * ps + f.y * pt)) / (f.x * pt + f.y * ps);
}

// Crosses into region `into` at a wall of normal n (unit, the way the light goes), keeping p's part along the wall and
// pt and solving for the part across. False if it can't: reflected, or if not even that, captured.
bool relativityTubeCross(int into, vec3 n, float pt, inout vec3 p, inout int region, inout bool captured) {
    vec3 pT = p - n * dot(p, n);
    float q;
    bool crossed = false;
    if (relativityTubeSolve(pt, pT, n, into, 1.0, q)) {
        p = pT + n * q;
        region = into;
        crossed = true;
    } else if (relativityTubeSolve(pt, pT, n, region, -1.0, q)) {
        p = pT + n * q;
    } else {
        captured = true;
    }
    return crossed;
}

// On to the next wall or joint: 1 once outside for good (x where it last crossed), -1 if captured, 0 still going. (Each
// helper at one call site: they're inlined whole, and the world's programs all carry this.)
int relativityTubeStep(inout vec3 x, inout vec3 p, float pt, inout int region) {
    vec3 w = relativityTubeVelocity(p, pt, region);
    // Outside: the first capsule or bubble it enters; inside segment k (or a bubble): where it leaves it.
    float event = 1e30;
    int hit = -1;
    for (int e = 0; e < relativityBubbleCount(); e += 1) {
        int r = RELATIVITY_BUBBLE_REGION + e;
        if (region >= 0 && r != region) continue;
        vec2 range;
        if (!relativityBubbleSpan(x, w, e, range)) continue;
        if (region >= 0) {
            event = max(range.y, 0.0);
            hit = r;
        } else if (range.x > RELATIVITY_TUBE_EPS && range.x < event && range.y > range.x) {
            event = range.x;
            hit = r;
        }
    }
    for (int g = 0; g < relativityTubePoints(); g += 1) {
        if (!relativityTubeSegment(g) || (region >= 0 && g != region)) continue;
        vec2 range;
        if (!relativityTubeCapsule(x, w, g, range)) continue;
        if (region >= 0) {
            event = max(range.y, 0.0);
            hit = g;
        } else if (range.x > RELATIVITY_TUBE_EPS && range.x < event && range.y > range.x) {
            event = range.x;
            hit = g;
        }
    }
    int outcome = 0;
    if (region < 0 && hit < 0) {
        outcome = 1;
    } else {
        if (region >= 0 && hit < 0) event = 0.0;
        // Inside, a joint first: across into the next segment.
        int next = -1;
        vec3 n = vec3(0.0);
        if (region >= 0 && !relativityIsBubble(region) && relativityTubeSegment(region + 1)) {
            vec3 m = relativityTubeJoint(region);
            float wm = dot(w, m);
            if (wm > 1e-9) {
                float t = dot(relativityTubePoint(region + 1) - x, m) / wm;
                if (t > -RELATIVITY_TUBE_EPS && t < event) { event = max(t, 0.0); next = region + 1; n = m; }
            }
        }
        if (relativityTubeSegment(region - 1)) {
            vec3 m = relativityTubeJoint(region - 1);
            float wm = dot(w, m);
            if (wm < -1e-9) {
                float t = dot(relativityTubePoint(region) - x, m) / wm;
                if (t > -RELATIVITY_TUBE_EPS && t < event) { event = max(t, 0.0); next = region - 1; n = -m; }
            }
        }
        x += w * event;
        // Where it goes, across what: a joint's plane; into a tube, the region just past the wall (or the capsule's);
        // out of one, the region past it if another tube's, else outside.
        int into = next;
        if (next < 0) {
            int beyond = relativityTubeRegion(x + w * RELATIVITY_TUBE_EPS);
            // (Into a wall: its normal turned in, at the one it hit.)
            int wall = region;
            if (region < 0) wall = hit;
            n = relativityWallOutward(x, wall);
            if (region < 0) {
                n = -n;
                into = beyond;
                if (beyond < 0) into = hit;
            } else if (beyond >= 0 && beyond != region) {
                into = beyond;
            }
        }
        int was = region;
        bool captured = false;
        // (Reflected off a tube's wall from inside: stepped back in, off it.)
        if (!relativityTubeCross(into, n, pt, p, region, captured) && !captured && was >= 0 && into < 0) x -= n * RELATIVITY_TUBE_EPS;
        if (captured) outcome = -1;
    }
    return outcome;
}

// Light at x heading along d (inside a tube, as its observers see it): its momentum, energy at infinity and region.
void relativityTubeStart(vec3 x, vec3 d, out vec3 p, out float pt, out int region) {
    region = relativityTubeRegion(x);
    p = d;
    pt = -1.0;
    if (region >= 0) {
        vec4 f = relativityTubeMetric(region);
        vec2 o = relativityTubeObservers(region);
        float ms = dot(d, relativityTubeAxis(region));
        p = d + relativityTubeAxis(region) * (f.w + (o.x - 1.0) * ms);
        pt = -1.0 + o.y * ms;
    }
}

// The direction region g's observers see light of momentum p and energy pt (any scale) going (TubeModule.observe).
vec3 relativityTubeObserve(vec3 p, float pt, int g) {
    vec4 f = relativityTubeMetric(g);
    vec2 o = relativityTubeObservers(g);
    vec3 t = relativityTubeAxis(g);
    float ps = dot(p, t);
    float scale = -(1.0 + o.y * f.w / o.x) / (pt - o.y * ps / o.x);
    return normalize((p - t * ps) * scale + t * ((scale * ps - f.w) / o.x));
}

// Light from o along d (as seen there) through the tubes: the line it leaves on, from where it last crossed, or false if
// captured (or still inside when the walk runs out).
bool relativityTubePass(inout vec3 o, inout vec3 d) {
    vec3 p;
    float pt;
    int region;
    relativityTubeStart(o, d, p, pt, region);
    vec3 x = o;
    bool out_ = false;
    for (int e = 0; e < relativityTubeEvents(); e += 1) {
        vec3 w = relativityTubeVelocity(p, pt, region);
        int outcome = relativityTubeStep(x, p, pt, region);
        if (outcome < 0) break;
        if (outcome > 0) {
            o = x;
            d = normalize(w);
            out_ = true;
            break;
        }
    }
    return out_;
}

// How far light leaving the camera along d misses target: the target less the nearest point of its path (the straight
// pieces between walls, up to where it's captured, if it is: what's seen is the target's light, wherever it goes after).
bool relativityTubeMiss(vec3 d, vec3 target, out vec3 miss) {
    vec3 p;
    float pt;
    int region;
    vec3 x = vec3(0.0);
    relativityTubeStart(x, d, p, pt, region);
    float best = 1e30;
    miss = vec3(1e9);
    for (int e = 0; e < relativityTubeEvents(); e += 1) {
        vec3 a = x;
        vec3 w = normalize(relativityTubeVelocity(p, pt, region));
        int outcome = relativityTubeStep(x, p, pt, region);
        // (Out for good: on without end; else to the wall it met, captured there or not.)
        float len = 1e30;
        if (outcome != 1) len = length(x - a);
        vec3 near = a + w * clamp(dot(target - a, w), 0.0, len);
        if (length(target - near) < best) { best = length(target - near); miss = target - near; }
        if (outcome != 0) break;
    }
    return best < 1e30;
}

// Whether the straight line from the camera to target stays clear of every tube (both ends outside): unbent.
bool relativityTubeClear(vec3 target) {
    bool clear = relativityTubeRegion(vec3(0.0)) < 0 && relativityTubeRegion(target) < 0;
    for (int g = 0; g < relativityTubePoints() && clear; g += 1) {
        vec2 range;
        if (relativityTubeSegment(g) && relativityTubeCapsule(vec3(0.0), target, g, range) && range.y > 0.0 && range.x < 1.0) clear = false;
    }
    for (int e = 0; e < relativityBubbleCount() && clear; e += 1) {
        vec2 range;
        if (relativityBubbleSpan(vec3(0.0), target, e, range) && range.y > 0.0 && range.x < 1.0) clear = false;
    }
    return clear;
}

// Inside region k, the direction its observers see light going whose path runs along unit `along` (closed form: light
// seen along m runs along (m across, A + B m_s)); false if there's none.
bool relativityTubeStraightAt(vec3 along, int k, out vec3 m) {
    vec3 t = relativityTubeAxis(k);
    vec4 f = relativityTubeMetric(k);
    vec2 o = relativityTubeObservers(k);
    float aa = f.z * f.w - f.y;
    float bb = f.z * o.x + f.y * o.y;
    float ss = dot(along, t);
    vec3 across = along - t * ss;
    float sigma = length(across);
    m = along;
    bool ok = true;
    if (sigma < 1e-6) {
        m = t * sign(ss);
        if ((aa + bb * sign(ss)) * ss < 0.0) m = -m;
    } else {
        float qa = bb * bb * sigma * sigma + ss * ss;
        float qb = 2.0 * aa * bb * sigma * sigma;
        float qc = aa * aa * sigma * sigma - ss * ss;
        float disc = qb * qb - 4.0 * qa * qc;
        ok = disc >= 0.0;
        if (ok) {
            float ms = (-qb + sqrt(disc)) / (2.0 * qa);
            // (The root whose light runs toward it, not away along t.)
            if ((aa + bb * ms) * ss < 0.0 || abs(ms) > 1.0) ms = (-qb - sqrt(disc)) / (2.0 * qa);
            ms = clamp(ms, -1.0, 1.0);
            m = t * ms + across * (sqrt(1.0 - ms * ms) / sigma);
        }
    }
    return ok;
}

const int RELATIVITY_WALL_SCAN = 8;       // TubeModule.WALL_SCAN
const int RELATIVITY_WALL_BISECTIONS = 8; // TubeModule.WALL_BISECTIONS

// The aim's first guesses, in closed form, from inside a tube (TubeModule.guess; else the straight line). A target in
// the same straight piece, exactly (the direction whose light runs straight at it). One outside: the light leaving
// through the side in the target's plane through the axis (the piece taken as endless), where its line out runs at the
// target (a scan along the axis, then bisection; TubeModule.wallGuess); failing that, as if it left the straight line's
// way through a wall facing the target. Or (beside, and where neither has light) as if it ran inside to the wall beside
// the target and left there.
vec3 relativityTubeGuess(vec3 target, bool beside) {
    vec3 s = normalize(target);
    vec3 guess = s;
    int k = relativityTubeRegion(vec3(0.0));
    if (k >= 0) {
        vec3 t = relativityTubeAxis(k);
        vec4 f = relativityTubeMetric(k);
        vec2 o = relativityTubeObservers(k);
        float radius = 0.0;
        if (!relativityIsBubble(k)) radius = relativityTubeRadius(k);
        // The straight line inside to aim along (closed form, below), unless the light leaves the straight line's way.
        vec3 along = s;
        bool inside = relativityTubeRegion(target) == k;
        bool bubble = relativityIsBubble(k);
        // (A bubble's "axis point" its centre, and nothing's beside it: it has no length.)
        vec3 base = relativityTubePoint(max(k, 0));
        if (bubble) {
            base = relativityBubbleCentre(k - RELATIVITY_BUBBLE_REGION);
            radius = 0.0;
            beside = false;
        }
        vec3 rel = target - base;
        float zTarget = dot(rel, t);
        vec3 radial = rel - t * zTarget;
        bool found = false;
        if (!inside && !beside && !bubble && length(radial) > radius) {
            vec3 n = normalize(radial);
            float zFrom = dot(-base, t);
            float lo = min(zFrom, zTarget) - length(radial);
            float hi = max(zFrom, zTarget) + length(radial);
            bool bisecting = false;
            bool prevOk = false;
            float prevZ = lo;
            float prevF = 0.0;
            float z0 = lo;
            float z1 = hi;
            float f0 = 0.0;
            for (int i = 0; i < RELATIVITY_WALL_SCAN + 1 + RELATIVITY_WALL_BISECTIONS + relativityRuntimeZero(); i += 1) {
                if (!bisecting && i > RELATIVITY_WALL_SCAN) break;
                float z = lo + (hi - lo) * float(i) / float(RELATIVITY_WALL_SCAN);
                if (bisecting) z = 0.5 * (z0 + z1);
                // How far the light out misses the target's way along the axis, leaving at z.
                vec3 wall = relativityTubePoint(k) + t * z + n * radius;
                vec3 m;
                bool ok = relativityTubeStraightAt(normalize(wall), k, m);
                float fz = 0.0;
                if (ok) {
                    float ms = dot(m, t);
                    vec3 p = m + t * (f.w + (o.x - 1.0) * ms);
                    float pt = -1.0 + o.y * ms;
                    vec3 pT = p - n * dot(p, n);
                    float q;
                    ok = relativityTubeSolve(pt, pT, n, -1, 1.0, q);
                    if (ok) fz = dot(normalize(pT + n * q), t) - dot(normalize(target - wall), t);
                }
                if (bisecting) {
                    if (ok) {
                        guess = m;
                        if ((fz <= 0.0) == (f0 <= 0.0)) { z0 = z; f0 = fz; } else z1 = z;
                    }
                } else {
                    if (ok && prevOk && (prevF <= 0.0) != (fz <= 0.0)) {
                        bisecting = true;
                        found = true;
                        z0 = prevZ;
                        z1 = z;
                        f0 = prevF;
                        guess = m;
                    }
                    prevZ = z;
                    prevF = fz;
                    prevOk = ok;
                }
            }
        }
        if (!inside && !beside && !found) {
            // Out the side facing the target, or (beside the axis) the end.
            vec3 n = t;
            if (dot(s, t) < 0.0) n = -t;
            if (length(radial) > radius) n = normalize(radial);
            if (bubble) n = normalize(rel);
            vec3 pT = s - n * dot(s, n);
            float q;
            if (relativityTubeSolve(-1.0, pT, n, k, 1.0, q)) guess = relativityTubeObserve(pT + n * q, -1.0, k);
            else beside = true;
        }
        if (beside && !inside) {
            // The wall's point nearest the target.
            vec3 near = relativityTubePoint(0);
            for (int g = 0; g < relativityTubePoints(); g += 1) {
                if (!relativityTubeSegment(g)) continue;
                vec3 c = relativityTubeClosest(target, g);
                if (length(target - c) < length(target - near)) near = c;
            }
            along = normalize(near + normalize(target - near) * (0.999 * radius));
        }
        if (inside || beside) {
            vec3 m;
            if (relativityTubeStraightAt(along, k, m)) guess = m;
        }
    }
    return guess;
}

// Two unit vectors perpendicular to unit d and each other (FieldLens.tangents).
void relativityTangents(vec3 d, out vec3 e1, out vec3 e2) {
    vec3 helper = vec3(1.0, 0.0, 0.0);
    if (abs(d.y) < 0.9) helper = vec3(0.0, 1.0, 0.0);
    e1 = normalize(cross(d, helper));
    e2 = cross(d, e1);
}

const float RELATIVITY_TUBE_STEP = 1e-4;      // TubeModule.QUICK_STEP: the aim's finite-difference step (radians)
const float RELATIVITY_TUBE_TOLERANCE = 2e-4; // TubeModule.QUICK_TOLERANCE: its miss allowed, of the distance
const int RELATIVITY_TUBE_HALVINGS = 4;       // TubeModule.QUICK_HALVINGS
const int RELATIVITY_TUBE_GRID = 1;           // TubeModule.QUICK_GRID
const int RELATIVITY_TUBE_AXIS_SCAN = 1;      // TubeModule.AXIS_SCAN

// Where the camera sees target through the tubes (TubeModule.quickAim): from either closed-form guess (from outside, the
// best of a few directions along the tube's axis as seen from here, the straight line among them), or failing both the
// best of a coarse grid over the tube's size as seen from here, Gauss-Newton steps on
// the traced path's miss, done once it misses by little or a step turns it by less than a quarter of a pixel (where the
// tube squeezes the view, a wide miss is a small angle); false if none gets there (the target hidden). One trace a pass round a single loop (each call
// is inlined whole).
bool relativityTubeAim(vec3 target, out vec3 seen) {
    float distance = length(target);
    vec3 straight = target / distance;
    seen = straight;
    if (relativityTubeClear(target)) return true;
    // (A quarter of a pixel, or the CPU's tolerance where that's looser.)
    float tolerance = max(RELATIVITY_TUBE_TOLERANCE, 0.25 * RelativityLensData[11].x) * max(distance, 1.0);
    float reach = min(atan(3.0 * relativityWallSize(), max(distance, relativityWallSize())), 0.6);
    int side = 2 * RELATIVITY_TUBE_GRID + 1;
    vec3 start = straight;
    vec3 e1 = vec3(0.0);
    vec3 e2 = vec3(0.0);
    bool fresh = true;
    // From outside, the way along the tube's axis (its piece nearest the target) as seen from here.
    bool outside = relativityTubeRegion(vec3(0.0)) < 0;
    vec3 way = vec3(0.0);
    if (outside) {
        int nearest = 0;
        float nearestD = 1e30;
        for (int g = 0; g < relativityTubePoints(); g += 1) {
            if (!relativityTubeSegment(g)) continue;
            float d = length(target - relativityTubeClosest(target, g));
            if (d < nearestD) { nearestD = d; nearest = g; }
        }
        vec3 t = relativityTubeAxis(nearest);
        if (relativityTubeCount() == 0) t = relativityTubeAxis(RELATIVITY_BUBBLE_REGION);
        way = t - straight * dot(t, straight);
        if (length(way) > 1e-6) way = normalize(way);
        else way = vec3(0.0);
    }
    vec3 scanDir = straight;
    float scanMiss = 1e30;
    // phase 0: the grid (its cell); 1: the miss where it is; 2, 3: either way a step off; 4: the step, halved as needed;
    // 5: the scan along the axis (its cell).
    int attempt = 0;
    int phase = 1;
    int cell = 0;
    vec3 gridDir = straight;
    float gridMiss = 1e30;
    float a = 0.0;
    float b = 0.0;
    vec3 r = vec3(0.0);
    vec3 ja = vec3(0.0);
    float da = 0.0;
    float db = 0.0;
    int steps = 0;
    int halvings = 0;
    float bestMiss = 1e30;
    vec3 bestDir = straight;
    bool done = false;
    bool settled = false;
    // (At most as many traces as the block says: a group of vertices waits for its slowest.)
    int budget = min(3 * (1 + relativityTubeSteps() * (2 + RELATIVITY_TUBE_HALVINGS)) + side * side, int(RelativityLensData[122].y + 0.5));
    for (int pass = 0; pass < budget && !done; pass += 1) {
        // (An attempt's start, at one call site.)
        if (fresh) {
            if (attempt < 2) start = relativityTubeGuess(target, attempt == 1);
            else start = straight;
            relativityTangents(start, e1, e2);
            if (attempt == 0 && outside && length(way) > 0.0) phase = 5;
            fresh = false;
        }
        vec3 dir;
        if (phase == 5) {
            dir = normalize(straight + way * (reach * float(cell - RELATIVITY_TUBE_AXIS_SCAN) / float(RELATIVITY_TUBE_AXIS_SCAN)));
        } else if (phase == 0) {
            int i = cell / side - RELATIVITY_TUBE_GRID;
            int j = cell - side * (cell / side) - RELATIVITY_TUBE_GRID;
            dir = normalize(straight + e1 * (reach * float(i) / float(RELATIVITY_TUBE_GRID)) + e2 * (reach * float(j) / float(RELATIVITY_TUBE_GRID)));
        } else {
            float qa = a;
            float qb = b;
            if (phase == 2) qa += RELATIVITY_TUBE_STEP;
            if (phase == 3) qb += RELATIVITY_TUBE_STEP;
            if (phase == 4) { qa += da; qb += db; }
            dir = normalize(start + e1 * qa + e2 * qb);
        }
        vec3 m;
        bool ok = relativityTubeMiss(dir, target, m);
        bool finish = false;
        if (phase == 5) {
            if (ok && length(m) < scanMiss) { scanMiss = length(m); scanDir = dir; }
            cell += 1;
            if (cell > 2 * RELATIVITY_TUBE_AXIS_SCAN) {
                start = scanDir;
                relativityTangents(start, e1, e2);
                cell = 0;
                phase = 1;
            }
        } else if (phase == 0) {
            if (ok && length(m) < gridMiss) { gridMiss = length(m); gridDir = dir; }
            cell += 1;
            if (cell == side * side / 2) cell += 1;
            if (cell >= side * side) {
                if (gridMiss >= 1e30) {
                    done = true;
                } else {
                    start = gridDir;
                    relativityTangents(start, e1, e2);
                    a = 0.0;
                    b = 0.0;
                    phase = 1;
                }
            }
        } else if (phase == 1) {
            if (!ok) {
                finish = true;
            } else {
                r = m;
                steps = 0;
                if (length(r) < tolerance || relativityTubeSteps() == 0) finish = true;
                else phase = 2;
            }
            if (finish && !ok) r = vec3(1e30);
        } else if (phase == 2) {
            if (!ok) finish = true;
            else { ja = (m - r) / RELATIVITY_TUBE_STEP; phase = 3; }
        } else if (phase == 3) {
            if (!ok) {
                finish = true;
            } else {
                vec3 jb = (m - r) / RELATIVITY_TUBE_STEP;
                // Least squares: [ja jb] [da db] = -r.
                float aa = dot(ja, ja);
                float ab = dot(ja, jb);
                float bb = dot(jb, jb);
                float det = aa * bb - ab * ab;
                if (abs(det) < 1e-20) {
                    finish = true;
                } else {
                    float ra = -dot(ja, r);
                    float rb = -dot(jb, r);
                    da = (bb * ra - ab * rb) / det;
                    db = (aa * rb - ab * ra) / det;
                    halvings = 0;
                    phase = 4;
                }
            }
        } else {
            if (ok && length(m) < length(r)) {
                a += da;
                b += db;
                r = m;
                steps += 1;
                if (length(r) < tolerance || steps >= relativityTubeSteps()) finish = true;
                else phase = 2;
                if (length(vec2(da, db)) < 0.25 * RelativityLensData[11].x && length(r) < 0.02 * max(distance, 1.0)) {
                    finish = true;
                    settled = true;
                }
            } else {
                halvings += 1;
                da *= 0.5;
                db *= 0.5;
                if (halvings >= RELATIVITY_TUBE_HALVINGS) finish = true;
            }
        }
        if (finish) {
            if (length(r) < bestMiss || settled) { bestMiss = length(r); bestDir = normalize(start + e1 * a + e2 * b); }
            attempt += 1;
            // (From outside the second guess is the first.)
            if (outside && attempt == 1) attempt = 2;
            if (bestMiss < tolerance || settled || attempt >= 3) {
                done = true;
            } else {
                fresh = true;
                a = 0.0;
                b = 0.0;
                phase = attempt == 2 ? 0 : 1;
                cell = 0;
            }
        }
    }
    seen = bestDir;
    return bestMiss < 0.02 * max(distance, 1.0);
}

// Whether the field lens places the geometry: several sources, or any tube.
bool relativityFieldComposed() { return relativityFieldCount() > 1 || relativityWalls() || (relativityFieldCount() == 1 && !relativityFieldHole(0)); }

// Where the light leaving the camera along d ends (FieldLens.sky): false if it's captured. Tubes first: they're round the
// camera, or near it (nothing farther lenses them).
// With where its last straight leg sets off from (o: the camera's if nothing bent it): no nearer than where it passes
// closest to each source (a hole's leg is the line it leaves on, from where that meets the line it came in on: for light
// bent far round, a point it never came near).
bool relativityFieldLeg(vec3 d, out vec3 o, out vec3 escape) {
    o = vec3(0.0);
    escape = d;
    if (relativityWalls() && !relativityTubePass(o, escape)) return false;
    if (!relativityFieldPropagate(o, escape)) return false;
    float past = 0.0;
    for (int k = 0; k < relativityFieldCount(); k += 1) past = max(past, dot(relativityFieldCentre(k) - o, escape));
    o += escape * past;
    return true;
}

bool relativityFieldSky(vec3 d, out vec3 escape) {
    vec3 o;
    return relativityFieldLeg(d, o, escape);
}

// Images this far into a hole's shadow (a fraction of its angular radius) are hidden: a little short of its edge, where
// the camera's directions (coordinate tangents) and the critical angle's (a static observer's) differ.
const float RELATIVITY_SHADOW_MARGIN = 0.97;

// How far either side of the flip in the chain's order (a fraction of the two sources' distance apart, along the line
// of sight) its two orders are blended.
const float RELATIVITY_FIELD_BLEND = 0.3;

// Where the camera sees `target` (FieldLens.image): its primary image or, with `secondary`, its secondary one; false
// if a hole hides it. Each source's own exact image, chained back toward the camera: the source the light meets last
// (farthest along the line of sight) images the target, the point as that makes it appear (at the same distance)
// is imaged by the next source back, and so on. Nothing solved: a lookup per source. (Exact for one source; for
// several as close as solving the composition, or closer: FieldLensTest.) The secondary image is the target's own
// nearest hole's; the rest image it as a primary. A mass, which doesn't aim, moves it the other way as far as it
// moves the light there.
bool relativityFieldImage(vec3 target, bool secondary, bool tubes, out vec3 seen) {
    int n = relativityFieldCount();
    int terminal = -1;
    float best = 1e30;
    for (int k = 0; k < n; k += 1) {
        float x = length(target - relativityFieldCentre(k)) / relativityFieldScale(k);
        if (x < best) { best = x; terminal = k; }
    }
    if (secondary && (terminal < 0 || !relativityFieldHole(terminal))) return false;
    float distance = length(target);
    vec3 line = target / distance;
    // The order the chain takes the sources in flips where two of them are equally far along the line of sight (a
    // plane through the camera, between two holes it stands between): images jumped there, a seam. So near the
    // flip the chain is taken both ways (that pair swapped the second time) and the two blended, evenly at the flip.
    int pairA = -1;
    int pairB = -1;
    float pairGap = 1e30;
    for (int a = 0; a < n; a += 1) {
        for (int b = a + 1; b < n; b += 1) {
            float gap = abs(dot(relativityFieldCentre(a) - relativityFieldCentre(b), line));
            if (gap < pairGap) { pairGap = gap; pairA = a; pairB = b; }
        }
    }
    float width = pairA >= 0 ? RELATIVITY_FIELD_BLEND * length(relativityFieldCentre(pairA) - relativityFieldCentre(pairB)) : 0.0;
    float keep = pairA >= 0 ? 0.5 + 0.5 * smoothstep(0.0, width, pairGap) : 1.0;
    int passes = keep < 0.999 ? 2 : 1;
    vec3 blended = vec3(0.0);
    float weights = 0.0;
    for (int pass = 0; pass < passes; pass += 1) {
        vec3 point = target;
        vec3 chained = line;
        bool found = true;
        int done = 0;
        for (int i = 0; i < n && found; i += 1) {
            int next = -1;
            float farthest = -1e30;
            for (int k = 0; k < n; k += 1) {
                if ((done & (1 << k)) != 0) continue;
                // (The second pass swaps the pair's places in the order.)
                int key = pass == 1 && k == pairA ? pairB : pass == 1 && k == pairB ? pairA : k;
                float along = dot(relativityFieldCentre(key), line);
                if (along > farthest) { farthest = along; next = k; }
            }
            done |= 1 << next;
            if (relativityFieldHole(next)) {
                vec3 aimed;
                found = relativityHoleAim(vec3(0.0), relativityFieldCentre(next), relativityFieldParams(next).x, point, secondary && next == terminal, aimed);
                chained = aimed;
            } else {
                vec3 turn = vec3(0.0);
                vec3 move = vec3(0.0);
                relativityFieldWeak(next, vec3(0.0), point, turn, move);
                chained = normalize(chained - move / distance);
            }
            point = chained * distance;
        }
        float weight = pass == 0 ? keep : 1.0 - keep;
        if (found) {
            blended += chained * weight;
            weights += weight;
        }
    }
    // (One way hidden, the other found: that one, not a hole at the flip.)
    if (weights <= 0.0) return false;
    seen = normalize(blended);
    // The tubes last, nearest the camera (as relativityFieldSky takes them first): the point as the sources show it,
    // seen through them.
    if (tubes && relativityWalls()) {
        vec3 through;
        if (!relativityTubeAim(seen * distance, through)) return false;
        seen = through;
    }
    // Where the chain's sources couple too strongly for it (light round two holes in turn), its answer can be light
    // that really falls in: never seen inside a hole's shadow (from the camera, the critical angle round it; past 90
    // degrees inside its photon sphere), unless nearer than the hole (in front of it). (Not where the light passes the
    // target, checked forward: by an Einstein ring the map stretches without bound, and that hid the floor behind a hole
    // seen head-on.)
    for (int k = 0; k < n; k += 1) {
        if (!relativityFieldHole(k)) continue;
        vec3 c = relativityFieldCentre(k);
        float rc = length(c);
        float u0 = relativityFieldParams(k).x / rc;
        if (u0 >= 1.0 || distance <= rc) continue;
        float critical = asin(min(2.59807621 * u0 * sqrt(1.0 - u0), 1.0));
        float shadow = u0 > 2.0 / 3.0 ? 3.14159265 - critical : critical;
        if (acos(clamp(dot(seen, c / rc), -1.0, 1.0)) < RELATIVITY_SHADOW_MARGIN * shadow) return false;
    }
    return true;
}


int relativityFarImage(vec3 p, out vec3 seen) {
    bool nearAxis = false;
    int image = relativitySchwarzschildImage(p, seen, nearAxis);
    if (relativitySecond()) return image == 2 || nearAxis ? -1 : image;
    if (image == 2) return 2;
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
    // Several sources: composed along the light (the field lens), primary and secondary images alike. (Called from
    // here alone: each call is inlined whole, and programs that never bend vertices drop it.)
    if (relativityFieldComposed()) {
        vec3 seen;
        bool longWay = relativitySecond();
        // (The light table where it's on, in this pass's signature: through the tubes after, as the field image takes them.)
        bool found;
        if (relativityTableOn()) {
            found = relativityTableImage(p, seen);
            if (found && relativityWalls()) {
                vec3 through;
                found = relativityTubeAim(seen * d, through);
                seen = through;
            }
        } else {
            found = relativityFieldImage(p, longWay, true, seen);
        }
        if (!found) {
            vec4 hidden = proj * vec4(normalize(hole) * d, 1.0);
            hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
            return hidden;
        }
        // (A secondary image behind the primaries along the same ray: its light went the long way round.)
        // (Not a ?: inside the constructor: Veil's GLSL processor writes that one back wrong.)
        float depth = d;
        if (longWay) depth += 4.0 * RelativityLensData[0].w;
        return proj * vec4(seen * depth, 1.0);
    }
    if (relativitySecond()) {
        // The secondary image at the length of the light's way round (at least the way via the hole), so the primary
        // image of whatever is in front along the same ray wins the depth test. Every point drawn has one (out to the
        // render distance: far ones are slivers by the photon ring, cheap to draw; measured ~2 ms for all of them).
        float rp = length(p - hole);
        vec3 seen;
        if (relativityFarImage(p, seen) != 1) {
            vec4 hidden = proj * vec4(normalize(hole) * d, 1.0);
            hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
            return hidden;
        }
        // (Plus the way round, ~2 r_s: for points by the axis behind the hole rc + rp is their straight distance too, the
        // primary image's depth, and where the two overlap by the Einstein ring they fought: flickering as the camera moved.)
        return proj * vec4(seen * (rc + rp + 2.0 * RelativityLensData[0].w), 1.0);
    }
    float nearness = smoothstep(RELATIVITY_NEAR * rc, RELATIVITY_NEAR_BLEND * rc, d);
    vec3 seen;
    int image = 1;
    if (nearness > 0.0) {
        image = relativityFarImage(p, seen);
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
const float RELATIVITY_POLAR_EINSTEIN = 0.15; // QuadSubdivision.POLAR_EINSTEIN
// How far round the axis (blocks) faces by p are split polar (QuadSubdivision.polarReach): at least
// RELATIVITY_POLAR_REACH, more with the Einstein radius at p's distance along the axis.
float relativityPolarReach(vec3 p) {
    vec3 hole = RelativityLensData[0].xyz;
    float rc = length(hole);
    float d = abs(dot(p - hole, normalize(hole)));
    return max(RELATIVITY_POLAR_REACH, RELATIVITY_POLAR_EINSTEIN * sqrt(2.0 * RelativityLensData[0].w * d * rc / (rc + d)));
}
const float RELATIVITY_AXIS_SLACK = 40.0;

// Whether the lens bends draws made with projection proj (the level's, with lensing on): those carry the check.
bool relativityLensed(mat4 proj) {
    return RelativityLensData[0].w > 0.0 && RelativityLensData[1].x > 0.5 && RelativityLensData[1].y < 0.5 && relativityLevelProjection(proj);
}

// How far the light seen where a pixel's drawn may pass from the point it shows, through tubes: this much of its distance
// and these many blocks (loose: where a tube squeezes the view a tiny turn is a long way at the point).
const float RELATIVITY_TUBE_PIXEL_SLACK = 0.05;
const float RELATIVITY_TUBE_PIXEL_BLOCKS = 0.3;

// For the pixel showing view-space point unbent, drawn at view-space bent: whether it's in the wrong place.
bool relativityPixelWrong(vec3 unbent, vec3 bent) {
    // Tubes: the light seen where the pixel's drawn, followed (one trace: the aim per pixel is too dear), must pass by
    // what it shows; a triangle stretched across a tube's wall (its corners seen through it and not) doesn't. (With
    // other sources too, unchecked.)
    if (relativityWalls()) {
        if (relativityFieldCount() > 0) return false;
        vec3 miss;
        if (!relativityTubeMiss(normalize(bent), unbent, miss)) return true;
        return length(miss) > RELATIVITY_TUBE_PIXEL_SLACK * length(unbent) + RELATIVITY_TUBE_PIXEL_BLOCKS;
    }
    // Composed: by the light table, forward (one read): the light seen where the pixel's drawn, at the point's distance,
    // must be by the point; else the pixel's own image (a lookup per source) against where it's drawn.
    if (relativityFieldComposed() && relativityTableOn()) {
        vec2 uv;
        vec3 at;
        if (!relativityTableUv(normalize(bent), uv) || !relativityTablePoint(uv, length(unbent), at)) return true;
        if (relativityTableSignature(normalize(bent), unbent) != relativityTableSlotSignature(relativityTableSlot())) return true;
        return length(at - unbent) > RELATIVITY_PIXEL_SLACK * RelativityLensData[11].x * length(unbent);
    }
    if (relativityFieldComposed()) {
        vec3 own;
        if (!relativityFieldImage(unbent, relativitySecond(), false, own)) return true;
        return acos(clamp(dot(own, normalize(bent)), -1.0, 1.0)) > RELATIVITY_PIXEL_SLACK * RelativityLensData[11].x;
    }
    if (mod(RelativityLensData[11].y, 2.0) > 0.5) return false;
    float d = length(unbent);
    vec3 hole = RelativityLensData[0].xyz;
    // (Close to the camera the primary images are placed by the near march, not checked; the secondary ones always are:
    // the player's own body, round the camera, is all triangles across the ring.)
    if (d < RELATIVITY_NEAR_BLEND * length(hole) && !relativitySecond()) return false;
    // By the axis behind the hole the image moves round the Einstein ring far faster than the point does,
    // and a pixel's own image is touchy: checked as closely as elsewhere it threw away a ring of what's there (the faces
    // there are split round the axis, QuadSubdivision.POLAR_REACH, so their edges follow the ring anyway). Checked
    // loosely, which still catches a face straddling the axis (with the camera moving, before the split catches up):
    // drawn straight across the ring, off by hundreds of pixels. (With the capture filling the ring, an asymptote of the
    // lens, from the light itself, as closely as elsewhere.)
    float slack = !relativitySecond() && !relativityCaptureFills() && relativityByAxis(unbent, relativityPolarReach(unbent)) ? RELATIVITY_AXIS_SLACK : RELATIVITY_PIXEL_SLACK;
    vec3 seen;
    int image = relativityFarImage(unbent, seen);
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
