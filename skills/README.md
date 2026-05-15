# Skills

Agent Skills for deploying and operating the Nubian stack. Follows the open
[Anthropic Agent Skills format](https://github.com/anthropics/skills): one
folder per skill, each with a `SKILL.md` (YAML frontmatter + body) and
optional `scripts/` and `references/`.

Use these from a coding agent that loads skills automatically, or read them
yourself.

| Skill | Purpose |
|-------|---------|
| [`deploy-sandbox`](deploy-sandbox/) | Install the Nubian Python controller into any Ubuntu desktop (local Docker, GCP, Hetzner) |
| [`deploy-grounder`](deploy-grounder/) | Stand up a UI-TARS-1.7B GPU host that serves grounding via vLLM or llama.cpp |
| [`wire-agent`](wire-agent/) | Point the Java agent at a remote sandbox + grounder via `.env` and verify end-to-end |
