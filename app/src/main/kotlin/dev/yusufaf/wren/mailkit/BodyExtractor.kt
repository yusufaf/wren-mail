package dev.yusufaf.wren.mailkit

import android.text.Html
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.Viewable

/**
 * Extracts a plain-text body for the watch screen. Prefers text/plain parts;
 * HTML-only messages (most commercial mail) fall back to the text/html part
 * converted to plain text.
 */
object BodyExtractor {

    fun extract(message: Message, sizeLimit: Long): String? {
        val viewables = mutableListOf<Viewable>()
        MessageExtractor.findViewablesAndAttachments(message, viewables, null)
        val plain = firstText(viewables, html = false, sizeLimit)
        if (!plain.isNullOrBlank()) return plain
        return firstText(viewables, html = true, sizeLimit)?.takeIf { it.isNotBlank() }
    }

    private fun firstText(viewables: List<Viewable>, html: Boolean, sizeLimit: Long): String? {
        for (viewable in viewables) {
            val text = when (viewable) {
                is Viewable.Alternative ->
                    firstText(if (html) viewable.html else viewable.text, html, sizeLimit)

                is Viewable.Html ->
                    if (html) {
                        MessageExtractor.getTextFromPart(viewable.part, sizeLimit)
                            ?.let(::htmlToPlainText)
                    } else {
                        null
                    }

                is Viewable.Text ->
                    if (html) null else MessageExtractor.getTextFromPart(viewable.part, sizeLimit)

                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun htmlToPlainText(html: String): String {
        // Html.fromHtml keeps the text inside tags it doesn't render, so drop
        // style/script blocks first or CSS leaks into the body text.
        val stripped = html
            .replace(STYLE_OR_SCRIPT, "")
            .replace(HTML_COMMENT, "")
        return Html.fromHtml(stripped, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace(' ', ' ')
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()
    }

    private val STYLE_OR_SCRIPT =
        Regex("<(style|script)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val EXTRA_BLANK_LINES = Regex("\n{3,}")
}
