// Debug (GameTests): the vertex lens's own maths (vertex_lens_core.glsl, prepended) for a list of view-space points,
// one per output texel (LensCheck).
uniform sampler2D Points; // view-space positions
uniform mat4 Projection;
uniform int Direct; // 0: the bend; 4: why a point is hidden; 7: the tubes' aim; 8: the field lens's sky; 9: its image

out vec4 Result;

void main() {
    vec3 p = texelFetch(Points, ivec2(gl_FragCoord.xy), 0).xyz;
    if (Direct == 9) {
        // The field lens's image of p (relativityFieldImage): x 1 found, -1 not; y the last solve's miss (pixels; -1 none
        // ran); z the chain guess's angle from the result (pixels).
        vec3 seen;
        bool found = relativityFieldImage(p, false, true, seen);
        Result = vec4(found ? 1.0 : -1.0, relativitySolveResidual, 0.0, 0.0);
        return;
    }
    if (Direct == 8) {
        // The field lens's sky (relativityFieldSky) for the view direction p: xyz where its light ends heading, w 1, or
        // -1 captured.
        vec3 escape;
        bool out_ = relativityFieldSky(normalize(p), escape);
        Result = vec4(escape, out_ ? 1.0 : -1.0);
        return;
    }
    if (Direct == 7) {
        // The field lens's tubes alone (relativityTubeAim): xyz where p is seen, w 1 found, -1 hidden.
        vec3 seen;
        bool found = relativityTubeAim(p, seen);
        Result = vec4(seen, found ? 1.0 : -1.0);
        return;
    }
    if (Direct == 4) {
        // Why p is hidden: x the Schwarzschild image (1 found, -1 none, 2 nothing to bend), y 1 near the axis behind the
        // hole.
        vec3 seen;
        bool nearAxis;
        int image = relativitySchwarzschildImage(p, seen, nearAxis);
        Result = vec4(float(image), nearAxis ? 1.0 : 0.0, 0.0, 0.0);
        return;
    }
    vec4 bent = relativityBend(Projection * vec4(p, 1.0), Projection);
    // x: 1 placed, -1 hidden (past the far plane); yz the bent NDC
    bool hidden = bent.z > bent.w || bent.w <= 0.0;
    Result = vec4(hidden ? -1.0 : 1.0, bent.xy / bent.w, 0.0);
}
