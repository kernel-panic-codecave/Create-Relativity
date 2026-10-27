// The lens pass's view of the frame (black_hole_lens.fsh): the view ray through a pixel, the distance to what's drawn
// there, the level's fog, and the nearest holes. Needs VeilCamera.

uniform sampler2D DiffuseDepthSampler;
uniform float LensFogStart;   // the level's terrain fog this frame (vanilla's linear fog). Not vanilla's uniform names:
uniform float LensFogEnd;     // Veil sets those from the render state when binding, and by the post pass there's no fog
uniform int LensFogShape;     // 0 sphere, 1 cylinder
uniform vec4 LensFogColor;    // the terrain fog's colour (alpha its strength)
uniform int ProfileFlags;     // profiling only (0 normally): 16 draws fogged geometry as drawn; 128 a plain sky; 256 no disk
#define MAX_HOLES 8
uniform int HoleCount;
uniform vec4 Holes[MAX_HOLES]; // xyz: centre relative to the camera (blocks), w: Schwarzschild radius (blocks)

const float SKY_DEPTH = 0.99999;

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

// The screen position of direction dir, and whether it's on screen (in front, inside the frame).
bool screenUv(vec3 dir, out vec2 uv) {
    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    if (clip.w <= 0.0) return false;
    uv = clip.xy / clip.w * 0.5 + 0.5;
    return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

// Distance to the surface drawn at uv; the sky (nothing drawn) counts as infinitely far.
float sceneDistance(vec2 uv) {
    float depth = texture(DiffuseDepthSampler, uv).r;
    if (depth >= SKY_DEPTH) return 1e30;
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float distance = length(view.xyz / view.w);
    // Wholly in the fog, drawn geometry looks just like sky, and near a fold the vertex lens can leave a gap in nearer
    // ground through which far ground shows (its own images there hidden): such pixels are traced like sky, so the
    // nearer ground the light really meets wins.
    if (distance >= LensFogEnd && LensFogColor.a > 0.0 && (ProfileFlags & 16) == 0) return 1e30;
    return distance;
}

// Angle between neighbouring output pixels (radians).
float pixelAngle() {
    return 2.0 / VeilCamera.ProjMat[1][1] / float(textureSize(DiffuseDepthSampler, 0).y);
}

// Vanilla's fog distance of camera-relative point p.
float fogDistance(vec3 p) {
    if (LensFogShape == 0) return length(p);
    return max(length(p.xz), abs(p.y));
}
