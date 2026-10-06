// High-quality lens: each world vertex moves to where its light appears (the primary image), keeping its true distance,
// so depth testing sorts what's in front. Injected at the end of vanilla world vertex shaders (see vertex_lens.json).
// Only draws using the level's own projection are lensed: not the hand, GUI items or other level views.

// One array, not named members: Veil lists a program's active uniforms and warns about block members it can't locate,
// except arrays (which it takes for another mod's uniform block). Same std140 layout as the members would have:
// [0] the hole: xyz centre relative to the camera in view space (blocks), w Schwarzschild radius (0 = off)
// [1] x: 1 while the level is drawn with lensing on; y: 1 skips the image table (debug)
// [2..5] the level's projection this frame, by column
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[6];
};
uniform sampler3D RelativityImages; // SchwarzschildImages.forFiltering over (sqrt u0, sqrt uP, phi / pi): r the
                                    // launch-angle correction, g whether there's an image (interpolated)

const float RELATIVITY_AXIS_CUT = 2.0;  // blocks: larger than a block face's diagonal
const float RELATIVITY_BEYOND = 1e4;   // NDC depth of hidden vertices: far past the far plane

bool relativityLevelProjection(mat4 proj) {
    mat4 d = proj - mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]);
    float diff = abs(d[0][0]) + abs(d[1][1]) + abs(d[2][2]) + abs(d[3][2]) + abs(d[2][3]) + abs(d[2][0]) + abs(d[2][1]);
    return diff < 1e-4;
}

// Works in view space (camera at the origin): the projection alone recovers that from the clip position, whatever
// space the draw's own vertices and model-view matrix use (chunk-relative, world, posed).
vec4 relativityLens(vec4 clip, mat4 proj) {
    if (RelativityLensData[1].x < 0.5 || RelativityLensData[0].w <= 0.0) return clip;
    if (!relativityLevelProjection(proj)) return clip;
    vec4 view = inverse(proj) * clip;
    vec3 p = view.xyz / view.w;
    vec3 h = RelativityLensData[0].xyz;
    float rs = RelativityLensData[0].w;
    float rc = length(h);
    vec3 fromHole = p - h;
    float rp = length(fromHole);
    if (rc <= rs || rp <= rs) return clip;
    float u0 = max(rs / rc, 1e-4);
    float uP = max(rs / rp, 1e-4);
    if (u0 < 2e-3 && uP < 2e-3) return clip; // bending under a few milliradians
    vec3 e1 = -h / rc; // hole to camera
    vec3 radial = fromHole / rp;
    float cosPhi = clamp(dot(radial, e1), -1.0, 1.0);
    vec3 side = radial - cosPhi * e1;
    float sideLength = length(side);
    vec3 e2 = side / max(sideLength, 1e-6);
    float phi = acos(cosPhi);

    vec3 size = vec3(64.0, 64.0, 128.0);
    vec3 axes = vec3(sqrt(u0), sqrt(uP), phi / 3.14159265);
    vec2 image = texture(RelativityImages, (axes * (size - 1.0) + 0.5) / size).rg;
    if (RelativityLensData[1].y > 0.5) return proj * vec4(p, 1.0);
    // No primary image (hidden behind the hole), or near the camera-hole line behind the hole (images round the
    // Einstein ring at the azimuth of their side of the line, so a triangle across it would be stretched over the whole
    // ring): such a vertex gets a depth far past the far plane (toward the hole on screen), so a triangle reaching it
    // passes the far plane within a hair of its other vertices: clipped there, or with depth clamping on, drawn at
    // the far plane, which the post pass takes for sky and redraws (the ring) from the capture.
    bool nearAxis = cosPhi < 0.0 && rp * sideLength < RELATIVITY_AXIS_CUT;
    if (image.g < 0.5 || nearAxis) {
        vec4 hidden = proj * vec4(-e1 * length(p), 1.0);
        hidden.z = RELATIVITY_BEYOND * abs(hidden.w);
        return hidden;
    }
    float rcU = 1.0 / u0;
    float rpU = 1.0 / uP;
    float local = atan(rpU * sin(phi), rcU - rpU * cos(phi)) + image.r;
    // The table's angle is in a static observer's frame (radial lengths stretched by 1 / sqrt(1 - u0)); the camera's
    // directions are coordinate directions, like the rest of the lens.
    float psi = atan(sin(local), cos(local) * sqrt(1.0 - u0));
    vec3 seen = -cos(psi) * e1 + sin(psi) * e2;
    return proj * vec4(seen * length(p), 1.0);
}

void tail() {
    gl_Position = relativityLens(gl_Position, ProjMat);
}
