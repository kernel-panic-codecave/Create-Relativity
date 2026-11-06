// The lens's fields (LensFieldType; put together by client/lens/field/LensFields): what every source type's own file
// adds to, and what the light is followed by. Static fields: light's energy at infinity p_t = -1, its momentum p, and
// H = (A + 2 B (p.t) + C (p.t)^2 + D |p|^2) / 2 = 0 along it; flat A -1, B 0, C 0, D 1. An isotropic source (a hole, a
// mass) adds to A and D; an axial one (a tube, a bubble: tipped cones along an axis t) to A, B and C, the strongest one
// there (the most inside) taking the axis, so overlapping ones merge (an SDF's union) rather than add up. Each part
// comes with its gradient, in closed form (the march takes no differences), and a safe step: how far light may go before
// the field changes much (an SDF's distance).

struct LensFieldSample {
    float a;      // A's isotropic part (flat -1)
    vec3 ga;
    float d;      // D (flat 1)
    vec3 gd;
    float axialA; // A's part from the strongest axial source (added to a)
    vec3 gAxialA;
    float b;      // B, C: from the strongest axial source
    vec3 gb;
    float c;
    vec3 gc;
    vec3 axis;    // its axis t
    vec3 turn;    // across a joint the axis turns: dt/dx = turn (x) gTurn (outer product), t's other changes left out
    vec3 gTurn;
    float obsAlpha; // its observers': light they see along unit m has p = (m + t (obsAlpha + (obsBeta - 1) m.t)) /
    float obsBeta;  // (1 + obsGamma m.t) (flat 0, 1, 0)
    float obsGamma;
    float weight; // how far inside it the point is (0 none, 1 inside)
    float safe;   // how far light may go from here before the field changes much (blocks)
    bool absorbed; // inside a horizon: light here is lost
};

// The field at x, every type's added in (generated after the types' code: LensFields).
LensFieldSample lensFieldAt(vec3 x);

LensFieldSample lensFieldFlat() {
    LensFieldSample s;
    s.a = -1.0;
    s.ga = vec3(0.0);
    s.d = 1.0;
    s.gd = vec3(0.0);
    s.axialA = 0.0;
    s.gAxialA = vec3(0.0);
    s.b = 0.0;
    s.gb = vec3(0.0);
    s.c = 0.0;
    s.gc = vec3(0.0);
    s.axis = vec3(1.0, 0.0, 0.0);
    s.turn = vec3(0.0);
    s.gTurn = vec3(0.0);
    s.obsAlpha = 0.0;
    s.obsBeta = 1.0;
    s.obsGamma = 0.0;
    s.weight = 0.0;
    s.safe = 1e30;
    s.absorbed = false;
    return s;
}

// An isotropic source's part: added to A and D.
void lensFieldAddIsotropic(inout LensFieldSample s, float da, vec3 ga, float dd, vec3 gd) {
    s.a += da;
    s.ga += ga;
    s.d += dd;
    s.gd += gd;
}

// An axial source's part (weight: how far inside it; its A's part, B and C along axis, each with its gradient; turn and
// gTurn its axis's turning, zero if none; its observers' alpha, beta and gamma, for the camera inside it): kept if it's
// the strongest here.
void lensFieldAddAxial(inout LensFieldSample s, float weight, vec3 axis, float da, vec3 ga, float b, vec3 gb, float c, vec3 gc, vec3 turn, vec3 gTurn, float alpha, float beta, float gamma) {
    // (Not a bare return: Veil's processor fails on those in void functions.)
    if (weight > s.weight) {
        s.weight = weight;
        s.axis = axis;
        s.axialA = da;
        s.gAxialA = ga;
        s.b = b;
        s.gb = gb;
        s.c = c;
        s.gc = gc;
        s.turn = turn;
        s.gTurn = gTurn;
        s.obsAlpha = alpha;
        s.obsBeta = beta;
        s.obsGamma = gamma;
    }
}

// Inside a horizon here: light reaching it is lost.
void lensFieldAbsorb(inout LensFieldSample s) {
    s.absorbed = true;
}

// The momentum of light seen going along unit m by the observers at a point of field s.
vec3 lensFieldStart(LensFieldSample s, vec3 m) {
    float mt = dot(m, s.axis);
    return (m + s.axis * (s.obsAlpha + (s.obsBeta - 1.0) * mt)) / (1.0 + s.obsGamma * mt);
}

// A source's safe step here (blocks): the field's, the least of its sources'.
void lensFieldStep(inout LensFieldSample s, float safe) {
    s.safe = min(s.safe, safe);
}

// H for momentum p here.
float lensFieldH(LensFieldSample s, vec3 p) {
    float ps = dot(p, s.axis);
    return 0.5 * (s.a + s.axialA + 2.0 * s.b * ps + s.c * ps * ps + s.d * dot(p, p));
}

// dx/dl = dH/dp: light's velocity here (any scale).
vec3 lensFieldVelocity(LensFieldSample s, vec3 p) {
    return s.d * p + s.axis * (s.b + s.c * dot(p, s.axis));
}

// dp/dl = -dH/dx.
vec3 lensFieldForce(LensFieldSample s, vec3 p) {
    float ps = dot(p, s.axis);
    vec3 grad = 0.5 * (s.ga + s.gAxialA + 2.0 * ps * s.gb + ps * ps * s.gc + dot(p, p) * s.gd);
    // (The axis turning: dH/d(p.t) = B + C p.t, d(p.t)/dx = (p.turn) gTurn.)
    grad += (s.b + s.c * ps) * dot(p, s.turn) * s.gTurn;
    return -grad;
}

// One step of light at x with momentum p through the fields (s: the field at x), the midpoint rule over the field's
// safe step (at least minStep, at most maxStep): x, p and s moved on.
void lensFieldAdvance(inout vec3 x, inout vec3 p, inout LensFieldSample s, float minStep, float maxStep) {
    vec3 v = lensFieldVelocity(s, p);
    float dl = clamp(s.safe, minStep, maxStep) / max(length(v), 1e-6);
    vec3 xm = x + v * (0.5 * dl);
    vec3 pm = p + lensFieldForce(s, p) * (0.5 * dl);
    LensFieldSample sm = lensFieldAt(xm);
    x += lensFieldVelocity(sm, pm) * dl;
    p += lensFieldForce(sm, pm) * dl;
    s = lensFieldAt(x);
}

// Where light seen along unit d from the camera first gets r out (on the chord of the step that takes it there);
// false if it doesn't within the steps.
bool lensFieldReach(vec3 d, float r, out vec3 at) {
    vec3 x = vec3(0.0);
    LensFieldSample s = lensFieldAt(x);
    vec3 p = lensFieldStart(s, d);
    at = d * r;
    for (int i = 0; i < 1500; i += 1) {
        vec3 a = x;
        lensFieldAdvance(x, p, s, 0.02, max(r, 1.0));
        if (s.absorbed) return false;
        if (length(x) >= r) {
            vec3 u = normalize(x - a);
            float b = dot(a, u);
            float t = -b + sqrt(max(b * b - (dot(a, a) - r * r), 0.0));
            at = a + u * t;
            return true;
        }
    }
    return false;
}
