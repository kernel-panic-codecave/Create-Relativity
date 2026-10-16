// Debug (GameTests): the vertex lens's own bend (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel: the Kerr solve's outcome and the bent clip position.
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;
uniform int Direct; // 6: the closed-form exact Kerr image (see below); 1: the Kerr solve itself (as the image map runs it, from the Schwarzschild image), not the map;
                    // 3: the solve from the direction in Guesses
uniform sampler2D Guesses; // view-space directions (Direct 3)


out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    if (Direct == 6) {
        // The closed-form exact image from the approximation's (relativityKerrExactImage): xyz the unit view direction,
        // w 1 found, 0 not (xyz the approximation's), -1 no approximate image either.
        vec3 seen;
        bool nearAxis;
        int image = relativityKerrApprox(p, seen, nearAxis);
        vec3 exact;
        bool found = image == 1 && relativityKerrExactImage(p, seen, exact);
        Result = found ? vec4(exact, 1.0) : vec4(seen, image == 1 ? -relativityKerrExactWhy - 0.001 : -1.0);
        return;
    }
    if (Direct == 4) {
        // Why p is hidden: x the Schwarzschild image (1 found, -1 none, 2 nothing to bend), y 1 near the axis behind the
        // hole, z the map (1 placed, 0 outside it, -1 hidden, 2 not asked), w the filtered map alpha round p.
        vec3 seen;
        bool nearAxis;
        int image = relativitySchwarzschildImage(p, seen, nearAxis);
        vec3 kerr;
        int found = 2;
        if (RelativityLensData[1].z > 0.5) found = relativityKerrMapImage(p, kerr);
        mat3 viewToGame = mat3(RelativityLensData[8].xyz, RelativityLensData[9].xyz, RelativityLensData[10].xyz);
        vec3 d = viewToGame * p - RelativityLensData[6].xyz;
        float r = length(d);
        float rIn = RelativityLensData[11].z;
        float rOut = RelativityLensData[11].w;
        float sLog = log(r / rIn) / log(rOut / rIn);
        vec3 size = RELATIVITY_MAP_SIZE;
        vec3 at = vec3(atan(d.z, d.x) / 6.28318530718 + 0.5, ((d.y / r * 0.5 + 0.5) * (size.y - 1.0) + 0.5) / size.y,
            (sLog * (size.z - 1.0) + 0.5) / size.z);
        Result = vec4(float(image), nearAxis ? 1.0 : 0.0, float(found), texture(RelativityKerrMap, at).a);
        return;
    }
    if (Direct == 3) {
        vec3 guess = normalize(texelFetch(Guesses, ivec2(gl_FragCoord.xy), 0).xyz);
        vec3 kerr;
        float v;
        int found = relativityKerrImage(p, guess, 99.0, kerr, v);
        float code = float(found);
        if (found != 1 && relativityLastSolve.x > 4.5) code = 5.0;
        Result = vec4(code, found == 1 ? acos(clamp(dot(kerr, guess), -1.0, 1.0)) : -1.0, v, relativityLastSolve.y);
        return;
    }
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
            if (found == 1) {
                vec4 clip = Projection * vec4(kerr * length(p), 1.0);
                Result = vec4(code, clip.xy / clip.w, relativityLastSolve.y);
                return;
            }
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
