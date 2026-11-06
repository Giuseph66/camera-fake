package br.com.neurelix.vcam.engine.gl

/** Todos os shaders GLSL ES 3.00. Convenção: texturas intermediárias em orientação GL (topo da imagem em t=1). */
object Shaders {
    const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Passo de entrada/blit: aplica espelhamento, enquadramento (crop/fit/stretch), zoom/pan e rotação.
     * Variante OES (câmera/vídeo) ou 2D (bitmap ou textura GL já "em pé").
     * uMode: 0 = crop, 1 = fit, 2 = stretch.
     */
    fun source(oes: Boolean): String = (if (oes) "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n#define OES 1\n" else "#version 300 es\n") + """
precision highp float;
in vec2 vUv;
out vec4 o;
#ifdef OES
uniform samplerExternalOES uTex;
uniform mat4 uSt;
#else
uniform sampler2D uTex;
uniform float uFlipV;
#endif
uniform float uMirror;
uniform float uOutAspect;
uniform float uMode;
uniform vec2 uBuf;
uniform vec2 uRot;
uniform float uZoom;
uniform vec2 uPan;
void main() {
    vec2 p = vUv;
    if (uMirror > 0.5) p.x = 1.0 - p.x;
    vec2 q = p - 0.5;
    vec2 up = (abs(uRot.y) > 0.5) ? uBuf.yx : uBuf;
    float ca = up.x / up.y;
    vec2 c;
    if (uMode > 1.5) {
        c = q + 0.5;
    } else {
        float k = (uMode < 0.5) ? max(1.0, uOutAspect / ca) : min(1.0, uOutAspect / ca);
        c = vec2(q.x * uOutAspect / (k * ca), q.y / k) + 0.5;
    }
    c = (c - 0.5) / uZoom + 0.5 + uPan * (1.0 - 1.0 / uZoom) * 0.5;
    bool inside = c.x >= 0.0 && c.x <= 1.0 && c.y >= 0.0 && c.y <= 1.0;
    c = clamp(c, 0.0, 1.0);
    vec2 a = (c - 0.5) * up;
    vec2 b = vec2(a.x * uRot.x - a.y * uRot.y, a.x * uRot.y + a.y * uRot.x);
    vec2 s = b / uBuf + 0.5;
#ifdef OES
    vec2 t = (uSt * vec4(s, 0.0, 1.0)).xy;
    vec3 rgb = texture(uTex, t).rgb;
#else
    if (uFlipV > 0.5) s.y = 1.0 - s.y;
    vec3 rgb = texture(uTex, s).rgb;
#endif
    o = vec4(inside ? rgb : vec3(0.0), 1.0);
}
"""

    /** Redução de ruído: bilateral espacial (raio por prioridade) + temporal recursivo com bloqueio por movimento. */
    const val DENOISE = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uCur;
uniform sampler2D uHist;
uniform vec2 uTexel;
uniform float uStrength;
uniform float uHistValid;
uniform int uRadius;
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec3 c = textureLod(uCur, vUv, 0.0).rgb;
    vec3 acc = vec3(0.0); float wsum = 0.0;
    float sr = mix(0.03, 0.22, uStrength);
    float ss = mix(0.8, 2.0, uStrength);
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            if (abs(x) > uRadius || abs(y) > uRadius) continue;
            vec3 n = textureLod(uCur, vUv + vec2(float(x), float(y)) * uTexel, 0.0).rgb;
            vec3 d = n - c;
            float ws = exp(-float(x * x + y * y) / (2.0 * ss * ss));
            float wr = exp(-dot(d, d) / (2.0 * sr * sr));
            float w = ws * wr;
            acc += n * w; wsum += w;
        }
    }
    vec3 spatial = acc / max(wsum, 1e-5);
    vec3 h = textureLod(uHist, vUv, 0.0).rgb;
    float diff = abs(lum(spatial) - lum(h));
    float wt = uHistValid * uStrength * 0.75 * exp(-(diff * diff) / (2.0 * 0.05 * 0.05));
    o = vec4(mix(spatial, h, wt), 1.0);
}
"""

    /** Nitidez + exposição (ganho em EV, via domínio linear aproximado) + brilho/contraste/gama/temperatura/saturação. */
    const val GRADE = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uSharp;
uniform float uGain;
uniform float uBright;
uniform float uContrast;
uniform float uSat;
uniform float uTemp;
uniform float uGamma;
void main() {
    vec3 c = textureLod(uTex, vUv, 0.0).rgb;
    if (uSharp > 0.0) {
        vec3 n = textureLod(uTex, vUv + vec2(0.0, uTexel.y), 0.0).rgb + textureLod(uTex, vUv - vec2(0.0, uTexel.y), 0.0).rgb
               + textureLod(uTex, vUv + vec2(uTexel.x, 0.0), 0.0).rgb + textureLod(uTex, vUv - vec2(uTexel.x, 0.0), 0.0).rgb;
        c += (4.0 * c - n) * (uSharp * 0.9);
    }
    c = clamp(c, 0.0, 1.0);
    if (uGain != 1.0) {
        c = pow(c, vec3(2.2)) * uGain;
        c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    }
    c += uBright;
    c = (c - 0.5) * uContrast + 0.5;
    c = vec3(c.r * (1.0 + 0.25 * uTemp), c.g, c.b * (1.0 - 0.25 * uTemp));
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, uSat);
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / uGamma));
    o = vec4(c, 1.0);
}
"""

    /** Medição de luminância: amostra o nível de mipmap grosso numa grade 4x4. */
    const val METER = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform float uLod;
void main() {
    float l = dot(textureLod(uTex, vUv, uLod).rgb, vec3(0.299, 0.587, 0.114));
    o = vec4(l, l, l, 1.0);
}
"""

    /**
     * Padrões de diagnóstico procedurais (sem CPU). Quadros numerados trazem:
     *  - dígitos 7 segmentos (contador de quadros);
     *  - código de barras binário de 32 bits do contador (linha inferior) e 16 bits de tempo em ms (linha acima);
     *  - letra "F" no canto superior esquerdo para verificar orientação/espelhamento.
     * uPattern: 0 barras, 1 gradiente, 2 numerado, 3 barras com ruído, 9 aviso de fonte perdida.
     */
    const val PATTERN = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform int uPattern;
uniform float uFrameHi;
uniform float uFrameLo;
uniform float uTimeBits;
uniform float uNoise;
uniform float uAspect;

const int SEG[10] = int[10](0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F);

float box(vec2 p, vec2 lo, vec2 hi) { return step(lo.x, p.x) * step(p.x, hi.x) * step(lo.y, p.y) * step(p.y, hi.y); }

float digit(vec2 p, int d) {
    int m = SEG[d];
    float r = 0.0;
    if ((m & 1)  != 0) r += box(p, vec2(0.15, 1.82), vec2(0.85, 1.98));
    if ((m & 2)  != 0) r += box(p, vec2(0.82, 1.02), vec2(0.98, 1.88));
    if ((m & 4)  != 0) r += box(p, vec2(0.82, 0.12), vec2(0.98, 0.98));
    if ((m & 8)  != 0) r += box(p, vec2(0.15, 0.02), vec2(0.85, 0.18));
    if ((m & 16) != 0) r += box(p, vec2(0.02, 0.12), vec2(0.18, 0.98));
    if ((m & 32) != 0) r += box(p, vec2(0.02, 1.02), vec2(0.18, 1.88));
    if ((m & 64) != 0) r += box(p, vec2(0.15, 0.92), vec2(0.85, 1.08));
    return clamp(r, 0.0, 1.0);
}

vec3 bars(float x) {
    int i = int(clamp(floor(x * 8.0), 0.0, 7.0));
    if (i == 0) return vec3(1.0);
    if (i == 1) return vec3(1.0, 1.0, 0.0);
    if (i == 2) return vec3(0.0, 1.0, 1.0);
    if (i == 3) return vec3(0.0, 1.0, 0.0);
    if (i == 4) return vec3(1.0, 0.0, 1.0);
    if (i == 5) return vec3(1.0, 0.0, 0.0);
    if (i == 6) return vec3(0.0, 0.0, 1.0);
    return vec3(0.0);
}

vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031 + vec3(uFrameLo * 0.0137 + uFrameHi * 0.31));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 uv = vUv;
    vec3 c;
    if (uPattern == 0) {
        c = bars(uv.x);
        if (uv.y < 0.15) c = vec3(uv.x);
    } else if (uPattern == 1) {
        c = hsv(uv.x, 0.85, mix(0.25, 1.0, uv.y));
    } else if (uPattern == 3) {
        c = 0.2 + 0.6 * bars(uv.x);
        if (uv.y < 0.15) c = vec3(0.5);
        vec2 px = floor(uv * vec2(1280.0, 720.0));
        float n = (hash(px) + hash(px + 17.3) + hash(px + 41.7) - 1.5) * 2.0;
        c = clamp(c + n * uNoise, 0.0, 1.0);
    } else if (uPattern == 9) {
        float stripe = step(0.5, fract((uv.x * uAspect + uv.y) * 10.0));
        c = mix(vec3(0.35, 0.02, 0.02), vec3(0.6, 0.05, 0.05), stripe);
        if (abs(uv.x - uv.y * 0.5625 - 0.2) < 0.01 || abs(uv.x + uv.y * 0.5625 - 0.8) < 0.01) c = vec3(1.0);
    } else {
        c = vec3(0.10, 0.12, 0.16) + 0.06 * vec3(step(0.5, fract(uv.x * 16.0)) * step(0.5, fract(uv.y * 9.0)));
        // letra F (orientação) no canto superior esquerdo
        vec2 f = vec2(uv.x * uAspect, uv.y);
        float fm = box(f, vec2(0.04 * 1.0, 0.70), vec2(0.07, 0.96)) + box(f, vec2(0.04, 0.93), vec2(0.18, 0.96)) + box(f, vec2(0.04, 0.82), vec2(0.14, 0.85));
        c = mix(c, vec3(1.0, 0.8, 0.1), clamp(fm, 0.0, 1.0));
        // dígitos (6) centrais
        float frame = uFrameHi * 65536.0 + uFrameLo;
        vec2 dp = (uv - vec2(0.15, 0.30)) / vec2(0.12, 0.30) ;
        dp = vec2(dp.x * 1.0, dp.y * 2.0);
        float di = floor(dp.x);
        if (di >= 0.0 && di < 6.0 && dp.y >= 0.0 && dp.y <= 2.0) {
            float div = pow(10.0, 5.0 - di);
            int d = int(mod(floor(frame / div), 10.0));
            float lx = (fract(dp.x) - 0.12) / 0.76;
            if (lx >= 0.0 && lx <= 1.0) c = mix(c, vec3(0.2, 1.0, 0.4), digit(vec2(lx, dp.y), d));
        }
        // código de barras: 32 bits do quadro (linha y 0.04..0.12) e 16 bits de tempo (0.14..0.22)
        float bx = uv.x * 40.0 - 4.0;
        if (bx >= 0.0 && bx < 32.0 && uv.y > 0.04 && uv.y < 0.12) {
            float bi = floor(bx);
            // bits 0..15 => uFrameHi (MSB primeiro); 16..31 => uFrameLo
            float vv = bi < 16.0 ? uFrameHi : uFrameLo;
            float bit = mod(floor(vv / pow(2.0, 15.0 - mod(bi, 16.0))), 2.0);
            c = vec3(bit);
        }
        if (bx >= 0.0 && bx < 16.0 && uv.y > 0.14 && uv.y < 0.22) {
            float bit = mod(floor(uTimeBits / pow(2.0, 15.0 - floor(bx))), 2.0);
            c = vec3(bit);
        }
        // marcadores de alinhamento
        if (uv.y > 0.04 && uv.y < 0.22 && (uv.x < 0.075 && uv.x > 0.02)) c = vec3(1.0, 0.0, 1.0);
    }
    o = vec4(c, 1.0);
}
"""
}
