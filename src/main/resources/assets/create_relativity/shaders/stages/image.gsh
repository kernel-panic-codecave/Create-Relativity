#version 150
// The image stage's geometry shader (ImageStage), last before the fragment shader in a lensed program: each triangle
// passed through as the vertex lens bent it (the vertex shader's, or the tessellation stage's evaluation shader), then
// drawn again at its other images (the light table's other slots: lens_stage_vertex.glsl's relativityStage outputs).
// Triangles whose image isn't the straight one between their corners' are left out here, whole (no check per pixel):
// one straddling a jump in the image (a corner lost in a shadow, or seen through a tube while another's seen past it);
// the tessellation stage makes them small, so what's missing is a sliver. Other images are mirrored: only faces whose fronts are seen (clockwise on screen) are drawn. The program's
// own varyings (renamed relativityStage_<name> on the way in) filled in per program: //@varyings their
// declarations, //@copy each copied from `corner`. Run through Veil's shader processing as
// create_relativity:shaders/stages/image.gsh (shader injections can target it).
layout(triangles) in;
// (At most one other image a triangle: its declared output, max_vertices, costs every triangle through it on Intel,
// drawn again or not. Slots 1 to 3 are the others, the primary's 0.)
layout(triangle_strip, max_vertices = 6) out;
// (As the lens core declares it: a block's declarations must agree across a program's stages.)
layout(std140) uniform RelativityLens {
    vec4 RelativityLensData[159];
};
//@varyings
//@images
in vec3 relativityStageUnbent[];
in vec3 relativityStageBent[];
flat in float relativityStageChecked[];
out vec3 relativityUnbent;
out vec3 relativityBent;
flat out float relativityChecked;
in vec4 relativityStageA[];
in vec2 relativityStageB[];
in vec2 relativityStageP[];
in vec4 relativityStageT[];
in vec4 relativityStageH[];

vec2 relativityStageImage(int corner, int s) {
    if (s == 0) return relativityStageP[corner];
    if (s == 1) return relativityStageA[corner].xy;
    if (s == 2) return relativityStageA[corner].zw;
    return relativityStageB[corner];
}

vec3 relativityStageAt(int corner, vec2 uv) {
    vec2 t = (2.0 * uv / RelativityLensData[7].xy - 1.0) * RelativityLensData[6].xy;
    return normalize(vec3(t, -1.0)) * length(relativityStageUnbent[corner]);
}

void relativityStageEmit(int corner, vec3 at, float checked) {
//@copy
    gl_Position = mat4(RelativityLensData[2], RelativityLensData[3], RelativityLensData[4], RelativityLensData[5]) * vec4(at, 1.0);
    relativityUnbent = relativityStageUnbent[corner];
    relativityBent = at;
    relativityChecked = checked;
    EmitVertex();
}

void relativityStagePass(int corner) {
//@copy
    gl_Position = gl_in[corner].gl_Position;
    relativityUnbent = relativityStageUnbent[corner];
    relativityBent = relativityStageBent[corner];
    relativityChecked = relativityStageChecked[corner];
    EmitVertex();
}

// Whether the draw is placing vertices the long way round the hole (the player's own image: the block's [11].y bit 2).
bool relativityStageLongWay() {
    return mod(RelativityLensData[11].y, 4.0) > 1.5;
}

// Whether two images (view directions) lie far apart round a hole (xyz its centre) as seen: a triangle whose corners do
// spans its ring, its straight image across the screen, no image of it.
bool relativityStageApart(vec3 a, vec3 b, vec4 hole) {
    vec3 h = normalize(hole.xyz);
    vec3 ua = normalize(a) - h * dot(normalize(a), h);
    vec3 ub = normalize(b) - h * dot(normalize(b), h);
    if (length(ua) < 1e-6 || length(ub) < 1e-6) return false;
    return dot(normalize(ua), normalize(ub)) < 0.5;
}

bool relativityStageApart3(vec3 a, vec3 b, vec3 c, vec4 hole) {
    return relativityStageApart(a, b, hole) || relativityStageApart(b, c, hole) || relativityStageApart(c, a, hole);
}

// Whether the line of sight through a hole's centre crosses the triangle (corners a, b, c, view space, unbent) behind
// the hole: its image a ring round the shadow (the Einstein ring), which no triangle can be; its corners' images on
// every side of the ring, the straight triangle between them over the shadow.
bool relativityStageRinged(vec3 a, vec3 b, vec3 c, vec4 hole) {
    float far = length(hole.xyz);
    vec3 h = hole.xyz / far;
    vec3 e1 = b - a;
    vec3 e2 = c - a;
    vec3 q = cross(h, e2);
    float det = dot(e1, q);
    if (abs(det) < 1e-12) return false;
    float u = dot(-a, q) / det;
    vec3 r = cross(-a, e1);
    float v = dot(h, r) / det;
    if (u < 0.0 || v < 0.0 || u + v > 1.0) return false;
    return dot(e2, r) / det > far;
}

// Whether view-space point p lies beyond a hole along the line of sight to it.
bool relativityStageBehind(vec3 p, vec4 hole) {
    return dot(p, hole.xyz) > dot(hole.xyz, hole.xyz);
}

// Whether the triangle's image wraps round a hole's ring (no straight triangle can draw it), for the hole its corner
// k's light passes nearest (relativityStageH: w 0 none).
bool relativityStageWraps(int k) {
    vec4 hole = relativityStageH[k];
    if (hole.w <= 0.0) return false;
    vec3 a = relativityStageUnbent[0];
    vec3 b = relativityStageUnbent[1];
    vec3 c = relativityStageUnbent[2];
    // (The long way round, by the camera itself (the player's own image): corners far apart round the ring.)
    if (relativityStageLongWay() && relativityStageApart3(relativityStageBent[0], relativityStageBent[1], relativityStageBent[2], hole)) return true;
    if (relativityStageRinged(a, b, c, hole)) return true;
    // (Behind the hole, its corners' images far apart round the ring: its image an arc of the ring, magnified round it;
    // the straight triangle a chord across the shadow. In front, a triangle's seen nearly straight, however wide.)
    return relativityStageBehind(a, hole) && relativityStageBehind(b, hole) && relativityStageBehind(c, hole)
        && relativityStageApart3(relativityStageBent[0], relativityStageBent[1], relativityStageBent[2], hole);
}

void main() {
    // The triangle as the lens bent it, unless its image jumps inside it: a corner lost in a shadow (it has no image), or
    // a tube's outline across it (corners seen through and past the tube: relativityStageT.w 2 and 1). (Not by how far
    // its image stretches: by the Einstein ring that's real, an image magnified round the ring.)
    float w0 = relativityStageT[0].w;
    float w1 = relativityStageT[1].w;
    float w2 = relativityStageT[2].w;
    bool across = w0 > 0.5 && w1 > 0.5 && w2 > 0.5 && (abs(w0 - w1) > 0.5 || abs(w0 - w2) > 0.5);
    bool shadowed = relativityStageT[0].z + relativityStageT[1].z + relativityStageT[2].z > 0.5;
    if (relativityStageWraps(0) || relativityStageWraps(1) || relativityStageWraps(2)) shadowed = true;
    if (!shadowed && !across) {
        for (int corner = 0; corner < 3; corner += 1) relativityStagePass(corner);
        EndPrimitive();
    }
    int emitted = 0;
    if (!RELATIVITY_STAGE_IMAGES || relativityStageLongWay() || mod(RelativityLensData[8].x, 8.0) > 3.5) emitted = 1;
    for (int s = 0; s <= 3 && emitted < 1; s += 1) {
        vec2 a = relativityStageImage(0, s);
        if (a.x <= 0.0) continue;
        vec2 b = vec2(0.0);
        vec2 c = vec2(0.0);
        int sb = -1;
        int sc = -1;
        float nb = 30.0;
        float nc = 30.0;
        for (int t = 0; t <= 3; t += 1) {
            vec2 cb = relativityStageImage(1, t);
            vec2 cc = relativityStageImage(2, t);
            // (Its own slot's a little nearer. Not ?: inside a larger expression: Veil's processing drops its brackets.)
            float own = 0.0;
            if (t == s) own = 0.5;
            float db = distance(cb, a) - own;
            float dc = distance(cc, a) - own;
            if (cb.x > 0.0 && db < nb) {
                nb = db;
                b = cb;
                sb = t;
            }
            if (cc.x > 0.0 && dc < nc) {
                nc = dc;
                c = cc;
                sc = t;
            }
        }
        if (sb < 0 || sc < 0 || (s == 0 && sb == 0 && sc == 0)) continue;
        // (Far apart on the table: on different sheets, across a fold.)
        if (distance(b, c) > 30.0) continue;
        vec3 pa = relativityStageAt(0, a);
        vec3 pb = relativityStageAt(1, b);
        vec3 pc = relativityStageAt(2, c);
        // (Its corners far apart round the ring: its image an arc of it, which a straight triangle across the shadow isn't.)
        if (relativityStageH[0].w > 0.0 && relativityStageApart3(pa, pb, pc, relativityStageH[0])) continue;
        // (Its winding on the table's plane, which is the screen's: x right, y up. These images are mirrored: a face
        // whose front is seen comes out clockwise. Its back's left out; its front drawn counter-clockwise, culling keeping
        // it.)
        vec2 ab = b - a;
        vec2 ac = c - a;
        if (ab.x * ac.y - ab.y * ac.x >= 0.0) continue;
        relativityStageEmit(0, pa, 2.0);
        relativityStageEmit(2, pc, 2.0);
        relativityStageEmit(1, pb, 2.0);
        EndPrimitive();
        emitted += 1;
    }
}
