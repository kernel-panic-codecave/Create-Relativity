#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The bent paths of view rays at half resolution, from the quarter-resolution table (scene_table.fsh): interpolated
// where its neighbours agree; where they don't (an edge in what the paths meet), depth 1, and scene_refine_march.fsh
// marches just those (SparseMarchStage). The lens pass interpolates this one in turn, and takes the nearest entry where
// it can't, so no path is marched at full resolution.

uniform sampler2D SceneTable; // scene_table.fsh, quarter resolution
uniform int LensMode;         // see black_hole_lens.fsh; 3 and 5 leave the tables unused

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome (0: not marched)

void main() {
    Table = vec4(0.0);
    gl_FragDepth = 0.0;
    if (HoleCount == 0 || LensMode == 3 || LensMode == 5) return;
    if (VertexLensOn == 1 && !skyNear(texCoord, 3.0)) return;
    SceneHit hit;
    SceneHit nearest;
    if (!sceneTableAt(SceneTable, texCoord, hit, nearest)) {
        hit = nearest;
        if ((ProfileFlags & 1024) == 0) gl_FragDepth = 1.0; // to march (timing: 1024 never marches)
    }
    Table = vec4(hit.value, float(hit.outcome));
}
