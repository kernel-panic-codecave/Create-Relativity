#veil:buffer veil:camera VeilCamera
#include create_relativity:lens_scene

// The half-resolution paths scene_refine.fsh couldn't interpolate, marched (SparseMarchStage runs this only there).

in vec2 texCoord;
out vec4 Table; // value (SceneHit), outcome

void main() {
    SceneHit hit = marchScene(viewRay(texCoord), 1e30);
    Table = vec4(hit.value, float(hit.outcome));
}
