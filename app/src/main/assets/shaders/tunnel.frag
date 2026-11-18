// Starfield tunnel: stars streaming out of a vanishing point.
// uSpeed is a custom uniform, set from Kotlin in StarfieldTunnelScene.onBindUniforms().

uniform float uSpeed;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
    uv.x -= (uOffset - 0.5) * 0.3;

    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float depth = 0.3 / max(r, 0.001) + uTime * uSpeed;
    float ang = a / 6.28318 + 0.5;

    vec2 grid = vec2(ang * 24.0, depth * 2.0);
    vec2 cell = floor(grid);
    vec2 f = fract(grid) - 0.5;
    float h = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);

    float star = (1.0 - smoothstep(0.0, 0.12, length(f * vec2(1.0, 0.4)))) * step(0.85, h);
    vec3 col = vec3(0.6 + 0.4 * h, 0.75, 1.0) * star * smoothstep(0.0, 0.25, r);

    float rings = 0.5 + 0.5 * sin(depth * 6.0);
    col += vec3(0.05, 0.0, 0.12) * rings * smoothstep(0.0, 0.6, r);
    col += vec3(0.4, 0.2, 0.8) * 0.02 / max(r, 0.02);

    gl_FragColor = vec4(col, 1.0);
}
