// The live image map from baked camera slots (KerrSlots.kt): for one radius layer of points round a lone spinning hole,
// each texel's turn from the four slots round the camera, blended. Each slot was baked with its camera at azimuth 0
// (on the hole's +x side) at its own distance and height angle; the camera's actual azimuth round the spin axis (game
// y) turns the point into the slots' frame and the turn back out. Hidden (alpha 0) where any slot weighed in has no image.

uniform sampler3D Slot0;
uniform sampler3D Slot1;
uniform sampler3D Slot2;
uniform sampler3D Slot3;
uniform vec4 Weights;      // the four slots' blend weights
uniform float Azimuth;     // the camera's azimuth round the spin axis: atan(z, x) of camera minus hole
uniform vec2 Radii;        // the map's inner and outer radius (blocks): the same grid as the slots'
uniform vec3 SlotCamera[4]; // each slot's camera from the hole (blocks, the slots' frame: azimuth 0)
uniform vec3 Camera;        // the camera from the hole (blocks, the slots' frame: turned back by Azimuth)
uniform int Layer;

out vec4 Result;

const vec3 SIZE = vec3(128.0, 64.0, 48.0); // ImageMapSolver.WIDTH, HEIGHT, DEPTH

// Turns d by angle a round game y (x toward z).
vec3 turn(vec3 d, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec3(d.x * c - d.z * s, d.y, d.x * s + d.z * c);
}

vec4 slotAt(sampler3D slot, vec3 d) {
    float r = length(d);
    float s = log(r / Radii.x) / log(Radii.y / Radii.x);
    vec3 at = vec3(atan(d.z, d.x) / 6.28318530718 + 0.5, ((d.y / r * 0.5 + 0.5) * (SIZE.y - 1.0) + 0.5) / SIZE.y,
        (s * (SIZE.z - 1.0) + 0.5) / SIZE.z);
    return texture(slot, at);
}

// A direction's angle from the camera's view of the hole (axis), and round it from game up's side: the frame lensing
// works in (images keep their place relative to the hole as the camera moves).
vec2 holeAngles(vec3 dir, vec3 axis) {
    vec3 up = abs(axis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 zero = normalize(up - dot(up, axis) * axis);
    vec3 quarter = cross(axis, zero);
    return vec2(acos(clamp(dot(dir, axis), -1.0, 1.0)), atan(dot(dir, quarter), dot(dir, zero)));
}

vec3 fromHoleAngles(vec2 angles, vec3 axis) {
    vec3 up = abs(axis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 zero = normalize(up - dot(up, axis) * axis);
    vec3 quarter = cross(axis, zero);
    return cos(angles.x) * axis + sin(angles.x) * (cos(angles.y) * zero + sin(angles.y) * quarter);
}

// Slot k's image of point d (slots' frame), as its change of hole angles from the straight line of sight (the azimuth
// change wrapped to -pi .. pi).
vec2 slotShift(vec4 m, vec3 camera, vec3 d) {
    vec3 game = d - camera;
    float span = length(game);
    vec3 straight = game / span;
    vec3 seen = normalize(straight + m.rgb * span);
    vec3 axis = normalize(-camera);
    vec2 a = holeAngles(seen, axis);
    vec2 b = holeAngles(straight, axis);
    vec2 shift = a - b;
    shift.y -= 6.28318530718 * floor(shift.y / 6.28318530718 + 0.5);
    return shift;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float azimuth = ((float(texel.x) + 0.5) / SIZE.x - 0.5) * 6.28318530718;
    float cosine = float(texel.y) / (SIZE.y - 1.0) * 2.0 - 1.0;
    float sine = sqrt(max(1.0 - cosine * cosine, 0.0));
    float radius = Radii.x * pow(Radii.y / Radii.x, float(Layer) / (SIZE.z - 1.0));
    vec3 d = radius * vec3(sine * cos(azimuth), cosine, sine * sin(azimuth));
    vec3 inSlot = turn(d, -Azimuth);
    vec4 a = slotAt(Slot0, inSlot);
    vec4 b = slotAt(Slot1, inSlot);
    vec4 c = slotAt(Slot2, inSlot);
    vec4 e = slotAt(Slot3, inSlot);
    float missing = 0.0;
    if (Weights.x > 1e-4 && a.a < 0.999) missing = 1.0;
    if (Weights.y > 1e-4 && b.a < 0.999) missing = 1.0;
    if (Weights.z > 1e-4 && c.a < 0.999) missing = 1.0;
    if (Weights.w > 1e-4 && e.a < 0.999) missing = 1.0;
    if (missing > 0.5) {
        Result = vec4(0.0);
        return;
    }
    // Blended as hole-angle shifts (each slot's from its own camera), then put back for this camera.
    vec2 shift = Weights.x * slotShift(a, SlotCamera[0], inSlot) + Weights.y * slotShift(b, SlotCamera[1], inSlot)
        + Weights.z * slotShift(c, SlotCamera[2], inSlot) + Weights.w * slotShift(e, SlotCamera[3], inSlot);
    vec3 game = inSlot - Camera;
    float span = length(game);
    vec3 straight = game / span;
    vec3 axis = normalize(-Camera);
    vec3 seen = fromHoleAngles(holeAngles(straight, axis) + shift, axis);
    Result = vec4(turn((seen - straight) / span, Azimuth), 1.0);
}
