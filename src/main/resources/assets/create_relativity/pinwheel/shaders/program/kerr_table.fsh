#veil:buffer veil:camera VeilCamera
#include create_relativity:kerr

// The nearest hole's Kerr march at reduced resolution: one ray per texel, through the same output pixel position as
// the lens pass (black_hole_lens.fsh), which interpolates these and re-marches only where neighbours disagree.

uniform vec4 Disk;               // see black_hole_lens.fsh
uniform int HoleCount;
uniform vec4 Holes[8];
uniform vec4 HoleSpins[8];
uniform int KerrTableEnabled; // see black_hole_lens.fsh; 0 leaves the table unused, so it isn't marched
uniform int LensMode;         // see black_hole_lens.fsh; 5 skips the tables (profiling)

in vec2 texCoord;
layout(location = 0) out vec4 Table0; // deflection (exit direction minus the view direction), status
layout(location = 1) out vec4 Table1; // exit position (camera-relative), disk crossing's distance (0: none)
layout(location = 2) out vec4 Table2; // disk crossing's radius, frequency shift, cos and sin of its azimuth

vec3 viewRay(vec2 uv) {
    vec4 view = VeilCamera.IProjMat * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize((VeilCamera.IViewMat * vec4(view.xyz / view.w, 0.0)).xyz);
}

void main() {
    KerrRay ray;
    ray.status = 2;
    vec3 dir = viewRay(texCoord);
    if (HoleCount > 0 && KerrTableEnabled != 0 && LensMode != 5) {
        vec2 diskRadii = vec2(0.0);
        if (Disk.x > 0.0) diskRadii = Disk.yz;
        ray = kerrMarch(Holes[0].xyz, Holes[0].w, HoleSpins[0].x, dir, diskRadii);
    }
    if (ray.status == 2) {
        Table0 = vec4(0.0, 0.0, 0.0, 2.0);
        Table1 = vec4(0.0);
        Table2 = vec4(0.0);
        return;
    }
    Table0 = vec4(ray.exitDir - dir, float(ray.status));
    Table1 = vec4(ray.exitPos, ray.diskDist);
    Table2 = vec4(ray.diskRho, ray.diskG, cos(ray.diskAngle), sin(ray.diskAngle));
}
