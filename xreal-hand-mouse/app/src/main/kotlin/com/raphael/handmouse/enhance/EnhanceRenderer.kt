package com.raphael.handmouse.enhance

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface

/**
 * The enhancer's GPU pass (2026-09-29): one decoded MJPEG frame in, one frame into the encoder's
 * input [Surface] out. Four full-screen passes, OpenGL ES 3.0:
 *
 * 1. **H** — RGB → YCbCr (BT.601 full range, what JPEG stores); luma deblocking across the
 *    vertical 8-px block edges.
 * 2. **V** — the same across the horizontal edges; chroma rebuilt from the 16×16 tile centres
 *    (at the stream's JPEG quality the chroma is mostly one flat value per tile) with a joint
 *    bilateral filter guided by luma, so colour follows edges instead of squares.
 * 3. **T** — temporal denoise: blends in the previous output, motion-compensated with the
 *    global frame motion, less where the two disagree (moving hands, occlusions).
 * 4. **F** — stabilizing crop/zoom into the output size, a light unsharp mask on luma (with
 *    coring, so JPEG noise is not sharpened), YCbCr → RGB, the tone curve — on luma, applied as
 *    one gain to all three channels. Per channel, the black level cut green (the Eye's darkest
 *    channel at night) to 0 first and turned dark areas into purple blotches (S25 Edge, 2026-09-29).
 *
 * Passes 1–3 run in source pixel coordinates, framebuffer row 0 = image top row; pass 4 flips
 * into the window's bottom-up rows. Intermediates are RGBA16F where renderable, else RGBA8.
 *
 * Single-threaded: every call on the thread that constructed it.
 */
class EnhanceRenderer(
    surface: Surface,
    private val srcWidth: Int,
    private val srcHeight: Int,
    /** Source rows that carry picture (the MJPEG stream's bottom rows are garbage). */
    private val usableHeight: Int,
    private val outWidth: Int,
    private val outHeight: Int,
) : AutoCloseable {

    companion object {
        private const val TAG = "EnhanceRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT = 0x40

        const val DEBLOCK_ALPHA = 0.06f
        const val DEBLOCK_BETA = 0.02f
        const val CHROMA_SIGMA_PX = 12f
        const val CHROMA_SIGMA_LUMA = 0.1f
        const val TEMPORAL_MAX = 0.5f
        const val SHARPEN = 0.3f

        private const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        /** Needs `float Y(ivec2)`, `uAlpha`, `uBeta`. Deblocked luma [y] of the pixel [p] across the
         * block edges along [dir] ([len] pixels in that direction, a multiple of 8). */
        private const val DEBLOCK = """
uniform float uAlpha;
uniform float uBeta;
float deblock(ivec2 p, ivec2 dir, int len, float y) {
    int x = p.x * dir.x + p.y * dir.y;
    int m = x & 7;
    bool qSide = m < 4;
    int k = qSide ? m : 7 - m;
    int b = qSide ? x - m : x - m + 8;
    if (b <= 0 || b + 3 >= len) return y;
    ivec2 base = p - dir * x;
    float p0 = Y(base + dir * (b - 1));
    float q0 = Y(base + dir * b);
    float s = q0 - p0;
    if (abs(s) >= uAlpha) return y;
    float p1 = Y(base + dir * (b - 2));
    float q1 = Y(base + dir * (b + 1));
    bool f2 = abs(p0 - p1) < uBeta && abs(q0 - q1) < uBeta;
    float n = 1.0;
    if (f2) {
        float p2 = Y(base + dir * (b - 3));
        float p3 = Y(base + dir * (b - 4));
        float q2 = Y(base + dir * (b + 2));
        float q3 = Y(base + dir * (b + 3));
        bool f4 = abs(p1 - p2) < uBeta && abs(p2 - p3) < uBeta && abs(q1 - q2) < uBeta && abs(q2 - q3) < uBeta;
        n = f4 ? 4.0 : 2.0;
    }
    float kf = float(k);
    if (kf >= n) return y;
    float off = s * (n - kf - 0.5) / (2.0 * n);
    return qSide ? y - off : y + off;
}
"""

        private const val PASS_H = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uSrc;
uniform ivec2 uSize;
out vec4 frag;
float Y(ivec2 p) { return dot(texelFetch(uSrc, p, 0).rgb, vec3(0.299, 0.587, 0.114)); }
$DEBLOCK
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    float cb = dot(c, vec3(-0.168736, -0.331264, 0.5)) + 0.5;
    float cr = dot(c, vec3(0.5, -0.418688, -0.081312)) + 0.5;
    frag = vec4(deblock(p, ivec2(1, 0), uSize.x, y), cb, cr, 1.0);
}"""

        private const val PASS_V = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uYcc;
uniform ivec2 uSize;
uniform float uSigmaS2;
uniform float uSigmaR2;
out vec4 frag;
float Y(ivec2 p) { return texelFetch(uYcc, p, 0).r; }
$DEBLOCK
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uYcc, p, 0);
    float y = deblock(p, ivec2(0, 1), uSize.y, c.r);
    vec2 pos = vec2(p) + 0.5;
    ivec2 t0 = ivec2(floor((pos - 8.0) / 16.0)) - 1;
    vec2 acc = vec2(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            ivec2 cp = clamp((t0 + ivec2(i, j)) * 16 + 8, ivec2(0), uSize - 1);
            vec4 s = texelFetch(uYcc, cp, 0);
            vec2 d = vec2(cp) + 0.5 - pos;
            float dl = s.r - c.r;
            float w = exp(-dot(d, d) / (2.0 * uSigmaS2) - dl * dl / (2.0 * uSigmaR2));
            acc += w * s.gb;
            wsum += w;
        }
    }
    frag = vec4(y, wsum > 1e-4 ? acc / wsum : c.gb, 1.0);
}"""

        private const val PASS_T = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform vec2 uTexSize;
uniform vec2 uValid;
uniform vec2 uCenter;
uniform vec3 uMotion;
uniform float uKmax;
out vec4 frag;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uCur, p, 0);
    if (uKmax <= 0.0) { frag = c; return; }
    vec2 r = vec2(p) + 0.5 - uCenter - uMotion.xy;
    float cs = cos(uMotion.z);
    float sn = sin(uMotion.z);
    vec2 q = uCenter + vec2(cs * r.x + sn * r.y, -sn * r.x + cs * r.y);
    if (q.x < 0.5 || q.y < 0.5 || q.x > uValid.x - 0.5 || q.y > uValid.y - 0.5) { frag = c; return; }
    vec4 h = texture(uPrev, q / uTexSize);
    float diff = abs(c.r - h.r) + 0.5 * (abs(c.g - h.g) + abs(c.b - h.b));
    frag = mix(c, h, uKmax * (1.0 - smoothstep(0.02, 0.08, diff)));
}"""

        private const val PASS_F = """#version 300 es
precision highp float;
uniform sampler2D uImg;
uniform vec2 uTexSize;
uniform vec2 uCenter;
uniform vec2 uOutSize;
uniform float uInvZoom;
uniform vec3 uCrop;
uniform vec3 uTone;
uniform float uSharp;
out vec4 frag;
void main() {
    vec2 o = vec2(gl_FragCoord.x, uOutSize.y - gl_FragCoord.y);
    vec2 d = (o - 0.5 * uOutSize) * uInvZoom;
    float cs = cos(uCrop.z);
    float sn = sin(uCrop.z);
    vec2 s = uCenter + vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y) + uCrop.xy;
    vec2 inv = 1.0 / uTexSize;
    vec4 c = texture(uImg, s * inv);
    float blur = 0.25 * (texture(uImg, (s + vec2(1.5, 0.0)) * inv).r + texture(uImg, (s - vec2(1.5, 0.0)) * inv).r
        + texture(uImg, (s + vec2(0.0, 1.5)) * inv).r + texture(uImg, (s - vec2(0.0, 1.5)) * inv).r);
    float det = c.r - blur;
    float y = c.r + uSharp * det * smoothstep(0.004, 0.012, abs(det));
    vec3 rgb = vec3(
        y + 1.402 * (c.b - 0.5),
        y - 0.344136 * (c.g - 0.5) - 0.714136 * (c.b - 0.5),
        y + 1.772 * (c.g - 0.5));
    float yt = pow(clamp((y - uTone.x) / (uTone.y - uTone.x), 0.0, 1.0), uTone.z);
    frag = vec4(clamp(rgb * (yt / max(y, 1e-4)), 0.0, 1.0), 1.0);
}"""
    }

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val progH: Int
    private val progV: Int
    private val progT: Int
    private val progF: Int
    private val vao = IntArray(1)
    private val srcTex: Int
    private val yccTex: Int
    private val yccFbo: Int
    private val ycc2Tex: Int
    private val ycc2Fbo: Int
    private val histTex = IntArray(2)
    private val histFbo = IntArray(2)
    private var histCur = 0

    /** Intermediate format actually in use (for the log). */
    val halfFloat: Boolean

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable ES3 EGL config" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        progH = program(PASS_H)
        progV = program(PASS_V)
        progT = program(PASS_T)
        progF = program(PASS_F)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        srcTex = texture(GLES30.GL_RGBA8, false)
        var half = true
        var tex = texture(GLES30.GL_RGBA16F, false)
        var fbo = framebuffer(tex)
        if (fbo == 0) {
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            half = false
            tex = texture(GLES30.GL_RGBA8, false)
            fbo = framebuffer(tex).also { check(it != 0) { "RGBA8 framebuffer incomplete" } }
        }
        halfFloat = half
        val fmt = if (half) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        yccTex = tex
        yccFbo = fbo
        ycc2Tex = texture(fmt, false)
        ycc2Fbo = framebuffer(ycc2Tex).also { check(it != 0) { "framebuffer incomplete" } }
        for (i in 0..1) {
            histTex[i] = texture(fmt, true)
            histFbo[i] = framebuffer(histTex[i]).also { check(it != 0) { "framebuffer incomplete" } }
        }
        checkGl("setup")
        Log.i(TAG, "GL ready: ${GLES30.glGetString(GLES30.GL_RENDERER)}, intermediates ${if (half) "RGBA16F" else "RGBA8"}")
    }

    /**
     * Renders [bitmap] (the decoded source frame, [srcWidth]×[srcHeight]) and hands it to the encoder
     * with presentation time [ptsNs].
     *
     * @param motion content motion from the previously rendered frame, `[dx px, dy px, dθ rad]`;
     *   null disables the temporal blend for this frame (unknown motion, a skipped frame)
     * @param crop `[ux px, uy px, phi rad]` of [StabilizationPath]
     * @param tone `[black, white, gamma]` of [TonePlan]
     */
    fun render(bitmap: Bitmap, motion: FloatArray?, crop: FloatArray, zoom: Float, tone: FloatArray, ptsNs: Long) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)

        GLES30.glViewport(0, 0, srcWidth, srcHeight)
        // H: source → ycc
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, yccFbo)
        GLES30.glUseProgram(progH)
        bindTex(progH, "uSrc", 0, srcTex)
        GLES30.glUniform2i(loc(progH, "uSize"), srcWidth, srcHeight)
        GLES30.glUniform1f(loc(progH, "uAlpha"), DEBLOCK_ALPHA)
        GLES30.glUniform1f(loc(progH, "uBeta"), DEBLOCK_BETA)
        draw()
        // V: ycc → ycc2
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ycc2Fbo)
        GLES30.glUseProgram(progV)
        bindTex(progV, "uYcc", 0, yccTex)
        GLES30.glUniform2i(loc(progV, "uSize"), srcWidth, srcHeight)
        GLES30.glUniform1f(loc(progV, "uAlpha"), DEBLOCK_ALPHA)
        GLES30.glUniform1f(loc(progV, "uBeta"), DEBLOCK_BETA)
        GLES30.glUniform1f(loc(progV, "uSigmaS2"), CHROMA_SIGMA_PX * CHROMA_SIGMA_PX)
        GLES30.glUniform1f(loc(progV, "uSigmaR2"), CHROMA_SIGMA_LUMA * CHROMA_SIGMA_LUMA)
        draw()
        // T: ycc2 + previous history → new history
        val prev = histCur
        histCur = 1 - histCur
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo[histCur])
        GLES30.glUseProgram(progT)
        bindTex(progT, "uCur", 0, ycc2Tex)
        bindTex(progT, "uPrev", 1, histTex[prev])
        GLES30.glUniform2f(loc(progT, "uTexSize"), srcWidth.toFloat(), srcHeight.toFloat())
        GLES30.glUniform2f(loc(progT, "uValid"), srcWidth.toFloat(), usableHeight.toFloat())
        GLES30.glUniform2f(loc(progT, "uCenter"), srcWidth / 2f, usableHeight / 2f)
        GLES30.glUniform3f(loc(progT, "uMotion"), motion?.get(0) ?: 0f, motion?.get(1) ?: 0f, motion?.get(2) ?: 0f)
        GLES30.glUniform1f(loc(progT, "uKmax"), if (motion != null) TEMPORAL_MAX else 0f)
        draw()
        // F: history → encoder surface
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outWidth, outHeight)
        GLES30.glUseProgram(progF)
        bindTex(progF, "uImg", 0, histTex[histCur])
        GLES30.glUniform2f(loc(progF, "uTexSize"), srcWidth.toFloat(), srcHeight.toFloat())
        GLES30.glUniform2f(loc(progF, "uCenter"), srcWidth / 2f, usableHeight / 2f)
        GLES30.glUniform2f(loc(progF, "uOutSize"), outWidth.toFloat(), outHeight.toFloat())
        GLES30.glUniform1f(loc(progF, "uInvZoom"), 1f / zoom)
        GLES30.glUniform3f(loc(progF, "uCrop"), crop[0], crop[1], crop[2])
        GLES30.glUniform3f(loc(progF, "uTone"), tone[0], tone[1], tone[2])
        GLES30.glUniform1f(loc(progF, "uSharp"), SHARPEN)
        draw()
        checkGl("render")
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    override fun close() {
        try {
            GLES30.glDeleteFramebuffers(4, intArrayOf(yccFbo, ycc2Fbo, histFbo[0], histFbo[1]), 0)
            GLES30.glDeleteTextures(5, intArrayOf(srcTex, yccTex, ycc2Tex, histTex[0], histTex[1]), 0)
            GLES30.glDeleteVertexArrays(1, vao, 0)
            for (p in intArrayOf(progH, progV, progT, progF)) GLES30.glDeleteProgram(p)
        } catch (_: Exception) {
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private val locations = HashMap<Int, HashMap<String, Int>>()

    private fun loc(program: Int, name: String): Int =
        locations.getOrPut(program) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun bindTex(program: Int, name: String, unit: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc(program, name), unit)
    }

    private fun draw() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    private fun texture(internalFormat: Int, linear: Boolean): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, srcWidth, srcHeight)
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    /** Framebuffer on [tex], or 0 when that format is not renderable here. */
    private fun framebuffer(tex: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glGetError() // an unsupported format may leave an error behind
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, f, 0)
            return 0
        }
        return f[0]
    }

    private fun program(fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        check(ok[0] != 0) { "link failed: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun checkGl(where: String) {
        val e = GLES30.glGetError()
        check(e == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} in $where" }
    }
}
