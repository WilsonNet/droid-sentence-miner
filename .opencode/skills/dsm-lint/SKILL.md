---
name: dsm-lint
description: Write Kotlin that passes Droid Sentence Miner's strict linters (detekt allRules + ktlint_official + Compose rules, Android Lint warningsAsErrors) on the first try, and edit legacy baselined code without resurfacing hundreds of findings. Use before writing or changing any Kotlin in app/, when detekt fails, or when asked to "make lint pass".
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Lint-clean Kotlin for this repo

`./gradlew detekt detektDebug detektDebugUnitTest lintDebug testDebugUnitTest assembleDebug` is the
PR gate. Every detekt rule is on (`allRules = true`), warnings are errors, ktlint runs inside detekt
(140 columns). Old findings live in `app/detekt-baseline*.xml`; new code must be clean.

Fast loop: `./gradlew detekt` (syntax, ~20 s) for main+test, `detektDebug` / `detektDebugUnitTest`
add type resolution (named-argument and Compose rules). Collect findings with
`./gradlew detekt detektDebug detektDebugUnitTest --continue 2>&1 | grep '^e: ' | sort -u`.

## How baselines resurface (read this before touching an old file)

Baseline IDs are `Rule:File.kt:<enclosing signature>$<element text>`, not line numbers. Editing
the text of a function, a `when`, a list literal or a class body changes that key, and **every**
baselined finding inside it comes back:

- adding one branch to `DebugApi`'s big `when` → ~90 findings (WhenEntryBracing,
  BlankLineBetweenWhenConditions). New debug endpoints go in `debug/DebugExtras.kt` instead.
- adding an entry to `RecommendedDictionaries.ALL` → MultilineExpressionWrapping on the list.
- adding a member to a class → NoEmptyFirstLineInClassBody, TooManyFunctions, ImportOrdering of
  the file.
- re-indenting a function body (e.g. `= withContext {` → `=\n    withContext {`) → every long
  line, unnamed argument and complexity finding inside it.

So: **change as little legacy text as possible**, put new logic in new functions/files
(`CardTemplates.kt`, `DebugExtras.kt`, `GermanScanner.kt`), and fix what still resurfaces. Never
run `-PdetektAutoCorrect` and keep the result wholesale: it reformats *every* file, which
resurfaces thousands of baselined findings (tried: 515 → 1121). If you use it, revert every file
you didn't mean to change (`git checkout -- <file>`), and prefer it only for brand-new files.

When a legacy function you touched resurfaces a finding that needs a rewrite to fix
(MultilineExpressionWrapping on an old expression body, CyclomaticComplexMethod, LongMethod),
suppress it on that function with the reason:

```kotlin
@Suppress(
    "MultilineExpressionWrapping", // Legacy expression body; ktlint's wrapping rules disagree on these (dsm-lint skill).
    "CyclomaticComplexMethod", // Legacy; one branch per card content.
)
suspend fun buildCard(...): AnkiCard = withContext(Dispatchers.Default) { ... }
```

## The contradiction: multi-line single-expression bodies

Measured with a probe file (detekt 2, this config). For a function whose body is ONE multi-line
expression (`when`, `buildString {}`, `x.map { … }`, `withContext {}`):

| Shape | Result |
| --- | --- |
| `fun f(): T = when (x) {` … | MultilineExpressionWrapping |
| `fun f(): T =` ⏎ `    when (x) {` … | FunctionSignature ("first line fits on same line") |
| `fun f(): T {` ⏎ `    return when …` | FunctionExpressionBody |
| `fun f(): T {` ⏎ `    val r = when …` ⏎ `    return r` | **passes** |
| `@Suppress("FunctionSignature")` + `=` ⏎ `    when (x) {` | **passes** |

Exception: when signature + first line would exceed 140 columns, `=` ⏎ `    expr` passes
unsuppressed. Single-line bodies: always `fun f(): T = expr` (a block with only `return expr` is
flagged by ExpressionBodySyntax/FunctionExpressionBody).

## Rules that bite new code (and what to write instead)

| Finding | Write |
| --- | --- |
| UnnamedParameterUse / NamedArguments | name arguments whenever a call has 2+ params of the same type or >3 args: `joinRows(previous = a, next = b)`, `minOf(a = x, b = y)`, `text.substring(startIndex = i, endIndex = j)`, `replace(oldChar = 'a', newChar = 'b')`, kotest `checkAll(genA = …, genB = …, genC = …)` |
| BooleanPropertyNaming | `val isX` / `hasX` / `areX` for every Boolean, locals too (`isMidWord`, `isOrdered`); interface flags too (`hasIpaReading`) |
| UndocumentedPublic* | KDoc on every public class/property/function/enum entry; data class properties via `@property` tags in the class KDoc; companion objects need one |
| DocumentationOverPrivateFunction/Property | private members get `//` comments, never `/** */` |
| StringShouldBeRawString | regexes/strings with `\` or `\"` → raw strings: `Regex("""\s+""")`, `"""<div class="x">"""` |
| MultilineRawStringIndentation / StringTemplateIndent | avoid multi-line raw strings in tests; build text with `listOf(...).joinToString("\n")` |
| FunctionLiteral ("newline after arrow") | a lambda on a long line must be multi-line: `x.map { v ->` ⏎ `    body` ⏎ `}`; extract a `val` when it gets deep |
| MultilineExpressionWrapping on `val x = call(` multi-line | `val x =` ⏎ `    call(` (… indented +4) |
| ArgumentListWrapping / MaxLineLength | one argument per line once a call doesn't fit; extract locals rather than wrap 5 levels |
| ClassSignature | a data class that fits on one line must be on one line |
| ClassOrdering | properties first, then functions — also inside companion objects and interfaces (`fun` defaults after `val`s) |
| ReturnCount (max 2) | compute a value and return once (`val isDue = …; return if (isDue) … else …`) |
| CyclomaticComplexMethod (14) / CognitiveComplexMethod (15) | split into small private functions (see `GermanScanner.candidateLengths` → `wordEnd`, `nextWordStart`) |
| TooManyFunctions (11 per class/object/interface/file, overrides count) | move helpers into an `internal object` in another file (`GermanLanguage` delegates to `GermanScanner`/`GermanDeinflector`); `@Suppress` with a reason for interfaces/bridges that are big by design |
| MagicNumber | `private const val` for every number but 0/1/-1 in main code (indices included) |
| InjectDispatcher | take `io: CoroutineDispatcher = Dispatchers.IO` as a parameter |
| NestedScopeFunctions (max 1) | no `?.let { … ?.let { } }`; use a local `val` |
| ReplaceSafeCallChainWithRun | `x?.run { split(',').filter { … }.toSet() }` instead of `x?.a()?.b()` on non-null results |
| DestructuringDeclarationWithTooManyEntries (max 3) | index into the list instead |
| BracesOnWhenStatements / WhenEntryBracing | in a `when` statement where any branch has braces, all have braces and a blank line between branches |
| OptionalUnit | an empty branch is `{ // comment }`, not `Unit` |
| ImportOrdering | lexicographic, `java`/`javax`/`kotlin` last, no blank lines; no fully qualified names in code (UnnecessaryFullyQualifiedName) |
| Composable `FunctionName` | the config's `ignoreWhenAnnotatedWith` doesn't reach the ktlint rule: `@Suppress("FunctionName") // Composables are PascalCase.` |
| ComposeUnstableCollections | no `List` params on composables: wrap in an `@Immutable data class` (see `ChangelogNotes`) — kotlinx-collections-immutable isn't a dependency |
| Compose mutable params | don't pass `MutableStateFlow`/`MutableState`; pass the owner (`AppContainer`) or a read-only `StateFlow` + callback |

Tests: same rules (names in backticks are allowed). Expression bodies with `runTest { checkAll(...) }`
hit the contradiction above — use block bodies: `fun \`name\`() { runTest { … } }`.

## Checklist before running the gate

1. New logic in new files where possible; legacy diffs minimal (no re-indent, no import churn).
2. Every Boolean `is/has`, every same-type call named, every public thing documented.
3. Lines ≤ 140; lambdas on long lines multi-line; no multi-line single-expression bodies without
   the suppression or a local `val`.
4. Run `detekt` first (fast), then the full gate. Fix new-file findings by hand; for legacy
   resurfacing, prefer reverting the legacy edit to a smaller one over fixing its whole body.
