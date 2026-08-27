package acceptance

/**
 * Minimal JSON reader for the Acceptance Pipeline Specification feature IR.
 *
 * Hand-written on purpose. This file is dropped into a project whose build
 * configuration the acceptance layer does not own, and pulling in
 * kotlinx-serialization or Gson just to read one small, fully specified
 * document would be a dependency change with its own version conflicts.
 *
 * Yours to replace. Nothing else in the runtime depends on this reader beyond
 * the Map, List and String values it returns, so swapping it for a library the
 * project already ships is a safe improvement.
 */
internal class ApsIrException(message: String) : RuntimeException(message)

internal object ApsJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("trailing content after the top level value")
        return value
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(reason: String): Nothing =
            throw ApsIrException("Malformed JSON at offset $index: $reason")

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? = when (peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readKeyword("true", true)
            'f' -> readKeyword("false", false)
            'n' -> readKeyword("null", null)
            else -> readNumber()
        }

        private fun peek(): Char {
            if (atEnd()) fail("unexpected end of input")
            return text[index]
        }

        private fun expect(expected: Char) {
            if (peek() != expected) fail("expected '$expected' but found '${text[index]}'")
            index++
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a quoted object key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or '}' inside an object")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> fail("expected ',' or ']' inside an array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[index++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) fail("unterminated escape sequence")
            return when (val c = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) fail("truncated \\u escape")
                    val hex = text.substring(index, index + 4)
                    index += 4
                    hex.toIntOrNull(16)?.toChar() ?: fail("invalid \\u escape '$hex'")
                }
                else -> fail("unsupported escape '\\$c'")
            }
        }

        private fun readKeyword(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) fail("expected '$word'")
            index += word.length
            return value
        }

        private fun readNumber(): Any {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
            val raw = text.substring(start, index)
            if (raw.isEmpty()) fail("expected a value")
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("invalid number '$raw'")
        }
    }
}

internal fun Any?.apsTypeName(): String = when (this) {
    null -> "null"
    is Map<*, *> -> "an object"
    is List<*> -> "an array"
    is String -> "a string"
    is Boolean -> "a boolean"
    else -> "a number"
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.apsObject(what: String): Map<String, Any?> =
    this as? Map<String, Any?>
        ?: throw ApsIrException("$what must be a JSON object, found ${apsTypeName()}.")

internal fun Any?.apsArray(what: String): List<Any?> =
    this as? List<Any?>
        ?: throw ApsIrException("$what must be a JSON array, found ${apsTypeName()}.")

/** Background and examples are optional in the IR: absent means empty, not invalid. */
internal fun Any?.apsArrayOrEmpty(what: String): List<Any?> =
    if (this == null) emptyList() else apsArray(what)

internal fun Any?.apsText(what: String): String =
    this as? String
        ?: throw ApsIrException("$what must be a string, found ${apsTypeName()}.")
