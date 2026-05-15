# GGUF Q-level tradeoffs

When running UI-TARS-1.5-7B locally via llama.cpp instead of vLLM, the
quantization level affects accuracy on tiny / high-row targets.

| Q-level | File size | RAM | Warm latency | Accuracy on edge-row targets |
|---------|-----------|-----|--------------|------------------------------|
| Q4_K_M  | ~4.5 GB | 6 GB | ~2 s | misses high-row empty cells (G32 → row 13) |
| Q8_0    | ~8 GB | 10 GB | ~2.5 s | grounds high-row cells correctly |
| FP16    | ~14 GB | 18 GB | ~1.5 s | reference accuracy |

## Recommendation

Use **Q8_0** for grounding in any production-like setup. Q4 is fine for
prototyping but degrades on targets near the screen edges, especially in
empty-cell grids (LibreOffice Calc, raw spreadsheets).

## File names on Hugging Face

```
mradermacher/UI-TARS-1.5-7B-GGUF/UI-TARS-1.5-7B.Q4_K_M.gguf
mradermacher/UI-TARS-1.5-7B-GGUF/UI-TARS-1.5-7B.Q8_0.gguf
mradermacher/UI-TARS-1.5-7B-GGUF/mmproj-fp16.gguf
```

The mmproj-fp16.gguf is the vision projector — always use FP16 for the
projector regardless of the text-model Q-level. Lower-precision projectors
drop tokens and grounding accuracy collapses.

## Verifying which Q-level is loaded

```bash
curl -fsS http://<gpu-host>:9090/v1/models | jq .data[0].id
# Q4: UI-TARS-1.5-7B.Q4_K_M.gguf
# Q8: UI-TARS-1.5-7B.Q8_0.gguf
```

Set `NUBIAN_UGROUND_MODEL` to whatever the server reports.
