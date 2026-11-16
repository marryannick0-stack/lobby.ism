package com.lobbyism.app.render

/**
 * All GLSL sources. Written in GLSL ES 1.00 (OpenGL ES 2.0) on purpose: it runs
 * on every Android phone since 2012, including the Galaxy A50.
 *
 * Visual rule of the project: everything looks as if it has not fully arrived
 * in this world. Unstable, fragmented, analog-degraded. Never clean sci-fi.
 */
object Shaders {

    // ---------------------------------------------------------------- camera

    const val CAMERA_VS = """
attribute vec2 aPos;
uniform mat4 uTexMatrix;
varying vec2 vUv;
void main() {
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** The real world is drawn untouched: no grain, no tint (GDD: effects only on LOBBYISM elements). */
    const val CAMERA_FS = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uCamera;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(texture2D(uCamera, vUv).rgb, 1.0);
}
"""

    // ---------------------------------------------------------- point clouds

    /**
     * One shader for nodes and sky pillars. Every point carries a random seed;
     * all instability is derived from seed + time, so no per-frame CPU work.
     *
     * uMaterialize: 0 = particles scattered in space, 1 = assembled.
     * uGlitch:      0 = stable, 1 = pure noise (points jump and drop out).
     */
    const val POINTS_VS = """
attribute vec3 aPos;
attribute float aSeed;
uniform mat4 uMvp;
uniform float uTime;
uniform float uGlitch;
uniform float uMaterialize;
uniform float uPointSize;
uniform float uLock;
varying float vAlpha;

float hash(float n) { return fract(sin(n * 12.9898) * 43758.5453); }

void main() {
    vec3 rnd = vec3(hash(aSeed), hash(aSeed + 1.7), hash(aSeed + 3.1)) - 0.5;
    vec3 p = aPos;

    // Breathing: the whole form swells slightly, each point with its own phase.
    p *= 1.0 + 0.05 * sin(uTime * 1.7 + aSeed * 6.2831);

    // Not yet materialised: particles hang scattered and fall into place.
    float scatter = (1.0 - uMaterialize) * (1.0 - uMaterialize);
    p += rnd * 7.0 * scatter;

    // Glitch: in discrete time steps a share of the points jumps away.
    float step14 = floor(uTime * 14.0);
    float jump = step(1.0 - 0.30 * uGlitch, hash(aSeed + step14 * 0.37));
    p += rnd * jump * uGlitch * 0.8;

    // Missing parts: a share of the points is simply not there for a while.
    float gone = step(hash(aSeed * 3.3 + floor(uTime * 3.0) * 0.11), uGlitch * 0.5);

    gl_Position = uMvp * vec4(p, 1.0);
    if (gone > 0.5) gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // outside the view volume
    gl_PointSize = max(1.5, uPointSize / max(gl_Position.w, 0.05));

    // Nodes pulse from the INSIDE outwards (art constraint 9): a brightness
    // wave travels from the centre to the rim. A complete lock steadies it.
    float wave = 0.5 + 0.5 * sin(uTime * 3.2 - length(aPos) * 9.0);
    vAlpha = mix(0.35 + 0.65 * wave, 1.0, uLock) * (0.25 + 0.75 * uMaterialize + 0.2 * scatter);
}
"""

    const val POINTS_FS = """
precision mediump float;
uniform vec3 uColor;
varying float vAlpha;
void main() {
    float d = length(gl_PointCoord - 0.5);
    if (d > 0.5) discard;
    gl_FragColor = vec4(uColor, vAlpha * (1.0 - d * 1.5));
}
"""

    // -------------------------------------------------------------- composite

    const val COMPOSITE_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Lays the LOBBYISM layer over the camera image and degrades it like a worn
     * video signal: colour channels drift apart, lines wobble, bands tear
     * sideways on events, grain and scanlines sit on the objects only.
     */
    const val COMPOSITE_FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uLayer;
uniform float uTime;
uniform float uPulse;
uniform float uAberration;
uniform float uScanlines;
varying vec2 vUv;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec2 uv = vUv;

    // Tracking error: on a pulse, horizontal bands tear sideways.
    float band = floor(uv.y * 36.0);
    float tear = step(0.84, hash(vec2(band, floor(uTime * 18.0)))) * uPulse;
    uv.x += (hash(vec2(band, 3.0)) - 0.5) * 0.10 * tear;

    // Permanent, barely visible line wobble.
    uv.x += sin(uv.y * 220.0 + uTime * 9.0) * 0.0009;

    // Colour channels drift apart, more so during a pulse.
    float ab = uAberration * (1.0 + 3.0 * uPulse);
    vec4 cr = texture2D(uLayer, uv + vec2(ab, 0.0));
    vec4 cg = texture2D(uLayer, uv);
    vec4 cb = texture2D(uLayer, uv - vec2(ab, 0.0));
    vec3 col = vec3(cr.r, cg.g, cb.b);
    float a = max(cg.a, max(cr.a, cb.a));

    float scan = 0.80 + 0.20 * sin(vUv.y * uScanlines);
    float grain = (hash(vUv * vec2(913.0, 517.0) + uTime) - 0.5) * 0.30;
    col = clamp(col * scan + grain * a, 0.0, 1.0);

    // Premultiplied output; the camera stays partly visible through the objects.
    gl_FragColor = vec4(col, a * 0.55);
}
"""
}
