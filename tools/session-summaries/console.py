import sys


def show(body):
    print("\033[2J\033[H" + body + "\n❯\n[CAVEMAN] Opus 5.5 medium hypertask", flush=True)


show("⏺ Lauren Tan profile is done, checked and pushed. More details follow.")
for line in sys.stdin:
    if line.strip() == "summary-working":
        show("⏺ Previous reply.\n✻ Reading CI logs… (3s · esc to interrupt)")
    elif line.strip() == "summary-next":
        show("⏺ The release is ready. More details follow.")
