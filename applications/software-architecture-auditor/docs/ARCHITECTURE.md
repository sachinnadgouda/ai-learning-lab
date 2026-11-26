# Architecture Design

## Hexagonal Layering

Software Architecture Auditor follows a strict hexagonal architecture with zero framework dependencies inside `domain/`:

| Layer | Package | Responsibility | Allowed Dependencies |
|---|---|---|---|
| **Domain** | `dev.lab.auditor.domain` | Graph models, Tarjan SCC, Robert C. Martin coupling, rule validation & evaluation, scan diffing, immutable envelopes, prompt-injection sanitizer | Kotlin stdlib, `kotlinx.serialization`, SnakeYAML (safe loader) |
| **Application** | `dev.lab.auditor.application` | Ports (`ScanRepositoryPort`, `FileCachePort`, `AiAdvisorPort`, `AuditEventPort`), polyglot lexers/parsers, bounded incremental `RepositoryScanner`, `AuditUseCase`, `ReportExporter` | `domain` |
| **Infrastructure** | `dev.lab.auditor.infrastructure` | Startup-validated `AuditorConfig`, Prometheus `AuditorMetricsRegistry`, SQLite + Flyway adapter, Ktor CIO `HttpAiAdvisor`, Ktor Netty `HttpServer`, CLI `Main` | `application`, `domain`, Ktor, SQLite, Flyway |

## Deterministic Graph Construction

1. **Canonical Ordering:** Nodes are sorted by normalized POSIX relative path; edges are deduplicated and sorted by `(sourceNode, sourceModule, targetModule, targetModuleOrPackage, importSymbol, lineNumber)`.
2. **Content-Addressed Digest:** A SHA-256 digest is computed over canonical modules, nodes (including file SHA-256 hashes), and edges. Two scans of identical repository state always produce identical digests.
3. **Tarjan SCC ($O(V + E)$):** Implemented iteratively to prevent stack overflow on large repositories. Vertices and adjacency lists are traversed in sorted order; multi-node SCCs are canonically rotated to begin at their lexicographically smallest node, and explicit self-loops ($v \to v$) are also flagged.
4. **Robert C. Martin Coupling Metrics:** For each internal module $m$:
   - $C_a(m)$: count of distinct internal modules depending on $m$
   - $C_e(m)$: count of distinct internal modules $m$ depends on
   - $I(m) = \frac{C_e(m)}{C_a(m) + C_e(m)}$ (or $0.0$ when isolated)

## Polyglot Parser Coverage, Resilience & Explicit Limitations

`LanguageParsers` is a **deterministic, lexer/token-based import extractor** — it intentionally does **not** invoke full language compilers, build full compiler ASTs, or perform semantic type/symbol resolution.

Each language parser operates in two phases:
1. **Line-preserving lexing/stripping** removes comments and non-import string literals while preserving `\n` characters so extracted line numbers remain exact.
2. **Import & package extraction** runs on sanitized tokens/logical statements. Unterminated comments, unclosed strings/templates, unterminated import blocks, or unbalanced delimiters emit non-fatal `ParseWarning` entries without aborting the scan.

### Supported Constructs by Language

| Language | Supported Import & Lexical Constructs |
|---|---|
| **Kotlin (`.kt`, `.kts`)** | `package` declarations, single/multiline `import` statements, aliases (`as`), wildcard (`.*`), backtick-escaped identifiers, nested `/* /* */ */` block comments, `//` comments, escaped strings, nested `${"..."}"` string interpolations, and `"""..."""` raw strings |
| **Java (`.java`)** | `package` declarations, single/multiline `import` and `import static` statements, wildcard (`.*`), `//` and `/* */` comments, escaped strings, and `"""..."""` text blocks (including `\"""`) |
| **TypeScript / JS (`.ts`, `.tsx`, `.js`, `.jsx`, `.mjs`, `.cjs`)** | Single/multiline ES `import`, `import type`, side-effect `import "mod"`, `export { ... } from`, `export type { ... } from`, `export * [as ns] from`, CommonJS `require("mod")`, static-literal dynamic `import("mod")` / `` import(`mod`) ``, nested template literals `${...}`, and `import.meta` exclusion |
| **Python (`.py`)** | `import a, b as c`, `from pkg import ...`, relative imports (`from . import a, b`, `from ..pkg import ...`), multiline parenthesized `from ... import (...)`, `\` line continuations, `;`-separated statements, `#` comments, escaped strings, and triple-quoted docstrings |
| **Go (`.go`)** | `package` declarations, single, aliased, dot (`.`), blank (`_`), and multiline grouped `import (...)` blocks, `//`/`/* */` comments, interpreted strings, rune literals, and Go raw backtick strings (`` `...` `` with no backslash escaping) |

### Known Limitations (Non-AST Trade-offs)

- **No fully-qualified inline references without imports:** Direct inline calls such as `com.acme.infrastructure.DbPool.open()` in Kotlin/Java (without an `import` statement) are not detected as edges.
- **No dynamic / computed import resolution:** Computed specifiers such as TypeScript `` import(`./plugins/${name}`) `` or Python `importlib.import_module(var)` cannot be statically resolved (TypeScript non-literal `import(...)` emits a `ParseWarning`).
- **No build-system path-alias or tsconfig/go.mod resolution:** Module resolution maps extracted targets against discovered repository packages and directory modules rather than executing `tsconfig.json` `paths`, Webpack aliases, or external package registries.
- **No macro/preprocessor/type-level analysis:** Reflection, dependency-injection wiring annotations, and runtime plugin loading are outside static import-graph scope.

## Incremental Caching

`RepositoryScanner` hashes every file with SHA-256 and consults `FileCachePort` (`file_parse_cache` table keyed by `(relative_path, sha256)`). Unchanged files reuse cached parse results (`cacheHits`), while new or edited files are re-parsed (`cacheMisses`).
