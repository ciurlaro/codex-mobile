package io.github.ciurlaro.codexmobile.app.presentation.formatting

private val bareTaskMarker = Regex("""^(\s*)(\[[ xX]])(?=\s|$)""")

internal fun String.normalizeMarkdownTaskLists(): String {
    var fence: Char? = null
    return split('\n').joinToString("\n") { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()
            ?.takeIf { it == '`' || it == '~' }
            ?.takeIf { candidate -> trimmed.takeWhile { it == candidate }.length >= 3 }
        when {
            marker != null -> {
                if (fence == null) fence = marker
                else if (fence == marker && trimmed.dropWhile { it == marker }.isBlank()) fence = null
                line
            }
            fence == null -> bareTaskMarker.replaceFirst(line, "\$1- \$2")
            else -> line
        }
    }
}

internal fun effortLabel(value: String): String = when (value.lowercase()) {
    "none" -> "None"
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Extra High"
    "ultra" -> "Ultra"
    else -> value.replaceFirstChar { it.uppercase() }
}
