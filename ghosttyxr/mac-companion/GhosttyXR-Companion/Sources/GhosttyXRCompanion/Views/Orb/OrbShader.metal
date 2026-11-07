// Orb galaxy shader — Metal port.
//
// SOURCE OF TRUTH: website/src/components/voice/orb-renderer.ts (`fragmentShader`).
// Sibling ports: ghosttyxr/android/app/src/main/res/raw/orb_fragment.glsl (GLSL ES).
// Keep all three in sync; this file is a port, not an independent implementation.
//
// Mapping notes: uniforms collapse into `OrbUniforms`, `vec*`→`float*`,
// `mat2`→`float2x2`, and `gl_FragColor` becomes the fragment return value.
// `fract`/`mix`/`refract`/`smoothstep`/`step`/`clamp` all exist in Metal with the
// same semantics, so the body stays line-for-line comparable with the TS original.

#include <metal_stdlib>
using namespace metal;

struct OrbUniforms {
    float2 resolution; // uRes
    float3 background; // uBg
    float3 anchor;     // uAnchor
    float3 accent0;    // uC0
    float3 accent1;    // uC1
    float3 accent2;    // uC2
    float time;        // uTime
    float phase;       // uPhase
    float audio;       // uAudio
    float spin;        // uSpin
    float archetype;   // uArch (-1 = derive from phase)
    float lens;        // uLens
};

struct OrbVertexOut {
    float4 position [[position]];
    float2 uv;
};

// Fullscreen triangle strip matching the WebGL vertex buffer
// (pos.xy, uv.xy) × 4 in TRIANGLE_STRIP order.
constant float2 kOrbPositions[4] = {
    float2(-1.0, -1.0), float2(1.0, -1.0), float2(-1.0, 1.0), float2(1.0, 1.0)
};
constant float2 kOrbUVs[4] = {
    float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0), float2(1.0, 0.0)
};

vertex OrbVertexOut orbVertex(uint vertexID [[vertex_id]]) {
    OrbVertexOut out;
    out.position = float4(kOrbPositions[vertexID], 0.0, 1.0);
    out.uv = kOrbUVs[vertexID];
    return out;
}

static inline float h1(float x) {
    return fract(sin(x * 127.1) * 43758.5453);
}

static float4 starfield(float3 n, float t, constant OrbUniforms &u) {
    float lon = atan2(n.z, n.x);
    float lat = asin(clamp(n.y, -1.0, 1.0));
    float v1 = fract(u.phase * 7.13);
    float v2 = fract(u.phase * 3.71);
    float v3 = fract(u.phase * 5.37);
    float at = u.archetype >= 0.0 ? u.archetype : floor(fract(u.phase * 9.73) * 4.0);
    float isNeb = step(0.5, at) * (1.0 - step(1.5, at));
    float isCore = step(1.5, at) * (1.0 - step(2.5, at));
    float isDeep = step(2.5, at);

    float gb = lat + (0.15 + 0.4 * v1) * sin(lon * (1.0 + floor(v2 * 2.0)) + 1.3)
        + 0.12 * sin(lon * 3.0 + t * 0.1);
    float band = exp(-gb * gb * (5.0 + 10.0 * v3));
    band = mix(band, max(band, 0.8), isNeb);
    band *= 1.0 - 0.85 * isDeep;

    float n1 = sin(lon * 2.0 + sin(lat * 3.0 + t * 0.25) * 1.6 + t * 0.15);
    float n2 = sin(lon * 5.0 - sin(lat * 4.0 - t * 0.2) * 1.2 - t * 0.22 + 2.4);
    float neb = pow(0.5 + 0.5 * n1, 2.0) * (0.45 + 0.55 * pow(0.5 + 0.5 * n2, 2.0));
    float lane = pow(0.5 + 0.5 * sin(lon * 4.0 + lat * 7.0 + sin(lon * 2.0) * 2.0), 3.0);
    float galaxy = clamp(band * neb * (1.0 - lane * (0.55 + 0.35 * v2)), 0.0, 1.0);

    float3 hue = mix(
        mix(u.accent0, u.accent1, v1),
        mix(u.accent1, u.accent2, v3),
        0.5 + 0.5 * sin(lon + lat * 2.0 - t * 0.2)
    );
    float3 hueGrey = float3(dot(hue, float3(0.299, 0.587, 0.114)));
    hue = clamp(hueGrey + (hue - hueGrey) * 1.45, 0.0, 1.0);
    float3 dust = mix(float3(0.72, 0.78, 0.92), hue, 0.45 + 0.3 * v1 + 0.45 * isNeb);
    float3 col = dust * galaxy * (0.6 + 0.9 * isNeb);

    float shear = sin(lon * 13.0 + lat * 4.0 - t * 0.35) * sin(lon * 5.0 + t * 0.2);
    col += dust * band * neb * max(shear, 0.0) * 0.14;
    float gb2 = lat - (0.35 + 0.25 * v2) * sin(lon * 2.0 - 1.1) + 0.4;
    float arm = exp(-gb2 * gb2 * 7.0) * neb;
    col += mix(dust, u.accent1, 0.35) * arm * 0.2;

    float3 voidGlow = mix(float3(0.04, 0.03, 0.1), mix(u.accent0, mix(u.accent1, u.accent2, v3), v1) * 0.22, 0.75);
    col += voidGlow * (0.5 + 0.22 * sin(t * 0.4 + lon)) * (0.4 + 0.6 * band);
    col += float3(1.0, 0.88, 0.68) * pow(band, 4.0) * pow(neb, 2.0) * 0.4;

    float ca = v2 * 6.28318;
    float3 coreDir = normalize(float3(cos(ca) * 0.85, 0.6 * (v3 - 0.5), sin(ca) * 0.85));
    float bulge = max(dot(n, coreDir), 0.0);
    col += mix(float3(1.0, 0.85, 0.6), u.accent2, 0.25)
        * (pow(bulge, 14.0) * 1.6 + pow(bulge, 4.0) * 0.5) * isCore;

    float pocket = pow(neb, 5.0) * band * (0.7 + 0.3 * sin(t * 0.6 + lon * 3.0));
    col += mix(u.accent2, u.accent0, fract(v1 + 0.5 * sin(lon * 2.0) + 0.5))
        * pocket * (0.5 + 0.4 * v2 + 0.8 * isNeb);
    float pocket2 = pow(0.5 + 0.5 * sin(lon * 3.0 + lat * 4.0 - t * 0.18 + 2.0), 6.0) * band;
    col += mix(u.accent1, u.accent2, v3) * pocket2 * (0.25 + 0.3 * v1 + 0.5 * isNeb);

    float detail = smoothstep(90.0, 200.0, u.resolution.y);
    float2 gg = float2(lon, lat) * 34.0;
    float2 gc = floor(gg);
    float2 gf = fract(gg);
    float gh = h1(gc.x * 3.7 + gc.y * 11.3);
    float2 gp = float2(0.2 + 0.6 * h1(gh * 91.0), 0.2 + 0.6 * h1(gh * 47.0));
    float gd = length((gf - gp) * float2(cos(lat), 1.0));
    float grain = exp(-gd * gd * 700.0 * clamp(u.resolution.y / 420.0, 0.22, 1.0))
        * step(0.3, gh) * (0.15 + 0.85 * band);
    col += float3(0.88, 0.9, 1.0) * grain * 0.4 * detail;
    float weight = clamp(galaxy * 0.7 + pow(band, 4.0) * 0.25, 0.0, 1.0);

    for (int s = 0; s < 3; s++) {
        float scale = s == 0 ? 6.0 : (s == 1 ? 11.0 : 19.0);
        float2 g = float2(lon, lat) * scale;
        float2 cell = floor(g);
        float2 f = fract(g);
        float hx = h1(cell.x * 13.7 + cell.y * 7.3 + float(s) * 91.0);
        float hy = h1(cell.x * 5.1 + cell.y * 17.9 + float(s) * 37.0);
        float2 sp = float2(0.15 + 0.7 * hx, 0.15 + 0.7 * hy);
        float distanceToStar = length((f - sp) * float2(cos(lat), 1.0));
        float census = (v2 - 0.5) * 0.2 + 0.35 * isNeb - 0.2 * isCore + 0.3 * isDeep;
        float keep = step((s == 2 ? 0.3 : 0.55) + census, h1(hx * 89.0 + hy * 31.0) + band * 0.25);
        float res = clamp(u.resolution.y / 420.0, 0.22, 1.0);
        float twinkle = mix(0.92, 0.6 + 0.4 * sin(t * (1.5 + 3.0 * hx) + hx * 40.0), res);
        float hz = h1(hx * 53.0 + hy * 71.0 + cell.x);
        float sizeJitter = 0.35 + 1.8 * hz * hz;
        float sharp = (s == 0 ? 260.0 : (s == 1 ? 700.0 : 1600.0)) / sizeJitter * res;
        float star = exp(-distanceToStar * distanceToStar * sharp) * keep * twinkle;
        float3 tint = mix(
            float3(1.0),
            hx < 0.33 ? float3(0.85, 0.9, 1.0) : (hx < 0.66 ? float3(1.0, 0.95, 0.85) : mix(float3(1.0), u.accent1, 0.3)),
            0.6
        );
        float bright = (s == 0 ? 1.7 : (s == 1 ? 0.9 : 0.5)) * (0.55 + 0.7 * sizeJitter);
        float starFade = mix(s == 2 ? 0.14 : 0.45, 1.0, detail);
        col += tint * star * bright * starFade;
        if (s == 0) {
            float big = smoothstep(1.2, 2.0, sizeJitter);
            col += tint * exp(-distanceToStar * distanceToStar * 60.0) * 0.18 * big * twinkle * starFade;
            float2 delta = (f - sp) * float2(cos(lat), 1.0);
            float spike = exp(-delta.x * delta.x * 1200.0) * exp(-delta.y * delta.y * 26.0)
                + exp(-delta.y * delta.y * 1200.0) * exp(-delta.x * delta.x * 26.0);
            col += tint * spike * 0.3 * big * twinkle * starFade;
            weight = max(weight, spike * 0.3 * big * starFade);
        }
        weight = max(weight, star * min(bright, 1.5) * starFade);
    }

    float pa = v1 * 6.28318;
    float3 pulsarDir = normalize(float3(sin(pa) * 0.9, 1.4 * (v2 - 0.5), cos(pa) * 0.9));
    float pd = max(dot(n, pulsarDir), 0.0);
    float beat = pow(0.5 + 0.5 * sin(t * (1.2 + v3 + 1.5 * u.audio) + v3 * 6.28), 8.0);
    beat = min(1.0, beat + 0.6 * u.audio);
    float pulsarFade = mix(0.45, 1.0, detail);
    col += float3(0.9, 0.95, 1.0)
        * (pow(pd, 900.0) * (0.6 + 1.2 * beat) + pow(pd, 110.0) * 0.5 * beat) * pulsarFade;
    weight = max(weight, pow(pd, 900.0) * (0.5 + 0.5 * beat) * pulsarFade);
    return float4(min(col, float3(1.0)), min(weight, 1.0));
}

static float4 sphereAt(float3 n, float spin, float t, constant OrbUniforms &u) {
    float roll = t * 0.13;
    float cr = cos(roll);
    float sr = sin(roll);
    n = float3(cr * n.x - sr * n.y, sr * n.x + cr * n.y, n.z);
    float tilt = 0.45 + 0.35 * sin(t * 0.24);
    float cx = cos(tilt);
    float sx = sin(tilt);
    n = float3(n.x, cx * n.y - sx * n.z, sx * n.y + cx * n.z);
    float cs = cos(spin);
    float ss = sin(spin);
    n = float3(cs * n.x + ss * n.z, n.y, -ss * n.x + cs * n.z);
    return starfield(n, t, u);
}

static float3 shade(float2 p, constant OrbUniforms &u) {
    float radius = length(p);
    float t = u.time * 0.8 + u.phase;
    float rr = min(radius, 0.9995);
    float z = sqrt(1.0 - rr * rr);
    float3 normal = float3(p.x, p.y, z);
    float fresnel = pow(1.0 - z, 2.4);
    float3 ray = refract(float3(0.0, 0.0, -1.0), normal, 0.75);
    float hit = -2.0 * dot(normal, ray);
    float3 backNormal = normalize(normal + ray * hit);
    float sv = fract(u.phase * 6.31);
    float sw = fract(u.phase * 2.17);
    float warpedTime = t
        + (0.9 + 1.3 * sv) * sin(t * (0.09 + 0.07 * sw))
        + (0.5 + 0.8 * sw) * sin(t * (0.21 + 0.09 * sv) + 2.6);
    float4 front = sphereAt(normal, u.spin, warpedTime, u);
    float4 back = sphereAt(backNormal, u.spin, warpedTime * 0.8 + 2.7, u);

    float3 voidColor = mix(u.anchor * 0.04, u.anchor * 0.35, fresnel);
    float3 color = mix(u.background, voidColor, 0.97 - 0.04 * fresnel);
    float frontAlpha = clamp(front.a, 0.0, 1.0);
    float backAlpha = clamp(back.a, 0.0, 1.0);
    color = mix(color, back.rgb, backAlpha * 0.16);
    color = mix(color, front.rgb, frontAlpha * 0.85);

    float longitude = atan2(normal.x, normal.z);
    float speech = pow(
        0.5 + 0.5 * sin(longitude * 3.0 + sin(longitude * 7.0 + t * 1.1) * 0.7 + t * 0.5),
        3.0
    ) * (0.55 + 0.45 * sin(longitude * 5.0 - t * 0.65 + 1.7));
    float sky = -normal.y;
    float hang = smoothstep(-0.15, 0.5, sky);
    float rays = 0.7 + 0.3 * sin(longitude * 24.0 + sin(longitude * 9.0 - t * 0.8) * 2.0 + t * 1.6);
    float aurora = clamp(speech, 0.0, 1.0) * hang * rays * (1.0 + 2.2 * u.audio);
    float av = fract(u.phase * 2.93);
    float3 auroraColor = mix(
        float3(0.12, 0.95, 0.55),
        float3(0.45, 0.35, 1.0),
        smoothstep(0.0, 0.95, sky + 0.35 * speech)
    );
    auroraColor = mix(auroraColor, mix(u.accent0, u.accent2, av), 0.15 + 0.4 * av);
    color += auroraColor * aurora * 0.8;

    float meteorCadence = 4.5 + 3.5 * fract(u.phase * 4.91);
    float epoch = floor(t / meteorCadence);
    float meteorPhase = fract(t / meteorCadence);
    float2 start = float2(-1.1 + 2.2 * h1(epoch * 1.3), 0.85 - 1.4 * h1(epoch * 2.9));
    float2 direction = normalize(float2(0.7 + 0.5 * h1(epoch * 4.1), -0.35 - 0.4 * h1(epoch * 5.3)));
    float2 head = start + direction * meteorPhase * 2.8;
    float2 relative = p - head;
    float along = dot(relative, direction);
    float perpendicular = dot(relative, float2(-direction.y, direction.x));
    float visibility = smoothstep(0.0, 0.06, meteorPhase) * smoothstep(0.5, 0.32, meteorPhase);
    float tail = exp(-perpendicular * perpendicular * 1600.0) * exp(along * 9.0)
        * step(along, 0.0) * smoothstep(-0.5, -0.02, along);
    float headGlow = exp(-dot(relative, relative) * 900.0);
    color += (float3(1.0) * headGlow * 1.2 + mix(float3(1.0), u.accent1, 0.3) * tail * 0.85) * visibility;

    float3 lightDirection = normalize(float3(0.85 * sin(t * 0.42), 0.45 * sin(t * 0.26 + 1.2), 0.5));
    float diffuse = (0.62 + 0.65 * max(dot(normal, lightDirection), 0.0)) * (1.0 + 0.35 * u.audio);
    color *= diffuse;
    float3 voiceColor = mix(u.accent1, float3(1.0, 0.97, 0.9), 0.45);
    color += voiceColor * pow(1.0 - rr, 1.8) * u.audio * 0.5;
    color += (u.accent1 * 0.7 + float3(0.12)) * fresnel * u.audio * 0.65;
    color += color * u.audio * 0.18 * sin(t * 14.0 + rr * 40.0 + u.phase * 7.0);
    float counter = max(dot(normal.xy, -lightDirection.xy), 0.0) * fresnel;
    color += mix(u.accent0, float3(0.5, 0.6, 0.9), 0.5) * counter * 0.18;

    float3 key = normalize(float3(-0.45 + 0.3 * sin(t * 0.34), 0.62 + 0.2 * sin(t * 0.27 + 1.7), 0.64));
    float keyAmount = 0.5 * (0.78 + 0.22 * sin(t * 0.45 + 2.2));
    color += float3(1.0) * pow(max(dot(normal, key), 0.0), 150.0) * keyAmount;
    float3 sheen = normalize(float3(sin(t * 0.07) * 0.9, 0.35 + 0.3 * cos(t * 0.05), 0.7));
    color += float3(1.0) * pow(max(dot(normal, sheen), 0.0), 7.0) * 0.05;
    float3 counterLight = normalize(float3(0.52, -0.5 + 0.12 * sin(t * 0.09), 0.69));
    color += float3(1.0) * pow(max(dot(normal, counterLight), 0.0), 140.0) * 0.25;
    color = mix(color, front.rgb, frontAlpha * fresnel * 0.3);
    color = mix(color, color * 0.85, smoothstep(0.94, 1.0, rr) * 0.4);
    return color;
}

fragment float4 orbFragment(OrbVertexOut in [[stage_in]],
                            constant OrbUniforms &u [[buffer(0)]]) {
    float2 point = in.uv * 2.0 - 1.0;

    // Alpha coverage: the WebGL version renders opaque into a circular CSS mask.
    // On a transparent NSPanel we must own the mask, so the disc edge is a
    // premultiplied antialiased circle instead. `fwidth` would be ideal but the
    // 512² offscreen size is fixed, so a constant-width feather is exact enough.
    float radius = length(point);
    float coverage = 1.0 - smoothstep(0.988, 1.0, radius);
    if (coverage <= 0.0) {
        return float4(0.0);
    }

    float3 color;
    bool lensed = false;
    if (u.lens > 0.0) {
        float exponent = exp(2.0 * 1.7724539 * (radius - 0.9) / 0.1414214);
        float falloff = 0.5 + 0.5 * (exponent - 1.0) / (exponent + 1.0);
        if (falloff > 0.004) {
            float swell = 1.0 + 0.16 * (
                0.6 * sin(u.time * 0.9 + u.phase)
                + 0.4 * sin(u.time * 1.7 + u.phase * 1.3)
            );
            float lens = u.lens * falloff * swell;
            float red = 1.4 * (1.0 + 0.06 * sin(u.time * 1.3 + u.phase));
            float green = 1.2 * (1.0 + 0.06 * sin(u.time * 1.3 + u.phase + 2.1));
            float blue = 1.0 * (1.0 + 0.06 * sin(u.time * 1.3 + u.phase + 4.2));
            color = float3(
                shade(point * (1.0 - lens * red), u).r,
                shade(point * (1.0 - lens * green), u).g,
                shade(point * (1.0 - lens * blue), u).b
            );
            float2 absolute = min(abs(point), 1.0);
            float lobe = max(
                abs(absolute.x * 0.766 + absolute.y * 0.643),
                abs(absolute.x * 0.766 - absolute.y * 0.643)
            );
            float glow = 0.65 * pow(clamp((lobe - 0.0707) / 1.3435, 0.0, 1.0), 2.4) * falloff;
            glow += 1.02 * clamp(1.0 + (radius - 1.0) / 0.15, 0.0, 1.0)
                * step(radius, 1.0) * pow(lobe, 2.0);
            color += float3(0.25) * min(glow, 1.0);
            lensed = true;
        }
    }
    if (!lensed) {
        color = shade(point, u);
    }

    // Premultiplied alpha — the layer is composited over the desktop.
    return float4(color * coverage, coverage);
}
