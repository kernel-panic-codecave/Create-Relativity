#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The bent paths of view rays at reduced resolution (one per texel, through the same output pixel position as the
// lens pass, black_hole_lens.fsh), which the lens pass interpolates, re-marching only where neighbours disagree.

uniform int LensMode; // see black_hole_lens.fsh; 3 (march every pixel) leaves the table unused, so it isn't marched

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome (0: not marched)

void main() {
    Table = vec4(0.0);
    if (HoleCount == 0 || LensMode == 3) return;
    vec3 dir = displayRay(texCoord);
    vec2 ownUv = texCoord;
    if (DisplayTanHalfFov > 0.0) ownUv = frameUv(dir);
    SceneHit hit = marchScene(dir, sceneDistance(ownUv));
    Table = vec4(hit.value, float(hit.outcome));
}
