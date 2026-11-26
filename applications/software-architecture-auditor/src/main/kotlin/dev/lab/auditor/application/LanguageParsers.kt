package dev.lab.auditor.application

import dev.lab.auditor.domain.Language
import dev.lab.auditor.domain.ParseWarning

data class FileParseResult(
    val language: Language,
    val packageName: String,
    val lineCount: Int,
    val imports: List<ExtractedImport>,
    val warnings: List<ParseWarning>,
)

/**
 * Language-aware lexers and import extractors for Kotlin, Java, TypeScript/JS, Python, and Go.
 *
 * Scope & limitations:
 * - Performs token/lexer-level comment/string stripping and import-statement extraction
 *   (NOT full compiler AST parsing or semantic type/symbol resolution).
 * - Preserves exact 1-based line numbers and degrades gracefully on malformed or truncated
 *   files by emitting non-fatal [ParseWarning] entries.
 */
object LanguageParsers {

    fun detectLanguage(relativePath: String): Language? {
        val lower = relativePath.lowercase()
        return when {
            lower.endsWith(".kt") || lower.endsWith(".kts") -> Language.KOTLIN
            lower.endsWith(".java") -> Language.JAVA
            lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs") -> Language.TYPESCRIPT
            lower.endsWith(".py") -> Language.PYTHON
            lower.endsWith(".go") -> Language.GO
            else -> null
        }
    }

    fun parse(relativePath: String, language: Language, content: String): FileParseResult {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val lineCount = if (normalized.isEmpty()) 0 else normalized.count { it == '\n' } + 1
        return when (language) {
            Language.KOTLIN -> parseKotlinOrJava(relativePath, Language.KOTLIN, normalized, lineCount, isKotlin = true)
            Language.JAVA -> parseKotlinOrJava(relativePath, Language.JAVA, normalized, lineCount, isKotlin = false)
            Language.TYPESCRIPT -> parseTypeScript(relativePath, normalized, lineCount)
            Language.PYTHON -> parsePython(relativePath, normalized, lineCount)
            Language.GO -> parseGo(relativePath, normalized, lineCount)
        }
    }

    // --- Kotlin & Java ---

    private sealed interface JvmToken {
        val line: Int
        data class Ident(val text: String, override val line: Int) : JvmToken
        data class Punct(val ch: Char, override val line: Int) : JvmToken
    }

    private fun parseKotlinOrJava(
        file: String,
        language: Language,
        content: String,
        lineCount: Int,
        isKotlin: Boolean,
    ): FileParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val stripped = stripCStyleCommentsAndStrings(file, content, isKotlin = isKotlin, warnings = warnings)
        checkBalancedDelimiters(file, stripped, warnings)

        val tokens = tokenizeJvm(file, stripped, isKotlin = isKotlin, warnings = warnings)
        var packageName = ""
        val imports = mutableListOf<ExtractedImport>()

        var braceDepth = 0
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok is JvmToken.Punct) {
                when (tok.ch) {
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                }
                i++
                continue
            }

            if (braceDepth == 0 && tok is JvmToken.Ident) {
                val prevIsDot = i > 0 && tokens[i - 1] is JvmToken.Punct && (tokens[i - 1] as JvmToken.Punct).ch == '.'
                if (!prevIsDot && tok.text == "package" && packageName.isEmpty()) {
                    val (path, nextIdx, valid) = readJvmQualifiedPath(tokens, i + 1, allowWildcard = false)
                    if (valid && path.isNotEmpty()) {
                        packageName = path
                        i = skipOptionalSemicolon(tokens, nextIdx)
                        continue
                    } else {
                        warnings.add(ParseWarning(file, tok.line, "Malformed package declaration skipped"))
                    }
                } else if (!prevIsDot && tok.text == "import") {
                    val stmtLine = tok.line
                    var cursor = i + 1
                    // Java static import: `import static com.foo.Bar.baz;`
                    if (!isKotlin && cursor < tokens.size && tokens[cursor] is JvmToken.Ident && (tokens[cursor] as JvmToken.Ident).text == "static") {
                        cursor++
                    }
                    val (symbol, afterPath, valid) = readJvmQualifiedPath(tokens, cursor, allowWildcard = true)
                    if (!valid || symbol.isEmpty()) {
                        warnings.add(ParseWarning(file, stmtLine, "Malformed import statement skipped"))
                        i = advancePastMalformedJvmImport(tokens, cursor, stmtLine)
                        continue
                    }
                    cursor = afterPath
                    // Optional Kotlin alias: `import com.foo.Bar as Baz` (may span lines)
                    if (isKotlin && cursor < tokens.size && tokens[cursor] is JvmToken.Ident && (tokens[cursor] as JvmToken.Ident).text == "as") {
                        cursor++
                        if (cursor < tokens.size && tokens[cursor] is JvmToken.Ident) {
                            cursor++
                        } else {
                            warnings.add(ParseWarning(file, stmtLine, "Malformed Kotlin import alias skipped"))
                            i = advancePastMalformedJvmImport(tokens, cursor, stmtLine)
                            continue
                        }
                    }
                    cursor = skipOptionalSemicolon(tokens, cursor)
                    val targetPkg = symbol.substringBeforeLast('.', symbol)
                    imports.add(ExtractedImport(rawTarget = targetPkg, symbol = symbol, lineNumber = stmtLine))
                    i = cursor
                    continue
                }
            }
            i++
        }

        return FileParseResult(language, packageName, lineCount, imports, warnings)
    }

    private fun readJvmQualifiedPath(
        tokens: List<JvmToken>,
        startIdx: Int,
        allowWildcard: Boolean,
    ): Triple<String, Int, Boolean> {
        if (startIdx >= tokens.size) return Triple("", startIdx, false)
        val first = tokens[startIdx] as? JvmToken.Ident ?: return Triple("", startIdx, false)
        if (!isValidJvmSegment(first.text)) return Triple("", startIdx, false)

        val parts = mutableListOf(first.text)
        var cursor = startIdx + 1
        while (cursor < tokens.size) {
            val t = tokens[cursor]
            if (t is JvmToken.Punct && t.ch == '.') {
                cursor++
                if (cursor >= tokens.size) return Triple("", cursor, false)
                val next = tokens[cursor]
                if (allowWildcard && next is JvmToken.Punct && next.ch == '*') {
                    parts.add("*")
                    cursor++
                    // Wildcard must terminate the qualified path
                    if (cursor < tokens.size && tokens[cursor] is JvmToken.Punct && (tokens[cursor] as JvmToken.Punct).ch == '.') {
                        return Triple("", cursor, false)
                    }
                    break
                } else if (next is JvmToken.Ident && isValidJvmSegment(next.text)) {
                    parts.add(next.text)
                    cursor++
                } else {
                    return Triple("", cursor, false)
                }
            } else {
                break
            }
        }
        return Triple(parts.joinToString("."), cursor, true)
    }

    private fun isValidJvmSegment(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.startsWith('`') && text.endsWith('`') && text.length > 2) return true
        return (text[0].isLetter() || text[0] == '_' || text[0] == '$') &&
            text.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }

    private fun skipOptionalSemicolon(tokens: List<JvmToken>, idx: Int): Int {
        return if (idx < tokens.size && tokens[idx] is JvmToken.Punct && (tokens[idx] as JvmToken.Punct).ch == ';') {
            idx + 1
        } else {
            idx
        }
    }

    private fun advancePastMalformedJvmImport(tokens: List<JvmToken>, startIdx: Int, stmtLine: Int): Int {
        var cursor = startIdx
        while (cursor < tokens.size) {
            val t = tokens[cursor]
            if (t is JvmToken.Punct && t.ch == ';') return cursor + 1
            if (t.line > stmtLine) return cursor
            cursor++
        }
        return cursor
    }

    private fun tokenizeJvm(
        file: String,
        stripped: String,
        isKotlin: Boolean,
        warnings: MutableList<ParseWarning>,
    ): List<JvmToken> {
        val tokens = mutableListOf<JvmToken>()
        var i = 0
        var line = 1
        val n = stripped.length
        while (i < n) {
            val c = stripped[i]
            when {
                c == '\n' -> {
                    line++
                    i++
                }
                c.isWhitespace() -> i++
                isKotlin && c == '`' -> {
                    val start = i
                    val startLine = line
                    i++
                    var closed = false
                    while (i < n && stripped[i] != '\n') {
                        if (stripped[i] == '`') {
                            i++
                            closed = true
                            break
                        }
                        i++
                    }
                    if (!closed) {
                        warnings.add(ParseWarning(file, startLine, "Unterminated Kotlin backtick identifier"))
                    } else {
                        tokens.add(JvmToken.Ident(stripped.substring(start, i), startLine))
                    }
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (stripped[i].isLetterOrDigit() || stripped[i] == '_' || stripped[i] == '$')) i++
                    tokens.add(JvmToken.Ident(stripped.substring(start, i), line))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (stripped[i].isLetterOrDigit() || stripped[i] == '_')) i++
                    tokens.add(JvmToken.Ident(stripped.substring(start, i), line))
                }
                else -> {
                    tokens.add(JvmToken.Punct(c, line))
                    i++
                }
            }
        }
        return tokens
    }

    private fun stripCStyleCommentsAndStrings(
        file: String,
        src: String,
        isKotlin: Boolean,
        warnings: MutableList<ParseWarning>,
    ): String {
        val out = StringBuilder(src.length)
        val n = src.length
        var i = 0
        var line = 1

        // Recursively consumes Kotlin `${...}` interpolation blocks inside string literals so nested
        // strings, nested triple-quoted strings, and comments inside interpolations never break lexing.
        fun consumeKotlinInterpolation() {
            var depth = 1
            while (i < n && depth > 0) {
                val c = src[i]
                val next = if (i + 1 < n) src[i + 1] else '\u0000'
                when {
                    c == '/' && next == '/' -> {
                        i += 2
                        while (i < n && src[i] != '\n') i++
                    }
                    c == '/' && next == '*' -> {
                        val startLine = line
                        var cDepth = 1
                        i += 2
                        while (i < n && cDepth > 0) {
                            if (src[i] == '\n') {
                                out.append('\n')
                                line++
                                i++
                            } else if (src[i] == '/' && i + 1 < n && src[i + 1] == '*') {
                                cDepth++
                                i += 2
                            } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                                cDepth--
                                i += 2
                            } else {
                                i++
                            }
                        }
                        if (cDepth > 0) warnings.add(ParseWarning(file, startLine, "Unterminated block comment"))
                    }
                    c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                        val startLine = line
                        i += 3
                        var closed = false
                        while (i < n) {
                            if (src[i] == '\n') {
                                out.append('\n')
                                line++
                                i++
                            } else if (src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                                i += 2
                                consumeKotlinInterpolation()
                            } else if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                                i += 3
                                closed = true
                                break
                            } else {
                                i++
                            }
                        }
                        if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated triple-quoted string literal"))
                    }
                    c == '"' -> {
                        val startLine = line
                        i++
                        var closed = false
                        while (i < n) {
                            if (src[i] == '\\' && i + 1 < n) {
                                if (src[i + 1] == '\n') {
                                    out.append('\n')
                                    line++
                                }
                                i += 2
                            } else if (src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                                i += 2
                                consumeKotlinInterpolation()
                            } else if (src[i] == '"') {
                                i++
                                closed = true
                                break
                            } else if (src[i] == '\n') {
                                out.append('\n')
                                line++
                                i++
                                break
                            } else {
                                i++
                            }
                        }
                        if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated string literal"))
                    }
                    c == '{' -> {
                        depth++
                        i++
                    }
                    c == '}' -> {
                        depth--
                        i++
                    }
                    c == '\n' -> {
                        out.append('\n')
                        line++
                        i++
                    }
                    else -> i++
                }
            }
        }

        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'

            when {
                // Single-line comment
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                }
                // Multi-line block comment (nested in Kotlin, flat in Java)
                c == '/' && next == '*' -> {
                    val startLine = line
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (src[i] == '\n') {
                            out.append('\n')
                            line++
                            i++
                        } else if (isKotlin && src[i] == '/' && i + 1 < n && src[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                    if (depth > 0) {
                        warnings.add(ParseWarning(file, startLine, "Unterminated block comment"))
                    }
                    out.append(' ')
                }
                // Triple-quoted raw string (Kotlin) or text block (Java)
                c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    val startLine = line
                    i += 3
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\n') {
                            out.append('\n')
                            line++
                            i++
                        } else if (!isKotlin && src[i] == '\\' && i + 1 < n) {
                            if (src[i + 1] == '\n') {
                                out.append('\n')
                                line++
                            }
                            i += 2
                        } else if (isKotlin && src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                            i += 2
                            consumeKotlinInterpolation()
                        } else if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                            // Consume extra consecutive quotes in Kotlin raw strings (e.g. `""""`)
                            while (isKotlin && i + 3 < n && src[i + 3] == '"') i++
                            i += 3
                            closed = true
                            break
                        } else {
                            i++
                        }
                    }
                    if (!closed) {
                        warnings.add(ParseWarning(file, startLine, "Unterminated triple-quoted string literal"))
                    }
                    out.append(' ')
                }
                // Standard double-quoted string
                c == '"' -> {
                    val startLine = line
                    i++
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            if (src[i + 1] == '\n') {
                                out.append('\n')
                                line++
                            }
                            i += 2
                        } else if (isKotlin && src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                            i += 2
                            consumeKotlinInterpolation()
                        } else if (src[i] == '"') {
                            i++
                            closed = true
                            break
                        } else if (src[i] == '\n') {
                            out.append('\n')
                            line++
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    if (!closed) {
                        warnings.add(ParseWarning(file, startLine, "Unterminated string literal"))
                    }
                    out.append(' ')
                }
                // Char literal
                c == '\'' -> {
                    i++
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            i += 2
                        } else if (src[i] == '\'') {
                            i++
                            break
                        } else if (src[i] == '\n') {
                            out.append('\n')
                            line++
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    out.append(' ')
                }
                else -> {
                    if (c == '\n') line++
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // --- TypeScript / JavaScript ---

    private sealed interface Token {
        val line: Int
        data class Word(val text: String, override val line: Int) : Token
        data class Str(val value: String, override val line: Int) : Token
        data class TemplateExpr(override val line: Int) : Token
        data class Punct(val ch: Char, override val line: Int) : Token
    }

    private val tsExportStopKeywords = setOf(
        "function", "class", "const", "let", "var", "interface", "enum",
        "default", "abstract", "async", "declare", "namespace", "import", "export",
    )

    private fun parseTypeScript(file: String, content: String, lineCount: Int): FileParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val tokens = tokenizeJsLike(
            file = file,
            src = content,
            allowBacktick = true,
            backtickRawNoEscape = false,
            supportTemplateInterpolation = true,
            warnings = warnings,
        )
        val imports = mutableListOf<ExtractedImport>()

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok is Token.Word) {
                val prevIsDot = i > 0 && tokens[i - 1] is Token.Punct && (tokens[i - 1] as Token.Punct).ch == '.'
                when (tok.text) {
                    "import", "export" -> {
                        if (prevIsDot) {
                            i++
                            continue
                        }
                        val stmtLine = tok.line
                        // Skip `import.meta`
                        if (tok.text == "import" && i + 1 < tokens.size &&
                            tokens[i + 1] is Token.Punct && (tokens[i + 1] as Token.Punct).ch == '.'
                        ) {
                            i += 2
                            continue
                        }
                        // Side-effect import: `import "module"`
                        if (tok.text == "import" && i + 1 < tokens.size && tokens[i + 1] is Token.Str) {
                            val s = tokens[i + 1] as Token.Str
                            imports.add(ExtractedImport(s.value, s.value, stmtLine))
                            i += 2
                            continue
                        }
                        // Dynamic `import(...)`
                        if (tok.text == "import" && i + 2 < tokens.size &&
                            tokens[i + 1] is Token.Punct && (tokens[i + 1] as Token.Punct).ch == '('
                        ) {
                            val arg = tokens[i + 2]
                            when (arg) {
                                is Token.Str -> {
                                    imports.add(ExtractedImport(arg.value, arg.value, stmtLine))
                                    i += 3
                                    continue
                                }
                                is Token.TemplateExpr, is Token.Word -> {
                                    warnings.add(
                                        ParseWarning(
                                            file,
                                            stmtLine,
                                            "Dynamic non-literal import() cannot be statically resolved",
                                        ),
                                    )
                                    i += 2
                                    continue
                                }
                                else -> {
                                    i += 2
                                    continue
                                }
                            }
                        }

                        // Static ES import or re-export: scan forward for `from "..."`
                        // Must start with `{`, `*`, identifier, or `type` (not an export declaration keyword).
                        if (tok.text == "export" && i + 1 < tokens.size) {
                            val next = tokens[i + 1]
                            val isReExportStart = (next is Token.Punct && (next.ch == '{' || next.ch == '*')) ||
                                (next is Token.Word && next.text == "type" && i + 2 < tokens.size &&
                                    tokens[i + 2] is Token.Punct && ((tokens[i + 2] as Token.Punct).ch == '{' || (tokens[i + 2] as Token.Punct).ch == '*'))
                            if (!isReExportStart) {
                                i++
                                continue
                            }
                        }

                        var j = i + 1
                        var foundFrom = false
                        var braceDepth = 0
                        while (j < tokens.size) {
                            val t = tokens[j]
                            if (t is Token.Punct) {
                                when (t.ch) {
                                    '{' -> braceDepth++
                                    '}' -> if (braceDepth > 0) braceDepth--
                                    ';' -> if (braceDepth == 0) break
                                    '=' -> if (braceDepth == 0) break
                                }
                            }
                            if (braceDepth == 0 && t is Token.Word && t.text == "from" &&
                                j + 1 < tokens.size && tokens[j + 1] is Token.Str
                            ) {
                                val s = tokens[j + 1] as Token.Str
                                imports.add(ExtractedImport(s.value, s.value, stmtLine))
                                i = j + 2
                                foundFrom = true
                                break
                            }
                            if (braceDepth == 0 && t is Token.Word && t.text in tsExportStopKeywords) {
                                break
                            }
                            j++
                        }
                        if (foundFrom) continue
                    }
                    "require" -> {
                        if (!prevIsDot && i + 2 < tokens.size &&
                            tokens[i + 1] is Token.Punct && (tokens[i + 1] as Token.Punct).ch == '(' &&
                            tokens[i + 2] is Token.Str
                        ) {
                            val s = tokens[i + 2] as Token.Str
                            imports.add(ExtractedImport(s.value, s.value, tok.line))
                            i += 3
                            continue
                        }
                    }
                }
            }
            i++
        }

        val packageName = file.substringBeforeLast('/', "").replace('/', '.')
        return FileParseResult(Language.TYPESCRIPT, packageName, lineCount, imports, warnings)
    }

    private fun tokenizeJsLike(
        file: String,
        src: String,
        allowBacktick: Boolean,
        backtickRawNoEscape: Boolean = false,
        supportTemplateInterpolation: Boolean = false,
        warnings: MutableList<ParseWarning>,
    ): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var line = 1
        val n = src.length
        var braceBalance = 0
        val templateBraceStack = ArrayDeque<Int>()

        fun scanTemplateLiteral(startLine: Int, isContinuation: Boolean) {
            val sb = StringBuilder()
            var closed = false
            while (i < n) {
                if (!backtickRawNoEscape && src[i] == '\\' && i + 1 < n) {
                    sb.append(src[i + 1])
                    if (src[i + 1] == '\n') line++
                    i += 2
                } else if (supportTemplateInterpolation && src[i] == '$' && i + 1 < n && src[i + 1] == '{') {
                    // Enter `${...}` expression inside template literal; emit marker if opening chunk
                    if (!isContinuation) {
                        tokens.add(Token.TemplateExpr(startLine))
                    }
                    i += 2
                    templateBraceStack.addLast(0)
                    closed = true
                    break
                } else if (src[i] == '`') {
                    i++
                    closed = true
                    if (!isContinuation) {
                        tokens.add(Token.Str(sb.toString(), startLine))
                    }
                    break
                } else {
                    if (src[i] == '\n') line++
                    sb.append(src[i])
                    i++
                }
            }
            if (!closed) {
                warnings.add(ParseWarning(file, startLine, "Unterminated template literal"))
            }
        }

        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '\n' -> {
                    line++
                    i++
                }
                c.isWhitespace() -> i++
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    val startLine = line
                    i += 2
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\n') {
                            line++
                            i++
                        } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                            i += 2
                            closed = true
                            break
                        } else {
                            i++
                        }
                    }
                    if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated block comment"))
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    val startLine = line
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            sb.append(src[i + 1])
                            if (src[i + 1] == '\n') line++
                            i += 2
                        } else if (src[i] == quote) {
                            i++
                            closed = true
                            break
                        } else if (src[i] == '\n') {
                            line++
                            i++
                            break
                        } else {
                            sb.append(src[i])
                            i++
                        }
                    }
                    if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated string literal"))
                    tokens.add(Token.Str(sb.toString(), startLine))
                }
                allowBacktick && c == '`' -> {
                    val startLine = line
                    i++
                    scanTemplateLiteral(startLine, isContinuation = false)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                    tokens.add(Token.Word(src.substring(start, i), line))
                }
                else -> {
                    if (c == '{') {
                        if (templateBraceStack.isNotEmpty()) {
                            val top = templateBraceStack.removeLast()
                            templateBraceStack.addLast(top + 1)
                        } else {
                            braceBalance++
                        }
                        tokens.add(Token.Punct(c, line))
                        i++
                    } else if (c == '}') {
                        if (templateBraceStack.isNotEmpty()) {
                            val top = templateBraceStack.removeLast()
                            if (top == 0) {
                                // Resume enclosing template literal after closing `${...}`
                                i++
                                scanTemplateLiteral(line, isContinuation = true)
                            } else {
                                templateBraceStack.addLast(top - 1)
                                tokens.add(Token.Punct(c, line))
                                i++
                            }
                        } else {
                            braceBalance--
                            tokens.add(Token.Punct(c, line))
                            i++
                        }
                    } else {
                        tokens.add(Token.Punct(c, line))
                        i++
                    }
                }
            }
        }
        if (braceBalance != 0 || templateBraceStack.isNotEmpty()) {
            warnings.add(ParseWarning(file, line, "Unbalanced braces detected (balance=$braceBalance)"))
        }
        return tokens
    }

    // --- Python ---

    private val pyModuleIdentRegex = Regex("[A-Za-z_][A-Za-z0-9_.]*")
    private val pySimpleIdentRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val pyFromStmtRegex = Regex("^from\\s+(\\.+)([A-Za-z_][A-Za-z0-9_.]*)?\\s+import\\s+(.+)$")
    private val pyFromAbsStmtRegex = Regex("^from\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s+import\\s+(.+)$")
    private val pyImportStmtRegex = Regex("^import\\s+(.+)$")

    private data class PythonLogicalStatement(val text: String, val startLine: Int)

    private fun parsePython(file: String, content: String, lineCount: Int): FileParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val stripped = stripPythonCommentsAndStrings(file, content, warnings)
        val statements = splitPythonLogicalStatements(file, stripped, warnings)
        val imports = mutableListOf<ExtractedImport>()

        for (stmt in statements) {
            val trimmed = stmt.text.trim()
            if (trimmed.isEmpty()) continue

            val relMatch = pyFromStmtRegex.matchEntire(trimmed)
            if (relMatch != null) {
                val dots = relMatch.groupValues[1]
                val modulePart = relMatch.groupValues[2]
                val rawItems = relMatch.groupValues[3].trim()
                val items = parsePythonImportList(file, rawItems, stmt.startLine, warnings)
                if (modulePart.isEmpty()) {
                    // `from . import a, b` or `from .. import (x, y)` -> resolve each sibling explicitly
                    for (name in items) {
                        if (name == "*") {
                            imports.add(ExtractedImport(rawTarget = dots, symbol = "${dots}*", lineNumber = stmt.startLine))
                        } else {
                            val full = "$dots$name"
                            imports.add(ExtractedImport(rawTarget = full, symbol = full, lineNumber = stmt.startLine))
                        }
                    }
                } else {
                    val target = "$dots$modulePart"
                    val firstSym = items.firstOrNull().orEmpty()
                    val fullSymbol = if (firstSym.isNotEmpty() && firstSym != "*") "$target.$firstSym" else target
                    if (items.isNotEmpty()) {
                        imports.add(ExtractedImport(rawTarget = target, symbol = fullSymbol, lineNumber = stmt.startLine))
                    }
                }
                continue
            }

            val absMatch = pyFromAbsStmtRegex.matchEntire(trimmed)
            if (absMatch != null) {
                val target = absMatch.groupValues[1]
                val rawItems = absMatch.groupValues[2].trim()
                val items = parsePythonImportList(file, rawItems, stmt.startLine, warnings)
                val firstSym = items.firstOrNull().orEmpty()
                val fullSymbol = if (firstSym.isNotEmpty() && firstSym != "*") "$target.$firstSym" else target
                if (items.isNotEmpty()) {
                    imports.add(ExtractedImport(rawTarget = target, symbol = fullSymbol, lineNumber = stmt.startLine))
                }
                continue
            }

            if (trimmed.startsWith("from ")) {
                warnings.add(ParseWarning(file, stmt.startLine, "Malformed Python 'from ... import' statement skipped"))
                continue
            }

            val impMatch = pyImportStmtRegex.matchEntire(trimmed)
            if (impMatch != null) {
                val rawItems = impMatch.groupValues[1]
                for (item in rawItems.split(',')) {
                    val cleaned = item.trim()
                    if (cleaned.isEmpty()) continue
                    val parts = cleaned.split(Regex("\\s+as\\s+"), limit = 2)
                    val moduleName = parts[0].trim()
                    val aliasOk = parts.size == 1 || pySimpleIdentRegex.matches(parts[1].trim())
                    if (pyModuleIdentRegex.matches(moduleName) && aliasOk) {
                        imports.add(ExtractedImport(rawTarget = moduleName, symbol = moduleName, lineNumber = stmt.startLine))
                    } else {
                        warnings.add(ParseWarning(file, stmt.startLine, "Malformed Python import item '$cleaned'"))
                    }
                }
            }
        }

        val packageName = file.substringBeforeLast('/', "").replace('/', '.')
        return FileParseResult(Language.PYTHON, packageName, lineCount, imports, warnings)
    }

    private fun parsePythonImportList(
        file: String,
        rawItems: String,
        lineNo: Int,
        warnings: MutableList<ParseWarning>,
    ): List<String> {
        val unwrapped = if (rawItems.startsWith("(")) {
            if (!rawItems.endsWith(")")) {
                warnings.add(ParseWarning(file, lineNo, "Unterminated parenthesized Python import"))
                return emptyList()
            }
            rawItems.substring(1, rawItems.length - 1).trim()
        } else {
            rawItems
        }

        if (unwrapped.isEmpty()) {
            warnings.add(ParseWarning(file, lineNo, "Empty Python import list"))
            return emptyList()
        }
        if (unwrapped == "*") return listOf("*")

        val names = mutableListOf<String>()
        for (rawPart in unwrapped.split(',')) {
            val cleaned = rawPart.trim()
            if (cleaned.isEmpty()) continue
            val parts = cleaned.split(Regex("\\s+as\\s+"), limit = 2)
            val name = parts[0].trim()
            val aliasOk = parts.size == 1 || pySimpleIdentRegex.matches(parts[1].trim())
            if ((name == "*" || pySimpleIdentRegex.matches(name)) && aliasOk) {
                names.add(name)
            } else {
                warnings.add(ParseWarning(file, lineNo, "Malformed Python imported symbol '$cleaned'"))
            }
        }
        return names
    }

    private fun splitPythonLogicalStatements(
        file: String,
        stripped: String,
        warnings: MutableList<ParseWarning>,
    ): List<PythonLogicalStatement> {
        val statements = mutableListOf<PythonLogicalStatement>()
        val current = StringBuilder()
        var currentStartLine = -1
        var line = 1
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty() && currentStartLine > 0) {
                statements.add(PythonLogicalStatement(text, currentStartLine))
            }
            current.setLength(0)
            currentStartLine = -1
        }

        var i = 0
        val n = stripped.length
        while (i < n) {
            val c = stripped[i]
            when {
                // Explicit backslash line continuation
                c == '\\' && i + 1 < n && stripped[i + 1] == '\n' -> {
                    current.append(' ')
                    line++
                    i += 2
                }
                c == '\n' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        flush()
                    } else {
                        current.append(' ')
                    }
                    line++
                    i++
                }
                c == ';' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 -> {
                    flush()
                    i++
                }
                else -> {
                    if (!c.isWhitespace() && currentStartLine == -1) {
                        currentStartLine = line
                    }
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth-- else warnings.add(ParseWarning(file, line, "Unmatched ')' in Python source"))
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                    }
                    current.append(c)
                    i++
                }
            }
        }
        flush()

        if (parenDepth > 0 || bracketDepth > 0 || braceDepth > 0) {
            warnings.add(
                ParseWarning(
                    file,
                    line,
                    "Unclosed delimiter in Python source (paren=$parenDepth, bracket=$bracketDepth, brace=$braceDepth)",
                ),
            )
        }
        return statements
    }

    private fun stripPythonCommentsAndStrings(
        file: String,
        src: String,
        warnings: MutableList<ParseWarning>,
    ): String {
        val out = StringBuilder(src.length)
        var i = 0
        var line = 1
        val n = src.length

        while (i < n) {
            val c = src[i]
            when {
                c == '#' -> {
                    while (i < n && src[i] != '\n') i++
                }
                (c == '"' || c == '\'') && i + 2 < n && src[i + 1] == c && src[i + 2] == c -> {
                    val quote = c
                    val startLine = line
                    i += 3
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            if (src[i + 1] == '\n') {
                                out.append('\n')
                                line++
                            }
                            i += 2
                        } else if (src[i] == quote && i + 2 < n && src[i + 1] == quote && src[i + 2] == quote) {
                            i += 3
                            closed = true
                            break
                        } else {
                            if (src[i] == '\n') {
                                out.append('\n')
                                line++
                            }
                            i++
                        }
                    }
                    if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated Python triple-quoted string"))
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    val startLine = line
                    i++
                    var closed = false
                    while (i < n) {
                        if (src[i] == '\\' && i + 1 < n) {
                            if (src[i + 1] == '\n') {
                                out.append('\n')
                                line++
                            }
                            i += 2
                        } else if (src[i] == quote) {
                            i++
                            closed = true
                            break
                        } else if (src[i] == '\n') {
                            out.append('\n')
                            line++
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    if (!closed) warnings.add(ParseWarning(file, startLine, "Unterminated Python string literal"))
                    out.append(' ')
                }
                else -> {
                    if (c == '\n') line++
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // --- Go ---

    private fun parseGo(file: String, content: String, lineCount: Int): FileParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val tokens = tokenizeJsLike(
            file = file,
            src = content,
            allowBacktick = true,
            backtickRawNoEscape = true,
            supportTemplateInterpolation = false,
            warnings = warnings,
        )
        var packageName = ""
        val imports = mutableListOf<ExtractedImport>()

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok is Token.Word) {
                when (tok.text) {
                    "package" -> {
                        if (packageName.isEmpty() && i + 1 < tokens.size && tokens[i + 1] is Token.Word) {
                            packageName = (tokens[i + 1] as Token.Word).text
                            i += 2
                            continue
                        }
                    }
                    "import" -> {
                        if (i + 1 >= tokens.size) {
                            warnings.add(ParseWarning(file, tok.line, "Incomplete Go import declaration"))
                            break
                        }
                        val next = tokens[i + 1]
                        when {
                            next is Token.Punct && next.ch == '(' -> {
                                var j = i + 2
                                var closed = false
                                while (j < tokens.size) {
                                    val t = tokens[j]
                                    if (t is Token.Punct && t.ch == ')') {
                                        closed = true
                                        i = j + 1
                                        break
                                    }
                                    if (t is Token.Str) {
                                        imports.add(ExtractedImport(t.value, t.value, t.line))
                                    }
                                    j++
                                }
                                if (!closed) {
                                    warnings.add(ParseWarning(file, tok.line, "Unterminated Go import block"))
                                    break
                                }
                                continue
                            }
                            next is Token.Str -> {
                                imports.add(ExtractedImport(next.value, next.value, next.line))
                                i += 2
                                continue
                            }
                            // Aliased single import: `import alias "path"`, `import . "path"`, or `import _ "path"`
                            (next is Token.Word || (next is Token.Punct && (next.ch == '.' || next.ch == '_'))) &&
                                i + 2 < tokens.size && tokens[i + 2] is Token.Str -> {
                                val s = tokens[i + 2] as Token.Str
                                imports.add(ExtractedImport(s.value, s.value, s.line))
                                i += 3
                                continue
                            }
                        }
                    }
                }
            }
            i++
        }

        val fullPackage = if (packageName.isNotBlank()) {
            val dir = file.substringBeforeLast('/', "")
            if (dir.isNotBlank()) "$dir ($packageName)".substringBefore(" (").replace('/', '.') else packageName
        } else {
            file.substringBeforeLast('/', "").replace('/', '.')
        }
        return FileParseResult(Language.GO, fullPackage, lineCount, imports, warnings)
    }

    private fun checkBalancedDelimiters(file: String, stripped: String, warnings: MutableList<ParseWarning>) {
        var braces = 0
        var parens = 0
        var lastLine = 1
        for (ch in stripped) {
            when (ch) {
                '\n' -> lastLine++
                '{' -> braces++
                '}' -> braces--
                '(' -> parens++
                ')' -> parens--
            }
        }
        if (braces != 0) {
            warnings.add(ParseWarning(file, lastLine, "Unbalanced braces detected (balance=$braces)"))
        }
        if (parens != 0) {
            warnings.add(ParseWarning(file, lastLine, "Unbalanced parentheses detected (balance=$parens)"))
        }
    }
}
