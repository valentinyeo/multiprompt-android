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
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-1
wait_for_text TERMINALALPHA
shot pane-stream-terminal-first
python3 tools/reader-ownership/receipts.py ui "$EVIDENCE/$(printf %02d "$step")-pane-stream-terminal-first.xml" TERMINALALPHA > "$EVIDENCE/pane-stream-terminal-first.txt"
if grep -F TERMINALOMEGA "$EVIDENCE/pane-stream-terminal-first.txt"; then exit 1; fi
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-2
wait_for_text TERMINALOMEGA
shot pane-stream-terminal-updated
python3 tools/reader-ownership/receipts.py ui "$EVIDENCE/$(printf %02d "$step")-pane-stream-terminal-updated.xml" TERMINALOMEGA > "$EVIDENCE/pane-stream-terminal-updated.txt"
sudo -u verifyssh tmux capture-pane -p -t '=claude-control:' > "$EVIDENCE/pane-stream-host.txt"
sudo cat /home/verifyssh/pane-stream-stage.txt > "$EVIDENCE/pane-stream-stage.txt"
sudo grep -Fx VERIFY_RECEIVED:STREAM_531 /home/verifyssh/claude-receipts.txt > "$EVIDENCE/pane-stream-receipt.txt"
python3 tools/pane-stream/proof.py "$EVIDENCE" | tee "$EVIDENCE/pane-stream-proof.txt"
sudo -u verifyssh touch /home/verifyssh/pane-stream-release-3
