---
name: deploy-grounder
description: Stand up a GPU host serving UI-TARS-1.7B for pixel grounding. Use vLLM on big GPUs (24 GB+) or llama.cpp for CPU/small-GPU fallback. Returns a base URL the agent can plug into NUBIAN_UGROUND_BASE_URL. Run when target descriptions return wrong coordinates or the agent logs "UGround configured but unreachable".
---

# Deploy the UI-TARS grounder

## When to use
- The agent's planner emits target descriptions ("the Apply button"), but clicks land in the wrong place or fail with `target_grounded` errors.
- `curl http://<gpu-host>:<port>/v1/models` doesn't list `UI-TARS-1.5-7B` (or `UI-TARS-1.5-7B.Q8_0.gguf` for the quantised build).
- You're forking the repo and need a grounder somewhere.

## Two flavors

| Backend | Hardware | Latency | Cost | When |
|---------|----------|---------|------|------|
| **vLLM FP16** | 24 GB+ GPU (RTX PRO 5000, A6000, H100) | ~1 s warm | ~$0.20-0.50/hr rented | accurate grounding at full precision |
| **llama.cpp Q8 GGUF** | 8 GB+ GPU OR CPU | ~2-4 s warm | $0 self-hosted | small target rescue, CPU fallback |

The Java agent treats both as drop-in replacements — same OpenAI-compatible `/v1/chat/completions` endpoint.

## Run (vLLM on any GPU VM)

```bash
docker run -d --gpus all --name uitars \
  -p 18000:18000 \
  -e VLLM_MODEL=ByteDance-Seed/UI-TARS-1.5-7B \
  -e VLLM_ARGS="--kv-cache-dtype turboquant_k8v4 --max-num-seqs 8 --max-model-len 8192 --host 0.0.0.0 --port 18000" \
  vllm/vllm-openai:latest
```

First boot pulls ~15 GB of weights from Hugging Face. Verify:
```bash
curl -fsS http://<gpu-host>:18000/v1/models | jq .data[0].id
# expect: ByteDance-Seed/UI-TARS-1.5-7B
```

## Run (llama.cpp Q8 on CPU or small GPU)

```bash
docker run -d --name uitars-llamacpp \
  -p 9090:9090 \
  -v /opt/models:/models \
  ghcr.io/ggerganov/llama.cpp:server-cuda \
    -m /models/UI-TARS-1.5-7B.Q8_0.gguf \
    --mmproj /models/mmproj-fp16.gguf \
    --host 0.0.0.0 --port 9090 --threads 8
```

Download the GGUF files from `mradermacher/UI-TARS-1.5-7B-GGUF` on Hugging Face. Verify:
```bash
curl -fsS http://<gpu-host>:9090/v1/models
# model id: UI-TARS-1.5-7B.Q8_0.gguf
```

## Wire the agent

```bash
# .env
NUBIAN_UGROUND_BASE_URL=http://<gpu-host>:<port>/v1
NUBIAN_UGROUND_MODEL=<model-id-from-curl-above>
```

Restart the agent. On the first click, the trace logs `target_grounded ... model=<model-id>`.

## References
- [`references/coordinate-rules.md`](references/coordinate-rules.md) — never crop, never rescale 1024/1000
- [`references/q-level-tradeoffs.md`](references/q-level-tradeoffs.md) — Q4 vs Q8 GGUF — which one for which targets
