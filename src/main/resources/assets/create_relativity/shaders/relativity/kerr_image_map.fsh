// The Kerr image map (KerrImageMap.kt; read by vertex_lens_core.glsl, prepended): for one radius layer of points round
// a spinning hole, where each point's primary image is, found on the Kerr path table (relativityKerrImage) and stored as
// how far it's turned from the straight line of sight (game axes), which interpolates smoothly between texels however
// far the point is. Started from last frame's answer when there is one (a few Newton steps instead of a search), from
// the Schwarzschild image otherwise. Alpha 1 where there's an image, 0 where it's hidden (behind the hole, or at a fold:
// the vertex lens hides vertices there and the post pass draws them).

uniform sampler3D PreviousMap; // last frame's map (same layout)
uniform sampler3D PreviousPath; // last frame's path positions (red: where along its path each image was; 99 none)
uniform int PreviousValid;     // 1 if PreviousMap holds a map for this hole
uniform int Layer;             // the radius layer drawn
uniform int DebugMode;         // timing (GameTests): 1 stops after the Schwarzschild image, 2 skips the cold search
uniform int Frame;             // counts frames: texels hidden last frame search again only every 8th, staggered

layout(location = 0) out vec4 Result;
layout(location = 1) out float PathPosition; // where along its path the image is, to start the next solve; 99 none
                                             // searched for, -99 a search found it hidden, 98 a search fell back to
                                             // the Schwarzschild image

void main() {
    vec3 size = RELATIVITY_MAP_SIZE;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float azimuth = ((float(texel.x) + 0.5) / size.x - 0.5) * 6.28318530718;
    float cosine = float(texel.y) / (size.y - 1.0) * 2.0 - 1.0;
    float sine = sqrt(max(1.0 - cosine * cosine, 0.0));
    float rIn = RelativityLensData[11].z;
    float rOut = RelativityLensData[11].w;
    float radius = rIn * pow(rOut / rIn, float(Layer) / (size.z - 1.0));
    vec3 d = radius * vec3(sine * cos(azimuth), cosine, sine * sin(azimuth));
    mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
    vec3 game = RelativityLensData[6].xyz + d; // camera-relative
    vec3 straight = normalize(game);
    vec3 p = transpose(viewToGame) * game;
    PathPosition = 99.0;

    vec3 seen;
    bool nearAxis;
    int image = relativitySchwarzschildImage(p, seen, nearAxis);
    if (image == 2) {
        Result = vec4(viewToGame * seen - straight, 1.0);
        return;
    }
    if (nearAxis) {
        Result = vec4(0.0);
        return;
    }
    if (DebugMode == 1) {
        Result = vec4(viewToGame * seen - straight, 1.0);
        return;
    }
    vec3 kerr;
    int found = -1;
    vec4 previous = texelFetch(PreviousMap, ivec3(texel, Layer), 0);
    float lastV = PreviousValid == 1 ? texelFetch(PreviousPath, ivec3(texel, Layer), 0).r : 99.0;
    // A search that came to nothing last frame (the costliest kind) isn't repeated but every 8th frame: hidden stays
    // hidden, a fallback stays on the Schwarzschild image.
    bool again = ((texel.x + 3 * texel.y + 5 * Layer + Frame) & 7) == 0;
    if (lastV < -98.0 && !again) {
        Result = vec4(0.0);
        PathPosition = -99.0;
        return;
    }
    if (lastV > 97.5 && lastV < 98.5 && !again && image == 1) {
        Result = vec4(viewToGame * seen - straight, 1.0);
        PathPosition = 98.0;
        return;
    }
    float v = 99.0;
    if (PreviousValid == 1 && previous.a > 0.5) {
        found = relativityKerrImage(p, transpose(viewToGame) * normalize(straight + previous.rgb), lastV, kerr, v);
    }
    if (found != 1 && image == 1 && DebugMode != 2) found = relativityKerrImage(p, seen, 99.0, kerr, v);
    if (RelativityLensData[1].w > 3.5 && found < 0 && image == 1) found = 0; // debug 4: failures at the Schwarzschild image
    if (RelativityLensData[1].w > 0.5 && RelativityLensData[1].w < 1.5 && found == 0) found = -1; // debug 1: only placed
    if (found == 1) {
        Result = vec4(viewToGame * kerr - straight, 1.0);
        PathPosition = v;
    } else if (found == 0 && image == 1) {
        Result = vec4(viewToGame * seen - straight, 1.0); // off the path table: the Schwarzschild image stands
        PathPosition = 98.0;
    } else {
        Result = vec4(0.0);
        PathPosition = -99.0;
    }
}
