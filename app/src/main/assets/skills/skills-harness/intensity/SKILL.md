---
name: intensity
description: Recommend which LLM (Opus/Sonnet/Haiku) and which effort level to use for the current task, based on what this thread already knows about the problem. Use when Valentin asks "/intensity", "intensity assessment", "complexity assessment", "which model should I use", "what effort level", or "is this an Opus job".
allowed_tools:
  - AskUserQuestion
---

# Intensity Assessment

Judge the CURRENT task in this thread and recommend a model + effort level.
Do not restart or re-scope the work. Read what the thread already knows and rate it.

**The objective is highest quality per token — best outcome for the least cost.** Not "what's safest", not "what's most powerful". Always recommend the CHEAPEST model + LOWEST effort that still clears the quality bar for this task. Default question: "could a smaller model / lower effort do this just as well?" Only climb a tier when the task genuinely demands it (see rubric). Over-spend is a wrong answer here, same as under-spend.

## Output (terse, no preamble)

```
Recommend: <Model> / <effort>
Why: <one line>
Cheaper option: <Model/effort> — <when it holds / where it breaks>
```

That's it. Three lines. No headers, no tables.

## The two axes

**Model** = how hard the reasoning is.
**Effort** = how much thinking budget the answer deserves.
They are independent: a hard problem you've already half-solved can be Opus/low; an easy problem with many moving parts can be Sonnet/high.

## Model ladder — stop at the first that holds

1. **Haiku** — lookups, formatting, mechanical edits, single-file greps, "where is X". No real reasoning.
2. **Sonnet** — standard implementation, straightforward multi-file changes, prose/docs, refactors with a clear shape, most bug fixes. The default for "just build it".
3. **Opus** — genuinely hard: subtle bugs, security/auth/billing/schema, novel architecture, ambiguous requirements, anything where a wrong answer is expensive or Sonnet has already failed.

If two rungs both fit, take the lower (cheaper) one. Opus is a judgment call, not a comfort blanket.

## Effort ladder

- **low** — answer is mostly known, or the step is mechanical. Fast.
- **medium** — real work but bounded; the path is clear, execution just takes care.
- **high** — many interacting constraints, edge cases matter, or the plan itself is unclear.
- **xhigh/max** — only when a mistake is very costly and the problem is genuinely deep (security proof, tricky concurrency, one-shot irreversible change).

## Rating rubric — score the task on four signals

1. **Reasoning depth** — is the *approach* obvious, or does it need to be figured out?
2. **Blast radius** — how expensive is a wrong answer? (auth/billing/schema/prod deploy = high)
3. **Ambiguity** — are the requirements pinned down, or fuzzy?
4. **Context already gathered** — has this thread already done the hard thinking? If yes, drop a tier.

High on 1–3 pushes toward Opus and higher effort. High on 4 pulls back down.

## Method

1. Look at what the thread is actually doing right now (last user ask + recent work). Don't invent scope.
2. Score the four signals in your head.
3. Emit the three-line block. Give ONE cheaper alternative and name the condition under which it's safe.
4. If the current thread is already on the model you'd recommend, say so plainly ("you're already right").

## Calibration examples

- Author a docs/skill/prose file → **Sonnet / medium**. Cheaper: Sonnet/low if it's a small edit.
- Fix a null-check bug you've already located → **Sonnet / low**. Cheaper: Haiku if it's a one-liner.
- Rotate a credential across auth routes / touch billing / schema migration → **Opus / high**. Blast radius, not difficulty, drives this.
- Design a new subsystem with unclear requirements → **Opus / high**, and flag the ambiguity first (Rule 1).
- Grep for where a symbol is defined → **Haiku / low**.
