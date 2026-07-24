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

**One-command combined stack** — Kafka (with demo topics) + Kafka Explorer + a full local
SpectraLLM instance, pre-wired so the audit runs through SpectraLLM:
```bash
# SpectraLLM must be checked out next to this repo; download its models once:
#   cd ../SpectraLLM && ./scripts/start.sh --first-run
docker compose -f docker-compose-spectra.yml up -d --build
```
Explorer UI → http://localhost:8090 · SpectraLLM UI → http://localhost. Point at a
SpectraLLM elsewhere with `SPECTRALLM_DIR=/path/to/SpectraLLM`. See the header of
[`docker-compose-spectra.yml`](../docker-compose-spectra.yml) for details.

## Recommended Lightweight Models
- **Qwen 2.5-Coder 7B**: Best for JSON extraction and logic.
- **Llama 3.2 3B**: Fast for real-time (LIVE) analysis.
- **DeepSeek-R1-Distill-Qwen-7B**: Superior for complex anomaly reasoning.
