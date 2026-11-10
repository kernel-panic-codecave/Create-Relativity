// Debug (GameTests): the vertex lens's own maths (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel (LensCheck).
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;
uniform int Direct; // 0: the bend; 4: why a point is hidden; 7: the tubes' aim; 8: the field lens's sky; 9: its image; 10 the table's why; 11 its image

out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    if (Direct == 15) {
        // The fields' lens's image of p: the table's, finished by tracing (lensFieldRefine) where it fails or its light
        // has been through a wall (the table's direction layer's w): xyz the direction, w 1 by the table alone, 2
        // finished by tracing, -1 not found.
        float r = length(p);
        vec3 seen;
        bool found = relativityTableImage(p, seen);
        bool walled = !found;
        vec2 uv;
        if (found && relativityTableUv(seen, uv)) {
            float sh = log(r / RelativityLensData[6].z) / log(RelativityLensData[6].w / RelativityLensData[6].z) * float(relativityTableShells() - 1);
            int k = int(clamp(floor(sh), 0.0, float(relativityTableShells() - 2)));
            walled = relativityTableRead(uv, 2 * k + 1).w > 0.0 || relativityTableRead(uv, 2 * k + 3).w > 0.0;
        }
        // (Traced only where the table fails, from its search's nearest uv.)
        if (found) { Result = vec4(seen, 1.0); return; }
        seen = relativityTableDirection(relativityTableWhy.zw);
        bool ok = lensFieldRefine(p, seen, 3, 0.5 * RelativityLensData[11].x * r);
        Result = vec4(seen, ok ? 2.0 : -1.0);
        return;
    }
    if (Direct == 14) {
        // The fields' exact image of p (a reference for the table): light marched through them (lensFieldReach) to p's
        // distance, its direction refined by Gauss-Newton on where it lands, from the table's image (else straight): xyz
        // the direction, w 1 if it lands within a hundredth of a block, else -1.
        float r = length(p);
        vec3 d = normalize(p);
        vec3 seed;
        if (relativityTableImage(p, seed)) d = seed;
        vec3 e1 = normalize(cross(d, abs(d.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 e2 = cross(d, e1);
        float miss = 1e30;
        for (int it = 0; it < 10 && miss > 1e-3; it += 1) {
            vec3 at;
            vec3 ax;
            vec3 ay;
            bool ok = lensFieldReach(d, r, at);
            float h = 1e-4;
            bool okx = lensFieldReach(normalize(d + e1 * h), r, ax);
            bool oky = lensFieldReach(normalize(d + e2 * h), r, ay);
            if (!ok || !okx || !oky) break;
            vec3 res = at - p;
            miss = length(res);
            vec3 ja = (ax - at) / h;
            vec3 jb = (ay - at) / h;
            float aa = dot(ja, ja);
            float ab = dot(ja, jb);
            float bb = dot(jb, jb);
            float det = aa * bb - ab * ab;
            if (abs(det) < 1e-20) break;
            float ra = -dot(ja, res);
            float rb = -dot(jb, res);
            float da = clamp((bb * ra - ab * rb) / det, -0.2, 0.2);
            float db = clamp((aa * rb - ab * ra) / det, -0.2, 0.2);
            d = normalize(d + e1 * da + e2 * db);
            e1 = normalize(cross(d, abs(d.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
            e2 = cross(d, e1);
        }
        Result = vec4(d, miss < 0.01 ? 1.0 : -1.0);
        return;
    }
    if (Direct == 12) {
        // The fields' force on light at p (lensFieldForce: their gradients in closed form) for a set momentum, against
        // H's differences: xyz the closed form's, w how far apart they are.
        vec3 m = normalize(vec3(0.6, 0.3, -0.74));
        LensFieldSample s = lensFieldAt(p);
        vec3 force = lensFieldForce(s, m);
        float h = 0.002;
        vec3 numeric = -vec3(lensFieldH(lensFieldAt(p + vec3(h, 0.0, 0.0)), m) - lensFieldH(lensFieldAt(p - vec3(h, 0.0, 0.0)), m),
                             lensFieldH(lensFieldAt(p + vec3(0.0, h, 0.0)), m) - lensFieldH(lensFieldAt(p - vec3(0.0, h, 0.0)), m),
                             lensFieldH(lensFieldAt(p + vec3(0.0, 0.0, h)), m) - lensFieldH(lensFieldAt(p - vec3(0.0, 0.0, h)), m)) / (2.0 * h);
        Result = vec4(force, length(force - numeric));
        return;
    }
    if (Direct == 13) {
        // The fields at p: x A (both parts), y B, z C, w the axial weight.
        LensFieldSample s = lensFieldAt(p);
        Result = vec4(s.a + s.axialA, s.b, s.c, s.weight);
        return;
    }
    if (Direct == 11) {
        // The light table's image of p (relativityTableImage): xyz where it's seen, w 1 found, -1 not.
        vec3 seen;
        bool found = relativityTableImage(p, seen);
        Result = vec4(seen, found ? 1.0 : -1.0);
        return;
    }
    if (Direct == 10) {
        // The light table's image of p (relativityTableImage): x how it ended (relativityTableWhy), y steps, zw the uv.
        vec3 seen;
        relativityTableImage(p, seen);
        Result = relativityTableWhy;
        return;
    }
    if (Direct == 9) {
        // The field lens's image of p (relativityFieldImage): x 1 found, -1 not; y the last solve's miss (pixels; -1 none
        // ran); z the chain guess's angle from the result (pixels).
        vec3 seen;
        bool found = relativityFieldImage(p, false, true, seen);
        Result = vec4(found ? 1.0 : -1.0, relativitySolveResidual, 0.0, 0.0);
        return;
    }
    if (Direct == 8) {
        // The field lens's sky (relativityFieldSky) for the view direction p: xyz where its light ends heading, w 1, or
        // -1 captured.
        vec3 escape;
        bool out_ = relativityFieldSky(normalize(p), escape);
        Result = vec4(escape, out_ ? 1.0 : -1.0);
        return;
    }
    if (Direct == 7) {
        // The field lens's tubes alone (relativityTubeAim): xyz where p is seen, w 1 found, -1 hidden.
        vec3 seen;
        bool found = relativityTubeAim(p, seen);
        Result = vec4(seen, found ? 1.0 : -1.0);
        return;
    }
    if (Direct == 4) {
        // Why p is hidden: x the Schwarzschild image (1 found, -1 none, 2 nothing to bend), y 1 near the axis behind the
        // hole.
        vec3 seen;
        bool nearAxis;
        int image = relativitySchwarzschildImage(p, seen, nearAxis);
        Result = vec4(float(image), nearAxis ? 1.0 : 0.0, 0.0, 0.0);
        return;
    }
    vec4 bent = relativityBend(Projection * vec4(p, 1.0), Projection);
    // x: 1 placed, -1 hidden (past the far plane); yz the bent NDC
    bool hidden = bent.z > bent.w || bent.w <= 0.0;
    Result = vec4(hidden ? -1.0 : 1.0, bent.xy / bent.w, 0.0);
}
