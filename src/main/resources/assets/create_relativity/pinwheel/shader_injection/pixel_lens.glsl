// The pixel check (vertex_lens_core.glsl's relativityPixelWrong) at the end of the world fragment shaders whose vertex
// shaders the lens bends (pixel_lens.json, pixel_lens_sodium.json): a pixel not where its point appears is discarded.
#include create_relativity:vertex_lens_core

in vec3 relativityUnbent;
in vec3 relativityBent;
flat in float relativityChecked;

void tail() {
    if (relativityChecked > 0.5 && relativityPixelWrong(relativityUnbent, relativityBent)) discard;
    // (The face's outward normal from how its point moves across the screen, turned by which way round it's drawn: a
    // mirrored image is drawn the other way round as well.)
    vec3 normal = cross(dFdx(relativityUnbent), dFdy(relativityUnbent));
    if (!gl_FrontFacing) normal = -normal;
    if (relativityChecked > 0.5 && relativityTableBackFacing(relativityUnbent, relativityBent, normal)) discard;
}
