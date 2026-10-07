// The vertex lens (vertex_lens_core.glsl) at the end of vanilla's world vertex shaders (see vertex_lens.json).
#include create_relativity:vertex_lens_core

void tail() {
    gl_Position = relativityLens(gl_Position, ProjMat);
}
