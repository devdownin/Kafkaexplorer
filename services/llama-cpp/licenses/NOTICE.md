# Licences carried by the `kex-llm` image

| Component | Version | Licence |
|---|---|---|
| Gemma 4 E4B instruction-tuned, GGUF Q4_K_M by Unsloth | `unsloth/gemma-4-E4B-it-GGUF` at `bfc15c382204943c3a8fff0c750b94ae2364d7a3` (derived from `google/gemma-4-E4B-it`, Google DeepMind) | Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0> |
| llama.cpp (`llama-server`) | `server-b9828` | MIT — <https://github.com/ggml-org/llama.cpp/blob/master/LICENSE> |
| This repository's entrypoint script | — | AGPL-3.0-or-later |

Gemma 3 was distributed under Google's own terms of use, which are not a free licence; **Gemma 4 is
Apache-2.0** (model card of `google/gemma-4-E4B-it`), with no restriction on who may run it. That
difference is why this image can carry the weights without the condition `kex-forecast` carries for
Timestral. The retention of this model for Process Mining and the assistant is argued in
`EMBEDDED-LLM-SCOPE.md` §7.
