// The end of a tessellated lens twin's vertex shader (TessellationStage): its position left unbent for the patch, its
// bent one passed beside it (each vertex bent once, here; the control shader's bend test and the evaluation shader's
// corners read it). The lens core is included by LensStageProcessor.

out vec4 relativityVBent;
// Its images in the table's slots (table positions, 0 none: slots 0 and 1, then 2 and 3), as the image stage looks for
// them (lens_stage_vertex.glsl), for the evaluation stage to start its new vertices' searches from.
out vec4 relativityVSlotsA;
out vec4 relativityVSlotsB;
// Its second image by the chain of sources (as lens_stage_vertex.glsl finds it: xyz the view direction, w 1 found), for
// the control stage to split edges by how long their second images are.
out vec4 relativityVSecond;

vec2 relativityVTailImage(vec3 p, int s) {
    vec3 seen;
    vec2 uv;
    if (!relativityTableImageIn(p, s, seen) || !relativityTableUv(seen, uv)) return vec2(0.0);
    return uv;
}

bool relativityVTailNear(vec3 p) {
    for (int k = 0; k < relativityFieldCount(); k += 1) {
        if (relativityFieldHole(k) && length(p - relativityFieldCentre(k)) < RelativityLensData[28].w * relativityFieldParams(k).x) return true;
    }
    return false;
}

void relativityStageTail() {
    relativityVBent = relativityLens(gl_Position, ProjMat);
    relativityVSlotsA = vec4(0.0);
    relativityVSlotsB = vec4(0.0);
    relativityVSecond = vec4(0.0);
    vec4 view = inverse(ProjMat) * gl_Position;
    vec3 p = view.xyz / view.w;
    // (Not with sources and no walls: their second images come from the chain, per vertex, not the table.)
    bool chained = relativityFieldCount() > 0 && !relativityWalls();
    if (chained && relativityLensed(ProjMat) && mod(RelativityLensData[8].x, 4.0) < 1.5 && relativityVTailNear(p)) {
        vec3 seen;
        if (relativityFieldImage(p, true, true, seen)) relativityVSecond = vec4(seen, 1.0);
    }
    if (!chained && relativityLensed(ProjMat) && mod(RelativityLensData[8].x, 4.0) < 1.5 && relativityFieldComposed() && relativityTableOn() && relativityVTailNear(p)) {
        vec2 found[4];
        for (int s = 0; s < 4 + relativityRuntimeZero(); s += 1) found[s] = relativityVTailImage(p, s);
        relativityVSlotsA = vec4(found[0], found[1]);
        relativityVSlotsB = vec4(found[2], found[3]);
    }
}
