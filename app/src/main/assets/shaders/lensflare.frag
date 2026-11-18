// Lens flare: a bright light with a starburst, an anamorphic streak, a chromatic halo, and
// ghost reflections strung along an axis. The light is pinned in place. At rest the ghosts
// are converged into the light (uAxis = 0). Touch draws them out towards the finger.
// Custom uniforms, set by LensFlareScene (pixels, bottom-left origin):
//   uLight      the light's position
//   uAxis       vector from the light to the "centre" ghost; its length is the ghost spacing
//   uAxisAngle  angle of uAxis in radians, used to rotate the starburst
//   uGhostFade  0..1 ghost opacity; 0 while converged, so only the light shows

uniform vec2 uLight;
uniform vec2 uAxis;
uniform float uAxisAngle;
uniform float uGhostFade;

// Soft disc with a brighter rim, as an aperture ghost looks.
vec3 ghost(vec2 uv, vec2 pos, float size, vec3 tint) {
    float d = length(uv - pos);
    float disc = 1.0 - smoothstep(size * 0.55, size, d);
    float rim = 1.0 - smoothstep(0.0, size * 0.12, abs(d - size * 0.85));
    return tint * (disc * 0.22 + rim * 0.3);
}

// Thin ring, used once per colour channel to give chromatic separation.
float ring(float d, float radius, float width) {
    return 1.0 - smoothstep(0.0, width, abs(d - radius));
}

void main() {
    float s = min(uResolution.x, uResolution.y);
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / s;   // screen centre = (0,0)
    vec2 light = (uLight - 0.5 * uResolution) / s;
    vec2 rel = uv - light;
    float d = length(rel);
    float ang = atan(rel.y, rel.x);

    // Dusk sky, warmer near the light.
    float vy = gl_FragCoord.y / uResolution.y;
    vec3 sky = mix(vec3(0.015, 0.015, 0.05), vec3(0.09, 0.06, 0.18), vy);
    vec3 col = vec3(0.28, 0.12, 0.05) * exp(-d * 2.2);   // warm haze; the sky is added when standalone

    // Core and glow.
    col += vec3(1.0, 0.92, 0.8) * exp(-d * 28.0) * 1.4;
    col += vec3(1.0, 0.6, 0.3) * exp(-d * 6.0) * 0.4;

    // Starburst: integer angular frequencies, so there's no seam where atan wraps.
    // Measured from the flare axis, so the starburst turns as the direction changes.
    float ra = ang - uAxisAngle;
    float r1 = pow(0.5 + 0.5 * sin(ra * 12.0 + uTime * 0.15), 10.0);
    float r2 = pow(0.5 + 0.5 * sin(ra * 7.0 - uTime * 0.1 + 1.3), 14.0);
    col += vec3(1.0, 0.85, 0.7) * (r1 * 0.6 + r2 * 0.45) * exp(-d * 4.0);

    // Anamorphic horizontal streak.
    col += vec3(0.45, 0.65, 1.0) * exp(-abs(rel.y) * 140.0) * exp(-abs(rel.x) * 1.4) * 0.7;

    // Chromatic halo.
    float hr = 0.42;
    col += vec3(ring(d, hr * 1.03, 0.025), ring(d, hr, 0.025), ring(d, hr * 0.97, 0.025)) * 0.13;

    // Ghosts along the flare axis.
    vec2 axis = uAxis / s;
    float pulse = 0.9 + 0.1 * sin(uTime * 0.7);
    vec3 g = vec3(0.0);
    g += ghost(uv, light + axis * 0.45, 0.035, vec3(0.9, 0.9, 1.0)) * pulse;
    g += ghost(uv, light + axis * 0.75, 0.06, vec3(0.3, 0.6, 1.0));
    g += ghost(uv, light + axis * 1.3, 0.09, vec3(0.4, 1.0, 0.6)) * pulse;
    g += ghost(uv, light + axis * 1.6, 0.04, vec3(1.0, 0.5, 0.3));
    g += ghost(uv, light + axis * 2.0, 0.17, vec3(0.6, 0.4, 1.0));
    g += ghost(uv, light + axis * 2.45, 0.03, vec3(1.0, 0.9, 0.4)) * pulse;
    col += g * uGhostFade;

    if (uLayered > 0.5) {
        // Over a photo: no sky, and alpha from brightness, so it works with any blend mode.
        col = 1.0 - exp(-col * 1.3);
        float a = clamp(max(col.r, max(col.g, col.b)) * 1.4, 0.0, 1.0);
        gl_FragColor = vec4(col / max(a, 0.001), a);
    } else {
        // Soft tone-map so the core blooms instead of clipping.
        gl_FragColor = vec4(1.0 - exp(-(sky + col) * 1.3), 1.0);
    }
}
