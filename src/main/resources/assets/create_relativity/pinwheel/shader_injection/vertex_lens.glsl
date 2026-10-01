// High-quality lens: each world vertex moves to where its light appears (the primary image), keeping its true distance,
// so depth testing sorts what's in front. Injected at the end of vanilla world vertex shaders (see vertex_lens.json).
// Only draws using the level's own projection are lensed: not the hand, GUI items or the environment capture.

layout(std140) uniform RelativityLens {
    vec4 RelativityLensHole;   // xyz centre relative to the camera (blocks), w Schwarzschild radius (0 = off)
    vec4 RelativityLensParams; // x: 1 while the level is drawn with lensing on
    mat4 RelativityLensProj;   // the level's projection this frame
};
uniform sampler3D RelativityImages; // SchwarzschildImages: launch-angle correction over (sqrt u0, sqrt uP, phi / pi)

bool relativityLevelProjection(mat4 proj) {
    mat4 d = proj - RelativityLensProj;
    float diff = abs(d[0][0]) + abs(d[1][1]) + abs(d[2][2]) + abs(d[3][2]) + abs(d[2][3]) + abs(d[2][0]) + abs(d[2][1]);
    return diff < 1e-4;
}

vec4 relativityLens(vec4 clip, mat4 proj, mat4 modelView) {
    if (RelativityLensParams.x < 0.5 || RelativityLensHole.w <= 0.0) return clip;
    if (!relativityLevelProjection(proj)) return clip;
    vec4 view = inverse(proj) * clip;
    vec3 p = (inverse(modelView) * vec4(view.xyz / view.w, 1.0)).xyz;
    vec3 h = RelativityLensHole.xyz;
    float rs = RelativityLensHole.w;
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
    if (sideLength < 1e-5) return clip;
    vec3 e2 = side / sideLength;
    float phi = acos(cosPhi);

    vec3 size = vec3(64.0, 64.0, 128.0);
    vec3 axes = vec3(sqrt(u0), sqrt(uP), phi / 3.14159265);
    float correction = texture(RelativityImages, (axes * (size - 1.0) + 0.5) / size).r;
    if (correction < -1.0) return clip; // no primary image: hidden behind the hole, the shadow covers it
    float rcU = 1.0 / u0;
    float rpU = 1.0 / uP;
    float psi = atan(rpU * sin(phi), rcU - rpU * cos(phi)) + correction;
    vec3 seen = -cos(psi) * e1 + sin(psi) * e2;
    return proj * modelView * vec4(seen * length(p), 1.0);
}

void tail() {
    gl_Position = relativityLens(gl_Position, ProjMat, ModelViewMat);
}
