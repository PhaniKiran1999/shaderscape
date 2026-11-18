package com.kiran.android.wallpapers.skill

import android.app.PendingIntent
import android.content.Intent
import com.kiran.agentskills.sdk.Artifact
import com.kiran.agentskills.sdk.ParamsSchema
import com.kiran.agentskills.sdk.SkillDescriptor
import com.kiran.agentskills.sdk.SkillProviderService
import com.kiran.agentskills.sdk.ToolCall
import com.kiran.agentskills.sdk.ToolResult
import com.kiran.agentskills.sdk.ToolSpec
import com.kiran.android.wallpaper.engine.WallpaperSelection
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpaper.engine.composition.Layer
import com.kiran.android.wallpaper.engine.composition.LayerSource
import com.kiran.android.wallpaper.engine.validation.ShaderValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Exposes wallpaper creation to on-device agents (Agent Skill SDK).
 *
 * The agent's model writes a GLSL fragment shader. This service tidies up common mistakes,
 * validates the shader on the GPU and returns either fixable errors (the model retries, up to
 * [MAX_FAILED_ATTEMPTS] times in a row) or a **draft** with a preview artifact. Only the user can
 * save or apply a draft, in [PreviewActivity]. No tool here has side effects beyond drafts.
 */
class WallpaperSkillService : SkillProviderService() {

    private val store by lazy {
        WallpaperSelection.init(this)
        CompositionStore.instance
    }

    /** Consecutive failed attempts per calling app. Reset by a success or after a quiet spell. */
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()

    override fun onCreate() {
        super.onCreate()
        store.pruneDrafts(maxAgeMs = TimeUnit.DAYS.toMillis(7))
    }

    override fun descriptor() = DESCRIPTOR

    override suspend fun onInvoke(call: ToolCall): ToolResult = when (call.tool) {
        TOOL_CREATE -> create(call, draftId = null)
        TOOL_REVISE -> {
            val id = call.args.optString("draft_id")
            if (store.getDraft(id) == null) ToolResult.error("No draft with id '$id'. Call $TOOL_CREATE instead.")
            else create(call, draftId = id)
        }
        TOOL_LIST -> listSaved()
        else -> ToolResult.error("Unknown tool ${call.tool}")
    }

    private suspend fun create(call: ToolCall, draftId: String?): ToolResult {
        val caller = call.callerPackage ?: "unknown"
        val raw = call.args.optString("glsl")
        val previous = draftId?.let { store.getDraft(it) }
        val title = call.args.optString("title").trim().ifEmpty { previous?.title ?: "Agent wallpaper" }.take(40)

        val tidy = ShaderTidier.tidy(raw)
        call.progress("Checking the shader on the GPU")
        val result = withContext(Dispatchers.IO) { ShaderValidator.validate(tidy.code) }

        return when (result) {
            is ShaderValidator.Result.Invalid -> {
                val attempt = recordFailure(caller)
                val left = (MAX_FAILED_ATTEMPTS - attempt).coerceAtLeast(0)
                val data = JSONObject()
                    .put("ok", false)
                    .put("errors", JSONArray(result.errors))
                    .put("attempts_left", left)
                    .put(
                        "next_step",
                        if (left > 0) "Fix every error and call ${call.tool} again with the complete corrected shader."
                        else "Stop retrying. Tell the user the shader didn't compile and suggest a simpler idea.",
                    )
                if (tidy.notes.isNotEmpty()) {
                    // Error line numbers refer to this tidied code, so give the model that as its base.
                    data.put("auto_fixed", JSONArray(tidy.notes))
                    data.put("glsl_after_auto_fixes", tidy.code)
                }
                ToolResult.ok(data)
            }
            is ShaderValidator.Result.Valid -> {
                failures.remove(caller)
                val id = draftId ?: "draft-${UUID.randomUUID()}"
                val composition = Composition(
                    id = id,
                    title = title,
                    layers = listOf(Layer(LayerSource.Shader(tidy.code), id = "shader")),
                )
                store.saveDraft(composition)
                val data = JSONObject()
                    .put("ok", true)
                    .put("draft_id", id)
                    .put("title", title)
                    .put("warnings", JSONArray(result.warnings))
                    .put(
                        "next_step",
                        "Tell the user the wallpaper \"$title\" is ready and they can tap the preview to try it, " +
                            "then save or apply it. It is not saved yet. Use $TOOL_REVISE with draft_id for changes.",
                    )
                if (tidy.notes.isNotEmpty()) data.put("auto_fixed", JSONArray(tidy.notes))
                ToolResult.ok(data, listOf(previewArtifact(id, title)))
            }
        }
    }

    private fun listSaved(): ToolResult {
        val saved = store.all()
        return ToolResult.ok(
            JSONObject()
                .put("count", saved.size)
                .put("wallpapers", JSONArray().apply {
                    saved.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("layers", it.layers.size)) }
                }),
        )
    }

    private fun previewArtifact(draftId: String, title: String): Artifact {
        val intent = Intent(this, PreviewActivity::class.java)
            .putExtra(PreviewActivity.EXTRA_DRAFT_ID, draftId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, draftId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Artifact(id = "preview:$draftId", kind = "wallpaper_preview", title = title, subtitle = "Tap to preview", action = pi)
    }

    private fun recordFailure(caller: String): Int {
        val now = System.currentTimeMillis()
        val (count, at) = failures[caller] ?: (0 to now)
        val next = if (now - at > FAILURE_WINDOW_MS) 1 else count + 1
        failures[caller] = next to now
        return next
    }

    companion object {
        const val TOOL_CREATE = "create_shader_wallpaper"
        const val TOOL_REVISE = "revise_shader_wallpaper"
        const val TOOL_LIST = "list_my_wallpapers"
        const val MAX_FAILED_ATTEMPTS = 3
        private val FAILURE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10)

        /**
         * Kept short: small on-device models have little context, and this is added to the
         * prompt whenever the skill is selected.
         */
        private val INSTRUCTIONS = """
            To make a live wallpaper, write a GLSL ES 1.00 fragment shader body and call $TOOL_CREATE.
            Rules:
            - Write only helper functions and void main(). Set gl_FragColor = vec4(r, g, b, 1.0).
            - Already declared, do not redeclare: uniform float uTime (seconds); uniform vec2 uResolution (pixels);
              uniform vec2 uTouch (finger position in pixels, (-1,-1) when not touching); varying vec2 vUv (0..1).
            - No #version, no precision line, no textures. Loops need constant bounds: for (int i = 0; i < 8; i++).
            - Make it interactive: when uTouch.x >= 0.0, react near the finger.
            Example:
            void main() {
              vec2 p = vUv;
              vec3 c = 0.5 + 0.5 * cos(uTime + p.xyx * 3.0 + vec3(0.0, 2.0, 4.0));
              if (uTouch.x >= 0.0) c += 0.4 * exp(-distance(gl_FragCoord.xy, uTouch) / 60.0);
              gl_FragColor = vec4(c, 1.0);
            }
            If the tool returns errors, fix them and call it again with the whole corrected shader.
            When it returns ok, say a preview is ready to try. Never say it was saved or applied.
        """.trimIndent()

        val DESCRIPTOR = SkillDescriptor(
            id = "wallpapers",
            title = "Live wallpapers",
            summary = "Creates interactive animated live wallpapers from GLSL shaders.",
            keywords = listOf("wallpaper", "background", "shader", "live", "animated", "home screen", "lock screen", "glsl"),
            instructions = INSTRUCTIONS,
            tools = listOf(
                ToolSpec(
                    name = TOOL_CREATE,
                    description = "Checks a GLSL fragment shader and, if it works, makes a wallpaper draft the user can preview. " +
                        "Returns errors to fix when it doesn't compile.",
                    parameters = ParamsSchema.build {
                        string("title", "Short name for the wallpaper, 1-4 words.")
                        string("glsl", "The complete fragment shader body: helper functions plus void main().")
                    },
                ),
                ToolSpec(
                    name = TOOL_REVISE,
                    description = "Replaces the shader of an existing draft, e.g. after the user asks for changes.",
                    parameters = ParamsSchema.build {
                        string("draft_id", "draft_id returned by $TOOL_CREATE.")
                        string("glsl", "The complete new fragment shader body.")
                        string("title", "New name, if it should change.", required = false)
                    },
                ),
                ToolSpec(
                    name = TOOL_LIST,
                    description = "Lists the wallpapers the user has saved.",
                ),
            ),
        )
    }
}
