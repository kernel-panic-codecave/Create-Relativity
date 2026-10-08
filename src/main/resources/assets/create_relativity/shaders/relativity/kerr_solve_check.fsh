// Debug (GameTests): the vertex lens's own bend (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel: the Kerr solve's outcome and the bent clip position.
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;
uniform int Direct; // 1: the Kerr solve itself (as the image map runs it, from the Schwarzschild image), not the map


out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    if (Direct == 2) {
        // The image map at p: its filtered alpha (x) and the nearest texel's alpha (y)
        mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
        vec3 d = viewToGame * p - RelativityLensData[6].xyz;
        float r = length(d);
        float rIn = RelativityLensData[11].z;
        float rOut = RelativityLensData[11].w;
        float s = log(r / rIn) / log(rOut / rIn);
        vec3 size = RELATIVITY_MAP_SIZE;
        vec3 at = vec3(atan(d.z, d.x) / 6.28318530718 + 0.5, ((d.y / r * 0.5 + 0.5) * (size.y - 1.0) + 0.5) / size.y,
            (s * (size.z - 1.0) + 0.5) / size.z);
        float filtered = texture(RelativityKerrMap, at).a;
        float nearest = texelFetch(RelativityKerrMap, ivec3(at * size), 0).a;
        Result = vec4(filtered, nearest, s, 0.0);
        return;
    }
    if (Direct == 1) {
        // x: 1 found, 0 off the table (Schwarzschild), -1 failed, 5 found but at a fold, 6 near the axis, 2 unbent,
        // -2 no Schwarzschild image; w: magnification
        vec3 seen;
        bool nearAxis;
        int image = relativitySchwarzschildImage(p, seen, nearAxis);
        float code = 2.0;
        float magnification = 0.0;
        if (image == -1) code = -2.0;
        else if (image == 1 && nearAxis) code = 6.0;
        else if (image == 1) {
            vec3 kerr;
            float v;
            int found = relativityKerrImage(p, seen, 99.0, kerr, v);
            code = float(found);
            if (relativityLastSolve.x > 4.5) code = 5.0;
            magnification = relativityLastSolve.y;
        }
        Result = vec4(code, 0.0, 0.0, magnification);
        return;
    }
    vec4 bent = relativityBend(Projection * vec4(p, 1.0), Projection);
    // found + 10 * start (when found); bent NDC; the solve's path position v, or 100 when hidden past the far plane
    float found = float(relativityLastFound) + (relativityLastFound == 1 || relativityLastSolve.x > 4.5 ? 10.0 * relativityLastSolve.x : 0.0);
    Result = vec4(found, bent.xy / bent.w, relativityLastSolve.x > 4.5 ? relativityLastSolve.y + 1000.0 : bent.z > bent.w || bent.w <= 0.0 ? 100.0 : relativityLastSolve.y);
}
