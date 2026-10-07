#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The bent paths of view rays at reduced resolution (one per texel, through the same output pixel position as the
// lens pass, black_hole_lens.fsh), for the sky pixels, which the lens pass interpolates, re-marching only where
// neighbours disagree.

uniform int LensMode; // see black_hole_lens.fsh; 3 (march every pixel) leaves the table unused, so it isn't marched;
                      // 5 skips it (profiling)

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome (0: not marched)

void main() {
    Table = vec4(0.0);
    if (HoleCount == 0 || LensMode == 3 || LensMode == 5) return;
    // Only sky pixels use the table: where the world is drawn, the vertex lens already put it in place.
    float sceneDist = sceneDistance(texCoord);
    if (sceneDist < 1e29) return;
    SceneHit hit = marchScene(viewRay(texCoord), sceneDist);
    Table = vec4(hit.value, float(hit.outcome));
}
