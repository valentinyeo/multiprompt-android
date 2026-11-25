tap_desc 'Session actions'
tap_text Display
tap_text 'Show terminal details'
wait_for_text 'Terminal intentionally contains no chat replies.'
shot pane-scrollback
sleep 3
sudo cat /home/verifyssh/reader-requests.jsonl > "$EVIDENCE/pane-scrollback-requests.jsonl"
tap_desc 'Session actions'
tap_text Display
tap_text 'Hide terminal details'
wait_for_text CODEX_REPLY_claude_CLAUDE_CONTROL
sleep 3
sudo cat /home/verifyssh/reader-requests.jsonl > "$EVIDENCE/pane-native-before.jsonl"
fill 'Message claude-control' STREAM_531
tap_desc 'Send prompt'
wait_for_text STREAMCHATSTART
shot pane-stream-chat-first
python3 tools/reader-ownership/receipts.py ui "$EVIDENCE/$(printf %02d "$step")-pane-stream-chat-first.xml" STREAMCHATSTART --append "$EVIDENCE/pane-stream-ui.json"
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-0
wait_for_text STREAMCHATUPDATE
shot pane-stream-chat-updated
python3 tools/reader-ownership/receipts.py ui "$EVIDENCE/$(printf %02d "$step")-pane-stream-chat-updated.xml" STREAMCHATUPDATE --append "$EVIDENCE/pane-stream-ui.json"
sudo cat /home/verifyssh/reader-requests.jsonl > "$EVIDENCE/pane-native-after.jsonl"
tap_desc 'Session actions'
tap_text 'Open live terminal'
wait_for_text 'SSH · tmux attached'
sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}' > "$EVIDENCE/pane-stream-clients.txt"
grep -F claude-control "$EVIDENCE/pane-stream-clients.txt"
pane_stream_pixels() {
    local marker=$1 name=$2 attempt
    for attempt in {1..15}; do
        adb exec-out screencap -p > "$EVIDENCE/$name.png"
        tesseract "$EVIDENCE/$name.png" "$EVIDENCE/$name" --psm 11
        if grep -F "$marker" "$EVIDENCE/$name.txt"; then return 0; fi
        sleep 1
    done
    printf 'PANE_STREAM_TERMINAL_FROZEN: missing %s in the phone pixels\n' "$marker" >&2
    return 1
}
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-1
pane_stream_pixels TERMINALALPHA pane-stream-terminal-first
if grep -F TERMINALOMEGA "$EVIDENCE/pane-stream-terminal-first.txt"; then exit 1; fi
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-2
pane_stream_pixels TERMINALOMEGA pane-stream-terminal-updated
shot pane-stream-terminal
sudo -u verifyssh tmux capture-pane -p -t '=claude-control:' > "$EVIDENCE/pane-stream-host.txt"
sudo cat /home/verifyssh/pane-stream-stage.txt > "$EVIDENCE/pane-stream-stage.txt"
sudo grep -Fx VERIFY_RECEIVED:STREAM_531 /home/verifyssh/claude-receipts.txt > "$EVIDENCE/pane-stream-receipt.txt"
python3 tools/pane-stream/proof.py "$EVIDENCE" | tee "$EVIDENCE/pane-stream-proof.txt"
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-3
