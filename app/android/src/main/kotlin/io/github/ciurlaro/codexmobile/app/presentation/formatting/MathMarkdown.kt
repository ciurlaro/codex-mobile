package io.github.ciurlaro.codexmobile.app.presentation.formatting

import java.util.Base64

private const val MATH_LINK = "codex-math:"

internal fun String.normalizeMathMarkdown(): String {
    val out = StringBuilder(length)
    var index = 0
    var fenced = false
    while (index < length) {
        if (startsWith("```", index)) {
            fenced = !fenced
            out.append("```")
            index += 3
            continue
        }
        if (fenced) {
            out.append(this[index++])
            continue
        }
        if (this[index] == '`') {
            val end = indexOf('`', index + 1)
            if (end >= 0) {
                out.append(substring(index, end + 1))
                index = end + 1
                continue
            }
        }
        val displayClose = when {
            startsWith("$$", index) -> "$$"
            startsWith("\\[", index) -> "\\]"
            else -> null
        }
        if (displayClose != null) {
            val openLength = 2
            val end = indexOf(displayClose, index + openLength)
            if (end >= 0) {
                val formula = substring(index + openLength, end).trim()
                if (formula.isNotEmpty()) {
                    out.append("\n```math\n").append(formula).append("\n```\n")
                    index = end + displayClose.length
                    continue
                }
            }
        }
        if (startsWith("\\(", index)) {
            val end = indexOf("\\)", index + 2)
            if (end >= 0) {
                appendInlineMath(out, substring(index + 2, end))
                index = end + 2
                continue
            }
        }
        if (this[index] == '$' && (index == 0 || this[index - 1] != '\\')) {
            val end = findInlineDollarEnd(index + 1)
            if (end >= 0) {
                val formula = substring(index + 1, end)
                if (formula.looksLikeMath()) {
                    appendInlineMath(out, formula)
                    index = end + 1
                    continue
                }
            }
        }
        out.append(this[index++])
    }
    return out.toString()
}

internal fun decodeMathLink(link: String): String? = link
    .takeIf { it.startsWith(MATH_LINK) }
    ?.removePrefix(MATH_LINK)
    ?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }

private fun String.findInlineDollarEnd(start: Int): Int {
    var cursor = start
    while (cursor < length && this[cursor] != '\n') {
        if (this[cursor] == '$' && this[cursor - 1] != '\\') return cursor
        cursor++
    }
    return -1
}

private fun String.looksLikeMath(): Boolean {
    val value = trim()
    if (value.isEmpty()) return false
    if (value.any { it in "\\^_={}+−×÷±<>" }) return true
    if (value.any { it in "*/" }) return true
    if (value.all { it.isLetterOrDigit() || it == '.' || it == ',' }) return true
    if (!value.contains(' ') && value.any(Char::isLetter)) return true
    return !(value.first().isDigit() && value.any(Char::isLetter))
}

private fun appendInlineMath(out: StringBuilder, formula: String) {
    val encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(formula.trim().toByteArray(Charsets.UTF_8))
    out.append("![math](").append(MATH_LINK).append(encoded).append(')')
}
