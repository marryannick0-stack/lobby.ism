package com.lobbyism.app.render

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small OpenGL ES 2.0 helpers shared by the renderer. */
object GlUtil {
    private const val TAG = "LOBBYISM/GL"

    /** Compiles and links a shader program. Returns 0 and logs the reason on failure. */
    fun program(vertexSrc: String, fragmentSrc: String): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        // The shader objects are no longer needed once linked.
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) {
            Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    /** Uploads vertex data into a new static buffer object and returns its id. */
    fun staticBuffer(data: FloatArray): Int {
        val id = IntArray(1)
        GLES20.glGenBuffers(1, id, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return id[0]
    }
}
