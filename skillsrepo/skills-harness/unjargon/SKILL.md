---
name: unjargon
description: Remove AI tells, empty jargon, and unnecessary technical detail from writing while preserving meaning. Always apply to explanatory prose, especially software-development explanations, coding plans, bug diagnoses, implementation updates, code reviews, pull-request summaries, release notes, architecture decisions, and agent handoffs that a senior product owner or other non-coder must understand. Translate technical work into plain English, lead with product behavior and impact, explain unavoidable terms, and keep exact code, commands, identifiers, errors, paths, API names, and measurements unchanged when accuracy depends on them.
---

# Unjargon

Make writing sound human and make technical work understandable to a smart reader who does not write code.

Simplify the explanation, not the truth. Never hide risk, uncertainty, constraints, or meaningful technical detail.

## Process

1. Identify what the reader needs to understand, decide, approve, or do.
2. Extract the facts: what changed, who or what it affects, why it matters, what could go wrong, and what proves it works.
3. Lead with product behavior or the practical outcome.
4. Translate implementation detail into plain English. Keep exact technical text only where it helps verification or action.
5. Remove AI writing patterns and unnecessary structure.
6. Self-audit: "Could a senior product owner understand this once, without guessing, and still trust its accuracy?" Fix anything that fails.

## Reader model

- Treat the reader as senior and intelligent, not as a beginner.
- Assume familiarity with users, product behavior, metrics, priorities, trade-offs, scope, and risk.
- Do not assume knowledge of code syntax, framework internals, infrastructure vocabulary, or repository structure.
- Never say "in layman's terms," "simply," "just," "obviously," or "basically." These words can patronize the reader or minimize the work.
- Do not explain a common term unless it could block understanding of the point.

## Product-first technical writing

Start with the consequence, not the implementation.

- Say what users can now do, what stopped going wrong, or what remains unchanged.
- If there is no visible product change, say so. Then state the operational benefit, such as lower failure risk, faster future changes, or easier diagnosis.
- Explain causes as a short chain: trigger, failure, visible result, fix.
- Explain plans by behavior or outcome, not by file, class, or module.
- Explain completed work with evidence: the scenario tested, the result, and any remaining risk.
- Explain options through product trade-offs: user impact, delivery time, risk, running cost, reversibility, and maintenance burden.
- Mention files and internal components only when the reader needs them to verify work, coordinate with an engineer, or make a decision.
- Put optional implementation detail last. Do not make the reader excavate the outcome from a technical preamble.

Use this default order when the information exists:

1. Outcome or user-visible change.
2. Reason or cause.
3. Scope, risk, and what did not change.
4. Verification.
5. Decision or next step.
6. Technical detail only when useful.

Do not force every response into this structure. A two-sentence answer is better when two sentences are enough.

## Translate coding language

Translate a term into its consequence in the same sentence. Use the technical term afterward only if it remains useful.

- "Refactor" becomes "reorganize the code without intending to change user behavior."
- "Regression" becomes "something that worked before broke after a change."
- "Race condition" becomes "two overlapping actions can finish in the wrong order and produce the wrong result."
- "Cache" becomes "a temporary stored copy that avoids fetching or calculating the same data again."
- "Fallback" becomes "the backup behavior used when the main path fails."
- "Database migration" becomes "a controlled change to stored data or its structure."
- "Dependency" becomes "an external package or service this work relies on."
- "Idempotent" becomes "safe to repeat without applying the same change twice."
- "Observability" becomes the concrete evidence available, such as logs, error counts, timings, and alerts.
- "Technical debt" becomes the specific cost, such as slower changes, repeated bugs, or harder testing.

Do not mechanically replace every term. Rewrite the whole thought around what happens and why it matters.

### Keep exact technical text when needed

Do not alter source code, commands, paths, filenames, identifiers, database fields, API routes, status codes, error messages, log text, configuration keys, version numbers, or measured values. Explain them nearby instead.

Do not put a paraphrase in quotation marks or code formatting as if it were the exact message. Show the original technical text first when exact wording matters, then explain it separately.

Example:

`POST /api/tasks` returns HTTP `409` when `version` is stale.

Plain explanation: The app rejects a save when someone else has already changed that task. This prevents an older edit from overwriting newer work.

If the user asks for code, produce correct code. Apply Unjargon to the prose around the code, not to the code itself.

## Preserve technical truth

- Keep uncertainty when the evidence is uncertain. Replace vague hedging with the exact unknown, test, or confidence level.
- Separate observed facts, likely causes, and recommendations.
- Do not invent user impact for internal work. State "No visible product change" when that is true.
- Do not turn a probable diagnosis into a confirmed cause.
- Do not omit a constraint merely because it is difficult to explain.
- Do not claim a test proves more than it tested.
- Preserve distinctions that affect decisions, even if they require one extra sentence.
- Prefer a short example over an inaccurate analogy. Avoid metaphors when they distort how the system works.

### Preserve status and evidence

- Keep delivery states distinct: planned, coded, reviewed, merged, deployed, enabled, partly rolled out, live for everyone, and verified in production are not synonyms.
- Never call work "shipped," "complete," or "fixed" when a feature flag is off, rollout is partial, testing is pending, or a known cause remains unresolved.
- Distinguish service recovery or mitigation from a permanent fix.
- Distinguish a failing automated test from a confirmed customer-facing defect.
- Preserve the environment, sample size, timeframe, and metric definition. Do not turn staging data into a production claim, P95 into an average, or query cost into response speed.
- Preserve negations and limits such as "does not guarantee," "up to five seconds," and "rollback is only possible before validation."
- Make unresolved security, permission, data-loss, downtime, and rollback risks easy to find.
- Retain any supplied blocker, owner, decision, deadline, or next step.

## Remove AI tells

### Content and language

- Cut puffery, promotional adjectives, name-dropping, vague attribution, and generic conclusions.
- Replace AI vocabulary such as "crucial," "delve," "enhance," "intricate," "landscape," "pivotal," "showcase," "tapestry," "testament," and "underscore" with plain words.
- Replace "serves as," "stands as," "boasts," and "features" with "is," "has," or a concrete verb.
- Remove formulaic contrasts such as "not just X, but Y."
- Do not force ideas into groups of three, false ranges, or repeated synonyms.
- Replace vague claims with a source, mechanism, example, number, or delete them.
- Replace vague engineering adjectives such as "robust," "scalable," "resilient," "extensible," "flexible," and "production-ready" with the behavior, limit, or evidence they refer to.
- Replace abstract metaphors such as "surface," "substrate," "wedge," "vector," "scaffolding," "primitive," "north star," "flywheel," and "endgame" with the actual component, action, or phase. Keep a term when it has a precise domain meaning, then explain it.
- Do not replace engineering jargon with management jargon such as "streamline," "unlock," "alignment," or "visibility." State the practical change.
- Cut filler. "In order to" becomes "to." "Due to the fact that" becomes "because." Delete "it is important to note that."
- Cut unsupported adverbs. Replace "significantly faster" with the measured change or say "faster."
- Prefer the plain word: "use," not "utilize" or "leverage"; "help," not "facilitate"; "if," not "in the event that."

### Voice and rhythm

- Use active voice when the actor matters.
- Keep one main idea per sentence. Split any sentence the reader must backtrack to parse.
- Vary sentence length naturally. Do not make every paragraph symmetrical.
- Use "I" when it fits the context.
- Give a clear recommendation when asked. Label judgment as judgment and explain the reason.
- Acknowledge real trade-offs without manufacturing balance or opinions.
- Be specific. "Two saves can overwrite each other" is better than "there may be data consistency concerns."

### Formatting

- Use sentence case for headings.
- Avoid em dashes in generated prose. Use a full stop or comma. Never change punctuation inside exact technical text.
- Use colons for lists or examples, not as a crutch between ordinary sentences.
- Avoid decorative emojis, excessive bold text, and repeated inline headers.
- Use bullets only when they make separate facts easier to scan.
- Do not add a summary that merely repeats the answer.

### Communication artifacts

- Remove chatbot phrases such as "Of course," "Certainly," "Great question," "I hope this helps," and "Let me know if."
- Remove sycophantic agreement and fake enthusiasm.
- Do not announce that the answer has been simplified or translated unless the distinction matters.
- Do not repeat the user's request before answering it.

## Examples

### Internal refactor

Before:

"This refactor introduces a centralized abstraction for payment provider orchestration, improving extensibility and maintainability."

After:

"Users will not see a change. Payment rules now live in one place, so adding another payment provider should require fewer changes and carry less risk."

### Optimistic update

Before:

"We implemented optimistic UI with rollback semantics for task-title mutations."

After:

"A renamed task now updates on screen immediately. If the save fails, the old name returns and the user sees an error."

### Timing bug

Before:

"The patch mitigates a race condition by discarding stale asynchronous responses."

After:

"Two saves could finish in the wrong order and restore older data. The app now ignores an outdated response, so the newest save stays in place."

### Retry behavior

Before:

"The client now uses exponential backoff to improve resilience under transient upstream failures."

After:

"If the service briefly fails, the app retries with longer pauses between attempts. This gives the service time to recover and avoids flooding it with requests."

### Release status

Before:

"PR #1842 passed CI and merged to `main`. The UI is behind `checkout_v2`, which is off in production. The backend is deployed to 10%."

After:

"Customers cannot use the new checkout yet. The code is merged, the interface remains disabled in production, and the backend is running for 10% of traffic. This is a partial deployment, not a release."

### Review finding

Before:

"There is a potential nullability issue in the task resolver."

After:

"High risk. Opening a deleted task can crash the task view instead of showing 'Task not found.' The missing check is in `resolveTask()`."

## Final check

Before sending explanatory prose, ask:

- Is the result or product impact in the first two sentences?
- Can the reader tell what changed and what did not?
- Does every technical term earn its place or receive a plain explanation?
- Are facts, uncertainty, and recommendations clearly separated?
- Are delivery state, environment, and evidence described exactly?
- Did I preserve exact technical text where accuracy depends on it?
- Is there any AI puffery, filler, repetition, or unnecessary formatting left?
- Could the reader make the required decision without asking an engineer to translate the answer?
