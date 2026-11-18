// Plasma: overlapping sine fields cycling through a cosine palette. Touch makes ripples.
// The engine prepends precision, vUv, uTime, uResolution, uTouch and uOffset.

void main() {
    float scale = min(uResolution.x, uResolution.y);
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / scale;
    uv.x += (uOffset - 0.5) * 0.6;
    float t = uTime * 0.4;

    float v = sin(uv.x * 3.0 + t);
    v += sin((uv.y * 3.0 + t) * 0.7);
    v += sin((uv.x * 2.0 + uv.y * 2.0 + t) * 0.9);
    vec2 c = uv + vec2(sin(t * 0.5) * 0.8, cos(t * 0.33) * 0.8);
    v += sin(length(c) * 4.0 - t);

    if (uTouch.x >= 0.0) {
        vec2 tp = (uTouch - 0.5 * uResolution) / scale;
        tp.x += (uOffset - 0.5) * 0.6;
        float d = length(uv - tp);
        v += 0.8 * sin(d * 22.0 - uTime * 6.0) * exp(-d * 3.5);
    }

    vec3 col = 0.5 + 0.5 * cos(3.14159 * v * 0.5 + vec3(0.0, 2.0, 4.0) + t);
    gl_FragColor = vec4(col * 0.85, 1.0);
}
