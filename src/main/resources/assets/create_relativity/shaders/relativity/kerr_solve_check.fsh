// Debug (GameTests): the vertex lens's own bend (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel: the Kerr solve's outcome and the bent clip position.
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;

out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    vec4 bent = relativityBend(Projection * vec4(p, 1.0), Projection);
    Result = vec4(float(relativityLastFound), bent.xy / bent.w, bent.z > bent.w ? 1.0 : 0.0);
}
