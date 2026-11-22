# Useful inbox summaries

Android inbox rows show the waiting question, current work, or the first sentence of the last assistant reply. Model footers and shell prompts never become summaries.

Dispatch `verify.yml` with `feature=session-summaries.md`. For the signed published APK also set `released_chat=true` and `expected_commit` to the published source SHA. The isolated SSH fixture emits a reply with a model footer, then current work, then a structured question box with its options and hint, then a new reply once the question is answered. The real inbox must display each summary, show the question text rather than its options while the question waits, drop that question from the row after the answer, and discover the last reply within five seconds, without manual refresh. Evidence includes four phone screenshots, UI XML, source/APK hashes, a screen recording and `summary-result.txt`.
