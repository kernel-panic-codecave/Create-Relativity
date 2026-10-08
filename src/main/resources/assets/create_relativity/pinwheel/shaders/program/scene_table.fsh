#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The bent paths of view rays at quarter resolution (one per texel, through the same output pixel position as the
// lens pass, black_hole_lens.fsh), wherever sky shows near the texel: scene_refine.fsh interpolates them to half
// resolution, marching only where neighbours disagree.

uniform int LensMode; // see black_hole_lens.fsh; 3 (march every pixel) leaves the table unused, so it isn't marched;
                      // 5 skips it (profiling)

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome (0: not marched)

void main() {
    Table = vec4(0.0);
    if ((HoleCount == 0 && SourceCount == 0) || LensMode == 3 || LensMode == 5) return;
    // Only sky pixels use the table (where the world is drawn, the vertex lens already put it in place), but every
    // texel round them is marched, so a sky pixel beside drawn ground still has neighbours to interpolate between.
    if (!skyNear(texCoord, 6.0)) return;
    SceneHit hit = marchScene(viewRay(texCoord), 1e30);
    Table = vec4(hit.value, float(hit.outcome));
}
