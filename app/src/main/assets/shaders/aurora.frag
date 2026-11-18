// Aurora: fractal-noise curtains over a starry night sky.
// Layered over other content (uLayered), it drops the sky and outputs alpha.

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p *= 2.0;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    float aspect = uResolution.x / uResolution.y;
    vec2 p = vec2(uv.x * aspect + uOffset * 0.6, uv.y);
    float t = uTime * 0.05;

    vec3 sky = mix(vec3(0.01, 0.015, 0.05), vec3(0.02, 0.05, 0.13), uv.y);
    vec3 col = vec3(0.0);   // the aurora and stars only; the sky is added below when standalone

    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float n = fbm(vec2(p.x * 1.5 + fi * 3.7, t * 2.0 + fi));
        float center = 0.55 + fi * 0.08 + (n - 0.5) * 0.5;
        float d = uv.y - center;
        float curtain = fbm(vec2(p.x * 6.0 + fi * 11.0, uv.y * 2.0 - t * 6.0));
        float band = exp(-d * d * 60.0) * smoothstep(0.25, 0.9, curtain);
        vec3 tint = mix(vec3(0.1, 0.95, 0.55), vec3(0.55, 0.3, 0.95), fi / 2.0);
        col += tint * band * 0.9;
    }

    float s = hash(floor(gl_FragCoord.xy / 2.0));
    float twinkle = 0.5 + 0.5 * sin(uTime * 2.0 + s * 100.0);
    col += step(0.998, s) * twinkle * smoothstep(0.3, 1.0, uv.y);

    if (uLayered > 0.5) {
        // Over a photo: no sky, and alpha from brightness, so it works with any blend mode.
        float a = clamp(max(col.r, max(col.g, col.b)) * 1.5, 0.0, 1.0);
        gl_FragColor = vec4(col / max(a, 0.001), a);
    } else {
        gl_FragColor = vec4(sky + col, 1.0);
    }
}
