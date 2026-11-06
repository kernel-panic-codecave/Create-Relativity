// Warp bubbles (lens BubbleField): Alcubierre's, an ellipsoidal wall (a sphere's a special case) round a push of v
// along n, frozen where it is (WarpEllipsoid; WarpBubble a sphere of it): g^ti = -v f n, g^ij = delta - v^2 f^2 n n,
// so along n: B = v f, C = -v^2 f^2. f on the ellipsoidal radius s (1 on the wall): the tanh wall (sharpness 8 on s)
// or, with an inner radius, exactly 1 inside it, easing to 0 at 1 (6t^5 - 15t^4 + 10t^3). Its observers ride with it
// (Eulerian: light seen along m has p = m / (1 + v f m.n)).
// Data (BubbleField): [5e] bubble e: xyz its centre (view space), w v; [5e + 1..3] its semi-axes, xyz each over its
// length squared, w its length; [5e + 4] xyz n (reversed: light followed arriving), w its inner radius (0 tanh wall).

const float fieldSharpness = 8.0;

void fieldSample(vec3 x, inout LensFieldSample s) {
    int bubbles = fieldCount();
    for (int e = 0; e < bubbles; e += 1) {
        vec4 centre = fieldData(5 * e);
        vec4 a0 = fieldData(5 * e + 1);
        vec4 a1 = fieldData(5 * e + 2);
        vec4 a2 = fieldData(5 * e + 3);
        vec4 push = fieldData(5 * e + 4);
        float v = centre.w;
        float inner = push.w;
        vec3 rel = x - centre.xyz;
        vec3 u = vec3(dot(rel, a0.xyz), dot(rel, a1.xyz), dot(rel, a2.xyz));
        float r = length(u);
        vec3 gR = vec3(0.0);
        if (r > 1e-6) gR = (u.x * a0.xyz + u.y * a1.xyz + u.z * a2.xyz) / r;
        float shortest = min(a0.w, min(a1.w, a2.w));
        float f = 0.0;
        float slope = 0.0;
        float safe = 0.0;
        if (inner > 0.0) {
            float t = clamp((1.0 - r) / (1.0 - inner), 0.0, 1.0);
            f = t * t * t * (t * (6.0 * t - 15.0) + 10.0);
            slope = -30.0 * t * t * (1.0 - t) * (1.0 - t) / (1.0 - inner);
            safe = max(r - 1.0, inner - r) * shortest;
        } else {
            float tp = tanh(fieldSharpness * (r + 1.0));
            float tm = tanh(fieldSharpness * (r - 1.0));
            float norm = 1.0 / (2.0 * tanh(fieldSharpness));
            f = (tp - tm) * norm;
            slope = fieldSharpness * ((1.0 - tp * tp) - (1.0 - tm * tm)) * norm;
            safe = (abs(r - 1.0) - 3.0 / fieldSharpness) * shortest;
        }
        vec3 gF = slope * gR;
        float b = v * f;
        if (f > 0.0) lensFieldAddAxial(s, f, push.xyz,
            0.0, vec3(0.0),
            b, v * gF,
            -b * b, (-2.0 * v * b) * gF,
            vec3(0.0), vec3(0.0),
            0.0, 1.0, b);
        lensFieldStep(s, max(safe, 0.02));
    }
}
