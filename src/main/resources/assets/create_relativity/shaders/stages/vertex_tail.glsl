// The end of a lens twin's vertex shader (ImageStage: its own vertex shader's copy, for render types drawn as strips or
// fans, untessellated): the vertex bent, and what the geometry stage needs (its unbent and bent view-space positions,
// whether it was bent, the per-vertex part: lens_stage_vertex.glsl, included by LensStageProcessor).

out vec3 relativityStageUnbent;
out vec3 relativityStageBent;
flat out float relativityStageChecked;

void relativityStageTail() {
    mat4 relativityInverse = inverse(ProjMat);
    vec4 relativityBefore = relativityInverse * gl_Position;
    relativityStageChecked = 0.0;
    if (relativityLensed(ProjMat)) relativityStageChecked = 1.0;
    gl_Position = relativityLens(gl_Position, ProjMat);
    vec4 relativityAfter = relativityInverse * gl_Position;
    relativityStageUnbent = relativityBefore.xyz / relativityBefore.w;
    relativityStageBent = relativityAfter.xyz / relativityAfter.w;
    relativityStageVertex(relativityStageUnbent, relativityStageBent, relativityStageChecked);
}
