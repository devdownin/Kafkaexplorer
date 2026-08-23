# LLM Provider Guide (Process Mining)

Kafka SQL Explorer's AI-assisted auditing works with cloud **and** fully local LLMs. Pick the option that matches your constraints — everything is also editable live from the **Config** page, which includes a **Test LLM** button to verify connectivity.

## Option A: Anthropic Claude (Default)
Set your API key as an environment variable:
```bash
export ANTHROPIC_API_KEY='your-api-key'
```

## Option B: Open Source / Local (Ollama, vLLM, LM Studio)
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

### The prompt has to fit the model's window

This is the one setting a local deployment gets wrong silently. Ollama gives a model **4 096
tokens** unless the machine has the VRAM for more; the app's OpenAI-compatible request carries
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

## Option C: SpectraLLM (local, private, domain-tuned)
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

**One-command combined stack, nothing built** — Kafka (with demo topics) + Kafka Explorer +
a full local SpectraLLM instance, all from the images both projects publish under
`compagnonsdudev`. No checkout of SpectraLLM, no Maven, no npm:

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
| [`…limits.yml`](../docker-compose-spectra-hub.limits.yml) | Memory limits on all nine services. |
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
