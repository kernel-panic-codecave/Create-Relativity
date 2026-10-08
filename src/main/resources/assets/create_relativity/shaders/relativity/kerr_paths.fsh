// Light paths for the vertex lens (KerrPaths.kt; read by vertex_lens_core.glsl, which is prepended with kerr.glsl): for
// each table texel (a direction from the camera, relativityTableDir: every direction, wherever the camera looks), a ray
// through every source together (kerr.glsl's Sources and spacetimeBackward), followed back in time, recorded as where it
// is seen from the lens centre (the dominant source) at fixed points along it.
//
// A path is parameterised by distance along it, s, around its closest approach to the centre s*: samples at
// s = s* -+ L (k / 7)^2 for k = 7 .. 1 (L: s* coming in, the rest of the path going out), dense where the path turns
// and smooth through it. Nothing assumes one turning point or attraction: a path may come close more than once (two
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

in vec2 texCoord;
layout(location = 0) out vec4 Path0; // r_min, status (0 unmarched, 1 escapes, 2 captured, 3 incomplete), closest approach
layout(location = 1) out vec4 Path1; // in 1, in 2
layout(location = 2) out vec4 Path2; // in 3, in 4
layout(location = 3) out vec4 Path3; // in 5, in 6
layout(location = 4) out vec4 Path4; // in 7 (the start), out 1
layout(location = 5) out vec4 Path5; // out 2, out 3
layout(location = 6) out vec4 Path6; // out 4, out 5
layout(location = 7) out vec4 Path7; // out 6, out 7 (the end sphere)

const int PATH_STEPS = 420;

vec3 pathE1;     // centre to camera
vec3 pathSide;   // in the ray's plane, perpendicular to pathE1, toward the ray's side
vec3 pathNormal; // the ray's plane's normal
float pathTurn = 0.0; // the last angle, for unwrapping

// A sample: the angle round the centre in the ray's plane (unwrapped against the last one), and the offset out of it
// with the distance from the centre packed into the second channel, of the photon at x (camera-relative, game axes).
vec2 pathSample(vec3 x) {
    vec3 d = x - CentreGame;
    float r = length(d);
    vec3 n = d / max(r, 1e-6);
    float angle = atan(dot(n, pathSide), dot(n, pathE1));
    angle += 6.28318530718 * floor((pathTurn - angle) / 6.28318530718 + 0.5);
    pathTurn = angle;
    return vec2(angle, relativityPackPair(dot(n, pathNormal), r));
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
            step = min(step, max(0.05 * abs(r - s1.x), 0.25 * wall));
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

    // First march: the path's length, its closest approach to the centre, and its fate.
    vec4 x4 = start;
    vec4 p4 = startP;
    float s = 0.0;
    float sClosest = 0.0;
    float rMin = length(start.xyz - CentreGame);
    float status = 3.0;
    for (int n = 0; n < PATH_STEPS; n += 1) {
        float ds = pathStep(x4);
        pathAdvance(x4, p4, ds);
        s += ds;
        if (pathCaptured(x4.xyz)) {
            status = 2.0;
            sClosest = s;
            rMin = length(x4.xyz - CentreGame);
            break;
        }
        float r = length(x4.xyz - CentreGame);
        if (r < rMin) {
            rMin = r;
            sClosest = s;
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
    float sEnd = s;

    // Second march: the samples, at s* -+ L (k / 7)^2.
    vec2 qIn[8];
    vec2 qOut[8];
    x4 = start;
    p4 = startP;
    s = 0.0;
    vec2 q = pathSample(x4.xyz);
    for (int k = 0; k < 8; k += 1) {
        qIn[k] = q;
        qOut[k] = q;
    }
    int nextIn = 6; // samples in 7 .. 1 on the way in, 7 the start; then the closest approach (0); then out 1 .. 7
    int nextOut = 1;
    vec4 previous = x4;
    float sPrevious = 0.0;
    for (int n = 0; n < PATH_STEPS + 16 && nextOut <= 7; n += 1) {
        // The next sample's arc length.
        float target;
        if (nextIn >= 0) {
            float w = float(nextIn) / 7.0;
            target = sClosest - sClosest * w * w;
        } else {
            float w = float(nextOut) / 7.0;
            target = sClosest + (sEnd - sClosest) * w * w;
        }
        if (s >= sEnd && target > sEnd + 1e-3) break;
        float ds = min(pathStep(x4), max(target - s, 1e-4));
        if (target <= s + 1e-4) ds = 0.0;
        if (ds > 0.0) {
            previous = x4;
            sPrevious = s;
            pathAdvance(x4, p4, ds);
            s += ds;
        }
        if (s + 1e-4 >= target) {
            // On the sample (steps end on it, up to rounding).
            q = pathSample(x4.xyz);
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
    }
    if (status == 1.0 && nextOut <= 7) status = 3.0;
    if (status == 2.0) {
        // Falls in: only the way in exists; flat past its last sample, so interpolation near it stays on it.
        for (int k = 0; k < 8; k += 1) qOut[k] = qIn[0];
    }
    Path0 = vec4(rMin, status, qIn[0]);
    Path1 = vec4(qIn[1], qIn[2]);
    Path2 = vec4(qIn[3], qIn[4]);
    Path3 = vec4(qIn[5], qIn[6]);
    Path4 = vec4(qIn[7], qOut[1]);
    Path5 = vec4(qOut[2], qOut[3]);
    Path6 = vec4(qOut[4], qOut[5]);
    Path7 = vec4(qOut[6], qOut[7]);
}
