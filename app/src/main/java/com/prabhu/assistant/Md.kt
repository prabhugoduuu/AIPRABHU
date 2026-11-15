package com.prabhu.assistant

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Small markdown renderer: headings, bold, inline code, code blocks, bullets. */
object Md {
    private const val F = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    private val head = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^(\\s*)[-*]\\s+")

    fun render(src: String, codeBg: Int): CharSequence {
        val sb = SpannableStringBuilder()
        var inCode = false
        for (raw in src.split("\n")) {
            if (raw.trim().startsWith("```")) { inCode = !inCode; continue }
            val start = sb.length
            if (inCode) {
                sb.append(raw).append("\n")
                sb.setSpan(TypefaceSpan("monospace"), start, sb.length, F)
                sb.setSpan(BackgroundColorSpan(codeBg), start, sb.length, F)
                sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, F)
                continue
            }
            var line = raw
            var isHead = false
            val h = head.find(line)
            if (h != null) { line = line.substring(h.range.last + 1); isHead = true }
            val b = bullet.find(line)
            if (b != null) line = b.groupValues[1] + "• " + line.substring(b.range.last + 1)
            appendInline(sb, line, codeBg)
            if (isHead) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, F)
                sb.setSpan(RelativeSizeSpan(1.15f), start, sb.length, F)
            }
            sb.append("\n")
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, line: String, codeBg: Int) {
        var pos = 0
        for (m in inline.findAll(line)) {
            sb.append(line.substring(pos, m.range.first))
            val s = sb.length
            if (m.groups[1] != null) {
                sb.append(m.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, F)
            } else {
                sb.append(m.groupValues[2])
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, F)
                sb.setSpan(BackgroundColorSpan(codeBg), s, sb.length, F)
            }
            pos = m.range.last + 1
        }
        sb.append(line.substring(pos))
    }
}
