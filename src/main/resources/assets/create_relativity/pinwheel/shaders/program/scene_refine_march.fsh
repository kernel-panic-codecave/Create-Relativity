#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The half-resolution paths scene_refine.fsh couldn't interpolate, marched (SparseMarchStage runs this only there).

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome

void main() {
    // Through the vertex lens, only sky pixels are traced; drawn straight, each pixel's path up to what it shows.
    float sceneDist = 1e30;
    if (VertexLensOn == 0) sceneDist = sceneDistance(texCoord);
    SceneHit hit = marchScene(viewRay(texCoord), sceneDist);
    Table = vec4(hit.value, float(hit.outcome));
}
