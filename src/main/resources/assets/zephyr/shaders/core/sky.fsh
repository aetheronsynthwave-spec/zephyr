#version 330

// Zephyr custom skies. Everything is computed from the view direction and the time: no textures.
// ColorModulator carries the parameters: x = seconds, y = style, z = brightness, w = horizon blend.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in vec3 direction;

out vec4 fragColor;

const float TAU = 6.28318530718;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < 5; octave++) {
        value += amplitude * noise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        amplitude *= 0.5;
    }
    return value;
}

mat3 rotation(vec3 axis, float angle) {
    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float o = 1.0 - c;
    return mat3(o * axis.x * axis.x + c, o * axis.x * axis.y - axis.z * s, o * axis.z * axis.x + axis.y * s,
                o * axis.x * axis.y + axis.z * s, o * axis.y * axis.y + c, o * axis.y * axis.z - axis.x * s,
                o * axis.z * axis.x - axis.y * s, o * axis.y * axis.z + axis.x * s, o * axis.z * axis.z + c);
}

// One layer of round, twinkling stars, one candidate per cell of the direction lattice.
float stars(vec3 d, float scale, float rarity, float time) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    float pick = hash(cell);
    if (pick < rarity) {
        return 0.0;
    }
    vec3 offset = vec3(hash(cell + 1.3), hash(cell + 2.7), hash(cell + 4.1)) * 0.6 + 0.2;
    float distance = length(p - cell - offset);
    float twinkle = 0.7 + 0.3 * sin(time * (1.0 + 3.0 * hash(cell + 7.0)) + pick * 40.0);
    return smoothstep(0.17, 0.0, distance) * twinkle * (0.4 + 0.6 * hash(cell + 9.0));
}

vec3 starfield(vec3 d, float time) {
    float a = stars(d, 90.0, 0.86, time);
    float b = stars(d, 180.0, 0.90, time * 1.3) * 0.6;
    float c = stars(d, 42.0, 0.965, time * 0.7) * 1.6;
    return vec3(a + b) * vec3(0.85, 0.9, 1.0) + c * vec3(1.0, 0.94, 0.85);
}

// Curtains of light hanging over a slowly turning star dome.
vec3 aurora(vec3 d, float time) {
    vec3 sky = mix(vec3(0.015, 0.025, 0.06), vec3(0.0, 0.008, 0.025), clamp(d.y, 0.0, 1.0));
    sky += starfield(rotation(vec3(0.2, 1.0, 0.35), time * 0.006) * d, time) * 0.8;
    float up = max(d.y, 0.0);
    vec3 light = vec3(0.0);
    // March through a few layers of height: each is a plane the curtains are drawn on.
    for (int layer = 0; layer < 18; layer++) {
        float height = 0.6 + float(layer) * 0.055;
        vec2 plane = d.xz * (height / (up + 0.12));
        float bend = fbm(vec3(plane * 0.7, time * 0.035)) * 3.2;
        float fold = abs(sin(plane.x * 1.1 + bend + time * 0.05) * 0.55 + plane.y * 0.28 - 0.35);
        float curtain = smoothstep(0.22, 0.0, fold) * (0.35 + 0.65 * noise(vec3(plane * 9.0, time * 0.4)));
        float t = float(layer) / 17.0;
        vec3 tint = mix(vec3(0.10, 1.0, 0.55), vec3(0.55, 0.20, 0.95), smoothstep(0.15, 0.95, t));
        light += tint * curtain * (1.0 - t * 0.65);
    }
    return sky + light * 0.14 * smoothstep(0.02, 0.22, up);
}

// Slowly rotating clouds of coloured gas, folded by their own noise.
vec3 nebula(vec3 d, float time) {
    vec3 p = rotation(vec3(0.35, 1.0, 0.15), time * 0.01) * d;
    vec3 warp = vec3(fbm(p * 2.2 + vec3(0.0, time * 0.012, 0.0)), fbm(p * 2.2 + 5.2), fbm(p * 2.2 + 9.7));
    float gas = fbm(p * 2.4 + warp * 2.6);
    float wisps = fbm(p * 6.5 + warp * 3.4 - vec3(time * 0.01));
    vec3 color = vec3(0.012, 0.008, 0.035);
    color = mix(color, vec3(0.06, 0.30, 0.46), smoothstep(0.34, 0.68, gas));
    color = mix(color, vec3(0.62, 0.10, 0.52), smoothstep(0.48, 0.80, gas * 0.6 + warp.x * 0.5));
    color += vec3(1.0, 0.62, 0.28) * pow(smoothstep(0.52, 0.82, wisps), 2.0) * smoothstep(0.40, 0.70, gas);
    color *= 0.55 + 0.75 * smoothstep(0.30, 0.65, fbm(p * 3.7 + 21.0)); // dust lanes
    return color + starfield(p, time) * (0.6 + 0.8 * gas);
}

// A spiral galaxy turning overhead.
vec3 galaxy(vec3 d, float time) {
    vec3 axis = normalize(vec3(0.35, 0.8, 0.45));
    vec3 right = normalize(cross(axis, vec3(0.0, 0.0, 1.0)));
    vec3 forward = cross(right, axis);
    float facing = dot(d, axis);
    vec2 disc = vec2(dot(d, right), dot(d, forward)) / max(facing + 0.45, 0.05);
    float radius = length(disc);
    float angle = atan(disc.y, disc.x) - time * 0.035;
    float spiral = angle * 2.0 + log(radius + 0.02) * 6.0;
    vec3 turned = rotation(axis, time * 0.035) * d;
    float dust = fbm(turned * 9.0);
    float arm = pow(0.5 + 0.5 * cos(spiral + (dust - 0.5) * 3.0), 2.5);
    float fade = exp(-radius * 1.7) * smoothstep(-0.35, 0.25, facing);
    vec3 color = vec3(0.006, 0.008, 0.022);
    color += mix(vec3(0.20, 0.30, 0.85), vec3(0.70, 0.35, 0.75), dust) * arm * fade * 1.5;
    color += vec3(1.0, 0.86, 0.62) * exp(-radius * radius * 38.0) * smoothstep(0.0, 0.3, facing);
    color *= 1.0 - 0.55 * smoothstep(0.52, 0.74, fbm(turned * 17.0 + 3.0)) * fade;
    color += starfield(turned, time) * (0.55 + 2.2 * arm * fade);
    return color;
}

// Black and white: ink strokes of wind circling the sky, in the client's own style.
vec3 mono(vec3 d, float time) {
    vec3 p = rotation(vec3(0.0, 1.0, 0.0), time * 0.018) * d;
    float height = p.y;
    float around = atan(p.z, p.x);
    vec3 loop = vec3(cos(around), height * 2.5, sin(around));
    float color = mix(0.07, 0.012, smoothstep(0.0, 0.8, height));
    float flow = fbm(loop * 2.0 + vec3(0.0, 0.0, time * 0.03));
    for (int i = 0; i < 6; i++) {
        float n = float(i);
        float centre = 0.10 + n * 0.13 + (flow - 0.5) * 0.55
                + 0.09 * sin(around * (2.0 + mod(n, 3.0)) + time * (0.12 + 0.03 * n) + n * 1.9);
        float width = 0.008 + 0.022 * noise(loop * 3.0 + n * 4.0 + time * 0.05);
        float stroke = exp(-pow((height - centre) / width, 2.0));
        // Streaks along the stroke, so it reads as a dry brush rather than a smooth band.
        float grain = 0.35 + 0.65 * noise(vec3(loop.x * 5.0, (height - centre) * 190.0, loop.z * 5.0));
        float broken = smoothstep(0.34, 0.62, noise(loop * 1.4 + n * 11.0 - time * 0.07));
        color += stroke * grain * broken * (0.55 - 0.05 * n);
    }
    return vec3(color) + vec3(dot(starfield(p, time), vec3(0.333))) * smoothstep(0.05, 0.4, height);
}

void main() {
    vec3 d = normalize(direction);
    float time = ColorModulator.x;
    int style = int(ColorModulator.y + 0.5);
    vec3 color = style == 0 ? aurora(d, time) : style == 1 ? nebula(d, time) : style == 2 ? galaxy(d, time) : mono(d, time);
    color *= ColorModulator.z;
    // Meet the world's fog at the horizon so distant terrain does not end in a hard line.
    float horizon = (1.0 - smoothstep(-0.05, 0.22, d.y)) * ColorModulator.w;
    color = mix(color, FogColor.rgb, horizon);
    // Dither away banding in the dark gradients.
    color += (hash(vec3(gl_FragCoord.xy, 1.0)) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
