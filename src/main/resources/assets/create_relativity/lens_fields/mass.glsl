// Masses (lens MassField): the weak field of a mass M (either sign), softened by e inside the body,
// Phi = -M / sqrt(r^2 + e^2): A = -1 + 2 Phi, D = 1 + 2 Phi (PointMass), each mass's added in.
// Data (MassField): [2i] mass i: xyz its centre (view space), w M (blocks); [2i + 1] x its softening e.

void fieldSample(vec3 x, inout LensFieldSample s) {
    int masses = fieldCount();
    for (int i = 0; i < masses; i += 1) {
        vec4 mass = fieldData(2 * i);
        float e = fieldData(2 * i + 1).x;
        vec3 rel = x - mass.xyz;
        float r2 = dot(rel, rel) + e * e;
        float r = sqrt(r2);
        float phi = -mass.w / r;
        vec3 gPhi = (mass.w / (r2 * r)) * rel;
        lensFieldAddIsotropic(s, 2.0 * phi, 2.0 * gPhi, 2.0 * phi, 2.0 * gPhi);
        lensFieldStep(s, max(0.2 * r, 0.02));
    }
}
