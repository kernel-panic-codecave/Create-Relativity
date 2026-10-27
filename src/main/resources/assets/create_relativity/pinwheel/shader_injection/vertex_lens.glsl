// The vertex lens (vertex_lens_core.glsl) at the end of vanilla's world vertex shaders (see vertex_lens.json), and what
// the pixel check (pixel_lens.glsl) needs: the vertex's unbent and bent view-space positions, and whether it was bent.
#include create_relativity:vertex_lens_core

out vec3 relativityUnbent;
out vec3 relativityBent;
flat out float relativityChecked;

void tail() {
    mat4 relativityInverse = inverse(ProjMat);
    vec4 relativityBefore = relativityInverse * gl_Position;
    relativityChecked = relativityLensed(ProjMat) ? 1.0 : 0.0;
    gl_Position = relativityLens(gl_Position, ProjMat);
    vec4 relativityAfter = relativityInverse * gl_Position;
    relativityUnbent = relativityBefore.xyz / relativityBefore.w;
    relativityBent = relativityAfter.xyz / relativityAfter.w;
}
