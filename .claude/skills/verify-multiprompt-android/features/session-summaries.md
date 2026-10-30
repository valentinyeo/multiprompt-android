# Useful inbox summaries

Android inbox rows show the first sentence of the last assistant reply, or current work. Model footers and shell prompts never become summaries.

Dispatch `verify.yml` with `feature=session-summaries.md`. For the signed published APK also set `released_chat=true` and `expected_commit` to the published source SHA. The isolated SSH fixture emits a reply with a model footer, then current work, then a new reply. The real inbox must display each summary and discover the last reply within five seconds, without manual refresh. Evidence includes three phone screenshots, UI XML, source/APK hashes, a screen recording and `summary-result.txt`.
