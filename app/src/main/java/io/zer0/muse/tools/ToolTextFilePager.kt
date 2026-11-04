package io.zer0.muse.tools

import java.io.File
import java.io.Reader
import java.nio.charset.Charset

/** Reads bounded character pages from app-accessible UTF text files without loading the whole file. */
internal object ToolTextFilePager {

    fun readPage(
        file: File,
        path: String,
        charset: Charset,
        requestedOffset: Int,
        requestedLength: Int,
        continuationTool: String = "read_file",
    ): String {
        val pageLength = requestedLength.takeIf { it > 0 }
            ?.coerceAtMost(TOOL_OUTPUT_READ_PAGE_CHARS)
            ?: TOOL_OUTPUT_READ_PAGE_CHARS
        val start = alignOffsetToCodePoint(file, charset, requestedOffset.coerceAtLeast(0))
        val content = StringBuilder(pageLength)
        val hasMore = file.reader(charset).buffered().use { reader ->
            skipCharacters(reader, start)
            val buffer = CharArray(minOf(8_192, pageLength))
            while (content.length < pageLength) {
                val count = reader.read(buffer, 0, minOf(buffer.size, pageLength - content.length))
                if (count < 0) break
                content.append(buffer, 0, count)
            }
            reader.read() >= 0
        }
        if (content.isNotEmpty() && Character.isHighSurrogate(content.last())) {
            content.setLength(content.length - 1)
        }
        val nextOffset = start + content.length
        return buildString(content.length + 180) {
            append(content)
            if (hasMore) {
                append("\n\n[The file has further content; continue calling ").append(continuationTool).append(": path=\"")
                append(path)
                append("\", offset_chars=")
                append(nextOffset)
                append(", length_chars=")
                append(pageLength)
                append(']')
            }
        }
    }

    private fun alignOffsetToCodePoint(file: File, charset: Charset, offset: Int): Int {
        if (offset <= 0) return 0
        return file.reader(charset).buffered().use { reader ->
            skipCharacters(reader, offset - 1)
            val before = reader.read()
            val at = reader.read()
            if (
                before >= 0 && at >= 0 &&
                Character.isHighSurrogate(before.toChar()) &&
                Character.isLowSurrogate(at.toChar())
            ) {
                offset - 1
            } else {
                offset
            }
        }
    }

    private fun skipCharacters(reader: Reader, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (reader.read() < 0) {
                return
            } else {
                remaining--
            }
        }
    }
}
