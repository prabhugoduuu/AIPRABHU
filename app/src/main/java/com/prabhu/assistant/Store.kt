package com.prabhu.assistant

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

val cBg = 0xFF0F1115.toInt()
val cSurface = 0xFF1A1D24.toInt()
val cSurface2 = 0xFF252A34.toInt()
val cLine = 0xFF2B303B.toInt()
val cText = 0xFFE8EAF0.toInt()
val cMute = 0xFF8D95A5.toInt()
val cAccent = 0xFF6C8CFF.toInt()
val cErr = 0xFFFF7B7B.toInt()

object AppState { @Volatile var visible = false }

/** type: "claude", "gemini" or "openai" (any OpenAI-compatible API, uses baseUrl) */
data class ModelCfg(var name: String, var type: String, var key: String, var modelId: String, var baseUrl: String)

/** kind: "image" (data = base64 jpeg), "pdf" (data = base64 pdf, pages = jpeg page images), "text" (data = file text) */
class Att(val kind: String, val mime: String, val data: String, val name: String) {
    var pages: List<String> = emptyList()
    var thumb: Bitmap? = null
}

class Msg(val role: String, val text: String, val model: String = "", val files: String = "") {
    var thumb: Bitmap? = null
    var atts: List<Att> = emptyList()   // kept only while the app is open
}

class Chat(val id: String, var title: String, val msgs: MutableList<Msg> = ArrayList())

object Presets {
    val labels = listOf(
        "Claude (Anthropic)", "Gemini (Google)", "OpenAI (ChatGPT)",
        "Groq", "OpenRouter", "Custom (any OpenAI-compatible API)"
    )
    val items = listOf(
        ModelCfg("Claude Sonnet 5.5", "claude", "", "claude-sonnet-5-5", ""),
        ModelCfg("Gemini 2.5 Flash", "gemini", "", "gemini-2.5-flash", ""),
        ModelCfg("GPT-4o mini", "openai", "", "gpt-4o-mini", "https://api.openai.com/v1"),
        ModelCfg("Llama 3.3 70B (Groq)", "openai", "", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1"),
        ModelCfg("OpenRouter", "openai", "", "openai/gpt-4o-mini", "https://openrouter.ai/api/v1"),
        ModelCfg("My AI", "openai", "", "", "")
    )
    val typeLabels = listOf("claude" to "Claude (Anthropic)", "gemini" to "Gemini (Google)", "openai" to "OpenAI-compatible")
}

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("prabhu", Context.MODE_PRIVATE)

    fun models(c: Context): MutableList<ModelCfg> {
        val s = sp(c)
        if (!s.contains("models")) {
            val m = migrate(c)
            saveModels(c, m)
            return m
        }
        val out = ArrayList<ModelCfg>()
        try {
            val arr = JSONArray(s.getString("models", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ModelCfg(o.optString("n"), o.optString("t"), o.optString("k"), o.optString("m"), o.optString("u")))
            }
        } catch (e: Exception) { }
        return out
    }

    fun saveModels(c: Context, list: List<ModelCfg>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("t", it.type).put("k", it.key).put("m", it.modelId).put("u", it.baseUrl))
        }
        sp(c).edit().putString("models", arr.toString()).apply()
    }

    /** Brings keys from the older version of the app. */
    private fun migrate(c: Context): MutableList<ModelCfg> {
        val s = sp(c)
        val out = ArrayList<ModelCfg>()
        for (i in 0 until 6) {
            val k = (s.getString("k$i", "") ?: "").trim()
            if (k.isEmpty()) continue
            val m = (s.getString("m$i", "") ?: "").trim()
            when (s.getString("p$i", "Gemini")) {
                "Claude" -> out.add(ModelCfg("Claude", "claude", k, m.ifEmpty { "claude-sonnet-5-5" }, ""))
                "OpenAI" -> out.add(ModelCfg("OpenAI", "openai", k, m.ifEmpty { "gpt-4o-mini" }, "https://api.openai.com/v1"))
                "Groq" -> out.add(ModelCfg("Groq", "openai", k, m.ifEmpty { "llama-3.3-70b-versatile" }, "https://api.groq.com/openai/v1"))
                else -> out.add(ModelCfg("Gemini", "gemini", k, m.ifEmpty { "gemini-2.5-flash" }, ""))
            }
        }
        return out
    }

    fun active(c: Context): Int = sp(c).getInt("active", 0)
    fun setActive(c: Context, i: Int) = sp(c).edit().putInt("active", i).apply()

    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun lang(c: Context): String = sp(c).getString("lang", "te-IN")!!
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    fun igToken(c: Context): String = sp(c).getString("ig_token", "") ?: ""
    fun setIgToken(c: Context, v: String) = sp(c).edit().putString("ig_token", v).apply()
    fun igAccountId(c: Context): String = sp(c).getString("ig_account_id", "") ?: ""
    fun setIgAccountId(c: Context, v: String) = sp(c).edit().putString("ig_account_id", v).apply()

    fun loadChats(c: Context): MutableList<Chat> {
        val f = File(c.filesDir, "chats.json")
        val out = ArrayList<Chat>()
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ch = Chat(o.getString("id"), o.optString("title", "Chat"))
                val ms = o.getJSONArray("msgs")
                for (j in 0 until ms.length()) {
                    val m = ms.getJSONObject(j)
                    ch.msgs.add(Msg(m.getString("r"), m.getString("t"), m.optString("m"), m.optString("f")))
                }
                out.add(ch)
            }
        } catch (e: Exception) { }
        return out
    }

    fun saveChats(c: Context, list: List<Chat>) {
        try {
            val arr = JSONArray()
            list.take(60).forEach { ch ->
                val ms = JSONArray()
                ch.msgs.forEach {
                    ms.put(JSONObject().put("r", it.role).put("t", it.text).put("m", it.model).put("f", it.files))
                }
                arr.put(JSONObject().put("id", ch.id).put("title", ch.title).put("msgs", ms))
            }
            File(c.filesDir, "chats.json").writeText(arr.toString())
        } catch (e: Exception) { }
    }
}
