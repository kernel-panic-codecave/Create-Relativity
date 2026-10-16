// The pixel check (vertex_lens_core.glsl's relativityPixelWrong) at the end of the world fragment shaders whose vertex
// shaders the lens bends (pixel_lens.json, pixel_lens_sodium.json): a pixel not where its point appears is discarded.
#include create_relativity:vertex_lens_core

in vec3 relativityUnbent;
in vec3 relativityBent;
flat in float relativityChecked;
in vec3 relativityCorrection;

void tail() {
    relativityPixelStage = true;
    relativityPixelCorrection = relativityCorrection;
    if (relativityChecked > 0.5 && relativityPixelWrong(relativityUnbent, relativityBent)) discard;
}
