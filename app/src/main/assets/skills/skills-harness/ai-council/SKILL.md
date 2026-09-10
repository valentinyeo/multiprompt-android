---
name: ai-council
description: Run a question through a real multi-model "AI council" via OpenRouter that debates, peer-reviews, and synthesizes a final answer (Karpathy's LLM Council pattern, popularized by Ole Lehmann). Use when the user asks to "run this by the council", "get multiple AI opinions", "multi-model review", "LLM council", "AI council", or wants to reduce single-model bias on high-stakes questions, contested claims, strategic decisions, code reviews, or writing critiques. Requires OPENROUTER_API_KEY.
---

# AI Council

Three-stage deliberation across multiple real models via OpenRouter. Each member answers independently, peer-reviews the others anonymously, then a designated Chairman synthesizes the final answer. Based on Andrej Karpathy's `llm-council` pattern.

Core insight: a single model sounds exactly as confident when wrong as when right. Different model families (Anthropic / OpenAI / Google / xAI) disagree in substantive ways, and peer review catches hallucinations no single model would flag.

**There is no in-chat simulation mode.** One model role-playing four personas is theater — it doesn't produce genuine disagreement. The council always runs against real, distinct models via OpenRouter.

## The three stages

1. **First opinions** — each council member answers the query independently, in parallel. No cross-talk, no awareness of other members. Prevents anchoring.
2. **Anonymized peer review** — each member sees the others' answers relabeled "Response A/B/C/D" and ranks/critiques them. Hiding identities prevents models favoring their own family.
3. **Chairman synthesis** — one designated model reads the original answers plus all critiques and writes the final answer the user will actually act on.

## Running the council

```bash
export OPENROUTER_API_KEY=sk-or-...
pip install httpx  # one-time
python ~/.claude/skills/ai-council/scripts/council.py "<your question>"
```

If `OPENROUTER_API_KEY` is not set, tell the user — do not try to approximate the council any other way.

### Default lineup
- `anthropic/claude-sonnet-4.5` — careful reasoning, surfaces tradeoffs
- `openai/gpt-5.1` — broad synthesis, pragmatic framing
- `google/gemini-2.5-pro` — structured, analytical
- `x-ai/grok-4` — contrarian, challenges premises
- **Chairman:** `anthropic/claude-sonnet-4.5`

Reasoning/thinking mode is **on by default** (`reasoning: {effort: "high"}`) — activates extended thinking on Claude, reasoning on GPT-5, thinking budget on Gemini, reasoning on Grok.

### Customizing
```bash
python council.py "question" \
  --council anthropic/claude-opus-4-7 openai/gpt-5.1 google/gemini-2.5-pro \
  --chairman anthropic/claude-opus-4-7
```

Edit `references/prompts.md` to change stage prompts in one place.

## Output

Stdout prints each stage as it runs, the aggregate ranking, and the final Chairman answer. Stderr logs per-call metadata: served model, provider, reasoning tokens, cost. Full transcript (all stages + call log + costs) saved to `./council-runs/<timestamp>.json`.

## Cost & latency

~9 API calls per query (4 opinions + 4 reviews + 1 chairman). Typical wall time 30-90 seconds, cost $0.30-$1.50 depending on question length and model mix. This is slow and expensive on purpose — only run it when the question deserves it.

## When to use

- Genuinely contested strategic questions (build vs buy, architecture, pricing)
- High-stakes single-shot decisions (investor pitch framing, partnership responses)
- Red-teaming your own plan ("poke holes in this")
- Hallucination-sensitive factual claims (if all 4 converge, confidence is high; if they split, you know where to verify)
- Subjective writing quality (persuasiveness, tone)

## When NOT to use

- Simple factual lookups — one search wins
- Code you can just run — the test is the test
- Questions with a clear objective answer
- Quick iteration — the council is latency-heavy by design

## Attribution

- Pattern: Andrej Karpathy, `llm-council` (Nov 2025) — https://github.com/karpathy/llm-council
- Popularization: Ole Lehmann (@itsolelehmann), Mar-Apr 2026
