// The Kerr image map (KerrImageMap.kt; read by vertex_lens_core.glsl, prepended): for one radius layer of points round
// a spinning hole, where each point's primary image is, found on the Kerr path table (relativityKerrImage) and stored as
// how far it's turned from the straight line of sight (game axes) per block of its distance from the camera, which
// interpolates smoothly between texels however far the point is, and right by the camera. Started from last frame's answer when there is one (a few Newton steps instead of a search), from
// the Schwarzschild image otherwise. Alpha 1 where there's an image, 0 where it's hidden (behind the hole, or at a fold:
// the vertex lens hides vertices there and the post pass draws them).

uniform sampler3D PreviousMap; // last frame's map (same layout)
uniform sampler3D PreviousPath; // last frame's path positions (red: where along its path each image was; 99 none)
uniform int PreviousValid;     // 1 if PreviousMap holds a map for this hole
uniform int Layer;             // the radius layer drawn
uniform int DebugMode;         // timing (GameTests): 1 stops after the Schwarzschild image, 2 skips the cold search
uniform int RetryAll;          // 1 once the path table has settled: every earlier failure searches again
uniform int Frame;             // counts frames: texels hidden last frame search again only every 8th, staggered
uniform int SideCheck;         // 1: primary images must be on the point's own side of the hole (ownSide())
uniform int Second;            // 1: secondary images (the light the long way round), from the Schwarzschild secondary

layout(location = 0) out vec4 Result;
layout(location = 1) out float PathPosition; // where along its path the image is, to start the next solve; 99 none
                                             // searched for, -99 a search found no image, -98.5 one at a fold
                                             // (hidden either way), 98 a search fell back to the Schwarzschild image

// A first guess at the view direction of the image of camera-relative point game (game axes), through every source in
// front of it (kerr.glsl's Sources) as thin lenses: the lens equation theta = beta + sum alpha_i(theta) D_ls / D_s,
// alpha_i = 2 r_s / b (r_s = 2 m: a negative mass pulls the image in), solved by damped fixed-point iteration from the
// straight direction (which converges on the outer, primary image). Bubbles aren't lenses: left out.
vec3 weakImage(vec3 game) {
    float distance = length(game);
    vec3 straightDir = game / distance;
    vec3 dir = straightDir;
    for (int iteration = 0; iteration < 8; iteration += 1) {
        vec3 shifted = straightDir;
        for (int i = 0; i < SourceCount; i += 1) {
            vec4 s0 = Sources[3 * i];
            if (s0.w != SOURCE_KERR && s0.w != SOURCE_MASS) continue;
            float rs = 2.0 * Sources[3 * i + 1].x;
            float along = dot(s0.xyz, dir);
            if (along <= 0.0 || along >= distance) continue;
            vec3 toward = s0.xyz - dir * along;
            float b = max(length(toward), 0.5 * abs(rs) + 1e-3);
            shifted -= toward / b * (2.0 * rs / b) * (distance - along) / distance;
        }
        dir = normalize(mix(dir, normalize(shifted), 0.5));
    }
    return dir;
}

// Whether a primary image of camera-relative point game (game axes) seen along dirGame is on the point's own side of
// the hole: its angle round the hole on screen within SIDE_LIMIT of the point's own (both round the line from the
// camera to the hole). A non-spinning hole's primary image is always exactly on that side; a spinning hole's is turned
// a little by frame dragging. Near the axis behind the hole every point has images all round the Einstein ring, and a
// solve that takes whichever it converges to puts neighbouring points on opposite sides: a triangle across the ring,
// "vertex soup" that the pixel check can't catch (each fragment's point is imaged there, just on another branch).
const float SIDE_LIMIT = 1.2; // radians
// (A secondary image, the light the long way round, is on the far side: the same test against the opposite direction.)
bool ownSide(vec3 dirGame, vec3 game) {
    vec3 e = normalize(RelativityLensData[6].xyz);
    vec3 imageSide = dirGame - dot(dirGame, e) * e;
    vec3 pointSide = (game - RelativityLensData[6].xyz) - dot(game - RelativityLensData[6].xyz, e) * e;
    // (Points almost on the axis, or images almost at the hole's centre: no side to keep.)
    if (dot(imageSide, imageSide) < 1e-10 || dot(pointSide, pointSide) < 1e-6) return true;
    float side = Second == 1 ? -1.0 : 1.0;
    return acos(clamp(side * dot(normalize(imageSide), normalize(pointSide)), -1.0, 1.0)) < SIDE_LIMIT;
}

void main() {
    relativityForceSecond = Second == 1;
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
    // Stored per unit of the point's distance from the camera: near the camera the turn grows with the distance, from
    // nothing, in a direction that swings round with the point's; over a block it barely changes (it's the local
    // bending), so it interpolates where the turn itself wouldn't.
    float span = length(game);
    vec3 p = transpose(viewToGame) * game;
    PathPosition = 99.0;

    vec3 seen = normalize(p);
    bool nearAxis = false;
    int image = 1;
    // Round a hole, its Schwarzschild image is the first guess (and its axis cut); round another source, the point's
    // own direction.
    if (RelativityLensData[7].y > 0.5) image = relativitySchwarzschildImage(p, seen, nearAxis);
    if (image == 2) {
        Result = vec4((viewToGame * seen - straight) / span, 1.0);
        return;
    }
    // (Near the axis behind the hole the vertex lens hides vertices itself; the map is solved there too, or the texels
    // left empty would hide the points round them as well.)
    if (DebugMode == 1) {
        Result = vec4((viewToGame * seen - straight) / span, 1.0);
        return;
    }
    vec3 kerr;
    int found = -1;
    vec4 previous = texelFetch(PreviousMap, ivec3(texel, Layer), 0);
    float lastV = PreviousValid == 1 ? texelFetch(PreviousPath, ivec3(texel, Layer), 0).r : 99.0;
    // A search that came to nothing last frame (the costliest kind) isn't repeated but every 8th frame: hidden stays
    // hidden, a fallback stays on the Schwarzschild image.
    bool again = RetryAll == 1 || ((texel.x + 3 * texel.y + 5 * Layer + Frame) & 7) == 0;
    if (lastV < -98.0 && !again) {
        Result = vec4(0.0);
        PathPosition = -99.0;
        return;
    }
    if (lastV > 97.5 && lastV < 98.5 && !again && image == 1) {
        Result = vec4((viewToGame * seen - straight) / span, 1.0);
        PathPosition = 98.0;
        return;
    }
    // The starting guesses, tried in turn until one finds the image (one call site: each one inlined the whole solve,
    // and the program took the driver 35 s to compile):
    // last frame's answer; the thin-lens guess through every source; the image of the point just outside this one (the
    // next layer out, drawn first: followed in from far away, where every point's image is its own direction, this is
    // the primary image wherever it changes continuously, whatever bends the light); the Schwarzschild image.
    vec3 seeds[4];
    float warmV[4];
    int count = 0;
    if (PreviousValid == 1 && previous.a > 0.5) {
        seeds[count] = transpose(viewToGame) * normalize(straight + previous.rgb * span);
        warmV[count] = lastV;
        count += 1;
    }
    if (image == 1 && SourceCount > 0 && Second == 0) { // (the thin-lens guess is the primary image)
        seeds[count] = transpose(viewToGame) * weakImage(game);
        warmV[count] = 99.0;
        count += 1;
    }
    if (image == 1 && Layer + 1 < int(size.z)) {
        vec4 outer = texelFetch(PreviousMap, ivec3(texel, Layer + 1), 0);
        if (outer.a > 0.5) {
            float outerV = texelFetch(PreviousPath, ivec3(texel, Layer + 1), 0).r;
            seeds[count] = transpose(viewToGame) * normalize(straight + outer.rgb * span);
            warmV[count] = abs(outerV) > 14.0 ? 99.0 : outerV;
            count += 1;
        }
    }
    if (image == 1 && DebugMode != 2) {
        seeds[count] = seen;
        warmV[count] = 99.0;
        count += 1;
    }
    // A neighbour's image (last frame's, the same layer: azimuth and polar neighbours), for a texel whose own search
    // failed: accepted only if continuous with it (below), not an image on another branch.
    vec3 neighbourTurn = vec3(0.0);
    bool fromNeighbour = false;
    if (count < 4 && PreviousValid == 1 && previous.a < 0.5) {
        ivec3 offsets[4] = ivec3[](ivec3(1, 0, 0), ivec3(-1, 0, 0), ivec3(0, 1, 0), ivec3(0, -1, 0));
        for (int k = 0; k < 4; k += 1) {
            ivec3 at = ivec3(texel, Layer) + offsets[k];
            at.x = at.x - int(size.x) * int(floor(float(at.x) / size.x));
            if (at.y < 0 || at.y >= int(size.y)) continue;
            vec4 neighbour = texelFetch(PreviousMap, at, 0);
            if (neighbour.a < 0.5) continue;
            seeds[count] = transpose(viewToGame) * normalize(straight + neighbour.rgb * span);
            warmV[count] = texelFetch(PreviousPath, at, 0).r;
            if (abs(warmV[count]) > 14.0) warmV[count] = 99.0;
            neighbourTurn = neighbour.rgb;
            fromNeighbour = true;
            count += 1;
            break;
        }
    }
    float v = 99.0;
    for (int k = 0; k < count; k += 1) {
        found = relativityKerrImage(p, seeds[k], warmV[k], kerr, v);
        // From the neighbour's image: only an image whose turn (per block) is close to the neighbour's is the same
        // branch continued (within a quarter of the turn, or a hundredth of a radian over the point's distance).
        if (found == 1 && fromNeighbour && k == count - 1) {
            vec3 turn = (viewToGame * kerr - straight) / span;
            if (length(turn - neighbourTurn) > max(0.25 * length(neighbourTurn), 0.01 / span)) found = -1;
        }
        if (found == 1) break;
    }
    if (RelativityLensData[1].w > 3.5 && RelativityLensData[1].w < 4.5 && found < 0 && image == 1) found = 0; // debug 4: failures at the Schwarzschild image
    if (RelativityLensData[1].w > 0.5 && RelativityLensData[1].w < 1.5 && found == 0) found = -1; // debug 1: only placed
    if (found == 1 && SideCheck == 1 && !ownSide(viewToGame * kerr, game)) found = -1;
    if (found == 1) {
        Result = vec4((viewToGame * kerr - straight) / span, 1.0);
        PathPosition = v;
    } else if (found == 0 && image == 1) {
        Result = vec4((viewToGame * seen - straight) / span, 1.0); // off the path table: the Schwarzschild image stands
        PathPosition = 98.0;
    } else {
        Result = vec4(0.0);
        PathPosition = relativityLastSolve.x > 4.5 ? -98.5 : -99.0; // (-98.5: found, but at a fold)
    }
}
