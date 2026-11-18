// Ripples: rain-drop rings that refract whatever is below (readsBelow = true).
// Touch drops extra ripples. On its own, with nothing below, it shows a deep-water gradient.
// uDrop0..3 are custom uniforms set by RippleScene: xy in pixels (bottom-left), z = start
// time in seconds, or -1 when unused.

uniform vec3 uDrop0;
uniform vec3 uDrop1;
uniform vec3 uDrop2;
uniform vec3 uDrop3;

const float LIFE = 3.0;    // seconds a ring lasts
const float SPEED = 0.35;  // ring growth, in screen-short-side units per second

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Displacement contributed by one ring centred at c (normalised units) that started `age` ago.
vec2 ring(vec2 p, vec2 c, float age) {
    if (age < 0.0 || age > LIFE) return vec2(0.0);
    vec2 d = p - c;
    float r = length(d);
    float front = age * SPEED;
    float x = r - front;
    float envelope = exp(-x * x * 900.0) * (1.0 - age / LIFE) * (1.0 - age / LIFE);
    return (d / max(r, 0.0001)) * sin(x * 140.0) * envelope * 0.018;
}

vec2 touchRing(vec2 p, vec3 drop, float s) {
    if (drop.z < 0.0) return vec2(0.0);
    return ring(p, (drop.xy - 0.5 * uResolution) / s, uTime - drop.z) * 1.6;
}

void main() {
    float s = min(uResolution.x, uResolution.y);
    vec2 p = (gl_FragCoord.xy - 0.5 * uResolution) / s;

    // Ambient rain: one drop per grid cell, each on its own random cycle. Checking the
    // 3x3 neighbourhood lets rings cross cell borders.
    vec2 disp = vec2(0.0);
    const float CELL = 0.45;
    vec2 cell = floor(p / CELL);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 c = cell + vec2(float(i), float(j));
            float h = hash(c);
            float period = LIFE + 1.5 + h * 3.0;
            float t = uTime + h * 17.0;
            float cycle = floor(t / period);
            float age = t - cycle * period;
            vec2 centre = (c + vec2(hash(c + cycle), hash(c - cycle + 3.1))) * CELL;
            disp += ring(p, centre, age) * 0.7;
        }
    }
    disp += touchRing(p, uDrop0, s);
    disp += touchRing(p, uDrop1, s);
    disp += touchRing(p, uDrop2, s);
    disp += touchRing(p, uDrop3, s);

    vec2 uv = gl_FragCoord.xy / uResolution + disp * s / uResolution;
    vec3 base;
    if (uLayered > 0.5) {
        base = texture2D(uBelow, clamp(uv, 0.0, 1.0)).rgb;
    } else {
        base = mix(vec3(0.01, 0.06, 0.12), vec3(0.03, 0.22, 0.32), uv.y);
    }
    // A highlight where the surface tilts towards the light.
    float glint = clamp(dot(disp, normalize(vec2(-0.6, 0.8))) * 40.0, 0.0, 1.0);
    gl_FragColor = vec4(base + glint * 0.35, 1.0);
}
