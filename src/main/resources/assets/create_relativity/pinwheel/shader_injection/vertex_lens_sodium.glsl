// The vertex lens (vertex_lens_core.glsl) at the end of Sodium's terrain vertex shader (see vertex_lens_sodium.json),
// which Veil modifies like vanilla's when Sodium draws the terrain.
#include create_relativity:vertex_lens_core

void tail() {
    gl_Position = relativityLens(gl_Position, u_ProjectionMatrix);
}
