# UI-TARS coordinate-space rules

UI-TARS-1.5-7B always returns **native pixel coordinates** at the source
image's resolution. The Nubian pipeline assumes a 1024×1024 screenshot.

## What works

| Step | Behavior |
|------|----------|
| Screenshot | 1024×1024 PNG from `/eyes/screenshot` |
| Prompt | `Locate "<target description>", output the bounding box.` |
| Output | `(x1, y1, x2, y2)` or `(x, y)` in native px |
| Click | `Tools.click({x, y})` — no rescaling |

## What breaks

| Mistake | Symptom |
|---------|---------|
| Cropping the screenshot before sending | Clicks land in the cropped offset |
| Rescaling output by 1024/1000 because some examples use 1000² | Drift increases with distance from origin |
| Sending a 1920×1080 screenshot but treating output as 1024×1024 | Clicks miss by ~half the screen |
| Combining description-rewrite + grounding in one prompt | Latency jumps to 4 s+ and accuracy drops to ~48 px error |

## Config keys

```properties
nubian.uground.coordinate-space=native     # do not change
nubian.uground.resize-target=0             # 0 = no server-side resize
nubian.uground.vote-mode=false             # one call per target
```

## When clicks still miss

If grounding lands wrong and you've verified the rules above:
- Check `framebuffer_geometry` in `/eyes/screenshot` evidence — must be 1024×1024.
- If the target is tiny (< 24 px), enable Best-of-N sampling: `nubian.agent.best-of-n=3`.
- Last resort: enable two-brain rescue (Gemini Flash crops to a quadrant, UI-TARS grounds inside the crop, mapping is inverted back).
