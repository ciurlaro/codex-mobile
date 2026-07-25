package io.github.ciurlaro.codexmobile.app.security.display

internal fun String.toApprovalDisplayText(): String = buildString {
    var offset = 0
    while (offset < this@toApprovalDisplayText.length) {
        val codePoint = this@toApprovalDisplayText.codePointAt(offset)
        val type = Character.getType(codePoint)
        if (
            Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        ) {
            append("\\u{").append(codePoint.toString(16).uppercase()).append('}')
        } else {
            appendCodePoint(codePoint)
        }
        offset += Character.charCount(codePoint)
    }
}
