// The lens stages' per-vertex part (ImageStage): where the vertex's other images are, for the geometry stage
// (shaders/stages/image.gsh) to draw them, and whether it's lost in a shadow. Run by a lens twin's vertex shader
// (vertex_tail.glsl), or by the tessellation stage's evaluation shader (lens.tesh) for each vertex it makes. Needs the
// lens core, included before it (LensStageProcessor).

out vec4 relativityStageA;
out vec2 relativityStageB;
out vec2 relativityStageP;
out vec4 relativityStageT;
// The hole its light passes nearest (by impact, in r_s, off the line of sight): xyz its centre (view space), w its r_s; w
// 0 none. The geometry stage's ring rules use it.
out vec4 relativityStageH;

// Where the search for each slot's image starts (table position; x 0 the table's own seed, -1 none made): the
// evaluation stage's blend of its corners' (lens.tesh), nearby.
vec2 relativityStageSeed[4] = vec2[4](vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0));

// Where the camera sees p in slot s (table position; 0 none).
vec2 relativityStageImage(vec3 p, int s) {
    vec3 seen;
    vec2 uv;
    if (relativityStageSeed[s].x < 0.0) return vec2(0.0);
    if (!relativityTableImageInFrom(p, s, relativityStageSeed[s], seen) || !relativityTableUv(seen, uv)) return vec2(0.0);
    return uv;
}

// Whether p is within the stage's reach of a hole.
bool relativityStageNear(vec3 p) {
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (relativityFieldHole(k) && length(p - relativityFieldCentre(k)) < RelativityLensData[28].w * relativityFieldParams(k).x) return true;
    }
    return false;
}

// Whether view-space point p, bent to direction d, is lost in a hole's shadow: d within its critical impact parameter's
// angle ((3 sqrt 3 / 2) r_s at the hole's distance), and p no nearer than the hole's photon sphere along the line to it
// (a point in front of the hole is seen straight, over the shadow; only one beyond it has no image there, the lens
// having put it behind the hole).
bool relativityStageShadowed(vec3 p, vec3 d) {
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (!relativityFieldHole(k)) continue;
        vec3 c = relativityFieldCentre(k);
        float D = length(c);
        float critical = 2.59807621 * relativityFieldParams(k).x;
        if (D <= critical) continue;
        // (Drawn the long way round, a point near the camera too: its light would have to leave it the other way.)
        if (!relativitySecond() && dot(p, c / D) < D - 1.5 * relativityFieldParams(k).x) continue;
        if (dot(normalize(d), c / D) > cos(asin(critical / D))) return true;
    }
    return false;
}

// The vertex at view-space unbent, bent to view-space bent (checked: the pixel check's relativityChecked): its other
// images, how it's seen by a tube's outline, and whether it's in a shadow (relativityStageT.z 1: the geometry stage
// leaves its triangles out, which else fan across the shadow's edge).
void relativityStageVertex(vec3 unbent, vec3 bent, float checked) {
    // Tubes alone, seen from outside: by a tube's outline, which way the vertex is seen (w 1 in plain view, 2 through a
    // tube); else w 0.
    relativityStageT = vec4(0.0);
    relativityStageH = vec4(0.0);
    float nearest = 1e30;
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (!relativityFieldHole(k)) continue;
        vec3 c = relativityFieldCentre(k);
        float rs = relativityFieldParams(k).x;
        float impact = acos(clamp(dot(normalize(unbent), normalize(c)), -1.0, 1.0)) * length(c) / max(rs, 1e-6);
        if (impact < nearest) {
            nearest = impact;
            relativityStageH = vec4(c, rs);
        }
    }
    if (checked > 0.5 && relativityStageShadowed(unbent, bent)) relativityStageT.z = 1.0;
    bool tubesAlone = relativityWalls() && relativityFieldCount() == 0 && relativityTubeCount() > 0 && relativityTubeRegion(vec3(0.0)) < 0;
    if (tubesAlone && checked > 0.5 && relativityTubeByWall(unbent)) {
        relativityStageT.w = 1.0;
        if (!relativityTubeClear(unbent)) relativityStageT.w = 2.0;
    }
    relativityStageA = vec4(0.0);
    relativityStageB = vec2(0.0);
    relativityStageP = vec2(0.0);
    if (checked > 0.5 && mod(RelativityLensData[8].x, 4.0) < 1.5 && relativityFieldComposed() && relativityTableOn() && relativityStageNear(unbent)) {
        // (Its primary too, as the vertex lens found it: a triangle across a seam between slots is matched across them.)
        relativityStageP = relativityStageImage(unbent, 0);
        relativityStageA = vec4(relativityStageImage(unbent, 1), relativityStageImage(unbent, 2));
        relativityStageB = relativityStageImage(unbent, 3);
    }
}
