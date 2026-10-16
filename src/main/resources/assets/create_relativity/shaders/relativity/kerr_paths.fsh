// Light paths for the vertex lens (KerrPaths.kt; read by vertex_lens_core.glsl, which is prepended with kerr.glsl): for
// each table texel (a direction from the camera, relativityTableDir: every direction, wherever the camera looks), a ray
// through every source together (kerr.glsl's Sources and spacetimeBackward), followed back in time, recorded as where it
// is seen from the lens centre (the dominant source) at fixed points along it.
//
// A path is parameterised by how much it has changed, m = integral of |d turn| + |dr| / r (the angle its direction from
// the centre turns through, whichever way: a spinning hole swings light out of its starting plane, over its poles, and the
// angle in that plane alone missed it; and the distance from the centre), around its closest approach to the centre m*: 29 samples at
// m = m* -+ M k / 14 for k = 14 .. 1 (M: m* coming in, the rest of the path going out), so they're as close in every
// stretch, however the light bends there. (By distance along the path, a ray winding round the photon sphere spent its
// whole turn between two samples, a radian or more apart round the hole: interpolating across that put its image ten
// pixels off.) Nothing assumes one turning point or attraction: a path may come close more than once (two
// sources, a warp bubble) or be pushed away (negative energy); s* is just where it's closest. Two marches: the first
// finds s*, the path's length and its fate, the second records the samples. Every texel's ray is traced, however far
// it passes, so the vertex lens never switches method on screen (a visible seam).
//
// Each sample is the direction from the centre in the ray's own frame (pathAngles): the angle round the centre in the
// plane of the camera, the centre and the ray's start (from the camera's direction, unwrapped, so it keeps counting
// past behind the centre), and how far out of that plane the path has strayed; and its distance from the centre. The
// angle is stored at full precision, the offset and distance as two halves of the other channel (relative precision
// 1e-3: well under a pixel for both).

uniform vec3 CentreGame;  // the lens centre, camera-relative, game axes
uniform float CentreScale; // its size (r_s of a hole; 2|m| of a mass), for the table's knee
uniform vec2 TableSize;   // texels: azimuth, angle from the centre
uniform float PathRadius; // where paths start (if the camera's outside it) and end: past everything drawn
uniform int Group;        // which 8 of the table's 16 layers this draw writes (the most one draw can)
uniform sampler2D Head;   // group 1: group 0's head (the table's layer 0: what the first march found), copied out

in vec2 texCoord;
// Group 0 (layers 0-7): r_min, status (0 unmarched, 1 escapes, 2 captured, 3 incomplete), the closest approach; then
// in 1, 2 | in 3, 4 | in 5, 6 | in 7, out 1 | out 2, 3 | out 4, 5 | out 6, 7. Group 1 (layers 8-15): in 8, 9 | in 10, 11
// | in 12, 13 | in 14 (the start), out 8 | out 9, 10 | out 11, 12 | out 13, 14 (the end sphere) | unused.
layout(location = 0) out vec4 Path0;
layout(location = 1) out vec4 Path1;
layout(location = 2) out vec4 Path2;
layout(location = 3) out vec4 Path3;
layout(location = 4) out vec4 Path4;
layout(location = 5) out vec4 Path5;
layout(location = 6) out vec4 Path6;
layout(location = 7) out vec4 Path7;

const int PATH_STEPS = 420;

vec3 pathE1 = vec3(1.0, 0.0, 0.0);     // centre to camera (set in main; initialized against driver warnings)
vec3 pathSide = vec3(0.0, 1.0, 0.0);   // in the ray's plane, perpendicular to pathE1, toward the ray's side
vec3 pathNormal = vec3(0.0, 0.0, 1.0); // the ray's plane's normal
float pathTurn = 0.0; // the last angle, for unwrapping

// A sample: the angle round the centre in the ray's plane (unwrapped against the last one), and the elevation out of it
// (an angle: its sine loses precision at half precision near the plane's poles, where spinning holes swing light) with
// the distance from the centre packed into the second channel, of the photon at x (camera-relative, game axes).
vec2 pathSample(vec3 x) {
    vec3 d = x - CentreGame;
    float r = length(d);
    vec3 n = d / max(r, 1e-6);
    float angle = atan(dot(n, pathSide), dot(n, pathE1));
    angle += 6.28318530718 * floor((pathTurn - angle) / 6.28318530718 + 0.5);
    pathTurn = angle;
    return vec2(angle, relativityPackPair(asin(clamp(dot(n, pathNormal), -1.0, 1.0)), r));
}

// How much the path changes from x0 to x1 (camera-relative): the m of the samples' spacing. (The turn as 2 asin(chord / 2):
// acos of a dot product near 1 loses the small angles in single precision.)
float pathChange(vec3 x0, vec3 x1) {
    vec3 d0 = x0 - CentreGame;
    vec3 d1 = x1 - CentreGame;
    float r0 = length(d0);
    float r1 = length(d1);
    float turn = 2.0 * asin(min(0.5 * length(d1 / max(r1, 1e-6) - d0 / max(r0, 1e-6)), 1.0));
    return turn + abs(r1 - r0) / max(min(r0, r1), 1e-3);
}

// Whether x is behind some hole's horizon (with a margin: traced back, light only nears it).
bool pathCaptured(vec3 x) {
    for (int i = 0; i < SourceCount; i += 1) {
        vec4 s0 = Sources[3 * i];
        if (s0.w != SOURCE_KERR) continue;
        vec4 s1 = Sources[3 * i + 1];
        float h;
        vec3 l;
        float horizon = s1.x + sqrt(max(s1.x * s1.x - s1.y * s1.y, 0.0));
        if (kerrProfile(toLocal(x - s0.xyz), s1.x, s1.y, h, l) < 1.02 * horizon) return true;
    }
    return false;
}

// A coordinate step length at x: a tenth of the distance to the nearest source (no less than half a hole's mass, or a
// mass's softening), and a sixteenth of a bubble's wall near one.
float pathStep(vec4 x4) {
    float step = 1e30;
    for (int i = 0; i < SourceCount; i += 1) {
        vec4 s0 = Sources[3 * i];
        vec4 s1 = Sources[3 * i + 1];
        if (s0.w == SOURCE_KERR) {
            step = min(step, 0.05 * max(length(x4.xyz - s0.xyz), 0.5 * s1.x));
        } else if (s0.w == SOURCE_MASS) {
            step = min(step, 0.05 * max(length(x4.xyz - s0.xyz), max(s1.y, 2.0 * abs(s1.x))));
        } else if (s0.w == SOURCE_BUBBLE) {
            float r = length(x4.xyz - (s0.xyz + s1.z * Sources[3 * i + 2].xyz * x4.w));
            float wall = 1.0 / max(s1.y, 1e-3);
            step = min(step, max(0.5 * abs(r - s1.x), 0.25 * wall));
        }
    }
    return min(step, 0.05 * PathRadius);
}

// One RK4 step of light followed back in time, of about coordinate length ds.
void pathAdvance(inout vec4 x4, inout vec4 p4, float ds) {
    vec4 k1x;
    vec4 k1p;
    spacetimeBackward(x4, p4, k1x, k1p);
    float dl = ds / max(length(k1x.xyz), 1e-6);
    vec4 k2x;
    vec4 k2p;
    spacetimeBackward(x4 + 0.5 * dl * k1x, p4 + 0.5 * dl * k1p, k2x, k2p);
    vec4 k3x;
    vec4 k3p;
    spacetimeBackward(x4 + 0.5 * dl * k2x, p4 + 0.5 * dl * k2p, k3x, k3p);
    vec4 k4x;
    vec4 k4p;
    spacetimeBackward(x4 + dl * k3x, p4 + dl * k3p, k4x, k4p);
    x4 += dl / 6.0 * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
    p4 += dl / 6.0 * (k1p + 2.0 * k2p + 2.0 * k3p + k4p);
}

void main() {
    Path0 = vec4(0.0); Path1 = vec4(0.0); Path2 = vec4(0.0); Path3 = vec4(0.0);
    Path4 = vec4(0.0); Path5 = vec4(0.0); Path6 = vec4(0.0); Path7 = vec4(0.0);
    if (CentreScale <= 0.0 || SourceCount == 0) return;
    vec3 dir = relativityTableDir(gl_FragCoord.xy, TableSize, CentreGame, CentreScale);
    pathE1 = normalize(-CentreGame);
    pathNormal = cross(pathE1, dir);
    if (dot(pathNormal, pathNormal) < 1e-12) return; // straight at the centre
    pathNormal = normalize(pathNormal);
    pathSide = cross(pathNormal, pathE1);
    float rc = length(CentreGame);
    float along = dot(CentreGame, dir);

    // Start at the camera, or where the straight ray enters the path sphere (time: the light-travel time back to it).
    float startRadius = PathRadius;
    float t0 = 0.0;
    if (rc > startRadius) t0 = along - sqrt(max(along * along - (rc * rc - startRadius * startRadius), 0.0));
    vec4 start = vec4(dir * t0, -t0);
    vec4 startP = spacetimeArriving(start, dir);

    // First march (group 0; group 1 reads what it found from the head): how much the path changes in all (m), up to
    // its closest approach to the centre, and its fate.
    vec4 x4 = start;
    vec4 p4 = startP;
    float m = 0.0;
    float mClosest = 0.0;
    float rMin = length(start.xyz - CentreGame);
    float status = 3.0;
    int firstSteps = PATH_STEPS;
    if (Group == 1) {
        vec4 head = texelFetch(Head, ivec2(gl_FragCoord.xy), 0);
        mClosest = head.x;
        status = floor(head.y / 8192.0);
        m = head.y - 8192.0 * status;
        firstSteps = 0;
    }
    vec3 last = start.xyz;
    for (int n = 0; n < firstSteps; n += 1) {
        pathAdvance(x4, p4, pathStep(x4));
        float r = length(x4.xyz - CentreGame);
        m += pathChange(last, x4.xyz);
        last = x4.xyz;
        if (pathCaptured(x4.xyz)) {
            status = 2.0;
            mClosest = m;
            rMin = r;
            break;
        }
        if (r < rMin) {
            rMin = r;
            mClosest = m;
        }
        // Out past the sphere and still leaving: done.
        if (r > startRadius) {
            vec4 dx;
            vec4 dp;
            spacetimeBackward(x4, p4, dx, dp);
            if (dot(x4.xyz - CentreGame, dx.xyz) > 0.0) {
                status = 1.0;
                break;
            }
        }
    }
    float mEnd = m;

    // Second march: the same path again (the same steps), the samples at m* -+ M k / 14, each between the two steps
    // that straddle it (a step is at most a twentieth of the distance to the centre: straight enough between).
    vec2 qIn[15];
    vec2 qOut[15];
    x4 = start;
    p4 = startP;
    pathTurn = 0.0;
    vec2 q = pathSample(x4.xyz);
    for (int k = 0; k < 15; k += 1) {
        qIn[k] = q;
        qOut[k] = q;
    }
    int nextIn = 13; // samples in 14 .. 1 on the way in, 14 the start; then the closest approach (0); then out 1 .. 14
    int nextOut = 1;
    m = 0.0;
    vec3 previous = start.xyz;
    for (int n = 0; n < PATH_STEPS + 32 && nextOut <= 14; n += 1) {
        pathAdvance(x4, p4, pathStep(x4));
        float next = m + pathChange(previous, x4.xyz);
        // Every sample this step passed (the last one closes the path: on its end, wherever that is).
        for (int guard = 0; guard < 29 && nextOut <= 14; guard += 1) {
            float target = nextIn >= 0 ? mClosest * (1.0 - float(nextIn) / 14.0) : mClosest + (mEnd - mClosest) * float(nextOut) / 14.0;
            if (target > next + 1e-6) break;
            float f = clamp((target - m) / max(next - m, 1e-9), 0.0, 1.0);
            q = pathSample(mix(previous, x4.xyz, f));
            if (nextIn >= 1) {
                qIn[nextIn] = q;
                nextIn -= 1;
            } else if (nextIn == 0) {
                qIn[0] = q;
                qOut[0] = q;
                nextIn = -1;
            } else {
                qOut[nextOut] = q;
                nextOut += 1;
            }
        }
        m = next;
        previous = x4.xyz;
        if (m >= mEnd + 1e-6) break;
    }
    if (status == 1.0 && nextOut <= 14) status = 3.0;
    if (status == 2.0) {
        // Falls in: only the way in exists; flat past its last sample, so interpolation near it stays on it.
        for (int k = 0; k < 15; k += 1) qOut[k] = qIn[0];
    }
    if (Group == 0) {
        // The head: the closest approach's m, and status and the whole path's m together (it stays far under 8192), for
        // group 1; then the closest approach's sample.
        Path0 = vec4(mClosest, 8192.0 * status + min(mEnd, 8191.0), qIn[0]);
        Path1 = vec4(qIn[1], qIn[2]);
        Path2 = vec4(qIn[3], qIn[4]);
        Path3 = vec4(qIn[5], qIn[6]);
        Path4 = vec4(qIn[7], qOut[1]);
        Path5 = vec4(qOut[2], qOut[3]);
        Path6 = vec4(qOut[4], qOut[5]);
        Path7 = vec4(qOut[6], qOut[7]);
    } else {
        Path0 = vec4(qIn[8], qIn[9]);
        Path1 = vec4(qIn[10], qIn[11]);
        Path2 = vec4(qIn[12], qIn[13]);
        Path3 = vec4(qIn[14], qOut[8]);
        Path4 = vec4(qOut[9], qOut[10]);
        Path5 = vec4(qOut[11], qOut[12]);
        Path6 = vec4(qOut[13], qOut[14]);
        Path7 = vec4(0.0);
    }
}
