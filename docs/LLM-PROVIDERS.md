# LLM Provider Guide (Process Mining)

Kafka SQL Explorer's AI-assisted auditing works with cloud **and** fully local LLMs. Pick the option that matches your constraints — everything is also editable live from the **Config** page, which includes a **Test LLM** button to verify connectivity.

**The default is Option A, OpenRouter**, which is a *hosted* endpoint: the message digests Process
Mining builds leave your machine. If that is not acceptable, Option C and Option D keep everything
on your own network, and the Config page states which of the two you are on — read off the
resolved address, not off the provider's name, so an Ollama pointed at another box counts as
remote.

## Option A: OpenRouter (default — one key, many hosted models)

[OpenRouter](https://openrouter.ai) is a gateway in front of most hosted vendors, which makes it
the cheapest way to *try* several models against your own topics without opening an account with
each of them. It is also what this application ships pointed at, so the setup is one variable —
everything else is already the default:

```bash
export OPENROUTER_API_KEY='sk-or-v1-…'
```

```bash
docker run -p 127.0.0.1:8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e OPENROUTER_API_KEY=sk-or-v1-… \
  compagnonsdudev/kafkaexplorer:latest
```

The shipped values, for reference — nothing here needs to be written out unless you are changing it:

```yaml
claude:
  provider: OPENROUTER
  api-key: ${OPENROUTER_API_KEY:${ANTHROPIC_API_KEY:}}
  base-url: https://openrouter.ai/api/v1
  model: openai/gpt-4o-mini        # OpenRouter names models vendor/model
```

`ANTHROPIC_API_KEY` is read when `OPENROUTER_API_KEY` is unset — it is the historical name of this
one setting, not a second key — and `CLAUDE_API_KEY` outranks both, which is the unambiguous form
on a machine that exports several. Pick any slug from
[openrouter.ai/models](https://openrouter.ai/models); the default is cheap, current and supports
schemas, which makes it a starting point rather than a recommendation.

Three things worth knowing before you point it at a production cluster:

- **The key is required.** An anonymous request is a 401, so the Config page marks the field
  mandatory for this provider and Process Mining refuses up front instead of failing on the first
  analysed window.
- **Your messages go to whichever vendor serves the model.** This is a hosted gateway with a second
  hop behind it: the digests Process Mining builds leave your network, and the connection banner on
  the Config page says "remote inference" accordingly. If that is not acceptable, Option C or D.
- **Structured output depends on the model, not on OpenRouter.** Only some models — and only some
  of the upstream providers serving them — implement `response_format`. The app sends the schema
  anyway, and a model that refuses it gets one unconstrained retry and is remembered as such, *for
  that model alone*, so trying another one is not penalised by the first. `claude.structured-output:
  OFF` skips the probe entirely; the JSON is then recovered from the answer the way it is for
  SpectraLLM.

Requests carry OpenRouter's two attribution headers (`HTTP-Referer`, `X-Title`) naming this
project. They are sent to OpenRouter only, and they say nothing about your deployment, your
cluster or your messages.

### Where your messages are allowed to go

This is the one place in the application where "where does my data go" has an answer better than a
warning. OpenRouter can constrain routing at its own layer, so the shipped configuration asks it to:

```yaml
claude:
  openrouter-data-collection: DENY   # the default
```

`DENY` restricts routing to upstream providers that do **not** retain or train on what is sent, and
the Settings banner then states that property instead of merely warning that inference is remote.
The cost is stated where it is paid: a model served only by data-collecting providers stops being
routable, and OpenRouter reports that with the same 404 it uses for a mistyped slug — so the error
message names this setting, or an operator would spend the afternoon checking a model name that was
correct all along. Set `ALLOW` to widen the choice of models back.

Both pages that call a model say which of four cases you are in, in the same words —
`Stays on this host`, `No retention`, `Retention allowed`, `Governed by the endpoint` — because a
policy that reads differently depending on the screen is not a policy. Note what the sentence is
about: it states what **this deployment enforces**, not what a given model **declares**. Those are
different claims, and only the first is one this application can stand behind. `No retention` is
therefore never shown outside OpenRouter — on Anthropic, an arbitrary gateway or a remote Ollama
the question has an answer, but it belongs to the endpoint's own terms and nothing here can impose
or observe it, so the UI says exactly that instead of guessing.

Its sibling is deliberately **off**:

```yaml
claude:
  openrouter-require-parameters: false   # the default
```

`true` routes only to providers implementing every parameter sent, which turns structured output
from something discovered by a refusal into a routing guarantee. It is opt-in for the same reason
`structured-output: AUTO` leaves an unknown gateway alone: a model whose providers lack schema
support becomes *unroutable* rather than degrading, and that arrives as "no endpoints found" — not
as the 400 or 422 the per-model fallback can act on. Turn it on when you know your model is served
with schema support.

### Picking a model without knowing its name

Pick any slug from [openrouter.ai/models](https://openrouter.ai/models) — or don't, and let the
Settings page list them. **Browse models** asks OpenRouter for the models that can actually do this
job and shows them cheapest first: it filters on text output, on schema support, and on a context
window large enough for this deployment's own prompt budget plus its answer. Each row carries the
window, the published prices per million tokens, and a projected cost per analysed window.

Three things about that list are deliberate:

- **It states what it filtered by.** A filtered view presented as "the models" is the same lie as a
  truncated list presented as complete, so the criteria are printed above the rows and a checkbox
  widens the list to models without schema support. An empty list under stated criteria is a
  different answer from a catalogue that could not be read, and the page says which one it is.
- **The projected cost is a projection.** Everywhere else in this application a money figure is
  *read* from the provider's own accounting, because no price table lives here. This one is
  published prices multiplied by the same deliberately optimistic token estimate used elsewhere, so
  it can understate — the note under the list says so, and what an analysis really cost is still
  read back afterwards.
- **It is only fetched when you open it.** Nothing is asked of the gateway to render the Settings
  page.

**Test LLM applies nothing.** It probes the model in the form — including one you have just typed
and not saved — and says `Candidate reachable…` when what it tested is not what the deployment is
running. Previously the page had to save the form before it could probe, so trying a model
repointed the live deployment and, where settings are persisted, wrote it to disk. You can now try
three models and keep none.

**Only the model is tried unsaved.** The endpoint and the key always come from the saved
configuration, and that is deliberate: this application has no authentication, so a probe that let
its caller choose the URL would let anyone use the server as an HTTP client — and, since a blank
key falls back to the configured one, send your API key wherever they liked. Changing provider or
base URL therefore still goes through **Save**, and the page says so beside the button when your
form has moved ahead of what is running.

### What each analysis cost

OpenRouter prices every response, so Process Mining shows the real figure beside the token counts —
the last window and the running session total — rather than an estimate. It is read from the
provider's own accounting: this application keeps no price table, so a model that reports no cost
(the OpenAI API, Ollama, SpectraLLM) shows none rather than a zero, and a session containing one
unpriced call reports no total at all instead of one that understates the bill.

Two breakdowns travel beside it, both measurements rather than promises. **Cached prompt tokens**
say whether the provider served part of your prompt from its cache. **Reasoning tokens** say how
much of the answer the model spent deliberating before writing anything — already counted inside
the output tokens, so it explains a bill rather than adding to it. That one is worth watching on a
reasoning model: it is the number that shows `claude.max-tokens` being eaten by deliberation
*before* a run fails with a truncated answer, which is the failure this application otherwise only
reports after the fact. Both are shown only when non-zero, and a provider that reports neither
shows neither — an absent figure is never rendered as a zero.

### What the Test button can tell you here

On every other provider, **Test LLM** proves that something answered — which is worth knowing, and
is not the question you have when Process Mining misbehaves. That one is whether the slug you typed
can do this job, and OpenRouter is the only provider here that publishes the answer, so on it the
button says four more things about the model actually configured:

- **Whether it emits text at all.** An embeddings, rerank or speech model cannot answer a Process
  Mining prompt, and the gateway reports that with the same 404 it uses for a mistyped slug — so
  without this you would spend the afternoon checking a model name that was right.
- **What its schema support really is**, in three grades rather than two. `response_format` and
  `structured_outputs` are listed separately, and the interesting case is a model that has the
  first and not the second: it *accepts* the schema field and then ignores it. Nothing fails, so
  the per-model fallback described above never fires, and the JSON comes back as prose to be
  recovered. That is a guarantee that silently is not one, which is why it is named on screen
  rather than left to be discovered.
- **Whether the prompt budget fits its context window.** `process-mining.prompt-char-budget` is
  compared against the published `context_length`, plus `claude.max-tokens` — the answer is
  generated into the same window. Read it as a **floor**: the estimate is deliberately optimistic,
  so a budget it passes may still not fit, while one it rejects certainly does not.
- **Whether reasoning can be turned off.** On a model where it cannot, part of every answer's token
  budget goes to deliberation by construction — the cause of a truncated answer, visible here
  before a run fails rather than after.

Two things it deliberately does not do. It is asked **only when you press the button**, never on an
analysis, and only against OpenRouter's own host — pointing the provider at a corporate proxy
withholds the lookup rather than posting a vendor-specific path at it. And a lookup that fails says
so: "we asked and the answer is no" and "we could not ask" are different answers, and only the
first should change what you do next.

## Option B: Anthropic Claude
Set the provider and your API key:
```bash
CLAUDE_PROVIDER=ANTHROPIC
ANTHROPIC_API_KEY=sk-ant-…
CLAUDE_MODEL=claude-3-5-sonnet-20241022
```
The base URL fills itself in (`https://api.anthropic.com`) when you switch provider and have not
set one.

## Option C: Open Source / Local (Ollama, vLLM, LM Studio)

This is the option that keeps every byte on your machine, and the one to pick if the hosted
default is not acceptable. Note the two settings that have to move together — see the window
section just below, which is the trap this option carries.

1. Run your model (e.g., `ollama run qwen2.5-coder:7b`).
2. Update `src/main/resources/application.yml`:
```yaml
claude:
  provider: OPENAI_COMPATIBLE
  base-url: http://localhost:11434/v1 # For Ollama
  model: qwen2.5-coder:7b
```

A one-command stack with Ollama pre-wired is available:
```bash
docker compose -f docker-compose-llm.yml up -d
```

Going further on this option — which engine, which model at which quantisation, how small models
behave on constrained JSON, and how to tell that a prompt was silently truncated — is covered in
the [open-source LLM guide](LLM_OPEN_SOURCE_GUIDE.md) *(en français)*.

### The prompt has to fit the model's window

This is the one setting a local deployment gets wrong silently, and the reason it is a *local*
problem is that the shipped budget is sized for the shipped provider: a hosted model has room for
it, so moving to Option C is exactly the moment the two numbers stop agreeing. Ollama gives a model
**4 096 tokens** unless the machine has the VRAM for more; the app's OpenAI-compatible request carries
`model`, `messages`, `max_tokens`, `temperature` and `stream` — never `num_ctx`, which that
endpoint would not read from the body anyway; and `process-mining.prompt-char-budget` is 120 000
characters, about 30 000 tokens. Ollama does not refuse the excess: it drops the oldest messages
until the prompt fits, and logs that at debug level. The analysis then reasons on a fraction of
what it was given, and nothing says which fraction.

Set both halves together — the bundled stack does:

```bash
OLLAMA_CONTEXT_LENGTH=16384          # on the Ollama server (llama.cpp: -c 16384)
PROCESS_MINING_PROMPT_CHAR_BUDGET=16000   # ~4k tokens, leaving the rest of the window for the answer
```

Raising one without the other buys nothing, or truncates again. A wider window costs KV cache —
roughly 2 GB for a 7B model at 16k. The same arithmetic applies to vLLM (`--max-model-len`) and
to LM Studio's context slider.

## Option D: SpectraLLM (local, private, domain-tuned)
Audit Kafka exchanges with a self-hosted [SpectraLLM](https://github.com/devdownin/SpectraLLM)
instance — a fully local RAG + fine-tuning platform. Kafka Explorer calls SpectraLLM's
`POST /api/query` endpoint; no API key leaves your network.
```yaml
claude:
  provider: SPECTRA
  base-url: http://localhost:8080  # SpectraLLM API (e.g. http://spectra-api:8080 in Docker)
  use-rag: false                   # true = also retrieve from SpectraLLM's ingested corpus
  collection: ""                   # optional: a specific SpectraLLM collection to retrieve from
```
The `model` field is ignored — SpectraLLM serves whichever model it is configured to run.
Set `use-rag: true` to enrich the audit with SpectraLLM's document corpus; leave it `false`
to ground the analysis solely on the sampled Kafka messages.

When RAG is enabled, the Process Mining results show an **Evidence — cited sources** panel:
the corpus passages SpectraLLM grounded the audit on (with source file and relevance score),
turning each verdict into something verifiable.

**Combined stack, nothing built** — Kafka (with demo topics) + Kafka Explorer + a full local
SpectraLLM instance, all from the images both projects publish under `compagnonsdudev`. No
SpectraLLM checkout, no Maven, no npm. It does need **this** repository, whose demo seeder and
three service entrypoints the stack mounts:

```bash
docker compose -f docker-compose-spectra-hub.yml pull
docker compose -f docker-compose-spectra-hub.yml up -d
```

Explorer UI → http://localhost:8080 · SpectraLLM UI → http://localhost:8088 · SpectraLLM API →
http://localhost:8081. The first boot downloads ~4.8 GB of model weights in the background and
**nothing waits for it**, so both interfaces answer in seconds and Process Mining starts working
once the weights land. Plan for ~16 GB of RAM.

Four overlays sit beside it, each layered onto that file:

| Overlay | What it changes |
|---|---|
| [`…gpu.yml`](../docker-compose-spectra-hub.gpu.yml) | Both llama.cpp servers on CUDA — minutes per analysis become seconds. |
| [`…small.yml`](../docker-compose-spectra-hub.small.yml) | A 3B chat model instead of the 7B: ~2 GB, half the memory, far faster. |
| [`…limits.yml`](../docker-compose-spectra-hub.limits.yml) | Memory limits on the seven long-running services. The four it leaves out are one-shots that exit. |
| [`…ingest.yml`](../docker-compose-spectra-hub.ingest.yml) | SpectraLLM indexes the topics themselves, so the corpus answers questions about what is *in* your messages — and `CLAUDE_USE_RAG=true` lets the audits read it. |

**Do not set `SPECTRA_API_KEY` on that stack**: SpectraLLM's filter reads `X-API-Key` while this
application sends `Authorization: Bearer`, so a key there leaves everything looking healthy while
every Process Mining call answers 401. Neither application authenticates — keep the ports on the
loopback (`BIND_ADDR`, the default) or put something authenticating in front.

**The developer variant** builds the Explorer from source and follows a SpectraLLM checkout
beside this repository, profiles included:
```bash
# SpectraLLM checked out next to this repo; download its models once:
#   cd ../SpectraLLM && ./scripts/start.sh --first-run
docker compose -f docker-compose-spectra.yml up -d --build
```
Explorer UI → http://localhost:8090 · SpectraLLM UI → http://localhost. Point at a SpectraLLM
elsewhere with `SPECTRALLM_DIR=/path/to/SpectraLLM`. See the header of
[`docker-compose-spectra.yml`](../docker-compose-spectra.yml) for details.

## Recommended Lightweight Models
- **Qwen 2.5-Coder 7B**: Best for JSON extraction and logic.
- **Llama 3.2 3B**: Fast for real-time (LIVE) analysis.
- **DeepSeek-R1-Distill-Qwen-7B**: Superior for complex anomaly reasoning.

Whatever the size, check the two numbers above against each other: a 3B model with a 4k window
sees less of your messages than a 7B with 16k, and neither of them says so.
