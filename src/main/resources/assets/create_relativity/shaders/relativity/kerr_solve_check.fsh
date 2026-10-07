// Debug (GameTests): the vertex lens's own bend (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel: the Kerr solve's outcome and the bent clip position.
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;

out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    vec4 bent = relativityBend(Projection * vec4(p, 1.0), Projection);
    // found + 10 * start (when found); bent NDC; the solve's path position v, or 100 when hidden past the far plane
    float found = float(relativityLastFound) + (relativityLastFound == 1 || relativityLastSolve.x > 4.5 ? 10.0 * relativityLastSolve.x : 0.0);
    Result = vec4(found, bent.xy / bent.w, relativityLastSolve.x > 4.5 ? relativityLastSolve.y + 1000.0 : bent.z > bent.w || bent.w <= 0.0 ? 100.0 : relativityLastSolve.y);
}
