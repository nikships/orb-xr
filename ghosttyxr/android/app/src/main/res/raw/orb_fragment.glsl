precision highp float;

varying vec3 vNormal;         // shading normal, quad-local space
varying vec3 vObjectNormal;   // unit-sphere position, orb-local space
varying vec3 vLocalPosition;  // fragment position, quad-local space

uniform vec3 uAnchor, uC0, uC1, uC2;
uniform vec3 uEye;            // eye position, quad-local space (differs per eye: real stereo)
uniform vec3 uEyeObject;      // eye position, orb-local space
uniform vec2 uRes;            // the ball's own on-surface pixel size, for detail LOD
uniform float uTime;
uniform float uPhase;
uniform float uAudio;
uniform float uSpin;
uniform float uArch;

// A black marble with a galaxy turning inside it.
//
// This is a port of the website's orb (`website/src/components/voice/orb-renderer.ts`) onto a real
// 3D mesh with real per-eye stereo. The structure that makes it read as a black marble rather than
// a lava lamp is the **weight channel**: `starfield` returns colour *and* how much of it exists at
// that point, and the galaxy is `mix`ed over a near-black void by that weight. Most of the sphere
// therefore stays at the void colour, and the nebula band, stars, and tracers are the only things
// that light up. Adding colour everywhere instead of compositing it by weight is exactly what turns
// this into a multi-coloured mess.
//
// The dust is also deliberately pale (0.72, 0.78, 0.92) and only *tinted* toward the accents. The
// brand hues are accents on white dust, not the substance of the thing.

float h1(float x) { return fract(sin(x * 127.1) * 43758.5453); }

/** Galaxy sampled along a direction on the unit sphere. Returns (colour, weight). */
vec4 starfield(vec3 n, float t) {
  float lon = atan(n.z, n.x);
  float lat = asin(clamp(n.y, -1.0, 1.0));
  float v1 = fract(uPhase * 7.13);
  float v2 = fract(uPhase * 3.71);
  float v3 = fract(uPhase * 5.37);
  float at = uArch;
  float isNeb = step(0.5, at) * (1.0 - step(1.5, at));
  float isCore = step(1.5, at) * (1.0 - step(2.5, at));
  float isDeep = step(2.5, at);

  // The galactic band: a narrow, wavy arc across the sphere. This is the primary "tracer".
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

  vec3 hue = mix(
    mix(uC0, uC1, v1),
    mix(uC1, uC2, v3),
    0.5 + 0.5 * sin(lon + lat * 2.0 - t * 0.2)
  );
  // Saturation *boost* about the accent's own luminance, not a desaturation.
  vec3 hueGrey = vec3(dot(hue, vec3(0.299, 0.587, 0.114)));
  hue = clamp(hueGrey + (hue - hueGrey) * 1.45, 0.0, 1.0);
  vec3 dust = mix(vec3(0.72, 0.78, 0.92), hue, 0.45 + 0.3 * v1 + 0.45 * isNeb);
  vec3 col = dust * galaxy * (0.6 + 0.9 * isNeb);

  float shear = sin(lon * 13.0 + lat * 4.0 - t * 0.35) * sin(lon * 5.0 + t * 0.2);
  col += dust * band * neb * max(shear, 0.0) * 0.14;
  float gb2 = lat - (0.35 + 0.25 * v2) * sin(lon * 2.0 - 1.1) + 0.4;
  float arm = exp(-gb2 * gb2 * 7.0) * neb;
  col += mix(dust, uC1, 0.35) * arm * 0.2;

  vec3 voidGlow = mix(vec3(0.04, 0.03, 0.1), mix(uC0, mix(uC1, uC2, v3), v1) * 0.22, 0.75);
  col += voidGlow * (0.5 + 0.22 * sin(t * 0.4 + lon)) * (0.4 + 0.6 * band);
  col += vec3(1.0, 0.88, 0.68) * pow(band, 4.0) * pow(neb, 2.0) * 0.4;

  float ca = v2 * 6.28318;
  vec3 coreDir = normalize(vec3(cos(ca) * 0.85, 0.6 * (v3 - 0.5), sin(ca) * 0.85));
  float bulge = max(dot(n, coreDir), 0.0);
  col += mix(vec3(1.0, 0.85, 0.6), uC2, 0.25)
    * (pow(bulge, 14.0) * 1.6 + pow(bulge, 4.0) * 0.5) * isCore;

  float pocket = pow(neb, 5.0) * band * (0.7 + 0.3 * sin(t * 0.6 + lon * 3.0));
  col += mix(uC2, uC0, fract(v1 + 0.5 * sin(lon * 2.0) + 0.5))
    * pocket * (0.5 + 0.4 * v2 + 0.8 * isNeb);
  float pocket2 = pow(0.5 + 0.5 * sin(lon * 3.0 + lat * 4.0 - t * 0.18 + 2.0), 6.0) * band;
  col += mix(uC1, uC2, v3) * pocket2 * (0.25 + 0.3 * v1 + 0.5 * isNeb);

  float detail = smoothstep(90.0, 200.0, uRes.y);
  vec2 gg = vec2(lon, lat) * 34.0;
  vec2 gc = floor(gg);
  vec2 gf = fract(gg);
  float gh = h1(gc.x * 3.7 + gc.y * 11.3);
  vec2 gp = vec2(0.2 + 0.6 * h1(gh * 91.0), 0.2 + 0.6 * h1(gh * 47.0));
  float gd = length((gf - gp) * vec2(cos(lat), 1.0));
  float grain = exp(-gd * gd * 700.0 * clamp(uRes.y / 420.0, 0.22, 1.0))
    * step(0.3, gh) * (0.15 + 0.85 * band);
  col += vec3(0.88, 0.9, 1.0) * grain * 0.4 * detail;
  float weight = clamp(galaxy * 0.7 + pow(band, 4.0) * 0.25, 0.0, 1.0);

  for (int s = 0; s < 3; s++) {
    float scale = s == 0 ? 6.0 : (s == 1 ? 11.0 : 19.0);
    vec2 g = vec2(lon, lat) * scale;
    vec2 cell = floor(g);
    vec2 f = fract(g);
    float hx = h1(cell.x * 13.7 + cell.y * 7.3 + float(s) * 91.0);
    float hy = h1(cell.x * 5.1 + cell.y * 17.9 + float(s) * 37.0);
    vec2 sp = vec2(0.15 + 0.7 * hx, 0.15 + 0.7 * hy);
    float distanceToStar = length((f - sp) * vec2(cos(lat), 1.0));
    float census = (v2 - 0.5) * 0.2 + 0.35 * isNeb - 0.2 * isCore + 0.3 * isDeep;
    float keep = step((s == 2 ? 0.3 : 0.55) + census, h1(hx * 89.0 + hy * 31.0) + band * 0.25);
    float res = clamp(uRes.y / 420.0, 0.22, 1.0);
    float twinkle = mix(0.92, 0.6 + 0.4 * sin(t * (1.5 + 3.0 * hx) + hx * 40.0), res);
    float hz = h1(hx * 53.0 + hy * 71.0 + cell.x);
    float sizeJitter = 0.35 + 1.8 * hz * hz;
    float sharp = (s == 0 ? 260.0 : (s == 1 ? 700.0 : 1600.0)) / sizeJitter * res;
    float star = exp(-distanceToStar * distanceToStar * sharp) * keep * twinkle;
    vec3 tint = mix(
      vec3(1.0),
      hx < 0.33 ? vec3(0.85, 0.9, 1.0) : (hx < 0.66 ? vec3(1.0, 0.95, 0.85) : mix(vec3(1.0), uC1, 0.3)),
      0.6
    );
    float bright = (s == 0 ? 1.7 : (s == 1 ? 0.9 : 0.5)) * (0.55 + 0.7 * sizeJitter);
    float starFade = mix(s == 2 ? 0.14 : 0.45, 1.0, detail);
    col += tint * star * bright * starFade;
    if (s == 0) {
      float big = smoothstep(1.2, 2.0, sizeJitter);
      col += tint * exp(-distanceToStar * distanceToStar * 60.0) * 0.18 * big * twinkle * starFade;
      vec2 delta = (f - sp) * vec2(cos(lat), 1.0);
      float spike = exp(-delta.x * delta.x * 1200.0) * exp(-delta.y * delta.y * 26.0)
        + exp(-delta.y * delta.y * 1200.0) * exp(-delta.x * delta.x * 26.0);
      col += tint * spike * 0.3 * big * twinkle * starFade;
      weight = max(weight, spike * 0.3 * big * starFade);
    }
    weight = max(weight, star * min(bright, 1.5) * starFade);
  }

  float pa = v1 * 6.28318;
  vec3 pulsarDir = normalize(vec3(sin(pa) * 0.9, 1.4 * (v2 - 0.5), cos(pa) * 0.9));
  float pd = max(dot(n, pulsarDir), 0.0);
  float beat = pow(0.5 + 0.5 * sin(t * (1.2 + v3 + 1.5 * uAudio) + v3 * 6.28), 8.0);
  beat = min(1.0, beat + 0.6 * uAudio);
  float pulsarFade = mix(0.45, 1.0, detail);
  col += vec3(0.9, 0.95, 1.0)
    * (pow(pd, 900.0) * (0.6 + 1.2 * beat) + pow(pd, 110.0) * 0.5 * beat) * pulsarFade;
  weight = max(weight, pow(pd, 900.0) * (0.5 + 0.5 * beat) * pulsarFade);
  return vec4(min(col, vec3(1.0)), min(weight, 1.0));
}

/**
 * Tumble the sampling direction rather than the mesh.
 *
 * The website rotates the lookup because its sphere is a screen-space impostor. Here it matters for
 * a different reason: rotating the actual geometry would drag the speculars around with it, and a
 * highlight that slides across a marble as it spins is the one thing that instantly breaks the
 * illusion of a solid, lit object. Spinning only the interior keeps the galaxy turning while the
 * lighting stays anchored to the room.
 */
vec3 tumble(vec3 n, float spin, float t) {
  float roll = t * 0.13;
  float cr = cos(roll);
  float sr = sin(roll);
  n = vec3(cr * n.x - sr * n.y, sr * n.x + cr * n.y, n.z);
  float tilt = 0.45 + 0.35 * sin(t * 0.24);
  float cx = cos(tilt);
  float sx = sin(tilt);
  n = vec3(n.x, cx * n.y - sx * n.z, sx * n.y + cx * n.z);
  float cs = cos(spin);
  float ss = sin(spin);
  return vec3(cs * n.x + ss * n.z, n.y, -ss * n.x + cs * n.z);
}

void main() {
  vec3 normal = normalize(vNormal);
  vec3 view = normalize(uEye - vLocalPosition);
  float facing = clamp(dot(normal, view), 0.0, 1.0);
  // Distance from the centre of the ball's silhouette, the mesh equivalent of the website's disc
  // radius. Identity for a unit sphere: rr = sin(angle), facing = cos(angle).
  float rr = sqrt(max(0.0, 1.0 - facing * facing));
  float fresnel = pow(1.0 - facing, 2.4);
  float t = uTime * 0.8 + uPhase;

  vec3 sphere = normalize(vObjectNormal);
  vec3 objectView = normalize(uEyeObject - sphere);
  // Refract into the marble and find where the ray leaves the far wall. Sampling the galaxy at both
  // the near surface and that far point is what gives the interior real depth — and because the
  // view vector is genuinely per-eye, the two layers separate differently for each eye.
  vec3 ray = refract(-objectView, sphere, 0.75);
  float hit = -2.0 * dot(sphere, ray);
  vec3 backNormal = normalize(sphere + ray * hit);

  float sv = fract(uPhase * 6.31);
  float sw = fract(uPhase * 2.17);
  float warpedTime = t
    + (0.9 + 1.3 * sv) * sin(t * (0.09 + 0.07 * sw))
    + (0.5 + 0.8 * sw) * sin(t * (0.21 + 0.09 * sv) + 2.6);
  float backTime = warpedTime * 0.8 + 2.7;
  vec3 frontDir = tumble(sphere, uSpin, warpedTime);
  vec4 front = starfield(frontDir, warpedTime);
  vec4 back = starfield(tumble(backNormal, uSpin, backTime), backTime);

  // Near-black void, and the galaxy composited onto it by weight. This is the whole reason the orb
  // reads as a dark marble: where there is no galaxy, there is no light.
  vec3 voidColor = mix(uAnchor * 0.04, uAnchor * 0.35, fresnel);
  vec3 color = voidColor * (0.97 - 0.04 * fresnel);
  float frontAlpha = clamp(front.a, 0.0, 1.0);
  float backAlpha = clamp(back.a, 0.0, 1.0);
  color = mix(color, back.rgb, backAlpha * 0.16);
  color = mix(color, front.rgb, frontAlpha * 0.85);

  // Aurora tracers, anchored to the tumbling interior so they drift with the galaxy instead of
  // sitting on the surface like a decal.
  float longitude = atan(frontDir.x, frontDir.z);
  float speech = pow(
    0.5 + 0.5 * sin(longitude * 3.0 + sin(longitude * 7.0 + t * 1.1) * 0.7 + t * 0.5),
    3.0
  ) * (0.55 + 0.45 * sin(longitude * 5.0 - t * 0.65 + 1.7));
  float sky = -frontDir.y;
  float hang = smoothstep(-0.15, 0.5, sky);
  float rays = 0.7 + 0.3 * sin(longitude * 24.0 + sin(longitude * 9.0 - t * 0.8) * 2.0 + t * 1.6);
  float aurora = clamp(speech, 0.0, 1.0) * hang * rays * (1.0 + 2.2 * uAudio);
  float av = fract(uPhase * 2.93);
  vec3 auroraColor = mix(
    vec3(0.12, 0.95, 0.55),
    vec3(0.45, 0.35, 1.0),
    smoothstep(0.0, 0.95, sky + 0.35 * speech)
  );
  auroraColor = mix(auroraColor, mix(uC0, uC2, av), 0.15 + 0.4 * av);
  color += auroraColor * aurora * 0.8;

  // A view-plane basis, so the meteor can streak across the visible disc exactly as it does on the
  // website, where the shader had screen coordinates to work with.
  vec3 axis = abs(view.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
  vec3 discRight = normalize(cross(axis, view));
  vec3 discUp = cross(view, discRight);
  vec3 planar = normal - view * facing;
  vec2 p = vec2(dot(planar, discRight), dot(planar, discUp));

  float meteorCadence = 4.5 + 3.5 * fract(uPhase * 4.91);
  float epoch = floor(t / meteorCadence);
  float meteorPhase = fract(t / meteorCadence);
  vec2 start = vec2(-1.1 + 2.2 * h1(epoch * 1.3), 0.85 - 1.4 * h1(epoch * 2.9));
  vec2 direction = normalize(vec2(0.7 + 0.5 * h1(epoch * 4.1), -0.35 - 0.4 * h1(epoch * 5.3)));
  vec2 head = start + direction * meteorPhase * 2.8;
  vec2 relative = p - head;
  float along = dot(relative, direction);
  float perpendicular = dot(relative, vec2(-direction.y, direction.x));
  float visibility = smoothstep(0.0, 0.06, meteorPhase) * smoothstep(0.5, 0.32, meteorPhase);
  float tail = exp(-perpendicular * perpendicular * 1600.0) * exp(along * 9.0)
    * step(along, 0.0) * smoothstep(-0.5, -0.02, along);
  float headGlow = exp(-dot(relative, relative) * 900.0);
  color += (vec3(1.0) * headGlow * 1.2 + mix(vec3(1.0), uC1, 0.3) * tail * 0.85) * visibility;

  vec3 lightDirection = normalize(vec3(0.85 * sin(t * 0.42), 0.45 * sin(t * 0.26 + 1.2), 0.5));
  float diffuse = (0.62 + 0.65 * max(dot(normal, lightDirection), 0.0)) * (1.0 + 0.35 * uAudio);
  color *= diffuse;
  vec3 voiceColor = mix(uC1, vec3(1.0, 0.97, 0.9), 0.45);
  color += voiceColor * pow(1.0 - rr, 1.8) * uAudio * 0.5;
  color += (uC1 * 0.7 + vec3(0.12)) * fresnel * uAudio * 0.65;
  color += color * uAudio * 0.18 * sin(t * 14.0 + rr * 40.0 + uPhase * 7.0);
  float counter = max(dot(normal.xy, -lightDirection.xy), 0.0) * fresnel;
  color += mix(uC0, vec3(0.5, 0.6, 0.9), 0.5) * counter * 0.18;

  // Glass speculars. On a near-black body these are most of what says "solid object", and they are
  // computed from the true per-eye normal so each eye catches them in a slightly different place.
  vec3 key = normalize(vec3(-0.45 + 0.3 * sin(t * 0.34), 0.62 + 0.2 * sin(t * 0.27 + 1.7), 0.64));
  float keyAmount = 0.5 * (0.78 + 0.22 * sin(t * 0.45 + 2.2));
  color += vec3(1.0) * pow(max(dot(normal, key), 0.0), 150.0) * keyAmount;
  vec3 sheen = normalize(vec3(sin(t * 0.07) * 0.9, 0.35 + 0.3 * cos(t * 0.05), 0.7));
  color += vec3(1.0) * pow(max(dot(normal, sheen), 0.0), 7.0) * 0.05;
  vec3 counterLight = normalize(vec3(0.52, -0.5 + 0.12 * sin(t * 0.09), 0.69));
  color += vec3(1.0) * pow(max(dot(normal, counterLight), 0.0), 140.0) * 0.25;
  color = mix(color, front.rgb, frontAlpha * fresnel * 0.3);
  color = mix(color, color * 0.85, smoothstep(0.94, 1.0, rr) * 0.4);

  gl_FragColor = vec4(color, 1.0);
}
