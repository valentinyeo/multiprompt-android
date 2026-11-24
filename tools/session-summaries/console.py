import sys


def show(body):
    print("\033[2J\033[H" + body + "\n❯\n[CAVEMAN] Opus 5.5 medium hypertask", flush=True)


question = (
    "⏺ Previous reply.\n"
    "────────────────────────────────────────\n"
    "Which route should we use?\n\n"
    "❯ 1. Create a new account\n"
    "  2. Use the current account\n"
    "────────────────────────────────────────\n"
    "Enter to select · Tab/Arrow keys to navigate · Esc to cancel"
)
show("⏺ Lauren Tan profile is done, checked and pushed. More details follow.")
for line in sys.stdin:
    if line.strip() == "summary-working":
        show(question + "\n✻ Reading CI logs… (3s · esc to interrupt)")
    elif line.strip() == "summary-question":
        show(question)
    elif line.strip() == "summary-next":
        show(question + "\n⏺ The release is ready. More details follow.")
