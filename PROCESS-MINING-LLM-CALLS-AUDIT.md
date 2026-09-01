# Process Mining — the LLM calls, everywhere but OpenRouter (audit, 2026-08)

Review of what happens between the Process Mining page and a model when that model is **not**
reached through OpenRouter: `ANTHROPIC`, `OPENAI_COMPATIBLE`, `OLLAMA` and `SPECTRA`. The files are
`AnthropicLlmClient`, `SpectraLlmClient`, the non-OpenRouter half of `OpenAiCompatibleLlmClient`,
the plumbing they share (`LlmHttpSupport`, `LlmClientProvider`, `LlmClientFactory`,
`LlmJsonSupport`), what decides their behaviour (`ClaudeConfig`, `ConfigController`), the two
callers (`LlmAnalysisService`, `FieldProfilingService`) and the two screens that show the result
(`pages/ProcessMining.tsx`, `pages/Config.tsx`) — plus the stacks and scripts that configure them
(`compose/ollama.yml`, `compose/spectra-hub.yml`, `setup-llm.sh`, `setup-llm.ps1`).

The angle is deliberate and it is where the gap is. Every recent piece of work on this pipeline —
the routing policy, the per-model schema latch, the model catalogue, the key-credit read, the
price ceiling, the relayed-upstream diagnosis — is OpenRouter's, and so is most of the test suite:
`LlmStructuredOutputTest` has thirty-four cases, of which about twenty are about that gateway.
What is left over is the path taken by every deployment that keeps inference in-house, which is
the configuration this project ships two Docker stacks and two setup scripts for.

> **What this is derived from.** Everything below is read from the code and the shipped
> configuration, with file and line named. **Two things were measured** rather than reasoned
> about, both on this machine's JDK and quoted verbatim where they appear (L3). Nothing else was
> executed: this sandbox has a JDK 21 where `requireJavaVersion` pins 25, and no populated Maven
> repository, so neither `mvn verify` nor the Testcontainers broker can run. Where an item is an
> arithmetic consequence of two shipped values rather than an observation, it says so and names
> both values.
>
> **Status.** It implemented nothing when it was written. **L1, L2, L3, L5, L7, L10, L11 and part
> of L12 have since shipped**, in two changes: L1, L2 and L11 first, then the rest. What each
> section below describes is therefore the state that work was done *from*, and each shipped item
> now ends with what replaced it. **L4, L6, L8 and L9 remain open**, and the worklist at the end
> says so.
>
> **One thing was found while implementing rather than while reviewing**, and L2 overstated its
> symptom accordingly: the page does *not* print "The LLM could not be reached." on a client-side
> abort. `errorMessage` (`ProcessMining.tsx:106`) has an `ECONNABORTED` branch, so what was shown
> is *"The server did not answer in time. The model may still be working — check the backend logs,
> then retry with fewer topics, a smaller sample, or a faster model."* — advice about topics and
> sample size, on a one-word health check that carries neither. The substance of L2 is unchanged
> (the wait was shorter than the budget the two bundled stacks configure, so Test failed on an
> endpoint that was answering); the wording is corrected here rather than left to be found by the
> next reader.

Ranked. L1–L4 change what an operator is told or silently degrade a run; L5–L8 are gaps in the
plumbing that only bite the non-OpenRouter providers; L9–L11 are the coherence problems, one of
which turns off a feature on the stack it matters most for; L12 collects the small ones.

---

## L1 — A 400 that has nothing to do with the schema disables structured output for good

`OpenAiCompatibleLlmClient.generateWithMeta`, lines 99–113:

```java
boolean relayed = e.upstreamProvider() != null;
log.warn(…);
if (!relayed) {
    rememberSchemaRefusal(model);
}
return call(systemPrompt, userPrompt, null);
```

The conclusion is drawn **before** the retry that would test it. `looksLikeSchemaRefusal` is 400 or
422, which is right as a *trigger* — those are how an endpoint says it did not understand a field —
and is not evidence about *which* field. Four ordinary causes produce the same status on a
constrained request, none of them the schema:

- `max_tokens`, which this client always sends (line 246). The OpenAI API refuses it on reasoning
  models in favour of `max_completion_tokens`, and `OPENAI_COMPATIBLE` pointed at `api.openai.com`
  is a documented option here.
- `temperature: 0.0` (line 247), refused by those same models.
- A prompt over the model's context, which several gateways answer 400 rather than 413.
- Any body-level complaint from a local engine — llama.cpp, vLLM and LM Studio each have their own.

In all four the retry fails identically, the caller gets the real error, and the **latch stays
set**. `LlmClientProvider` fingerprints `provider|baseUrl|hash(apiKey)` and *not* the model, so it
survives every Settings change but those three: correcting the cause a minute later reuses the very
client that gave up. From then on every Process Mining window on that model runs unconstrained,
`LlmJsonSupport` carries the answers, and nothing on screen says so.

That is the failure mode the per-model latch was introduced to prevent, one door over. The class's
own comment argues the point exactly — *"even a genuine 400 only teaches us about the model that
provoked it"* — and then acts on a 400 whose genuineness was never established.

**Change.** Remember only what the retry proves: run the unconstrained call first, and call
`rememberSchemaRefusal(model)` on its success. It costs nothing — the retry already happens — and
it makes the recorded fact the one that was observed: *without the schema it works, with it it does
not.* A retry that fails too leaves the model unmarked, which is correct: the schema was not the
problem.

**Affects** `OLLAMA` and any gateway on `structured-output: ON`. Not `OPENAI_COMPATIBLE` under
`AUTO`, which sends no schema — see L11.

> **Shipped.** The unconstrained call now happens first and `rememberSchemaRefusal(model)` is
> called on its success, so what is recorded is what was observed: *without the schema it works,
> with it it does not.* A retry that fails too leaves the model unmarked and the caller gets that
> second failure, which is the honest one. The log follows: one line before the retry saying it is
> being tried, one after only when the conclusion is drawn. Pinned by
> `aFailureThatSurvivesDroppingTheSchemaTeachesNothingAboutTheSchema` (a 400 on `max_tokens`, which
> survives dropping the schema, must leave structured output alone) and its converse
> `aRefusalTheRetryConfirmsIsStillRemembered` — the first was checked to fail against the revision
> it describes, at the assertion that the next call constrains again.

---

## L2 — One endpoint, two waits, both wrong

`POST /api/config/test-llm` is called from two screens:

| Caller | Wait | |
|---|---|---|
| `pages/ProcessMining.tsx:102` | `LLM_TEST_TIMEOUT_MS = 90_000` | aborts at 90 s |
| `pages/Config.tsx:485` | none | `axios` has no default; it can spin for ever |

Against the shipped configuration of the two stacks this project provides for local inference:

- `compose/ollama.yml:74` → `CLAUDE_REQUEST_TIMEOUT_SECONDS=${EXPLORER_LLM_TIMEOUT:-300}`
- `compose/spectra-hub.yml:200` → the same, 300
- `pages/Config.tsx:1068` → the Settings field accepts up to **600**

So on both bundled local stacks the server is configured to wait five minutes and the Process
Mining page gives up after ninety seconds. The comment beside the first of those values says why
the 300 is there — *"a 7B model quantised to Q4 on CPU takes minutes on a prompt this size"* — and a
first call also pays the model load. What the operator then reads is
`"The LLM could not be reached."`: an unreachability verdict about an endpoint that was answering,
on the button whose entire job is to tell those two apart, on the deployment where it is hardest to
diagnose anything else.

The Settings button has the opposite defect and it is one this codebase has already named:
*"`axios` has no default timeout, so a server that never answered left `executing` true for ever"* —
fixed in the SQL editor, left standing here.

**Change.** Derive both from `llmRequestTimeoutSeconds`, which `GET /api/config` already serves
(`ConfigController:442`) and which the Process Mining page already fetches: the server's own budget
plus a margin, on both screens. A wait a UI invents for a call whose budget it knows is a wait that
is wrong the day that budget moves.

> **Shipped.** `pages/llmTimeout.ts` derives it: the served budget plus a 15 s margin for what is
> not generation, floored at the previous 90 s so a deployment on the shipped 60 s waits exactly
> what it waited before, and capped at 630 s so an aberrant setting cannot hold the browser
> indefinitely. Both screens use it — Process Mining off `llmInfo.llmRequestTimeoutSeconds`,
> Settings off the form's own field — and both now say, when it is *the browser* that gave up, how
> long it waited and against what budget (`describeTestTimeout`), instead of advising a change to
> topics a health check does not carry. Eight cases in `llmTimeout.test.ts`.

---

## L3 — "No LLM configured" cannot see an endpoint that is missing

`isApiKeyMissing()` — written twice, `LlmAnalysisService:961` and `FieldProfilingService:322` —
asks one question, and `isApiKeyRequired()` (`ClaudeConfig:359`) is false for `OPENAI_COMPATIBLE`,
`OLLAMA` and `SPECTRA`. There is no equivalent question about the **address**, and
`defaultBaseUrl(OPENAI_COMPATIBLE)` is `""` by design — the honest "we know nothing about this
endpoint".

So a deployment that selects `OPENAI_COMPATIBLE` and names no base URL passes every guard, builds
the prompt, and dies inside the HTTP builder. Measured here, on this JDK, with the exact strings
`LlmHttpSupport.v1Url` produces:

```
FAIL [/v1/chat/completions]                 -> IllegalArgumentException: URI with undefined scheme
FAIL [gpu-box:11434/v1/chat/completions]    -> IllegalArgumentException: invalid URI scheme gpu-box
OK   [http://localhost:11434/v1/chat/completions]
```

`IllegalArgumentException` is a `RuntimeException`, so `call()`'s `catch (RuntimeException e)`
(line 295) rethrows it as-is and the analysis reports **"LLM call failed: URI with undefined
scheme"** — a sentence naming neither `claude.base-url` nor the page that sets it. The second line
is the other half of the same hole: a host written without a scheme, which is exactly how one
writes an internal gateway.

The page compounds it. `ProcessMining.tsx:831` renders the endpoint as
`{llmInfo.llmBaseUrl && (…)}`, so a blank base URL does not read as *missing* — the line is simply
absent, under a banner that otherwise looks complete, beside a data-policy sentence
(`llmPolicy.ts`, tone `unenforceable`) confidently describing where digests go.

**Change.** An `isEndpointMissing()` beside `isApiKeyRequired()` — a provider that needs an address
and has none, or has one that is not an absolute http(s) URL — checked wherever the key is checked,
so the pipeline refuses before the read like it does for a missing key; and validated in
`settingsProblems` (`ConfigController:175`) alongside the enums and the positive ints, which is
where the same class of mistake is already caught for every other field.

> **Shipped.** `ClaudeConfig.configurationProblem()` answers both halves of one question — *can
> this deployment call a model?* — in one place rather than in the two services that each carried
> their own copy of the first, and the sentence it returns names the property, because it is
> rendered to whoever has to fix it. Both services consult it before reading anything, `test-llm`
> before probing, and `GET /api/config` publishes it as `llmConfigurationProblem`, which the
> Process Mining banner renders; a blank endpoint reads as `No endpoint configured.` there instead
> of the line simply vanishing. `POST /api/config` refuses a *typed* base URL that is not an
> absolute http(s) URL, and only a typed one — blank means "use the provider's default", which is a
> legitimate thing to save. Pinned by three cases in `ClaudeConfigTest` and one in
> `FieldProfilingServiceTest`; a fourth existing test had to be given a base URL, its fixture
> having been in exactly the shape this describes.

---

## L4 — On SpectraLLM the page names a model that is not answering

`docs/LLM-PROVIDERS.md`, Option D: *"The `model` field is ignored — SpectraLLM serves whichever
model it is configured to run."* `ClaudeConfig.defaultModel(SPECTRA)` returns `""` for the same
reason. But:

- `SpectraLlmClient:90-91` puts `config.getModel()` into `LlmUsage`, so it lands in the usage
  summary, in the `ANALYSIS_USAGE` event and in the INFO line of every analysis.
- `ProcessMining.tsx:811-812` renders `LLM runtime: {providerLabel} · {llmModel}`.
- `applyConfig` (`ConfigController:276-295`) fills in a default **base URL** on a provider switch
  and never touches the model.

A deployment moved off the shipped OpenRouter default therefore displays `openai/gpt-4o-mini` on
every window of a SpectraLLM session, in the one line whose job is to say what is answering. It is
the same defect the connection pill was rewritten for — a label asserting a fact nobody checked —
in the same shape, on the neighbouring banner.

**Change.** `LlmUsage.model` should be `null` where the provider selects the model itself, and the
banner should render the absence as such ("chosen by the server") rather than the stale slug. The
rule is the one this codebase applies to every count: a value nobody established is not rendered as
one that was.

---

## L5 — The Anthropic path shares none of the plumbing, and nothing pins it

`AnthropicLlmClient` does not go through `LlmHttpSupport`. That is defensible — it speaks through
the vendor SDK — but the plumbing it therefore skips is not one behaviour, it is six, and each is
one the other providers were given deliberately:

1. **`claude.request-timeout-seconds` does not apply.** No timeout is set on the SDK client
   (lines 30–40) and none on the call, so whatever the SDK's default is governs. `application.yml`
   scopes the setting correctly in a comment — *"HTTP LLM providers (OpenAI-compatible, Ollama,
   SpectraLLM)"* — while `Config.tsx:1066` renders the field unconditionally, with no provider
   condition and no note. A number on a form that does nothing for the provider in force is worse
   than an absent one.
2. **No `remedyFor`.** The 401/402/403/404/413 wording that names *the thing to go and change* is
   in `LlmHttpSupport:319`. On Anthropic a client error arrives as the SDK's own text, wrapped
   (line 121) — which is honest, and is exactly the "check base URL, model and API key" situation
   that wording was written to replace.
3. **No `Retry-After`.** `LlmHttpSupport.retryAfterMillis` reads a rate limit's own schedule in
   both legal forms. Anthropic publishes `retry-after` on a 429; here it is left to whatever the
   SDK does.
4. **No degrade path for a refused schema.** `structured-output: AUTO` turns schemas **on** for
   `ANTHROPIC` (`ClaudeConfig:347`), the schema travels as `OutputConfig`/`JsonOutputFormat`
   (lines 68–75), and if that endpoint refuses it — an older model, a gateway in front, an account
   not enabled for it — the analysis fails outright. On the OpenAI path the identical refusal costs
   one retry and the run succeeds. The provider whose schema support is asserted most confidently
   is the only one with no fallback behind the assertion.
5. **`temperature` is never set**, so the vendor default applies while every other provider here is
   pinned to `0.0` (`OpenAiCompatibleLlmClient:247`, `SpectraLlmClient:60`). Two calls whose answers
   are parsed as JSON, one of them run at a different determinism from the rest, by omission rather
   than by decision.
6. **No test touches this class.** `LlmStructuredOutputTest` drives `OLLAMA` against a stub
   `HttpServer`; `SpectraLlmClientTest` does the same for `SPECTRA`. The Anthropic SDK honours
   `baseUrl` — this client passes it through (lines 34–37) — so the same harness reaches it. Items
   1–5 are all pinnable that way and none is pinned.

**Change.** Set the timeout and the temperature from the config; make the Settings field state
which providers read it; and give the Anthropic path the same one-retry degrade the OpenAI one has,
keyed on the model like its sibling. Then write the stub-server test that would have caught any of
it.

> **Shipped, five of the six.** The client now applies `claude.request-timeout-seconds` through the
> SDK's `Timeout` builder, split the way `LlmHttpSupport.newClient` splits it and for the same
> reason (the connect timeout capped at 10 s, since a wrong port should not take a full minute to
> say so); pins `temperature` to 0.0 like every other provider here; reads `remedyFor` from
> `LlmHttpSupport` — made package-private rather than copied, a 402 meaning the same thing on both
> paths — and reports through `SqlErrorClassifier.explain`, which is never blank; and gets the same
> one-retry degrade its sibling has, keyed on the model and recorded only when the retry succeeds.
> **`AnthropicLlmClientTest` exists**, six cases, driven through the same stub `HttpServer` the
> other two clients use — the SDK honours `baseUrl`, so nothing had ever prevented this. Two of the
> six fail against the previous revision by construction: it sent no temperature and had no retry.
>
> The sixth is **not** shipped and is deliberately left: nothing asserts the *timeout* behaviourally.
> A test for it means a server that does not answer, and the SDK's own retry makes the wall time
> and the resulting exception non-deterministic — a slow, flaky test is worse than a stated gap.
> What is asserted is that the value is applied; what it does when it expires is the SDK's.
>
> The refusal memory moved to **`SchemaRefusalMemory`** in the process: it was `OpenAiCompatibleLlmClient`'s
> private field, and two copies of "which models cannot be constrained" is how one of them comes to
> latch on a status the other does not — the same argument that produced `SecureXml`, `LogSafe` and
> `EventTime`.
>
> Still open on this path: the Settings form renders `Request Timeout (s)` with no provider
> condition, which is now correct for every provider rather than for three of five, so what is left
> there is wording, not behaviour.

---

## L6 — Clients are replaced without being closed, and the fingerprint misses what they carry

`LlmClientProvider.get()` (lines 46–58) overwrites `client` on a fingerprint change and drops the
previous one. Nothing in the tree calls `close()` on an `LlmClient` or on anything inside one:

- `java.net.http.HttpClient` is `AutoCloseable` as of Java 21 (this project targets 25) and keeps a
  selector thread and a connection pool alive until it is collected. Two of the three clients hold
  one (`LlmHttpSupport.newClient`).
- The Anthropic SDK's client wraps an OkHttp client with a pool and dispatcher of its own.

Every Settings save that moves provider, base URL or key leaks one. Small, bounded by how often an
operator saves — and it is the sort of thing that is invisible until a deployment where somebody
scripts that endpoint.

The second half is the more interesting one. The fingerprint omits `requestTimeoutSeconds`, and
that value is **split across the two categories this class exists to distinguish**: the request
timeout is read per call (`withTimeout`, line 139), while the *connect* timeout is baked into the
client at construction (`newClient`, line 110). So raising the timeout in Settings moves one half
and leaves the other at the old value until the client happens to be rebuilt for an unrelated
reason — precisely the "what the client carries versus what it reads per call" defect this class
was written to fix, in the one field that was added to it afterwards.

**Change.** Add `requestTimeoutSeconds` to the fingerprint, and close the outgoing client when one
is replaced.

---

## L7 — Remote response bodies reach the log unsanitised, and one of them carries message content

`SpectraLlmClient:85`:

```java
log.error("SpectraLLM response has no 'answer' field: {}", response.body());
```

The **whole** body, unbounded and unneutralised — and on this provider that body is the model's
answer about Kafka payloads, so message-derived content lands in `logs/kafkaexplorer.log`, a named
volume in every stack and, as this repository's own notes put it, the first file anyone pastes into
a bug report. `OpenAiCompatibleLlmClient:293/296/299` are the same class, one degree milder: the
message logged embeds up to 300 characters of the provider's body verbatim (`truncate`, line 422).

`LogSafe.text` exists for exactly this and is applied to neither, while `LogSafe.slug` *is* applied
to the relayed provider name one file over (`LlmHttpSupport:370`) — so the rule is known here and
half-applied. The reasoning is the one already written down for the credentials that used to reach
the log inside auto-registration DDL: a hardening that holds at four entry points out of six is a
hardening nobody can rely on.

**Change.** `LogSafe.text` on both, and bound the SpectraLLM one with the same `truncate` its
siblings use. A body that will not parse is worth 300 characters of evidence, not all of it.

> **Shipped.** Both, plus the SpectraLLM catch that quotes a message built from the provider's
> body. The SpectraLLM one is bounded at 300 characters by the same `truncate` its siblings use.

---

## L8 — A redirect is retried three times and reported as transient

`LlmHttpSupport.newClient` does not set `followRedirects`, so the JDK default — `NEVER` — applies.
`sendWithRetry` (line 150) classifies a 2xx as success, a 4xx-that-is-not-429 as a configuration
error, and **everything else** as transient. A 301/302/307/308 therefore takes all three attempts
and comes back as `"OpenAI-compatible call failed with status 308: "` with an empty body — a
permanent misconfiguration reported as a passing one, having been retried on a schedule that could
not possibly help.

The case is ordinary rather than exotic on the providers this audit is about: a gateway that
redirects `http` to `https`, or `/v1` to `/v1/`, is a normal thing to sit in front of a local
engine.

**Change.** `followRedirects(NORMAL)` — the JDK refuses to follow https→http, so a credentialed
request is not downgraded — or, if following is not wanted, classify 3xx as a configuration error
naming the `Location` header. Either beats three attempts and a wrong verdict.

---

## L9 — Nothing says whether the constraint actually held

For OpenRouter this application distinguishes `CONSTRAINED` from `ACCEPTED_UNCONSTRAINED` — a
schema field accepted and then ignored — and the note explaining `SchemaSupport` says why it has
four values: *"a guarantee that silently is not one is worse than an outright refusal"*. Everywhere
else that same case is invisible. The field goes out, no error comes back, the answer arrives
wrapped in prose or a `<think>` block, `LlmJsonSupport` recovers the JSON, and the deployment
believes decoding was constrained. Nothing distinguishes it from a run where the schema held.

**The signal is already in hand and unread.** `LlmJsonSupport.extractJsonPayload` knows whether it
had to strip a reasoning block, strip a fence, or balance braces — that is its whole job. "A schema
was in force and the answer still needed repairing" is a fact about *this* call that any provider
can produce, with no catalogue, no second request and no per-provider knowledge.

The same shape applies to the other silent failure this pipeline has: **prompt truncation**.
`docs/LLM-PROVIDERS.md` devotes a section to it — Ollama drops the oldest messages until the prompt
fits and logs that at debug level, so the analysis reasons on a fraction of what it was handed and
nothing says which fraction — and the remedy offered is two environment variables set together and
trusted. Yet `usageOf` (`OpenAiCompatibleLlmClient:310`) already parses `usage.prompt_tokens` on
every call. Comparing what the provider says it read against the prompt's own length is the one
check that would say a window had been dropped, on every OpenAI-shaped endpoint, for free.

**Change.** Two flags on the answer, both derived from what is already parsed: *the schema was sent
and the answer still needed repair*, and *the endpoint counted far fewer prompt tokens than were
sent*. Surfaced as coverage warnings, which the page already renders. Deliberately a warning and
not a verdict: the ratio is an estimate, and this application does not own the tokeniser.

---

## L10 — The analysis half reports `e.getMessage()`; the profiling half reports the cause

`LlmAnalysisService:974`:

```java
return errorResult("LLM call failed: " + e.getMessage());
```

against `FieldProfilingService:135`:

```java
return FieldProfileResult.failed("LLM API error: " + SqlErrorClassifier.explain(e));
```

`getMessage()` is `null` for a `NullPointerException` and empty for several wrapper types, so the
same failure reads **"LLM call failed: null"** on one half of the pipeline and names its cause on
the other. This is the defect this codebase has now fixed in `ddl-preview`, in `QueryResult.error()`,
in `MetricService`'s startup restore and in `KafkaAdminService.pingDetail` — with `explain()`
written for it — left standing on the analysis call itself.

**Change.** One line.

> **Shipped.** Two, in the end: the log line had the same defect as the returned message.

---

## L11 — The stack shipped for local inference runs unconstrained, and the `OLLAMA` provider is set by nothing

`isStructuredOutputEnabled()` under `AUTO` says yes for `ANTHROPIC`, `OLLAMA` and `OPENROUTER`, and
leaves an unknown `OPENAI_COMPATIBLE` gateway alone — a good rule, for a good reason. Now what the
tree actually configures:

| Where | Provider set |
|---|---|
| `compose/ollama.yml:63` | `OPENAI_COMPATIBLE` |
| `setup-llm.sh:41` | `OPENAI_COMPATIBLE` |
| `setup-llm.ps1:39,44` | `OPENAI_COMPATIBLE` |
| `docs/LLM-PROVIDERS.md`, Option C | `OPENAI_COMPATIBLE` |
| `compose/spectra-hub.yml:186` | `SPECTRA` |

Nothing in the tree sets `OLLAMA`, except `LlmStructuredOutputTest`, which uses it as the stub
provider. So the enum value that exists to make `AUTO` say yes is set by no stack, no script and no
documented recipe — while `docs/DOCKERHUB.md:256` and the `CHANGELOG.md` entry for the OpenRouter
default (under `[1.8.10]`) both tell an operator to use `CLAUDE_PROVIDER=OLLAMA` to keep everything
in-house. Two instructions for one thing, and the one every shipped artefact follows is the one that
silently turns constrained decoding **off**.

It is off exactly where the schema work says it matters most — *"It matters most on the small
models this application is routinely pointed at"* — the stack in question serving `qwen2.5-coder:7b`
at 4-bit. And nothing on screen distinguishes that from a run where the schema was in force, which
is L9 again from the other end.

**Change.** Either name the provider `OLLAMA` in the two stacks, the two scripts and Option C — one
word each, and the `base-url` is already Ollama's — or let `AUTO` recognise an Ollama endpoint by
its address, the way `isOpenRouterEndpoint()` recognises OpenRouter's. The first is smaller and
says what it means; the second survives an operator who writes the compose file themselves.

> **Shipped, the first way.** `compose/ollama.yml`, `setup-llm.sh`, `setup-llm.ps1`, Option C of
> `docs/LLM-PROVIDERS.md` and the Ollama example in `docs/LLM_OPEN_SOURCE_GUIDE.md` all name
> `OLLAMA` now, each with the reason beside it — the two values reach the same client and speak the
> same dialect, which is precisely why they read as interchangeable and are not. The base URLs are
> unchanged and still explicit (the provider's own default is `localhost`, which inside a container
> is the container). Both docs keep `OPENAI_COMPATIBLE` where it is the right answer — vLLM, LM
> Studio, an unestablished gateway — and name `structured-output: ON` as the opt-in once its
> behaviour is known. The second way was left: detecting an Ollama by its address would put a
> guess where a declaration now stands, and `isOpenRouterEndpoint()` is deliberately used for a
> courtesy header rather than for anything load-bearing.

---

## L12 — The small ones

- **`isApiKeyMissing()` is written twice**, identically, in `LlmAnalysisService:961` and
  `FieldProfilingService:322`. The rule this codebase applies elsewhere (`SecureXml`, `LogSafe`,
  `EventTime`) puts it on `ClaudeConfig`, where `isApiKeyRequired()` already lives. It also becomes
  the natural home for L3's endpoint check. — **Shipped with L3**: both copies are gone, replaced by
  `configurationProblem()`.
- **SpectraLLM has no `noContentMessage` equivalent.** `SpectraLlmClient:83-87` refuses a missing or
  null `answer` and accepts everything else, so a blank one — or a non-textual node, where
  Jackson's `asText()` yields `""` — travels as an empty string and is reported one step later as
  "LLM returned an empty response." The OpenAI path was given three distinct sentences for this
  exact situation (`noContentMessage`, lines 397–420); the provider most likely to be running a
  small local model got none of them.
- **`rememberSchemaRefusal` clears the whole set at 64 models** (line 210). Defensible as written,
  and it means the 65th model probed re-probes every model already known to refuse. Bounded and
  harmless; recorded so it is a decision rather than a surprise.
- **`ClaudeConfig.probeCopy` copies `openrouterMaxPriceUsdPerMillion` and its two siblings** into a
  configuration that may not be OpenRouter's. Inert — `routingPolicy()` keys on the provider — and
  correct as written, since a probe must test what an analysis will do. Noted only because it is the
  one place a non-OpenRouter code path carries OpenRouter state.
- **`llmProviderDefaults` serves `defaultModel(SPECTRA) == ""`** and the browser applies it on a
  provider switch, but a switch made through the API applies no model default at all
  (`ConfigController:293`). That asymmetry is what L4 rests on.

---

## What this audit does not cover

OpenRouter, by request — the routing policy, the model catalogue, the shortlist, the key-credit
read, the price ceiling and the relayed-upstream diagnosis are all outside it, and where an item
above touches shared code (L1, L6, L7, L8) the change asked for is provider-neutral and improves
that path too.

Two things were looked at and found sound, and are recorded so they are not re-audited:
`KafkaLiveConsumer.recordSpend` (lines 333–376) handles an unpriced provider correctly — it stops
enforcing rather than counting unpriced calls as free, says so once, and sends it as its own SSE
event, which the page listens for (`ProcessMining.tsx:650`); and `LlmHttpSupport`'s decision to
make a *request* timeout terminal while retrying a connect timeout is right for exactly the reason
its comment gives, and matters most on the slow local models this audit is about.

---

## Worklist

| | Item | Change | Where |
|---|---|---|---|
| L1 | ~~A non-schema 400 latches for good~~ **shipped** | remember only what the retry proves | `OpenAiCompatibleLlmClient:99-113` |
| L2 | ~~Test aborts at 90 s against a 300 s server~~ **shipped** | derive both waits from the served budget | `ProcessMining.tsx:102`, `Config.tsx:485` |
| L3 | ~~A missing endpoint is not a state~~ **shipped** | `isEndpointMissing()`, checked and validated | `ClaudeConfig`, both services, `ConfigController:175` |
| L4 | A model named that is not answering | null it where the server picks it | `SpectraLlmClient:90`, `ProcessMining.tsx:811` |
| L5 | ~~Anthropic skips six behaviours~~ **five shipped** | timeout, temperature, degrade, and a test | `AnthropicLlmClient`, `Config.tsx:1066` |
| L6 | Clients leaked; fingerprint misses the timeout | close on replace, add the field | `LlmClientProvider:46-70` |
| L7 | ~~Remote bodies logged raw~~ **shipped** | `LogSafe.text` + truncate | `SpectraLlmClient:85`, `OpenAiCompatibleLlmClient:293` |
| L8 | 3xx retried as transient | follow redirects, or refuse and name `Location` | `LlmHttpSupport:109,150` |
| L9 | A constraint that silently did not hold | report repair, and a short prompt count | `LlmJsonSupport`, `usageOf` |
| L10 | ~~"LLM call failed: null"~~ **shipped** | `SqlErrorClassifier.explain` | `LlmAnalysisService:974` |
| L11 | ~~The local stack runs unconstrained~~ **shipped** | name `OLLAMA`, or detect the endpoint | `compose/ollama.yml:63`, `setup-llm.*`, docs |
| L12 | Five small ones — **one shipped** | see above | — |
