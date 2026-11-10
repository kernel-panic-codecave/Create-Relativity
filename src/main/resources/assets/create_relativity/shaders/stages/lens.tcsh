#version 430 core
// The tessellation stage's control shader (TessellationStage): the patch passed through, each edge split as finely as
// the lens bends it. Its ends' and midpoint's images (the vertex lens: relativityLens); the midpoint's off the great
// circle through the ends' (a straight line on screen), in pixels; split n ways that falls as 1 / n^2, so
// sqrt(off / RELATIVITY_TESS_TOLERANCE) pieces, at most RELATIVITY_TESS_MOST; more where three points can't tell, as
// each field type says (lens_fields: its fieldPieces). Filled in per
// program: //@fields the lens's fields (LensFields), //@varyings the patch's varyings in and out, //@copy each passed
// through. Run through Veil's shader processing as create_relativity:shaders/stages/lens.tcsh: the lens core is included
// by LensStageProcessor.
//@fields
uniform mat4 ProjMat;
layout(vertices = 3) out;
//@varyings
// Each corner's image as the vertex shader bent it (clip space): passed on, and the bend test's ends.
in vec4 relativityVBent[];
out vec4 relativityCBent[];
// Each corner's images in the table's slots (vertex_tess_tail.glsl), passed on.
in vec4 relativityVSlotsA[];
in vec4 relativityVSlotsB[];
// Each corner's second image by the chain (vertex_tess_tail.glsl).
in vec4 relativityVSecond[];
out vec4 relativityCSlotsA[];
out vec4 relativityCSlotsB[];
// Each invocation's edge's pieces and bend (pixels): the one opposite its corner.
out float relativityCPieces[];
out float relativityCOff[];
// How far (pixels) its edges' images bend at most (their midpoints' off straight): how far a point inside may be from the
// blend of its corners' images (the evaluation stage's guard on the light table's answers).
patch out float relativityTessBendPixels;

const float RELATIVITY_TESS_TOLERANCE = 0.5; // pixels an edge's image may stray from straight
const float RELATIVITY_TESS_MOST = 64.0;     // pieces an edge is split into at most (all GL guarantees)
const float RELATIVITY_TESS_STRETCH = 2.0;   // an edge's image this many times its own length is magnified...
const float RELATIVITY_TESS_SPAN = 16.0;     // ...and cut into pieces this many pixels long at most

vec3 relativityTessDirection(vec4 clip) {
    vec4 view = inverse(ProjMat) * clip;
    return normalize(view.xyz / view.w);
}

// How far (pixels) the midpoint's image dm is off the straight line between the ends' (da, db: unit view directions).
float relativityTessOff(vec3 da, vec3 db, vec3 dm) {
    vec3 n = cross(da, db);
    float off = length(dm - da);
    if (length(n) > 1e-7) off = abs(dot(dm, normalize(n)));
    return off / RelativityLensData[11].x;
}

// How many pieces an edge needs, from its ends' and midpoint's images (unit view directions) and its ends' real
// directions (ua, ub): enough for its image's bend (the midpoint's off the straight line between the ends' images), and,
// where its image is magnified (by an Einstein ring: stretched far past its own size), pieces no longer than
// RELATIVITY_TESS_SPAN pixels each, so the arc it's stretched along has vertices all the way round.
float relativityTessPieces(vec3 da, vec3 db, vec3 dm, vec3 ua, vec3 ub) {
    vec3 n = cross(da, db);
    float off = length(dm - da);
    if (length(n) > 1e-7) off = abs(dot(dm, normalize(n)));
    float pieces = ceil(sqrt(off / RelativityLensData[11].x / RELATIVITY_TESS_TOLERANCE));
    float bent = acos(clamp(dot(da, dm), -1.0, 1.0)) + acos(clamp(dot(dm, db), -1.0, 1.0));
    float real = acos(clamp(dot(normalize(ua), normalize(ub)), -1.0, 1.0));
    if (bent > RELATIVITY_TESS_STRETCH * real) pieces = max(pieces, ceil(bent / RelativityLensData[11].x / RELATIVITY_TESS_SPAN));
    return clamp(pieces, 1.0, RELATIVITY_TESS_MOST);
}

vec3 relativityTessView(vec4 clip) {
    vec4 view = inverse(ProjMat) * clip;
    return view.xyz / view.w;
}

void main() {
//@copy
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
    relativityCBent[gl_InvocationID] = relativityVBent[gl_InvocationID];
    relativityCSlotsA[gl_InvocationID] = relativityVSlotsA[gl_InvocationID];
    relativityCSlotsB[gl_InvocationID] = relativityVSlotsB[gl_InvocationID];
    // Each invocation measures one edge, the one opposite its corner (outer level i's), all three at once: Intel runs a
    // patch's invocations as lanes of one thread, and the lens on one lane for all three edges left the others idle.
    // Each its edge's pieces and bend out (relativityCPieces, relativityCOff), the levels set from them after.
    int i = gl_InvocationID;
    float pieces = 1.0;
    float off = 0.0;
    if (relativityLensed(ProjMat)) {
        int a = (i + 1) % 3;
        int b = (i + 2) % 3;
        vec3 va = relativityTessView(gl_in[a].gl_Position);
        vec3 vb = relativityTessView(gl_in[b].gl_Position);
        // The bend, only where some field bends the triangle's image off straight enough to show (lensFieldNear): its
        // ends' images as the vertex shader found them, its midpoint's here.
        if (mod(RelativityLensData[8].x, 2.0) < 0.5 && lensFieldNear(relativityTessView(gl_in[0].gl_Position), relativityTessView(gl_in[1].gl_Position), relativityTessView(gl_in[2].gl_Position))) {
            vec3 da = relativityTessDirection(relativityVBent[a]);
            vec3 db = relativityTessDirection(relativityVBent[b]);
            vec3 dm = relativityTessDirection(relativityLens(0.5 * (gl_in[a].gl_Position + gl_in[b].gl_Position), ProjMat));
            pieces = relativityTessPieces(da, db, dm, va, vb);
            off = relativityTessOff(da, db, dm);
        }
        // (And each field type's extremes, where three points can't follow the bend: lensFieldPieces.)
        pieces = max(pieces, lensFieldPieces(va, vb));
        // (And its second image, where both ends have one: cut into pieces no longer than RELATIVITY_TESS_SPAN pixels.
        // By the shadow's edge an edge's second image is stretched round the ring far past its primary's, and a long
        // straight piece of it is a chord across the shadow.)
        if (relativityVSecond[a].w > 0.5 && relativityVSecond[b].w > 0.5) {
            float arc = acos(clamp(dot(relativityVSecond[a].xyz, relativityVSecond[b].xyz), -1.0, 1.0));
            pieces = max(pieces, ceil(arc / RelativityLensData[11].x / RELATIVITY_TESS_SPAN));
        }
        pieces = min(pieces, RELATIVITY_TESS_MOST);
        // (A debug cap: the block's [11].w, 0 none.)
        if (RelativityLensData[11].w > 0.5) pieces = min(pieces, RelativityLensData[11].w);
    }
    relativityCPieces[gl_InvocationID] = pieces;
    relativityCOff[gl_InvocationID] = off;
    barrier();
    if (i == 0) {
        relativityTessBendPixels = max(relativityCOff[0], max(relativityCOff[1], relativityCOff[2]));
        gl_TessLevelOuter[0] = relativityCPieces[0];
        gl_TessLevelOuter[1] = relativityCPieces[1];
        gl_TessLevelOuter[2] = relativityCPieces[2];
        gl_TessLevelInner[0] = max(relativityCPieces[0], max(relativityCPieces[1], relativityCPieces[2]));
    }
}
