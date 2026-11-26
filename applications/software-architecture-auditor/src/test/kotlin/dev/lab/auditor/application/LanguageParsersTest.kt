package dev.lab.auditor.application

import dev.lab.auditor.domain.Language
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageParsersTest {

    @Test
    fun `kotlin parser ignores imports inside line comments, nested block comments, and raw strings`() {
        val code = """
            package com.app.domain

            // import com.app.infrastructure.LineCommentTrap
            /*
             * /* nested block comment */
             * import com.app.infrastructure.BlockCommentTrap
             */
            import com.app.shared.Money

            class Invoice {
                val s1 = "import com.app.infrastructure.DoubleQuotedTrap"
                val s2 = ${"\"\"\""}
                    import com.app.api.RawStringTrap
                ${"\"\"\""}
            }
        """.trimIndent()

        val result = LanguageParsers.parse("domain/Invoice.kt", Language.KOTLIN, code)
        assertEquals("com.app.domain", result.packageName)
        assertEquals(listOf("com.app.shared.Money"), result.imports.map { it.symbol })
        assertEquals(8, result.imports.single().lineNumber)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `java parser ignores comments and text blocks while extracting static and standard imports`() {
        val code = """
            package com.app.orders;

            import java.util.List;
            import static com.app.domain.Currency.EUR;
            // import com.app.infrastructure.Fake;
            /* import com.app.api.Fake; */

            public class OrderService {
                String s = "import com.app.infrastructure.StringTrap;";
                String block = ${"\"\"\""}
                    import com.app.infrastructure.TextBlockTrap;
                ${"\"\"\""};
            }
        """.trimIndent()

        val result = LanguageParsers.parse("orders/OrderService.java", Language.JAVA, code)
        assertEquals("com.app.orders", result.packageName)
        assertEquals(listOf("java.util.List", "com.app.domain.Currency.EUR"), result.imports.map { it.symbol })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `typescript parser extracts ES, side-effect, and require imports while ignoring strings and comments`() {
        val code = """
            // import { Fake } from "../infrastructure/comment";
            /* import { Fake2 } from "../api/block"; */
            import { Order } from "../domain/order";
            import "../telemetry/init";
            const db = require("../billing/client");
            const trap1 = "import { x } from '../infrastructure/str'";
            const trap2 = `import { y } from '../infrastructure/tpl'`;
        """.trimIndent()

        val result = LanguageParsers.parse("application/service.ts", Language.TYPESCRIPT, code)
        assertEquals(
            listOf("../domain/order", "../telemetry/init", "../billing/client"),
            result.imports.map { it.rawTarget },
        )
        assertEquals(3, result.imports[0].lineNumber)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `python parser strips docstrings, comments, and strings while parsing absolute and relative imports`() {
        val code = """
            ${"\"\"\""}
            Docstring trap:
            from infrastructure import db
            import api.admin
            ${"\"\"\""}
            # import infrastructure.pool
            import billing as b, orders
            from ..domain.models import Invoice
            msg = "from infrastructure import fake"
        """.trimIndent()

        val result = LanguageParsers.parse("app/service.py", Language.PYTHON, code)
        assertEquals(listOf("billing", "orders", "..domain.models"), result.imports.map { it.rawTarget })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `go parser handles single and grouped imports while ignoring comments and raw strings`() {
        val code = """
            package analytics

            import (
                "fmt"
                rep "acme.local/monolith/reporting"
            )
            // import "acme.local/monolith/infrastructure"
            func Run() string {
                return `import "acme.local/monolith/api"` + fmt.Sprint(rep.Version)
            }
        """.trimIndent()

        val result = LanguageParsers.parse("analytics/run.go", Language.GO, code)
        assertEquals(listOf("fmt", "acme.local/monolith/reporting"), result.imports.map { it.rawTarget })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `gracefully handles partially invalid truncated file with non-fatal warnings`() {
        val broken = """
            package com.app.notifications

            import com.app.reporting.Summary

            class Broken {
                fun run() {
                    /* unterminated comment
        """.trimIndent()

        val result = LanguageParsers.parse("notifications/Broken.kt", Language.KOTLIN, broken)
        assertEquals("com.app.notifications", result.packageName)
        assertEquals(listOf("com.app.reporting.Summary"), result.imports.map { it.symbol })
        assertTrue(result.warnings.isNotEmpty(), "Expected parse warnings for truncated file")
    }

    @Test
    fun `kotlin handles multiline imports, aliases, wildcards, backtick identifiers, nested strings, and comments inside strings`() {
        val code = """
            package com.app.billing

            import
                com.app.domain.Order
                as DomainOrder
            import com.app.shared.*
            import com.app.`in`.SpecialGateway
            import /* inline comment */ com.app.audit.AuditLog

            class Billing {
                val commentInStr1 = "/* not a block comment; import com.evil.Trap1"
                val commentInStr2 = "// not a line comment"
                val escapedQuotes = "escaped \" import com.evil.Trap2 \" trailing slash \\"
                val nestedInterpolation = "outer ${'$'}{"inner \"/* not comment */ import com.evil.Trap3\""}"
                val rawWithInterpolation = ${"\"\"\""}
                    /* fake comment */
                    ${'$'}{"nested raw \"import com.evil.Trap4\""}
                    import com.evil.Trap5
                ${"\"\"\""}
            }
        """.trimIndent()

        val result = LanguageParsers.parse("billing/Billing.kt", Language.KOTLIN, code)
        assertEquals("com.app.billing", result.packageName)
        assertEquals(
            listOf(
                "com.app.domain.Order",
                "com.app.shared.*",
                "com.app.`in`.SpecialGateway",
                "com.app.audit.AuditLog",
            ),
            result.imports.map { it.symbol },
        )
        assertEquals(
            listOf("com.app.domain", "com.app.shared", "com.app.`in`", "com.app.audit"),
            result.imports.map { it.rawTarget },
        )
        assertTrue(result.warnings.isEmpty(), "Unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `java handles multiline static imports, escaped text blocks, and comments inside strings`() {
        val code = """
            package com.app.orders;

            import static
                com.app.domain.Currency.USD;
            import
                com.app.shared.Money;

            public class Ledger {
                String a = "/* still a string, not comment */";
                String b = "escaped quote \" and backslash \\";
                String block = ${"\"\"\""}
                    \"\"\" import com.evil.FakeTextBlock;
                ${"\"\"\""};
            }
        """.trimIndent()

        val result = LanguageParsers.parse("orders/Ledger.java", Language.JAVA, code)
        assertEquals("com.app.orders", result.packageName)
        assertEquals(
            listOf("com.app.domain.Currency.USD", "com.app.shared.Money"),
            result.imports.map { it.symbol },
        )
        assertTrue(result.warnings.isEmpty(), "Unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `typescript handles multiline imports, dynamic imports, re-exports, nested template literals, and import meta`() {
        val code = """
            import {
                Alpha,
                Beta as B,
            } from "../domain/alpha";
            import type {
                Invoice,
            } from "../domain/invoice";
            export {
                Gamma,
                Delta as D,
            } from "./gamma";
            export * from "./reexport-all";
            export * as ns from "./reexport-ns";
            export type { PublicContract } from "./contracts";

            export default function bootstrap() {
                const url = import.meta.url;
                const s1 = "/* comment inside string */";
                const s2 = 'escaped \' quote and \\';
                const nested = `outer ${'$'}{`inner ${'$'}{"/* fake */"}`} /* still template */ import { Fake } from './trap'`;
                return url + s1 + s2 + nested;
            }

            async function loadPlugins(name: string) {
                const a = await import(
                    /* webpackChunkName: "lazy" */
                    "./lazy-plugin"
                );
                const b = await import(`./static-template-mod`);
                const c = await import(`./dynamic-${'$'}{name}`);
                return [a, b, c];
            }
        """.trimIndent()

        val result = LanguageParsers.parse("src/app/index.ts", Language.TYPESCRIPT, code)
        assertEquals(
            listOf(
                "../domain/alpha",
                "../domain/invoice",
                "./gamma",
                "./reexport-all",
                "./reexport-ns",
                "./contracts",
                "./lazy-plugin",
                "./static-template-mod",
            ),
            result.imports.map { it.rawTarget },
        )
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().message.contains("Dynamic non-literal import"))
    }

    @Test
    fun `python handles parenthesized multiline imports, relative sibling imports, backslash continuations, and comments inside strings`() {
        val code = """
            from ..domain.models import (
                Order,
                # comment inside parenthesized import
                Invoice as Inv,
            )
            from . import (
                sibling_a,
                sibling_b as sb,
            )
            from .. import shared_util
            from pkg.sub import \
                First, \
                Second
            import alpha as a, \
                beta.gamma

            s1 = "# not a comment: from evil import bad"
            s2 = "escaped \" from evil import trap \\"
            doc = '''
            from evil import docstring_trap
            '''
        """.trimIndent()

        val result = LanguageParsers.parse("src/billing/service.py", Language.PYTHON, code)
        assertEquals(
            listOf(
                "..domain.models",
                ".sibling_a",
                ".sibling_b",
                "..shared_util",
                "pkg.sub",
                "alpha",
                "beta.gamma",
            ),
            result.imports.map { it.rawTarget },
        )
        assertEquals(1, result.imports[0].lineNumber)
        assertTrue(result.warnings.isEmpty(), "Unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `go handles grouped imports with aliases, dot, blank imports, comments, and raw backtick strings with backslashes`() {
        val code = """
            package worker

            import (
                "context"
                // grouped comment
                dom "example.com/app/domain"
                . "example.com/app/shared"
                _ "example.com/app/metrics"
            )

            import singleAlias "example.com/app/single"

            const (
                rawWinPath = `C:\temp\dir\`
                commentInStr = "/* not a comment */"
                runeQuote = '\''
                runeSlash = '\\'
            )

            import "example.com/app/after-raw-string"
        """.trimIndent()

        val result = LanguageParsers.parse("internal/worker/run.go", Language.GO, code)
        assertEquals(
            listOf(
                "context",
                "example.com/app/domain",
                "example.com/app/shared",
                "example.com/app/metrics",
                "example.com/app/single",
                "example.com/app/after-raw-string",
            ),
            result.imports.map { it.rawTarget },
        )
        assertTrue(result.warnings.isEmpty(), "Unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `malformed files across all supported languages degrade gracefully with non-fatal warnings`() {
        val badKotlin = "package 123\nimport com.app..Broken\nimport com.app.Valid\nfun f() { val s = \"unterminated"
        val ktRes = LanguageParsers.parse("bad.kt", Language.KOTLIN, badKotlin)
        assertEquals(listOf("com.app.Valid"), ktRes.imports.map { it.symbol })
        assertTrue(ktRes.warnings.size >= 2)

        val badPython = "from ..domain import (\n    Order,\n    Invoice\n"
        val pyRes = LanguageParsers.parse("bad.py", Language.PYTHON, badPython)
        assertTrue(pyRes.warnings.isNotEmpty())

        val badGo = "package main\nimport (\n    \"fmt\"\n"
        val goRes = LanguageParsers.parse("bad.go", Language.GO, badGo)
        assertEquals(listOf("fmt"), goRes.imports.map { it.rawTarget })
        assertTrue(goRes.warnings.any { it.message.contains("Unterminated Go import block") })

        val badTs = "import { Ok } from './ok';\nconst broken = `unterminated template"
        val tsRes = LanguageParsers.parse("bad.ts", Language.TYPESCRIPT, badTs)
        assertEquals(listOf("./ok"), tsRes.imports.map { it.rawTarget })
        assertTrue(tsRes.warnings.any { it.message.contains("Unterminated template literal") })
    }
}
