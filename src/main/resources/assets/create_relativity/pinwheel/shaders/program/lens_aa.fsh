// Antialiasing and sharpening for what the lens pass made (black_hole_lens.fsh marks those pixels with alpha below 1:
// bent sky, the shadow and disk): its tables are coarser than the screen, so their edges are soft or
// stair-stepped. An FXAA-style blend along the edge where there is one, and a light contrast-adaptive sharpen; pixels
// the world drew itself (alpha 1) pass through untouched.

uniform sampler2D DiffuseSampler0; // the lens pass's output

in vec2 texCoord;
out vec4 fragColor;

uniform float AaSharpen;  // how much to sharpen

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 centre = texture(DiffuseSampler0, texCoord);
    if (centre.a > 0.99) {
        fragColor = vec4(centre.rgb, 1.0);
        return;
    }
    vec2 px = 1.0 / vec2(textureSize(DiffuseSampler0, 0));
    vec3 n = texture(DiffuseSampler0, texCoord + vec2(0.0, px.y)).rgb;
    vec3 s = texture(DiffuseSampler0, texCoord - vec2(0.0, px.y)).rgb;
    vec3 e = texture(DiffuseSampler0, texCoord + vec2(px.x, 0.0)).rgb;
    vec3 w = texture(DiffuseSampler0, texCoord - vec2(px.x, 0.0)).rgb;
    float lc = luma(centre.rgb);
    float ln = luma(n);
    float ls = luma(s);
    float le = luma(e);
    float lw = luma(w);
    float high = max(lc, max(max(ln, ls), max(le, lw)));
    float low = min(lc, min(min(ln, ls), min(le, lw)));
    vec3 colour = centre.rgb;
    if (high - low > max(0.0312, 0.125 * high)) {
        // An edge: blend along it (perpendicular to the luma gradient), as FXAA's console pass.
        float lnw = luma(texture(DiffuseSampler0, texCoord + vec2(-px.x, px.y)).rgb);
        float lne = luma(texture(DiffuseSampler0, texCoord + vec2(px.x, px.y)).rgb);
        float lsw = luma(texture(DiffuseSampler0, texCoord + vec2(-px.x, -px.y)).rgb);
        float lse = luma(texture(DiffuseSampler0, texCoord + vec2(px.x, -px.y)).rgb);
        vec2 dir = vec2(-((lnw + lne) - (lsw + lse)), (lnw + lsw) - (lne + lse));
        float reduce = max((lnw + lne + lsw + lse) * 0.03125, 1.0 / 128.0);
        float scale = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
        dir = clamp(dir * scale, vec2(-8.0), vec2(8.0)) * px;
        vec3 a = 0.5 * (texture(DiffuseSampler0, texCoord + dir * (1.0 / 3.0 - 0.5)).rgb + texture(DiffuseSampler0, texCoord + dir * (2.0 / 3.0 - 0.5)).rgb);
        vec3 b = 0.5 * a + 0.25 * (texture(DiffuseSampler0, texCoord - dir * 0.5).rgb + texture(DiffuseSampler0, texCoord + dir * 0.5).rgb);
        float lb = luma(b);
        colour = b;
        if (lb < low || lb > high) colour = a; // (b strayed past the neighbourhood: across the edge)
    } else {
        // No edge: sharpen a little, within the neighbours' range (no halos).
        vec3 sharp = colour + AaSharpen * (4.0 * colour - n - s - e - w);
        colour = clamp(sharp, min(min(n, s), min(e, w)), max(max(n, s), max(e, w)));
    }
    fragColor = vec4(colour, 1.0);
}
