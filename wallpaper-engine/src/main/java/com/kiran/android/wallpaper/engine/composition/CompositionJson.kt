package com.kiran.android.wallpaper.engine.composition

import org.json.JSONArray
import org.json.JSONObject

/** JSON (de)serialisation for [Composition]s. Unknown blend modes fall back to NORMAL. */
object CompositionJson {

    fun toJson(c: Composition): JSONObject = JSONObject()
        .put("id", c.id)
        .put("title", c.title)
        .put("layers", JSONArray().apply { c.layers.forEach { put(layerToJson(it)) } })

    fun fromJson(o: JSONObject): Composition {
        val arr = o.getJSONArray("layers")
        return Composition(
            id = o.getString("id"),
            title = o.optString("title", ""),
            layers = (0 until arr.length()).mapNotNull { layerFromJson(arr.getJSONObject(it)) },
        )
    }

    fun listToString(list: List<Composition>): String =
        JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    fun listFromString(text: String): List<Composition> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    fun encode(c: Composition): String = toJson(c).toString()

    fun decode(text: String): Composition = fromJson(JSONObject(text))

    private fun layerToJson(l: Layer): JSONObject {
        val o = JSONObject()
            .put("id", l.id)
            .put("blend", l.blend.name)
            .put("opacity", l.opacity.toDouble())
            .put("visible", l.visible)
        when (val s = l.source) {
            is LayerSource.Scene -> o.put("type", "scene").put("sceneId", s.sceneId)
            is LayerSource.Image -> o.put("type", "image").put("path", s.path).put("parallax", s.parallax)
            is LayerSource.Shader -> o.put("type", "shader").put("code", s.code)
        }
        return o
    }

    private fun layerFromJson(o: JSONObject): Layer? {
        val source = when (o.optString("type")) {
            "scene" -> LayerSource.Scene(o.getString("sceneId"))
            "image" -> LayerSource.Image(o.getString("path"), o.optBoolean("parallax", true))
            "shader" -> LayerSource.Shader(o.getString("code"))
            else -> return null
        }
        return Layer(
            source = source,
            blend = BlendMode.entries.firstOrNull { it.name == o.optString("blend") } ?: BlendMode.NORMAL,
            opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            visible = o.optBoolean("visible", true),
            id = o.optString("id").ifEmpty { newId() },
        )
    }
}
