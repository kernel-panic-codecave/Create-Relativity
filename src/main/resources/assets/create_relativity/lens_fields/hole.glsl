// Black holes (lens HoleField): Schwarzschild in isotropic coordinates round each, u = M / (2r):
// ds^2 = -((1 - u) / (1 + u))^2 dt^2 + (1 + u)^4 |dx|^2, so A = -((1 + u) / (1 - u))^2, D = (1 + u)^-4, each hole's
// added in (superposed). Its horizon is r = M / 2 (u = 1): light inside it is lost. Spin left out (spin 0).
// Data (HoleField): [i] hole i: xyz its centre (view space), w its mass M (blocks).

void fieldSample(vec3 x, inout LensFieldSample s) {
    int holes = fieldCount();
    for (int i = 0; i < holes; i += 1) {
        vec4 hole = fieldData(i);
        float m = hole.w;
        vec3 rel = x - hole.xyz;
        float r = max(length(rel), 1e-6);
        float u = 0.5 * m / r;
        if (u > 0.95) lensFieldAbsorb(s);
        u = min(u, 0.95);
        vec3 er = rel / r;
        float onePlus = 1.0 + u;
        float oneMinus = 1.0 - u;
        float a = -(onePlus * onePlus) / (oneMinus * oneMinus);
        float d = 1.0 / (onePlus * onePlus * onePlus * onePlus);
        // (dA/dr = 4u (1 + u) / (r (1 - u)^3); dD/dr = 4u / (r (1 + u)^5).)
        float daDr = 4.0 * u * onePlus / (r * oneMinus * oneMinus * oneMinus);
        float ddDr = 4.0 * u / (r * onePlus * onePlus * onePlus * onePlus * onePlus);
        lensFieldAddIsotropic(s, a + 1.0, daDr * er, d - 1.0, ddDr * er);
        // (A tenth of the way to the horizon: the bend is gentle on that scale, sharp near it.)
        lensFieldStep(s, max(0.1 * (r - 0.5 * m), 0.02));
    }
}
