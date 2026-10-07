#veil:buffer veil:camera VeilCamera

// The sky all round, as the game (or a shader pack) draws it: a latitude-longitude map that each frame takes the
// frame's colour wherever its direction shows sky on screen, and keeps what it had elsewhere (its framebuffer isn't
// cleared). The lens pass (black_hole_lens.fsh, skyAt) colours paths that escape from it where the frame can't.

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const float SKY_DEPTH = 0.99999; // lens_scene.glsl

void main() {
    float longitude = (texCoord.x - 0.5) * 2.0 * PI;
    float latitude = (texCoord.y - 0.5) * PI;
    vec3 dir = vec3(cos(latitude) * cos(longitude), sin(latitude), cos(latitude) * sin(longitude));
    vec4 clip = VeilCamera.ProjMat * (VeilCamera.ViewMat * vec4(dir, 0.0));
    if (clip.w <= 0.0) discard;
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) discard;
    if (texture(DiffuseDepthSampler, uv).r < SKY_DEPTH) discard;
    fragColor = vec4(texture(DiffuseSampler0, uv).rgb, 1.0);
}
