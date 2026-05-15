# UI-TARS Grounder Deployment

The agent's `click` actions need pixel coordinates. The planner LLM emits a
target *description* ("the Apply button near the bottom right") and the
grounder model converts that into `(x, y)`. We use **UI-TARS-1.7B** served
by **vLLM** on any GPU VM.

Set `nubian.uground.enabled=false` if you want to skip this — the agent still
runs, just without grounded clicks (it falls back to hotkeys and direct text).

## Hardware

| Spec | Minimum | Notes |
|------|---------|-------|
| GPU VRAM | 24 GB | UI-TARS-1.7B fits at FP16; 48 GB is comfortable |
| CUDA | 12.4+ (13.0 preferred) | vLLM 0.20+ ships against CUDA 13 |
| Disk | 32 GB | Model weights + HF cache |

Validated combo: a 48 GB GPU (RTX PRO 5000 class) on CUDA 13 with
`vllm:v0.20.1-cuda-13.0`.

## Run vLLM

On any GPU VM (cloud or self-hosted), pull a vLLM image and start the server:

```bash
docker run -d --gpus all --name uitars \
  -p 18000:18000 \
  -e VLLM_MODEL=ByteDance-Seed/UI-TARS-1.5-7B \
  -e VLLM_ARGS="--kv-cache-dtype turboquant_k8v4 --max-num-seqs 8 --max-model-len 8192 --host 0.0.0.0 --port 18000" \
  vllm/vllm-openai:latest
```

First boot downloads the model weights (~15 GB). Then verify:

```bash
curl -s http://<GPU_HOST>:18000/v1/models | jq .
# expect: { "data": [{ "id": "ByteDance-Seed/UI-TARS-1.5-7B", ... }] }
```

## Wire the agent

Edit `config/application-dev.properties`:

```properties
nubian.uground.enabled=true
nubian.uground.base-url=http://<GPU_HOST>:18000/v1
nubian.uground.model=ByteDance-Seed/UI-TARS-1.5-7B
nubian.uground.backend=uitars15
nubian.uground.coordinate-space=native
```

Restart the agent. On the first `click`, the trace will show
`target_grounded ... model=ByteDance-Seed/UI-TARS-1.5-7B`.

## Notes

- UI-TARS-1.5 returns native pixel coordinates at 1024×1024. Never crop the
  screenshot before sending, never rescale by 1024/1000 — both cause silent
  drift.
- "Locate X, output bbox" prompts ground best (~5 px error at 2 s). Don't
  combine description rewriting and grounding in one call.
- For low-confidence grounds, the planner can request Best-of-N sampling
  with clustering — already wired in `UGroundClient`.
