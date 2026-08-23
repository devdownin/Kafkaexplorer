# Code audit — simplification and optimisation

A pass over the whole tree (24 k lines of Java, 34 k of TypeScript) looking for the two things
that make a codebase heavier than the problem it solves: **one rule written in several places**,
and **work repeated where it did not have to be**. What follows is what was found, what was fixed
here, and what was deliberately left alone — that last section being the useful half of an audit,
since a finding recorded as "not treated" is what stops the next reader re-deriving it.

The method was mechanical rather than impressionistic: a duplicate-block scan over both trees, an
unused-export scan across the SPA, and a sweep for per-call regex compilation and per-record
factory construction. Findings that turned out to be import blocks or coincidental JSX tails were
discarded; what is below is what survived reading.

---

## Fixed

### F1 — XML parser hardening lived in five copies, and had already drifted

**Found.** The eight-line block that hardens a `DocumentBuilderFactory` against XXE appeared in
`XmlSchemaInferrer`, `FlinkSqlService`, `MessageFieldExtractorService`, `MessageMatcher` and
`XmlExtractUDF`. `secureXmlInputFactory()` was a second, **byte-identical** pair in
`MessageMatcher` and `PayloadDigestService`.

Copies of a security property do not stay copies. The drift was already there:

| | `disallow-doctype-decl` | secure processing | `namespaceAware(false)` | built per call |
|---|---|---|---|---|
| `MessageFieldExtractorService` | yes | **yes** | **yes** | no |
| `FlinkSqlService` | yes | no | no | no |
| `MessageMatcher` | yes | no | no | no |
| `XmlExtractUDF` | yes | no | no | no |
| `XmlSchemaInferrer` | yes | no | no | **yes** |

The last row is the one that matters twice over: `XmlSchemaInferrer` built a
`DocumentBuilderFactory` *per call* — feature negotiation plus parser discovery, on every sample —
against this project's own written rule, which existed because the per-record paths had already
been fixed for exactly that. A rule stated once and enforced in one of five places is not a rule.

Nowhere was `FEATURE_SECURE_PROCESSING` set on an **`XPathFactory`**, at any of the three sites
that compile an XPath. That is the half of XXE a `DocumentBuilder` does not cover: an expression
is evaluated by its own engine, with its own function resolution.

**Fixed.** `parser/SecureXml.java` is now the only place in the tree that configures an XML
parser, offering the four shapes the callers actually need — `documentBuilder()` (per-thread,
reset, for the per-record paths), `documentBuilderFactory()` (for a caller owning its own
lifecycle, such as the Flink UDF), `inputFactory()` (StAX) and `xpath()`. Every site takes the
strongest configuration of the five, XPath secure processing included.

One behaviour deliberately changed: an unrecognised security feature now **throws** instead of
being logged and swallowed (`XmlExtractUDF` caught and warned). A hardening that silently did not
apply is worse than a missing one — it is a deployment believed hardened. The JDK's bundled Xerces
supports all of them, so this can only fire on a different implementation on the classpath, which
is precisely the case worth failing on.

`SecureXmlTest` pins the four properties every entry point depends on: ordinary parsing, DOCTYPE
refusal, namespace-unawareness (load-bearing — the flatteners read `getTagName()`, which is why
the project's notes say never to call `getLocalName()`), reset between payloads, and a distinct
builder per thread. Before consolidation, pinning this meant writing those assertions five times,
so nobody had.

Net: **−90 lines, +1 class, +7 tests**, and one definition to review instead of five.

### F2 — the topic-pattern regex was recompiled once per topic

**Found.** `matchesTopicPattern(topic, pattern)` calls `patternToRegExp(pattern)` in its body, so
the compile happens **inside** every loop that uses it:

- `dataModel.ts` `filterTopics` — the Data Model's checkbox filter, which runs over the whole
  catalogue on **every keystroke**;
- `dataModel.ts` `filterEntities` — twice per entity;
- `streamFlow.ts` `expandTopicPatterns` — once per known topic.

On a nine-hundred-topic cluster that is nine hundred identical `RegExp` compilations per character
typed, for a pattern that by construction does not vary across the list. The cost scales with the
catalogue while the information does not.

**Fixed.** `topicPatternMatcher(pattern)` compiles once and returns the predicate;
`matchesTopicPattern` delegates to it so the single-question call site is unchanged and every loop
now passes the predicate directly. The compile is O(1) per filter instead of O(topics).

Returning a predicate rather than memoising inside `matchesTopicPattern` is the deliberate choice:
it puts the compilation where it visibly happens once, and says so to the caller, instead of
hiding a cache that the next loop would have to trust. Three cases are pinned — anchoring, case
insensitivity with a literal dot, and that repeated calls to one predicate keep answering (a
shared global `RegExp` carries `lastIndex`, and the second topic would stop matching).

### F3 — the same SQL grammar rule compiled at up to three sites

**Found.** In `FlinkSqlService`, some patterns are hoisted to `static final` and others are
compiled inline, with no line between the two. The projection regex
`^\s*SELECT\s+(.+?)\s+FROM\b` was compiled inline at **three** call sites with byte-identical
source; the aggregate-call regex at two; the window-call, aggregate-present and `LIMIT` patterns
once each in a per-query path.

The compile-per-call is the smaller half. Three copies of one grammar rule is how the three come
to disagree: whichever site is edited next takes the other two out of step, silently, and none of
them is covered by a test that would notice.

**Fixed.** Hoisted to named constants (`SELECT_PROJECTION`, `AGGREGATE_CALL`, `AGGREGATE_PRESENT`,
`WINDOW_TABLE_CALL`, `LIMIT_CLAUSE`) beside the ones already there. Behaviour is unchanged — the
sources were identical, which is what made the consolidation safe to do without a behavioural
test to hold it.

---

## Found, not treated

### N1 — `kafkaDirectSelect` re-declares a `FROM` pattern that already exists, and the two differ

`FlinkSqlService` hoists `FROM_TABLE` as ``(?i)\bFROM\s+[`"]?([\w.\-]+)[`"]?`` and then declares
``fromPattern`` inline as ``(?i)\bFROM\s+`?([\w.\-]+)`?`` — the same rule minus the double-quote
delimiter. They are **not** interchangeable: `FROM "orders"` yields `orders` under one and
`"orders` under the other.

Left alone on purpose. Collapsing them is not a refactor, it is a change to how the direct engine
resolves a double-quoted table name, and the tree holds no test that says which answer is
intended. It wants to be decided, then changed — not quietly unified inside a cleanup, which is
how a behaviour change gets shipped with no one having chosen it.

### N2 — three graph pages carry three copies of one viewport

`DataModel.tsx`, `StreamFlow.tsx` and `Lineage.tsx` each implement pointer panning, wheel zoom and
arrow/`+`/`−`/`0` keyboard handling over an SVG, with `isPanning` / `lastPos` refs and a
`{x, y, scale}` transform. The pan handlers are line-for-line identical across all three; the
keyboard branches differ only in their step. Roughly 120 lines of duplication, and the shape of it
— one interaction model, three implementations — is what lets a fix land on one page and not the
other two.

A `useGraphViewport` hook is the right answer and is a real refactor rather than an extraction:
`DataModel` layers `viewAdjusted` on top (documented as load-bearing — it suppresses the automatic
refit once the operator has framed the view themselves), `StreamFlow` clamps scale through
`clampScale`, and `Lineage` resets to a fixed origin. The hook has to carry those differences
without flattening them, and the three pages have very different test coverage. Sized rather than
attempted here, so it is not folded into a change whose other findings are behaviour-neutral.

### N3 — twenty-six exported symbols nothing imports

Across `topicSearch.ts`, `dataModel.ts`, `streamFlow.ts`, `queryWorkbench.ts`, `metricSuggestions.ts`,
`configDraft.ts`, `settingsPersistence.ts`, `flowChains.ts` and `navigation.ts`, twenty-six
`export function` / `export const` declarations are referenced only inside their own file — not
even by their own test.

Not treated, and the reason is worth stating rather than leaving as an omission: narrowing them
changes no behaviour, saves no bytes (they are all used internally, so nothing is tree-shaken),
and touches nine files. The gain is honest module surface; the cost is diff noise across pure
modules that are otherwise the best-tested code here. It is a worthwhile tidy-up on a day when it
is the only thing in the change, not riding along with a security consolidation.

The list is reproducible in one command, so it does not need to be pasted here:

```sh
cd src/main/webapp/src
for f in $(find . -name '*.ts' -o -name '*.tsx' | grep -v '\.test\.'); do
  grep -oE '^export (const|function) ([A-Za-z0-9_]+)' "$f" | awk '{print $3}' | while read -r n; do
    [ "$(grep -rlE "\b$n\b" --include='*.ts' --include='*.tsx' . | grep -vc "^$f$")" -eq 0 ] \
      && echo "$f :: $n"
  done
done
```

### N4 — `extractSimpleWhere` and `unsupportedWhereFragments` disagree about where a WHERE ends

`WHERE_WARNING_BLOCK` stops the clause before `GROUP BY` / `ORDER BY` / `HAVING` / `LIMIT`;
`extractSimpleWhere` compiles its own, which stops only at `LIMIT`. So on
`WHERE status = 'X' GROUP BY region` the two read different clause bodies.

It is currently harmless — the condition pattern that runs over the longer body matches only
`column = 'value'`, so the trailing `GROUP BY region` contributes nothing — which is exactly why
it is left recorded rather than fixed in passing. The safety is accidental, resting on the second
regex being narrow, and it would end the day either one is widened. Two definitions of "the WHERE
body" in one class want to become one, with a test on the `GROUP BY` case.

### N5 — `findValueIgnoreCase` is a linear scan per row, per condition

The direct engine's WHERE matcher falls back to a case-insensitive scan of the row's keys when the
exact lookup misses. When the column genuinely does not exist — a typo, or a field absent from
this topic's payloads — **every** row pays a full key scan, over a fetch that reaches 100 000
messages on an aggregate query.

Not treated because the fix is not the obvious one. Caching the resolved key per query is easy;
being correct about it is not, since the direct engine's rows are inferred per message and two
records of one topic need not carry the same keys. The measurement to take first is whether this
path is reached at all on a realistic miss, which wants a profile rather than a guess.

### N6 — 82 CodeQL findings predate this audit, and 16 of them are ReDoS on the SQL regexes

Not found by reading, found because the PR's CodeQL check went red and the alert had to be
explained rather than assumed. The explanation is worth keeping, and so is what it uncovered.

**The check failure itself is line attribution, not a regression.** Building a CodeQL database on
`main` and on this branch and diffing the two SARIF files gives **82 findings on each side, zero
genuinely new, zero resolved** — every finding on this branch exists identically on `main`, same
rule, same file, same message. What moved is the line number: hoisting the regexes shifted
`FlinkSqlService` by a few dozen lines, and GitHub attributes an alert as *new in this pull
request* when its line falls inside a changed hunk. Exactly five high-severity alerts land in the
added lines, which is exactly the count the check reports.

Reproduction, since this will recur on any diff that moves code in that file:

```sh
codeql database create db --language=java --command=./build.sh   # per branch
codeql database analyze db --format=sarif-latest --output=x.sarif \
  --download codeql/java-queries:codeql-suites/java-security-and-quality.qls
# then compare rule+file+message across the two SARIFs, ignoring startLine
```

**What it uncovered is the part that matters.** The five are all `java/polynomial-redos`, and they
are not noise: the SQL-parsing regexes are applied to a statement the caller supplies, and several
carry the classic ambiguity — `\s+(.+?)\s+` in `SELECT_PROJECTION`, and the four chained
`replaceAll` passes in `unsupportedWhereFragments`, each rescanning the previous one's output.
Sixteen such findings exist across the file, plus fourteen `java/sensitive-log` and twenty-seven
`java/log-injection`.

Deliberately **not** treated here. Fixing a regex's backtracking changes what it matches at the
edges, which is a behaviour change to SQL parsing — and this branch's whole claim is that it does
not change behaviour, verified against a baseline test run. Folding a ReDoS-hardening pass into a
consolidation would forfeit that claim for both. It is also worth doing properly rather than
quickly: the input is SQL typed by an operator into their own editor on an unauthenticated
internal tool, so the realistic impact is a self-inflicted stall rather than a remote DoS, which
is presumably why the findings have sat on `main` — but "the caller is trusted" is an argument the
rest of this codebase declines to make, and a query timeout is not a bound on a regex engine.

They want their own change, with the matching test cases, and a decision on whether the
`sensitive-log` and `log-injection` families are accepted or fixed — that last one being a
judgement about what this application is for, not a refactor.

**Update — the `polynomial-redos` half is now that change**, on the branch that follows this one:
16 findings down to 6, high-severity 33 down to 23, verified by rebuilding the CodeQL database
locally rather than by waiting for the gate. The six that remain are deliberate and are named
there. The `sensitive-log` and `log-injection` families are still undecided.

---

## Verification

- Backend: `./verify-offline.sh` — **724 passed, 0 failed** (4 skipped), against a baseline of
  717/0/4 on the unmodified tree taken the same way. The delta is the seven new `SecureXmlTest`
  cases; every pre-existing test is unchanged in outcome.
- Frontend: `npm run lint` clean (`--max-warnings 0`, `--report-unused-disable-directives`),
  `npm test` **1221 passed** against a baseline of 1218. The delta is the three new
  `topicPatternMatcher` cases.
- `npx tsc --noEmit` clean.

The offline harness is used because `packages.confluent.io` answers 403 through this environment's
proxy, which is the documented case it exists for — so the Avro and Schema Registry paths ran
against stubs, and **CI remains the authority**, as it does for every change here. Nothing in this
change touches those paths.
