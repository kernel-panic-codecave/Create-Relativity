// The vertex lens (vertex_lens_core.glsl) at the end of Sodium's terrain vertex shader (see vertex_lens_sodium.json),
// which Veil modifies like vanilla's when Sodium draws the terrain; with what the pixel check needs (pixel_lens.glsl).
#include create_relativity:vertex_lens_core

out vec3 relativityUnbent;
out vec3 relativityBent;
flat out float relativityChecked;
out vec3 relativityCorrection; // the exact solve's correction here (relativityLastCorrection), interpolated

void tail() {
    mat4 relativityInverse = inverse(u_ProjectionMatrix);
    vec4 relativityBefore = relativityInverse * gl_Position;
    relativityChecked = relativityLensed(u_ProjectionMatrix) ? 1.0 : 0.0;
    gl_Position = relativityLens(gl_Position, u_ProjectionMatrix);
    vec4 relativityAfter = relativityInverse * gl_Position;
    relativityUnbent = relativityBefore.xyz / relativityBefore.w;
    relativityBent = relativityAfter.xyz / relativityAfter.w;
    relativityCorrection = relativityLastCorrection;
}
