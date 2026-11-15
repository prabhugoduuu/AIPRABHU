package com.prabhu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    class Result(val ok: Boolean, val text: String, val used: String)

    private class Retry(msg: String) : Exception(msg)

    private const val SYS =
        "You are Prabhu, a helpful personal AI assistant. Reply in the same language the user writes in " +
        "(Telugu, English or Tenglish). Be clear, accurate and friendly. Use simple markdown when it helps."
    private const val BRIEF = " This answer will be read aloud: keep it short and conversational, with no markdown."

    /** Tries the active model first; if it fails and fallback is on, tries the other models in order. */
    fun ask(models: List<ModelCfg>, active: Int, fallback: Boolean, msgs: List<Msg>, brief: Boolean): Result {
        val order = ArrayList<ModelCfg>()
        models.getOrNull(active)?.let { order.add(it) }
        if (fallback) models.forEachIndexed { i, m -> if (i != active) order.add(m) }
        val list = order.filter { it.key.isNotBlank() }
        if (list.isEmpty()) return Result(false, "No API key set for this model. Open Settings and add one.", "")

        val hist = msgs.takeLast(20).dropWhile { it.role != "user" }.filter { it.text.isNotBlank() }
        // Only the two most recent messages with files are sent along, to keep requests small.
        val withAtt = hist.indices.filter { hist[it].role == "user" && hist[it].atts.isNotEmpty() }.takeLast(2).toSet()
        val sys = if (brief) SYS + BRIEF else SYS
        val errs = ArrayList<String>()
        for (m in list) {
            try {
                if (m.modelId.isBlank()) throw Retry("model ID is empty")
                val out = when (m.type) {
                    "claude" -> claude(m, sys, hist, withAtt)
                    "gemini" -> gemini(m, sys, hist, withAtt)
                    else -> openai(m, sys, hist, withAtt)
                }
                return Result(true, out, m.name)
            } catch (e: Exception) {
                errs.add("${m.name}: ${e.message}")
            }
        }
        return Result(false, "Couldn't get a reply.\n" + errs.joinToString("\n"), "")
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 120000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val detail = try {
                val o = JSONObject(txt)
                o.optJSONObject("error")?.optString("message") ?: o.optString("error")
            } catch (e: Exception) { "" }
            throw Retry("HTTP $code ${detail.take(100)}")
        }
        return txt
    }

    private fun fileText(a: Att) = "File \"${a.name}\":\n${a.data}"

    private fun claude(m: ModelCfg, sys: String, h: List<Msg>, withAtt: Set<Int>): String {
        val arr = JSONArray()
        h.forEachIndexed { i, x ->
            val role = if (x.role == "user") "user" else "assistant"
            val at = if (i in withAtt) x.atts else emptyList()
            if (at.isEmpty()) {
                arr.put(JSONObject().put("role", role).put("content", x.text))
            } else {
                val c = JSONArray()
                for (a in at) {
                    when (a.kind) {
                        "image" -> c.put(JSONObject().put("type", "image").put("source",
                            JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", a.data)))
                        "pdf" -> c.put(JSONObject().put("type", "document").put("source",
                            JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", a.data)))
                        else -> c.put(JSONObject().put("type", "text").put("text", fileText(a)))
                    }
                }
                c.put(JSONObject().put("type", "text").put("text", x.text))
                arr.put(JSONObject().put("role", role).put("content", c))
            }
        }
        val body = JSONObject().put("model", m.modelId).put("max_tokens", 4096)
            .put("system", sys).put("messages", arr)
        val txt = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to m.key, "anthropic-version" to "2023-06-01"), body
        )
        val parts = JSONObject(txt).optJSONArray("content") ?: return "(empty reply)"
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().ifBlank { "(empty reply)" }
    }

    private fun gemini(m: ModelCfg, sys: String, h: List<Msg>, withAtt: Set<Int>): String {
        val contents = JSONArray()
        h.forEachIndexed { i, x ->
            val parts = JSONArray()
            val at = if (i in withAtt) x.atts else emptyList()
            for (a in at) {
                when (a.kind) {
                    "image" -> parts.put(JSONObject().put("inlineData",
                        JSONObject().put("mimeType", "image/jpeg").put("data", a.data)))
                    "pdf" -> parts.put(JSONObject().put("inlineData",
                        JSONObject().put("mimeType", "application/pdf").put("data", a.data)))
                    else -> parts.put(JSONObject().put("text", fileText(a)))
                }
            }
            parts.put(JSONObject().put("text", x.text))
            contents.put(JSONObject().put("role", if (x.role == "user") "user" else "model").put("parts", parts))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
        val txt = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${m.modelId}:generateContent",
            mapOf("x-goog-api-key" to m.key), body
        )
        val parts = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return "(empty reply)"
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().ifBlank { "(empty reply)" }
    }

    /** OpenAI, Groq, OpenRouter and any other OpenAI-compatible API. PDFs are sent as page images. */
    private fun openai(m: ModelCfg, sys: String, h: List<Msg>, withAtt: Set<Int>): String {
        val base = m.baseUrl.trim().trimEnd('/').ifEmpty { "https://api.openai.com/v1" }
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        fun imageBlock(b64: String) = JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        h.forEachIndexed { i, x ->
            val role = if (x.role == "user") "user" else "assistant"
            val at = if (i in withAtt) x.atts else emptyList()
            if (at.isEmpty()) {
                arr.put(JSONObject().put("role", role).put("content", x.text))
            } else {
                val c = JSONArray()
                for (a in at) {
                    when (a.kind) {
                        "image" -> c.put(imageBlock(a.data))
                        "pdf" -> {
                            c.put(JSONObject().put("type", "text").put("text",
                                "PDF \"${a.name}\" (first ${a.pages.size} pages shown as images):"))
                            a.pages.forEach { c.put(imageBlock(it)) }
                        }
                        else -> c.put(JSONObject().put("type", "text").put("text", fileText(a)))
                    }
                }
                c.put(JSONObject().put("type", "text").put("text", x.text))
                arr.put(JSONObject().put("role", role).put("content", c))
            }
        }
        val body = JSONObject().put("model", m.modelId).put("messages", arr)
        val txt = post("$base/chat/completions", mapOf("Authorization" to "Bearer ${m.key}"), body)
        val c = JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
        return if (c.isNullOrBlank()) "(empty reply)" else c
    }
}
