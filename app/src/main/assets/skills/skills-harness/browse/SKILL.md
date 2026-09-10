---
name: browse
description: Execute web browser tasks autonomously via the lh play (Local Helper Playwriter on Windows) escalation chain. Use when asked to check, open, interact with, or verify anything on the web. Invoked with /browse.
version: 1.0.0
---

# Browse — Autonomous Web Task Execution

When the user asks you to check something on the web, open a URL, interact with a website, or verify something in a browser — **do it yourself**. Never ask Valentin to open something manually unless the entire escalation chain below has failed.

## Escalation Chain (MANDATORY — follow in order)

### Level 1: `lh play` (Local Helper Playwriter on Windows)

This is the **primary** tool. It executes JavaScript in Edge browser on Valentin's Windows machine via the Playwriter Chrome extension.

```bash
lh play "<javascript code>"
```

**Examples:**
```bash
# Navigate to a URL
lh play "await page.goto('https://example.com'); await page.snapshotForAI()"

# Click something
lh play "await page.getByRole('button', { name: 'Submit' }).click(); await page.snapshotForAI()"

# Fill a form
lh play "await page.getByLabel('Email').fill('test@example.com'); await page.snapshotForAI()"

# Take a screenshot
lh play "await page.goto('https://example.com'); await page.screenshot({ path: '/tmp/screenshot.png' })"

# Get page content
lh play "await page.goto('https://example.com'); const text = await page.locator('body').innerText(); console.log(text)"
```

**If `lh play` fails with connection error:** The Playwriter extension may be off on Windows. Ask Valentin: "Can you enable Playwriter on your Windows PC? I need it to check [URL]." Then retry.

**If `lh play` fails with `lh` not found or Local Helper down:** Check with `lh status`. If down, move to Level 2.

### Level 2: VPS Browser Tools

Try these in order. These run on the Linux VPS, not Valentin's Windows machine.

1. **dev-browser** — Playwright-based CLI, can connect to running Chrome or run headless
   ```bash
   dev-browser <<'EOF'
   const page = await browser.newPage();
   await page.goto('https://example.com');
   console.log(await page.title());
   await page.screenshot({ path: '/tmp/screenshot.png' });
   EOF
   ```

2. **agent-browser** — Simple fallback
   ```bash
   agent-browser open "https://example.com"
   agent-browser snapshot
   agent-browser screenshot /tmp/screenshot.png
   ```

3. **Playwright MCP** (`mcp__plugin_playwright_playwright__*`) — Headless, last resort on VPS

**Important:** Valentin CANNOT see VPS browser tools. Always take screenshots and describe what you see.

### Level 3: Ask Valentin (LAST RESORT)

Only reach this level if **all of the above failed**. When you do, provide TWO things:

1. **What you tried and why it failed** (brief)
2. **A ready-to-paste prompt for Claude Chrome Extension** — this is critical because the Chrome extension is an isolated session with no context from this conversation.

**Format the Chrome Extension prompt like this:**

```
I need you to do the following on this page:

**URL:** [exact URL to open]

**Task:** [clear description of what to do]

**Steps:**
1. [step 1]
2. [step 2]
3. ...

**What to report back:**
- [what information to copy/screenshot]
```

Make the prompt self-contained — include all URLs, credentials (if non-sensitive), expected page elements, and what success looks like. The Chrome extension has NO context about the current conversation.

## Rules

- **Always start at Level 1.** Never skip to asking Valentin.
- **Take screenshots** at VPS levels (Level 2) — Valentin can't see those browsers.
- **`lh play` uses Playwright API** — `page` object is already available, use `await`.
- **Reuse tabs** — check if a tab with the URL already exists before opening new ones.
- **Close tabs when done** — note the URL first in case Valentin needs it later.
- After completing a browser task, **report what you found/did** with evidence (screenshot path, extracted text, etc.).
