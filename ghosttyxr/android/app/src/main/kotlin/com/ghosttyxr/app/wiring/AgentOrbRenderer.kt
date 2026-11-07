package com.ghosttyxr.app.wiring

import android.content.res.Resources
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import com.ghosttyxr.app.R
import com.ghosttyxr.app.terminal.hands.AgentOrb
import com.ghosttyxr.app.terminal.hands.AgentOrbConfig
import com.ghosttyxr.app.terminal.hands.AgentOrbState
import com.ghosttyxr.app.terminal.hands.OrbEyeProjection
import com.ghosttyxr.app.terminal.hands.OrbSphereMesh
import com.ghosttyxr.app.util.GxrLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "AgentOrbRenderer"

/**
 * Everything the renderer needs to know about where the orb is and how it is moving, captured as
 * one immutable snapshot.
 *
 * Positions are in the quad's **unscaled local space** — the same space
 * [OrbEyeProjection.project] builds its frustum in. Bundling the eyes and the body state together
 * matters: they are produced on the main thread and consumed on the render thread, and if the eyes
 * could tear away from the pose they were derived from, the stereo would visibly swim.
 */
data class OrbFrame(
    val leftEye: Vector3,
    val rightEye: Vector3,
    val lean: Quaternion,
    val stretchAxis: Vector3,
    val stretch: Float,
    val energy: Float,
) {
    companion object {
        /** Rest pose for a viewer straight ahead at a typical palm distance. */
        fun resting(eyeDistance: Float): OrbFrame {
            val eye = Vector3(0f, 0f, eyeDistance)
            return OrbFrame(eye, eye, Quaternion.Identity, Vector3.Up, 0f, 0f)
        }
    }
}

/**
 * Draws the voice pet's orb into a `SurfaceEntity` surface as a genuine stereo pair.
 *
 * This replaces the old `GLSurfaceView` inside a `PanelEntity`. A panel is monoscopic: both eyes
 * receive the identical image, so no matter how good the shading is the orb can only ever read as
 * a picture of a sphere on a card. Here the surface is `SIDE_BY_SIDE` and each half is rendered
 * from that eye's real position with an off-axis frustum whose image plane is the quad itself, so
 * the quad becomes a window and the orb resolves as a solid ball floating over the palm.
 *
 * One opaque draw per eye and nothing outside the silhouette: no halo shell, no orbiting motes.
 * The orb is a solid black object, so the ball's own edge is the whole of it, and an alpha-1 black
 * fragment genuinely occludes the passthrough room behind it — the strongest depth cue available.
 *
 * All GL work happens on a private render thread driven by `Choreographer`; the main thread only
 * ever publishes an [OrbFrame].
 */
class AgentOrbRenderer(
    private val resources: Resources,
    config: AgentOrbConfig,
    /** Half-width of the quad in unscaled local metres — the window the frustum projects onto. */
    private val quadHalfExtent: Float,
    /** Orb radius in the same units. */
    private val orbRadius: Float,
) {
    private val state: AgentOrbState = AgentOrb.createOrbState(config)

    @Volatile
    private var frame: OrbFrame = OrbFrame.resting(DEFAULT_EYE_DISTANCE_M)

    @Volatile
    private var running = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var gl: OrbGl? = null
    private var choreographer: Choreographer? = null

    private val frameCallback: Choreographer.FrameCallback =
        Choreographer.FrameCallback { frameTimeNanos ->
            if (!running) return@FrameCallback
            drawFrame(frameTimeNanos)
            choreographer?.postFrameCallback(frameCallback)
        }

    /** Bind to [surface] and spin up the render thread. Call once, from the main thread. */
    fun attach(surface: Surface, width: Int, height: Int) {
        check(thread == null) { "renderer already attached" }
        val renderThread = HandlerThread("orb-render").apply { start() }
        thread = renderThread
        val renderHandler = Handler(renderThread.looper)
        handler = renderHandler
        // How many pixels across the ball itself actually gets. The shader scales its star
        // sharpness and detail cutoff off this, so feeding it the whole surface size would make the
        // stars finer than the sampling rate and set them shimmering.
        val ballDiameterPixels = height * (orbRadius / quadHalfExtent)
        renderHandler.post {
            choreographer = Choreographer.getInstance()
            gl = runCatching { OrbGl(resources, surface, width, height, ballDiameterPixels) }
                .onFailure { GxrLog.e(TAG, "orb GL init failed; pet will not render", it) }
                .getOrNull()
            if (gl != null && running) choreographer?.postFrameCallback(frameCallback)
        }
    }

    fun submit(next: OrbFrame) {
        frame = next
    }

    fun start() {
        if (running) return
        running = true
        handler?.post {
            if (running && gl != null) choreographer?.postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.post { choreographer?.removeFrameCallback(frameCallback) }
    }

    fun release() {
        running = false
        val renderThread = thread ?: return
        thread = null
        handler?.post {
            choreographer?.removeFrameCallback(frameCallback)
            gl?.release()
            gl = null
        }
        handler = null
        renderThread.quitSafely()
    }

    private fun drawFrame(frameTimeNanos: Long) {
        val context = gl ?: return
        val current = frame
        val timeSeconds = frameTimeNanos / NANOS_PER_SECOND
        AgentOrb.updateMotion(state, timeSeconds, current.energy.toDouble())
        context.draw(state, current, timeSeconds.toFloat(), orbRadius, quadHalfExtent)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val DEFAULT_EYE_DISTANCE_M = 0.45f
    }
}

/**
 * The GL half: owns the EGL context, programs, buffers, and the per-eye draw. Constructed and used
 * exclusively on the render thread.
 */
private class OrbGl(
    resources: Resources,
    surface: Surface,
    width: Int,
    height: Int,
    private val ballDiameterPixels: Float,
) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val mesh = OrbSphereMesh.create(LATITUDE_SEGMENTS, LONGITUDE_SEGMENTS)
    private var vertexBuffer = 0
    private var indexBuffer = 0

    private var orbProgram = 0

    private var uOrbModel = -1
    private var uOrbMvp = -1
    private var uOrbNormalMatrix = -1
    private var uOrbAnchor = -1
    private var uOrbC0 = -1
    private var uOrbC1 = -1
    private var uOrbC2 = -1
    private var uOrbEye = -1
    private var uOrbEyeObject = -1
    private var uOrbRes = -1
    private var uOrbTime = -1
    private var uOrbPhase = -1
    private var uOrbAudio = -1
    private var uOrbSpin = -1
    private var uOrbArch = -1

    private val projection = FloatArray(MATRIX_SIZE)
    private val bodyModel = FloatArray(MATRIX_SIZE)
    private val scratch = FloatArray(MATRIX_SIZE)
    private val mvp = FloatArray(MATRIX_SIZE)
    private val stretchMatrix = FloatArray(MATRIX_SIZE)
    private val rotationMatrix = FloatArray(MATRIX_SIZE)
    private val normalMatrix = FloatArray(NORMAL_MATRIX_SIZE)
    private val eyeObject = FloatArray(4)
    private val inverseModel = FloatArray(MATRIX_SIZE)

    private var surfaceWidth = width
    private var surfaceHeight = height

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val config = chooseConfig(ANTIALIASING_SAMPLES, DEPTH_BITS)
            ?: chooseConfig(0, DEPTH_BITS)
            ?: chooseConfig(0, FALLBACK_DEPTH_BITS)
            ?: error("no translucent GLES 2.0 EGL config available")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "eglMakeCurrent failed"
        }

        orbProgram = linkProgram(
            readShader(resources, R.raw.orb_vertex),
            readShader(resources, R.raw.orb_fragment),
            listOf(POSITION_ATTRIBUTE to "aPosition", NORMAL_ATTRIBUTE to "aNormal"),
        )
        bindUniforms()

        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        vertexBuffer = buffers[0]
        indexBuffer = buffers[1]
        uploadMesh()

        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    fun draw(
        state: AgentOrbState,
        frame: OrbFrame,
        timeSeconds: Float,
        orbRadius: Float,
        quadHalfExtent: Float,
    ) {
        refreshSurfaceSize()
        if (surfaceWidth <= 1 || surfaceHeight <= 1) return

        buildModelMatrices(state, frame, timeSeconds, orbRadius)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val eyeWidth = surfaceWidth / 2
        drawEye(0, eyeWidth, frame.leftEye, frame, timeSeconds, orbRadius, quadHalfExtent, state)
        drawEye(eyeWidth, eyeWidth, frame.rightEye, frame, timeSeconds, orbRadius, quadHalfExtent, state)

        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun drawEye(
        viewportX: Int,
        viewportWidth: Int,
        eye: Vector3,
        frame: OrbFrame,
        timeSeconds: Float,
        orbRadius: Float,
        quadHalfExtent: Float,
        state: AgentOrbState,
    ) {
        GLES20.glViewport(viewportX, 0, viewportWidth, surfaceHeight)
        // Scissor so nothing one eye draws can bleed across the seam into the other eye's half,
        // which would show up as a ghost image at the edge of the other eye's view.
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(viewportX, 0, viewportWidth, surfaceHeight)

        OrbEyeProjection.project(
            eye = eye,
            halfWidth = quadHalfExtent,
            halfHeight = quadHalfExtent,
            near = OrbEyeProjection.nearPlaneFor(eye.z, orbRadius),
            far = OrbEyeProjection.farPlaneFor(eye.z, orbRadius, ORB_CENTER_Z_RADII * orbRadius),
            out = projection,
        )

        GLES20.glUseProgram(orbProgram)
        setColor(uOrbAnchor, state.palette.anchor)
        setColor(uOrbC0, state.palette.c0)
        setColor(uOrbC1, state.palette.c1)
        setColor(uOrbC2, state.palette.c2)
        GLES20.glUniform2f(uOrbRes, ballDiameterPixels, ballDiameterPixels)
        GLES20.glUniform1f(uOrbTime, timeSeconds)
        GLES20.glUniform1f(uOrbPhase, state.phase.toFloat())
        GLES20.glUniform1f(uOrbAudio, frame.energy)
        GLES20.glUniform1f(uOrbSpin, state.spin.toFloat())
        GLES20.glUniform1f(uOrbArch, state.archetype.toFloat())
        GLES20.glUniform3f(uOrbEye, eye.x, eye.y, eye.z)
        setEyeObject(eye)
        bindMesh()

        Matrix.multiplyMM(mvp, 0, projection, 0, bodyModel, 0)
        GLES20.glUniformMatrix4fv(uOrbMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uOrbModel, 1, false, bodyModel, 0)
        GLES20.glUniformMatrix3fv(uOrbNormalMatrix, 1, false, normalMatrix, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun buildModelMatrices(
        state: AgentOrbState,
        frame: OrbFrame,
        timeSeconds: Float,
        orbRadius: Float,
    ) {
        // Only the lean and a slow idle wobble touch the geometry. The spin lives in the shader,
        // applied to the interior lookup instead: rotating the mesh would carry the normals with it
        // and slide the speculars across the surface, which reads as a spinning *image* rather than
        // a still ball with something turning inside it.
        val tilt = (sin(timeSeconds * TILT_SPEED + state.phase) * TILT_DEGREES).toFloat()
        val roll = (sin(timeSeconds * ROLL_SPEED + state.phase * 1.7) * ROLL_DEGREES).toFloat()
        val bodyRotation = frame.lean *
            Quaternion.fromAxisAngle(Vector3.Right, tilt) *
            Quaternion.fromAxisAngle(Vector3.Backward, roll)
        writeStretch(frame.stretchAxis, frame.stretch, stretchMatrix)
        writeRotation(bodyRotation, rotationMatrix)
        writeNormalMatrix(frame.stretchAxis, frame.stretch, rotationMatrix, normalMatrix)

        // orb-local unit sphere -> quad-local: push behind the window plane, stretch along the
        // travel axis, lean, then scale to physical radius. Sitting slightly behind the window
        // keeps the ball from poking through the frame and getting its silhouette clipped when you
        // look at it from an angle.
        Matrix.setIdentityM(bodyModel, 0)
        Matrix.translateM(bodyModel, 0, 0f, 0f, ORB_CENTER_Z_RADII * orbRadius)
        Matrix.multiplyMM(scratch, 0, bodyModel, 0, stretchMatrix, 0)
        Matrix.multiplyMM(bodyModel, 0, scratch, 0, rotationMatrix, 0)
        Matrix.scaleM(bodyModel, 0, orbRadius, orbRadius, orbRadius)
    }

    /** The eye in orb-local space, so the shader can refract the interior lookup correctly. */
    private fun setEyeObject(eye: Vector3) {
        if (!Matrix.invertM(inverseModel, 0, bodyModel, 0)) {
            GLES20.glUniform3f(uOrbEyeObject, 0f, 0f, EYE_OBJECT_FALLBACK_Z)
            return
        }
        val point = floatArrayOf(eye.x, eye.y, eye.z, 1f)
        Matrix.multiplyMV(eyeObject, 0, inverseModel, 0, point, 0)
        GLES20.glUniform3f(uOrbEyeObject, eyeObject[0], eyeObject[1], eyeObject[2])
    }

    private fun bindMesh() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES20.glEnableVertexAttribArray(POSITION_ATTRIBUTE)
        GLES20.glVertexAttribPointer(
            POSITION_ATTRIBUTE,
            3,
            GLES20.GL_FLOAT,
            false,
            OrbSphereMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES,
            0,
        )
        GLES20.glEnableVertexAttribArray(NORMAL_ATTRIBUTE)
        GLES20.glVertexAttribPointer(
            NORMAL_ATTRIBUTE,
            3,
            GLES20.GL_FLOAT,
            false,
            OrbSphereMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES,
            3 * Float.SIZE_BYTES,
        )
    }

    private fun uploadMesh() {
        val vertices = allocateFloats(mesh.vertices.size).apply {
            put(mesh.vertices)
            position(0)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            mesh.vertices.size * Float.SIZE_BYTES,
            vertices,
            GLES20.GL_STATIC_DRAW,
        )
        val indices: ShortBuffer = ByteBuffer
            .allocateDirect(mesh.indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(mesh.indices)
                position(0)
            }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indices.size * Short.SIZE_BYTES,
            indices,
            GLES20.GL_STATIC_DRAW,
        )
    }

    private fun bindUniforms() {
        uOrbModel = GLES20.glGetUniformLocation(orbProgram, "uModel")
        uOrbMvp = GLES20.glGetUniformLocation(orbProgram, "uModelViewProjection")
        uOrbNormalMatrix = GLES20.glGetUniformLocation(orbProgram, "uNormalMatrix")
        uOrbAnchor = GLES20.glGetUniformLocation(orbProgram, "uAnchor")
        uOrbC0 = GLES20.glGetUniformLocation(orbProgram, "uC0")
        uOrbC1 = GLES20.glGetUniformLocation(orbProgram, "uC1")
        uOrbC2 = GLES20.glGetUniformLocation(orbProgram, "uC2")
        uOrbEye = GLES20.glGetUniformLocation(orbProgram, "uEye")
        uOrbEyeObject = GLES20.glGetUniformLocation(orbProgram, "uEyeObject")
        uOrbRes = GLES20.glGetUniformLocation(orbProgram, "uRes")
        uOrbTime = GLES20.glGetUniformLocation(orbProgram, "uTime")
        uOrbPhase = GLES20.glGetUniformLocation(orbProgram, "uPhase")
        uOrbAudio = GLES20.glGetUniformLocation(orbProgram, "uAudio")
        uOrbSpin = GLES20.glGetUniformLocation(orbProgram, "uSpin")
        uOrbArch = GLES20.glGetUniformLocation(orbProgram, "uArch")
    }

    private fun refreshSurfaceSize() {
        val value = IntArray(1)
        if (EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, value, 0)) {
            surfaceWidth = value[0]
        }
        if (EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, value, 0)) {
            surfaceHeight = value[0]
        }
    }

    private fun chooseConfig(samples: Int, depthBits: Int): EGLConfig? {
        val attributes = buildList {
            add(EGL14.EGL_SURFACE_TYPE); add(EGL14.EGL_WINDOW_BIT)
            add(EGL14.EGL_RENDERABLE_TYPE); add(EGL14.EGL_OPENGL_ES2_BIT)
            add(EGL14.EGL_RED_SIZE); add(COLOR_BITS)
            add(EGL14.EGL_GREEN_SIZE); add(COLOR_BITS)
            add(EGL14.EGL_BLUE_SIZE); add(COLOR_BITS)
            add(EGL14.EGL_ALPHA_SIZE); add(COLOR_BITS)
            add(EGL14.EGL_DEPTH_SIZE); add(depthBits)
            if (samples > 0) {
                add(EGL14.EGL_SAMPLE_BUFFERS); add(1)
                add(EGL14.EGL_SAMPLES); add(samples)
            }
            add(EGL14.EGL_NONE)
        }.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) return null
        if (count[0] == 0) return null
        return configs[0]
    }

    private fun linkProgram(
        vertexSource: String,
        fragmentSource: String,
        attributes: List<Pair<Int, String>>,
    ): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        attributes.forEach { (index, name) -> GLES20.glBindAttribLocation(program, index, name) }
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("orb program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("orb shader compile failed: $log")
        }
        return shader
    }

    fun release() {
        runCatching {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            if (orbProgram != 0) GLES20.glDeleteProgram(orbProgram)
            GLES20.glDeleteBuffers(2, intArrayOf(vertexBuffer, indexBuffer), 0)
        }.onFailure { GxrLog.w(TAG, "orb GL teardown hiccup", it) }
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val LATITUDE_SEGMENTS = 128
        const val LONGITUDE_SEGMENTS = 192
        const val MATRIX_SIZE = 16
        const val NORMAL_MATRIX_SIZE = 9
        const val POSITION_ATTRIBUTE = 0
        const val NORMAL_ATTRIBUTE = 1
        const val COLOR_BITS = 8
        const val DEPTH_BITS = 24
        const val FALLBACK_DEPTH_BITS = 16
        const val ANTIALIASING_SAMPLES = 4
        const val ORB_CENTER_Z_RADII = -0.22f
        const val EYE_OBJECT_FALLBACK_Z = 6f
        const val TILT_SPEED = 0.27
        const val TILT_DEGREES = 8.0
        const val ROLL_SPEED = 0.16
        const val ROLL_DEGREES = 5.0

        fun allocateFloats(count: Int): FloatBuffer = ByteBuffer
            .allocateDirect(count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        fun readShader(resources: Resources, resourceId: Int): String =
            resources.openRawResource(resourceId).bufferedReader().use { it.readText() }

        fun setColor(location: Int, packed: Int) {
            GLES20.glUniform3f(
                location,
                ((packed shr 16) and 0xFF) / 255f,
                ((packed shr 8) and 0xFF) / 255f,
                (packed and 0xFF) / 255f,
            )
        }

        /**
         * Volume-preserving stretch along [axis]: `S = p*I + (k - p) * a a^T`, with `k = 1 + amount`
         * along the axis and `p = 1/sqrt(k)` across it. Squash-and-stretch is the oldest trick in
         * animation for conveying mass, and it costs nothing here.
         */
        fun writeStretch(axis: Vector3, amount: Float, out: FloatArray) {
            Matrix.setIdentityM(out, 0)
            if (amount <= 1e-4f) return
            val along = 1f + amount
            val across = 1f / sqrt(along)
            val delta = along - across
            val a = floatArrayOf(axis.x, axis.y, axis.z)
            for (column in 0..2) {
                for (row in 0..2) {
                    out[column * 4 + row] =
                        (if (column == row) across else 0f) + delta * a[column] * a[row]
                }
            }
        }

        fun writeRotation(rotation: Quaternion, out: FloatArray) {
            val x = rotation.x
            val y = rotation.y
            val z = rotation.z
            val w = rotation.w
            Matrix.setIdentityM(out, 0)
            out[0] = 1f - 2f * (y * y + z * z)
            out[1] = 2f * (x * y + z * w)
            out[2] = 2f * (x * z - y * w)
            out[4] = 2f * (x * y - z * w)
            out[5] = 1f - 2f * (x * x + z * z)
            out[6] = 2f * (y * z + x * w)
            out[8] = 2f * (x * z + y * w)
            out[9] = 2f * (y * z - x * w)
            out[10] = 1f - 2f * (x * x + y * y)
        }

        /**
         * `N = S^-1 * R`, the inverse-transpose of the model's linear part. Skipping this would
         * skew every normal during squash-and-stretch and drag the specular highlight — the very
         * cue the stereo work is there to sell — off its correct position.
         */
        fun writeNormalMatrix(
            axis: Vector3,
            amount: Float,
            rotation: FloatArray,
            out: FloatArray,
        ) {
            val along = 1f + amount.coerceAtLeast(0f)
            val across = 1f / sqrt(along)
            val inverseAlong = 1f / along
            val inverseAcross = 1f / across
            val delta = inverseAlong - inverseAcross
            val a = floatArrayOf(axis.x, axis.y, axis.z)
            for (column in 0..2) {
                for (row in 0..2) {
                    var sum = 0f
                    for (k in 0..2) {
                        val inverseStretch =
                            (if (k == row) inverseAcross else 0f) + delta * a[k] * a[row]
                        sum += inverseStretch * rotation[column * 4 + k]
                    }
                    out[column * 3 + row] = sum
                }
            }
        }
    }
}
