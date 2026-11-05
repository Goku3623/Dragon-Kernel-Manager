package com.example.data.visualstyle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real OpenGL ES 2.0 GPU-Accelerated Visual Filter & Color Grading Engine.
 * Executes a full GLSL fragment shader pipeline on the app's preview rendering surface:
 *  - 5-tap Convolution Unsharp Mask Sharpening (gated by expensiveEffectsEnabled)
 *  - RGB Channel Gain Balance
 *  - Planckian Kelvin Color Temperature (3000K - 9000K)
 *  - Brightness (50% - 150%) & Contrast (50% - 150%)
 *  - Gamma Correction (0.5 - 2.5)
 *  - Saturation (0% - 200%)
 *  - LUT-based Color Grading (Competitive, Ultra Vibrant, Cinematic Teal/Gold, AMOLED Boost, HDR S-Curve, Night Shield, Retro CRT)
 *  - Grayscale, Sepia, Inverted Colors, and Custom 3x3 Color Matrix
 *  - Zero-latency GPU Split-Screen Before/After Comparison Slider
 */
class VisualFilterGlRenderer(
    private val onStatsUpdated: (VisualPerformanceStats) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    private var currentAdjustments: VisualAdjustments = VisualAdjustments.DEFAULT

    @Volatile
    private var comparisonEnabled: Boolean = true

    @Volatile
    private var splitRatio: Float = 0.5f // 0.0 = all modified, 0.5 = 50/50 split, 1.0 = all original

    @Volatile
    private var previewOriginalOnly: Boolean = false

    @Volatile
    private var expensiveEffectsEnabled: Boolean = true

    @Volatile
    private var activeScene: GamingPreviewScene = GamingPreviewScene.COMPETITIVE_FPS

    @Volatile
    private var pendingCustomBitmap: Bitmap? = null

    @Volatile
    private var sceneNeedsReload: Boolean = true

    private var programId: Int = 0
    private var textureId: Int = 0
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1
    private var currentTextureWidth: Int = 960
    private var currentTextureHeight: Int = 540

    private var gpuRendererName: String = "OpenGL ES 2.0 GPU"
    private var gpuVendorName: String = "Hardware GPU"
    private var glVersion: String = "OpenGL ES 2.0"
    private var shaderCompiledOk: Boolean = true
    private var shaderError: String? = null
    private var rollingAvgMs: Float = 0.85f

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // Quad covering normalized device coordinates (-1..1)
    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )

    // Flip V vertically so Android Bitmap top-left aligns with OpenGL texture coordinates
    private val textureCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    )

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexCoords)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(textureCoords)
                position(0)
            }
    }

    fun updateParameters(
        adjustments: VisualAdjustments,
        isComparison: Boolean,
        splitPosition: Float,
        holdOriginal: Boolean,
        enableExpensiveEffects: Boolean
    ) {
        currentAdjustments = adjustments.clamped()
        comparisonEnabled = isComparison
        splitRatio = splitPosition.coerceIn(0f, 1f)
        previewOriginalOnly = holdOriginal
        expensiveEffectsEnabled = enableExpensiveEffects
    }

    fun setScene(scene: GamingPreviewScene, customBitmap: Bitmap? = null) {
        activeScene = scene
        if (customBitmap != null) {
            pendingCustomBitmap = customBitmap
        }
        sceneNeedsReload = true
    }

    fun releaseGlResources() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        if (textureId != 0) {
            val ids = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, ids, 0)
            textureId = 0
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.04f, 0.07f, 1.0f)
        gpuRendererName = GLES20.glGetString(GLES20.GL_RENDERER) ?: "OpenGL ES 2.0 GPU"
        gpuVendorName = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Android GPU"
        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "OpenGL ES 2.0"

        val compileResult = buildShaderProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE)
        programId = compileResult.first
        shaderCompiledOk = compileResult.first != 0
        shaderError = compileResult.second

        sceneNeedsReload = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val startNs = System.nanoTime()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (sceneNeedsReload || textureId == 0) {
            sceneNeedsReload = false
            uploadSceneTexture()
        }

        if (programId == 0 || textureId == 0) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
            onStatsUpdated(
                VisualPerformanceStats(
                    lastFrameRenderTimeMs = elapsedMs,
                    avgFrameRenderTimeMs = elapsedMs,
                    gpuRendererName = gpuRendererName,
                    gpuVendorName = gpuVendorName,
                    glVersion = glVersion,
                    shaderCompiledSuccessfully = shaderCompiledOk,
                    shaderErrorLog = shaderError,
                    expensiveEffectsEnabled = expensiveEffectsEnabled,
                    textureWidth = currentTextureWidth,
                    textureHeight = currentTextureHeight
                )
            )
            return
        }

        GLES20.glUseProgram(programId)

        val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // Bind scene texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)

        val adj = currentAdjustments
        val tempRgb = VisualAdjustments.kelvinToRgbMultipliers(adj.colorTempKelvin)

        // Upload uniforms
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uSaturation"),
            adj.saturationPercent / 100f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uContrast"),
            adj.contrastPercent / 100f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uBrightness"),
            (adj.brightnessPercent - 100f) / 100f
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uGamma"),
            adj.gamma.coerceIn(0.5f, 2.5f)
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uSharpness"),
            if (expensiveEffectsEnabled) (adj.sharpness / 100f) else 0f
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(programId, "uTexelSize"),
            1.0f / max(1, currentTextureWidth).toFloat(),
            1.0f / max(1, currentTextureHeight).toFloat()
        )
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(programId, "uChannelGains"),
            adj.redChannelPercent / 100f,
            adj.greenChannelPercent / 100f,
            adj.blueChannelPercent / 100f
        )
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(programId, "uTempMultipliers"),
            tempRgb[0],
            tempRgb[1],
            tempRgb[2]
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uEnableGrayscale"),
            if (adj.enableGrayscale) 1 else 0
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uEnableSepia"),
            if (adj.enableSepia) 1 else 0
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uEnableInvert"),
            if (adj.enableInvert) 1 else 0
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uUseCustomMatrix"),
            if (adj.useCustomColorMatrix) 1 else 0
        )

        val mat = adj.customMatrixValues
        if (mat.size == 9) {
            // Column-major for GLSL mat3
            val colMajor = floatArrayOf(
                mat[0], mat[3], mat[6],
                mat[1], mat[4], mat[7],
                mat[2], mat[5], mat[8]
            )
            GLES20.glUniformMatrix3fv(
                GLES20.glGetUniformLocation(programId, "uCustomMatrix"),
                1,
                false,
                colMajor,
                0
            )
        }

        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uLutIndex"),
            if (expensiveEffectsEnabled) adj.lutProfile.shaderIndex else 0
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uLutIntensity"),
            adj.lutIntensityPercent / 100f
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uComparisonEnabled"),
            if (comparisonEnabled) 1 else 0
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(programId, "uSplitRatio"),
            splitRatio
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "uHoldOriginal"),
            if (previewOriginalOnly) 1 else 0
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000f).coerceIn(0.05f, 50f)
        rollingAvgMs = (rollingAvgMs * 0.8f) + (elapsedMs * 0.2f)

        onStatsUpdated(
            VisualPerformanceStats(
                lastFrameRenderTimeMs = elapsedMs,
                avgFrameRenderTimeMs = rollingAvgMs,
                gpuRendererName = gpuRendererName,
                gpuVendorName = gpuVendorName,
                glVersion = glVersion,
                shaderCompiledSuccessfully = shaderCompiledOk,
                shaderErrorLog = shaderError,
                expensiveEffectsEnabled = expensiveEffectsEnabled,
                textureWidth = currentTextureWidth,
                textureHeight = currentTextureHeight
            )
        )
    }

    private fun uploadSceneTexture() {
        val bitmapToUpload: Bitmap = when {
            activeScene == GamingPreviewScene.CUSTOM_IMAGE && pendingCustomBitmap != null -> {
                pendingCustomBitmap!!
            }
            else -> {
                generateGamingSceneBitmap(activeScene, 960, 540)
            }
        }

        currentTextureWidth = bitmapToUpload.width
        currentTextureHeight = bitmapToUpload.height

        if (textureId != 0) {
            val old = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, old, 0)
            textureId = 0
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmapToUpload, 0)

        // Recycle procedural bitmaps immediately after uploading to GPU VRAM
        if (bitmapToUpload !== pendingCustomBitmap && !bitmapToUpload.isRecycled) {
            bitmapToUpload.recycle()
        }
    }

    private fun buildShaderProgram(vertexSrc: String, fragmentSrc: String): Pair<Int, String?> {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vShader.first == 0) return Pair(0, "Vertex shader error: ${vShader.second}")

        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fShader.first == 0) {
            GLES20.glDeleteShader(vShader.first)
            return Pair(0, "Fragment shader error: ${fShader.second}")
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader.first)
        GLES20.glAttachShader(program, fShader.first)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vShader.first)
        GLES20.glDeleteShader(fShader.first)

        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            return Pair(0, "Program link error: $info")
        }
        return Pair(program, null)
    }

    private fun compileShader(type: Int, source: String): Pair<Int, String?> {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            return Pair(0, log)
        }
        return Pair(shader, null)
    }

    companion object {
        private const val VERTEX_SHADER_SOURCE = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_SOURCE = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;

            uniform float uSaturation;
            uniform float uContrast;
            uniform float uBrightness;
            uniform float uGamma;
            uniform float uSharpness;
            uniform vec2 uTexelSize;
            uniform vec3 uChannelGains;
            uniform vec3 uTempMultipliers;

            uniform int uEnableGrayscale;
            uniform int uEnableSepia;
            uniform int uEnableInvert;
            uniform int uUseCustomMatrix;
            uniform mat3 uCustomMatrix;

            uniform int uLutIndex;
            uniform float uLutIntensity;

            uniform int uComparisonEnabled;
            uniform float uSplitRatio;
            uniform int uHoldOriginal;

            vec3 applyAnalyticLut(vec3 c, int lutIdx) {
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                if (lutIdx == 1) {
                    // Competitive Clarity: Lift dark shadows for enemy visibility & tame harsh glare
                    vec3 lifted = pow(max(c, vec3(0.0001)), vec3(0.84));
                    return mix(c, clamp(lifted * 1.03, 0.0, 1.0), 0.75);
                } else if (lutIdx == 2) {
                    // Ultra Vibrant: Selective vibrance boosting less-saturated pixels more strongly
                    float maxC = max(c.r, max(c.g, c.b));
                    float minC = min(c.r, min(c.g, c.b));
                    float sat = maxC - minC;
                    float vibBoost = 1.0 + (1.0 - sat) * 0.55;
                    return clamp(mix(vec3(luma), c, vibBoost), 0.0, 1.0);
                } else if (lutIdx == 3) {
                    // Cinematic Teal & Gold split-toning
                    vec3 shadowTint = vec3(0.02, 0.14, 0.22);
                    vec3 highlightTint = vec3(1.06, 0.93, 0.76);
                    vec3 graded = mix(c + shadowTint * (1.0 - luma) * 0.5, c * highlightTint, smoothstep(0.2, 0.85, luma));
                    return clamp(graded, 0.0, 1.0);
                } else if (lutIdx == 4) {
                    // AMOLED Deep Obsidian: True black floor crush + vivid highlight pop
                    vec3 crushed = smoothstep(vec3(0.045), vec3(0.96), c);
                    return clamp(mix(vec3(luma), crushed, 1.18), 0.0, 1.0);
                } else if (lutIdx == 5) {
                    // HDR-Inspired ACES-style S-Curve tone mapping
                    vec3 x = max(vec3(0.0), c * 1.15);
                    vec3 aces = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
                    return clamp(aces, 0.0, 1.0);
                } else if (lutIdx == 6) {
                    // Night Gaming Circadian Shield: Warm amber curve & blue attenuation
                    return clamp(vec3(c.r * 1.06, c.g * 0.94, c.b * 0.68), 0.0, 1.0);
                } else if (lutIdx == 7) {
                    // Retro Arcade 80s CRT: Lifted phosphor blacks & warm vintage split
                    vec3 vintage = vec3(
                        0.06 + c.r * 0.90,
                        0.04 + c.g * 0.85,
                        0.08 + c.b * 0.72
                    );
                    return clamp(vintage, 0.0, 1.0);
                }
                return c;
            }

            void main() {
                vec4 baseSample = texture2D(uTexture, vTexCoord);
                vec3 originalColor = baseSample.rgb;

                // If Hold-Original or Left side of Before/After split slider
                if (uHoldOriginal == 1) {
                    gl_FragColor = vec4(originalColor, 1.0);
                    return;
                }

                if (uComparisonEnabled == 1) {
                    float distToLine = abs(vTexCoord.x - uSplitRatio);
                    if (distToLine < 0.0035) {
                        gl_FragColor = vec4(0.0, 0.94, 1.0, 1.0);
                        return;
                    }
                    if (vTexCoord.x < uSplitRatio) {
                        gl_FragColor = vec4(originalColor, 1.0);
                        return;
                    }
                }

                vec3 color = originalColor;

                // 1. 5-Tap Unsharp Mask Sharpening Filter
                if (uSharpness > 0.01) {
                    vec3 n = texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                    vec3 s = texture2D(uTexture, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
                    vec3 e = texture2D(uTexture, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                    vec3 blur = (n + s + e + w) * 0.25;
                    vec3 edge = color - blur;
                    color = clamp(color + edge * (uSharpness * 2.2), 0.0, 1.0);
                }

                // 2. RGB Channel Gain & Color Temperature Multipliers
                color = color * uChannelGains * uTempMultipliers;

                // 3. Brightness & Contrast
                color = color + vec3(uBrightness);
                color = (color - 0.5) * uContrast + 0.5;
                color = clamp(color, 0.0, 1.0);

                // 4. Gamma Correction
                color = pow(max(color, vec3(0.0001)), vec3(1.0 / max(uGamma, 0.2)));

                // 5. Saturation
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = mix(vec3(luma), color, uSaturation);
                color = clamp(color, 0.0, 1.0);

                // 6. Analytic LUT Color Grading
                if (uLutIndex > 0 && uLutIntensity > 0.01) {
                    vec3 lutColor = applyAnalyticLut(color, uLutIndex);
                    color = mix(color, lutColor, uLutIntensity);
                }

                // 7. Custom 3x3 Color Matrix
                if (uUseCustomMatrix == 1) {
                    color = clamp(uCustomMatrix * color, 0.0, 1.0);
                }

                // 8. Sepia Filter
                if (uEnableSepia == 1) {
                    vec3 sepia = vec3(
                        dot(color, vec3(0.393, 0.769, 0.189)),
                        dot(color, vec3(0.349, 0.686, 0.168)),
                        dot(color, vec3(0.272, 0.534, 0.131))
                    );
                    color = clamp(sepia, 0.0, 1.0);
                }

                // 9. Grayscale Filter
                if (uEnableGrayscale == 1) {
                    float g = dot(color, vec3(0.299, 0.587, 0.114));
                    color = vec3(g);
                }

                // 10. Invert Colors Filter
                if (uEnableInvert == 1) {
                    color = vec3(1.0) - color;
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        /**
         * Generates rich, high-detail gaming benchmark scenes for real-time GPU shader inspection:
         * - Shadows, midtones, specular highlights, neon signage, HUD elements, and color charts.
         */
        fun generateGamingSceneBitmap(scene: GamingPreviewScene, width: Int, height: Int): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val w = width.toFloat()
            val h = height.toFloat()

            when (scene) {
                GamingPreviewScene.COMPETITIVE_FPS -> drawCompetitiveFpsScene(canvas, paint, w, h)
                GamingPreviewScene.CYBERPUNK_CITY -> drawCyberpunkScene(canvas, paint, w, h)
                GamingPreviewScene.AMOLED_DUNGEON -> drawAmoledDungeonScene(canvas, paint, w, h)
                GamingPreviewScene.OPEN_WORLD_HDR -> drawOpenWorldScene(canvas, paint, w, h)
                GamingPreviewScene.CUSTOM_IMAGE -> drawCompetitiveFpsScene(canvas, paint, w, h)
            }

            // Draw bottom-right mini Color Calibration Reference Swatches so users can verify R/G/B/C/M/Y response
            val swatchW = w * 0.032f
            val swatchH = h * 0.045f
            val startX = w - (swatchW * 7.2f)
            val startY = h - swatchH * 1.45f
            val swatchColors = intArrayOf(
                Color.rgb(235, 45, 65),   // Red
                Color.rgb(40, 225, 95),   // Green
                Color.rgb(45, 115, 250),  // Blue
                Color.rgb(0, 230, 245),   // Cyan
                Color.rgb(230, 50, 220),  // Magenta
                Color.rgb(250, 215, 35)   // Yellow
            )
            swatchColors.forEachIndexed { idx, col ->
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = col
                val left = startX + idx * (swatchW + 4f)
                canvas.drawRoundRect(RectF(left, startY, left + swatchW, startY + swatchH), 4f, 4f, paint)
            }

            return bmp
        }

        private fun drawCompetitiveFpsScene(canvas: Canvas, paint: Paint, w: Float, h: Float) {
            // Sky & Tactical Industrial Warehouse
            paint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                intArrayOf(Color.rgb(28, 42, 68), Color.rgb(64, 88, 118), Color.rgb(125, 145, 165)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h * 0.55f, paint)

            // Ground tactical grid
            paint.shader = LinearGradient(
                0f, h * 0.55f, 0f, h,
                intArrayOf(Color.rgb(38, 44, 48), Color.rgb(18, 22, 26)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.55f, w, h, paint)

            // Deep shadow alcove on left (tests Competitive shadow gamma lift!)
            paint.shader = null
            paint.color = Color.rgb(14, 17, 22)
            canvas.drawRect(w * 0.06f, h * 0.22f, w * 0.36f, h * 0.78f, paint)

            // Hidden target silhouettes inside dark shadow alcove
            paint.color = Color.rgb(34, 42, 52)
            canvas.drawCircle(w * 0.21f, h * 0.42f, h * 0.065f, paint)
            canvas.drawRoundRect(
                RectF(w * 0.15f, h * 0.49f, w * 0.27f, h * 0.72f),
                12f, 12f, paint
            )

            // Foliage & tactical cover crates on right
            paint.color = Color.rgb(46, 118, 62)
            canvas.drawCircle(w * 0.76f, h * 0.48f, h * 0.14f, paint)
            paint.color = Color.rgb(72, 158, 84)
            canvas.drawCircle(w * 0.81f, h * 0.45f, h * 0.09f, paint)

            paint.color = Color.rgb(168, 112, 54)
            canvas.drawRoundRect(RectF(w * 0.56f, h * 0.48f, w * 0.74f, h * 0.76f), 8f, 8f, paint)

            // Center Neon Cyan Crosshair
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.rgb(0, 240, 255)
            val cx = w * 0.5f
            val cy = h * 0.5f
            canvas.drawCircle(cx, cy, h * 0.045f, paint)
            canvas.drawLine(cx - 28f, cy, cx - 8f, cy, paint)
            canvas.drawLine(cx + 8f, cy, cx + 28f, cy, paint)
            canvas.drawLine(cx, cy - 28f, cx, cy - 8f, paint)
            canvas.drawLine(cx, cy + 8f, cx, cy + 28f, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawCyberpunkScene(canvas: Canvas, paint: Paint, w: Float, h: Float) {
            // Deep midnight synthwave sky
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.rgb(8, 5, 24), Color.rgb(24, 10, 52), Color.rgb(6, 8, 16)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // Skyscraper silhouettes
            paint.shader = null
            val buildings = listOf(
                RectF(w * 0.04f, h * 0.15f, w * 0.22f, h * 0.85f),
                RectF(w * 0.25f, h * 0.08f, w * 0.46f, h * 0.85f),
                RectF(w * 0.52f, h * 0.18f, w * 0.73f, h * 0.85f),
                RectF(w * 0.76f, h * 0.12f, w * 0.95f, h * 0.85f)
            )
            buildings.forEachIndexed { idx, rect ->
                paint.color = Color.rgb(12 + idx * 4, 14 + idx * 3, 28 + idx * 5)
                canvas.drawRect(rect, paint)
            }

            // Glowing Neon Signs (Magenta, Cyan, Gold)
            paint.shader = RadialGradient(
                w * 0.35f, h * 0.36f, h * 0.28f,
                intArrayOf(Color.argb(230, 255, 23, 112), Color.argb(0, 255, 23, 112)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.35f, h * 0.36f, h * 0.28f, paint)

            paint.shader = RadialGradient(
                w * 0.65f, h * 0.42f, h * 0.26f,
                intArrayOf(Color.argb(230, 0, 240, 255), Color.argb(0, 0, 240, 255)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.65f, h * 0.42f, h * 0.26f, paint)

            // Wet asphalt neon reflections
            paint.shader = LinearGradient(
                0f, h * 0.72f, w, h,
                intArrayOf(Color.rgb(255, 23, 112), Color.rgb(0, 240, 255), Color.rgb(255, 188, 0)),
                null,
                Shader.TileMode.CLAMP
            )
            paint.alpha = 115
            canvas.drawRect(0f, h * 0.75f, w, h, paint)
            paint.alpha = 255
        }

        private fun drawAmoledDungeonScene(canvas: Canvas, paint: Paint, w: Float, h: Float) {
            // Pitch black AMOLED abyss background
            paint.shader = null
            paint.color = Color.rgb(2, 3, 5)
            canvas.drawRect(0f, 0f, w, h, paint)

            // Subtle stone pillars in near-black (tests shadow detail vs AMOLED crush)
            paint.color = Color.rgb(16, 19, 26)
            canvas.drawRect(w * 0.10f, 0f, w * 0.22f, h, paint)
            canvas.drawRect(w * 0.78f, 0f, w * 0.90f, h, paint)

            // Warm Torchlight Radial Glow
            paint.shader = RadialGradient(
                w * 0.5f, h * 0.48f, h * 0.45f,
                intArrayOf(
                    Color.rgb(255, 220, 130),
                    Color.rgb(245, 115, 28),
                    Color.rgb(75, 22, 12),
                    Color.rgb(3, 4, 7)
                ),
                floatArrayOf(0f, 0.25f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.5f, h * 0.48f, h * 0.45f, paint)

            // Emerald magic rune in foreground
            paint.shader = RadialGradient(
                w * 0.32f, h * 0.72f, h * 0.16f,
                intArrayOf(Color.rgb(0, 245, 122), Color.argb(0, 0, 245, 122)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.32f, h * 0.72f, h * 0.16f, paint)
        }

        private fun drawOpenWorldScene(canvas: Canvas, paint: Paint, w: Float, h: Float) {
            // Bright HDR-style Sky Gradient
            paint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.58f,
                intArrayOf(Color.rgb(18, 92, 210), Color.rgb(78, 168, 245), Color.rgb(255, 218, 155)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h * 0.58f, paint)

            // Sunburst highlight (tests highlight roll-off & HDR S-Curve)
            paint.shader = RadialGradient(
                w * 0.75f, h * 0.24f, h * 0.26f,
                intArrayOf(Color.rgb(255, 255, 245), Color.rgb(255, 210, 115), Color.argb(0, 255, 190, 90)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.75f, h * 0.24f, h * 0.26f, paint)

            // Lush green hills & alpine lake
            paint.shader = LinearGradient(
                0f, h * 0.52f, 0f, h,
                intArrayOf(Color.rgb(34, 138, 68), Color.rgb(18, 82, 44)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.55f, w, h, paint)

            paint.shader = LinearGradient(
                w * 0.2f, h * 0.65f, w * 0.85f, h * 0.92f,
                intArrayOf(Color.rgb(22, 132, 195), Color.rgb(12, 68, 128)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(RectF(w * 0.18f, h * 0.66f, w * 0.86f, h * 0.92f), paint)
        }
    }
}
