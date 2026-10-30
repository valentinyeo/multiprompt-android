import copy
import hashlib
import os
import re
import time
from pathlib import Path

PROTOCOL = 1
PROVIDER = "claude"
BINDING = "exact_native_occurrence_v1"
STATE_VERSION = 2
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
TMUX_NAME = re.compile(r"[A-Za-z0-9._:-]{1,64}\Z")
SCAN_BUDGET = 8 << 20
LOOKBACK = 16 << 20
HANDOFFS = ("sent", "uncertain", "not_sent")
REQUIRED_SERVICES = ("resolve_tmux_agent", "send_response_preserve_draft", "attempt_prepare", "attempt_finish",
                     "attempt_bindings", "load_line", "conversation_rows")


def capabilities(context=None):
    context = context if isinstance(context, dict) else {}
    sid, tmux = context.get("sid"), context.get("tmux")
    attachable = (isinstance(sid, str) and SESSION_ID.match(sid) is not None
                  and isinstance(tmux, str) and TMUX_NAME.match(tmux) is not None)
    return dict(
        protocol=PROTOCOL,
        provider=PROVIDER,
        attachment=dict(native_tmux_session=attachable, sdk_parallel_agent=False, daemon_endpoint=False),
        transport=dict(kind="tmux_terminal_input", carries_app_id=False, busy_queue=True,
                       preserves_unfinished_draft="send_response_preserve_draft", typed_draft="not_sent",
                       durable_binding_before_write=True, repeated_attempt_sends_once=True),
        binding=dict(mode=BINDING, one_time=True, ambiguity="uncorrelated_never_promoted",
                     normalization="remove_cr_and_trailing_ascii_spaces", exclusive_input_channel_assumed=True,
                     age_limit=None, retention="core_attempt_bindings"),
        receipts=dict(
            accepted=["queue-operation.enqueue", "user", "attachment.queued_command"],
            started=["attachment.queued_command", "user"],
            message=["user", "assistant"],
            not_sent=["precondition_failure_before_terminal_write"],
            aliases=["command_uuid", "delivery_id", "record_uuid", "native_session_id"],
        ),
        requires_services=list(REQUIRED_SERVICES),
        unsupported=[
            "app_id_carriage_into_native_session",
            "native_ack_carrying_app_id",
            "hook_receipts",
            "sdk_parallel_agent_as_attachment",
            "daemon_endpoint",
            "pasted_content_wrapped_text",
            "queue_removal_or_dequeue_as_acceptance",
            "fuzzy_prefix_suffix_or_substring_matching",
        ],
    )


def _normal(text):
    return text.replace("\r", "").rstrip(" ")


def _match_digest(text):
    return hashlib.sha256(_normal(text).encode("utf-8", "surrogatepass")).hexdigest()[:32]


def _text_digest(text):
    return hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest()


def _scalar(value):
    return not isinstance(value, bool) and isinstance(value, (str, int)) and str(value) != ""


def _marker(key):
    if not isinstance(key, dict) or not _scalar(key.get("id")) or not _scalar(key.get("attempt")):
        return None
    return f"{key['id']}\n{key['attempt']}\n{key.get('epoch')}"


def _fresh(sid):
    return dict(v=STATE_VERSION, sid=sid, cursor=None, marks={})


def _load(raw, sid):
    if (not isinstance(raw, dict) or raw.get("v") != STATE_VERSION or raw.get("sid") != sid
            or not isinstance(raw.get("marks"), dict)):
        return _fresh(sid)
    state = copy.deepcopy(raw)
    state["marks"] = {marker: mark for marker, mark in state["marks"].items()
                      if isinstance(mark, dict) and {"accepted", "started", "popped"} <= mark.keys()}
    if not isinstance(state.get("cursor"), dict):
        state["cursor"] = None
    return state


def _line_end(source, limit):
    if limit <= 0:
        return 0
    floor = max(0, limit - LOOKBACK)
    position = limit
    while position > floor:
        start = max(floor, position - 65536)
        source.seek(start)
        found = source.read(position - start).rfind(b"\n")
        if found >= 0:
            return start + found + 1
        position = start
    return limit if floor else 0


def _locate(context, services):
    sid, tmux = context.get("sid"), context.get("tmux")
    if (not isinstance(sid, str) or SESSION_ID.match(sid) is None
            or not isinstance(tmux, str) or TMUX_NAME.match(tmux) is None):
        return None, "invalid_identity"
    resolver = services.get("resolve_tmux_agent")
    if not callable(resolver):
        return None, "resolver_unavailable"
    try:
        resolved = resolver(PROVIDER, tmux, require_bound=True)
    except Exception:
        return None, "resolve_failed"
    if not resolved:
        return None, "agent_unavailable"
    native_sid, path = resolved
    if native_sid != sid:
        return None, "session_mismatch"
    path = Path(path)
    declared = context.get("transcript_path")
    if declared and Path(declared) != path:
        return None, "transcript_mismatch"
    return path, None


def _outcome(reply):
    if not isinstance(reply, dict):
        return "uncertain", "unreadable_send_result"
    reason = reply.get("reason") if isinstance(reply.get("reason"), str) else None
    if reply.get("status") == "sent":
        return "sent", None
    if reply.get("status") == "not_sent":
        return "not_sent", reason or "not_sent"
    return "uncertain", reason or "uncertain_send_result"


def _stored_handoff(outcome):
    if outcome == "accepted":
        return "sent"
    return outcome if outcome in HANDOFFS else "uncertain"


def _refusal(state, context, intent, reason):
    facts = []
    if _scalar(intent.get("id")) and _scalar(intent.get("attempt")):
        facts.append(dict(kind="not_sent", sid=context.get("sid"),
                          event_id=f"claude:not_sent:{intent['id']}:{intent['attempt']}:{intent.get('epoch')}",
                          id=intent["id"], attempt=intent["attempt"], reason=reason))
    return dict(handoff="not_sent", reason=reason, facts=facts, state=state)


def submit(context, intent, state, services):
    context = context if isinstance(context, dict) else {}
    intent = intent if isinstance(intent, dict) else {}
    services = services if isinstance(services, dict) else {}
    sid = context.get("sid")
    state = _load(state, sid if isinstance(sid, str) else "")
    text = intent.get("text")
    if not _scalar(intent.get("id")) or not _scalar(intent.get("attempt")):
        return _refusal(state, context, intent, "invalid_intent")
    if not isinstance(text, str) or not text.strip() or "\x00" in text:
        return _refusal(state, context, intent, "invalid_text")
    for name in ("attempt_prepare", "attempt_finish", "send_response_preserve_draft"):
        if not callable(services.get(name)):
            return _refusal(state, context, intent, f"{name}_unavailable")
    path, reason = _locate(context, services)
    if reason is not None:
        return _refusal(state, context, intent, reason)
    try:
        with open(path, "rb") as source:
            info = os.fstat(source.fileno())
    except OSError:
        return _refusal(state, context, intent, "transcript_unavailable")
    key = dict(provider=PROVIDER, sid=sid, id=intent["id"], attempt=intent["attempt"], epoch=intent.get("epoch"),
               text_sha256=_text_digest(text))
    binding = dict(v=1, dev=info.st_dev, ino=info.st_ino, boundary=info.st_size, match=_match_digest(text),
                   time=time.time())
    try:
        claim = services["attempt_prepare"](key, binding)
    except Exception:
        return _refusal(state, context, intent, "attempt_prepare_failed")
    status = claim.get("status") if isinstance(claim, dict) else None
    if status == "existing":
        return dict(handoff=_stored_handoff(claim.get("outcome")), facts=[], state=state,
                    reason="attempt_already_claimed")
    if status != "claimed":
        return _refusal(state, context, intent, "attempt_prepare_unavailable" if status == "unavailable"
                        else "attempt_prepare_invalid")
    try:
        handoff, why = _outcome(services["send_response_preserve_draft"](
            dict(op="send", tmux=context["tmux"], sid=sid, text=text, token=_text_digest(f"{_marker(key)}")[:16])))
    except Exception:
        handoff, why = "uncertain", "send_interrupted"
    recorded = True
    try:
        services["attempt_finish"](key, handoff)
    except Exception:
        recorded = False
        if handoff == "not_sent":
            handoff, why = "uncertain", "attempt_finish_failed"
    result = _refusal(state, context, intent, why) if handoff == "not_sent" else dict(
        handoff=handoff, reason=why, facts=[], state=state)
    result["outcome_recorded"] = recorded
    return result


def _transcript(context, services):
    sid = context.get("sid")
    if not isinstance(sid, str) or SESSION_ID.match(sid) is None:
        return None, "invalid_identity"
    declared = context.get("transcript_path")
    if isinstance(declared, str) and declared and Path(declared).stem == sid:
        return Path(declared), None
    return _locate(context, services)


def _attempts(services, sid, notes):
    fetch = services.get("attempt_bindings")
    if not callable(fetch):
        notes.append(dict(kind="attempt_bindings_unavailable"))
        return None
    try:
        rows = fetch()
    except Exception:
        rows = None
    if not isinstance(rows, list):
        notes.append(dict(kind="attempt_bindings_unavailable"))
        return None
    found = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        key, binding = row.get("key"), row.get("binding")
        if not isinstance(key, dict) or not isinstance(binding, dict):
            continue
        if key.get("provider") != PROVIDER or key.get("sid") != sid:
            continue
        marker = _marker(key)
        if (marker is None or isinstance(binding.get("ino"), bool) or not isinstance(binding.get("ino"), int)
                or isinstance(binding.get("boundary"), bool) or not isinstance(binding.get("boundary"), int)
                or not isinstance(binding.get("match"), str)):
            continue
        outcome = row.get("outcome")
        found[marker] = dict(marker=marker, id=key["id"], attempt=key["attempt"], match=binding["match"],
                             dev=binding.get("dev"), ino=binding["ino"], boundary=binding["boundary"],
                             handoff=outcome if outcome in HANDOFFS + ("accepted",) else None)
    return found


def _cursor(state, current, source, info, notes):
    cursor = state["cursor"]
    if (isinstance(cursor, dict) and cursor.get("ino") == info.st_ino and cursor.get("dev") == info.st_dev
            and isinstance(cursor.get("offset"), int) and 0 <= cursor["offset"] <= info.st_size):
        unseen = [a["boundary"] for a in current.values() if a["marker"] not in state["marks"]]
        if unseen:
            return min(cursor["offset"], _line_end(source, min(min(unseen), info.st_size)))
        return cursor["offset"]
    if cursor is not None:
        notes.append(dict(kind="transcript_cursor_reset"))
    boundaries = [a["boundary"] for a in current.values()]
    return _line_end(source, min(min(boundaries), info.st_size) if boundaries else info.st_size)


def _read(source, offset, size):
    records = []
    spent = 0
    source.seek(offset)
    while spent < SCAN_BUDGET and offset < size:
        raw = source.readline()
        if not raw.endswith(b"\n"):
            break
        records.append((offset, offset + len(raw), raw))
        offset += len(raw)
        spent += len(raw)
    return records, offset, spent >= SCAN_BUDGET and offset < size


def _parse(start, finish, raw, sid, inode, services):
    event = services["load_line"](raw)
    if not isinstance(event, dict) or event.get("isSidechain"):
        return [], []
    if isinstance(event.get("sessionId"), str) and event["sessionId"] != sid:
        return [], []
    stamp = event.get("timestamp") if isinstance(event.get("timestamp"), str) else ""
    base = f"claude:{sid}:{inode}:{start}:{hashlib.sha256(raw).hexdigest()[:12]}"
    shape = dict(start=start, end=finish, base=base, stamp=stamp)
    kind = event.get("type")
    if kind == "queue-operation":
        operation, content = event.get("operation"), event.get("content")
        if (operation == "enqueue" and isinstance(content, str) and content.strip()
                and not content.lstrip().startswith("<task-notification>")):
            return [dict(shape, kind="enqueue", source="queue-operation.enqueue", key=_match_digest(content))], []
        if operation == "popAll":
            return [dict(shape, kind="popAll", source="queue-operation.popAll")], []
        return [], []
    if kind not in ("user", "assistant", "attachment"):
        return [], []
    try:
        rows = services["conversation_rows"](event, PROVIDER, start)
    except Exception:
        return [], []
    occurrences, messages = [], []
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or row.get("role") not in ("user", "assistant") or not isinstance(row.get("text"), str):
            continue
        native = row.get("id") or event.get("uuid")
        message = dict(kind="message", sid=sid, event_id=f"{base}:{index}", role=row["role"], text=row["text"],
                       timestamp=row.get("timestamp") or stamp, offset=start, end=finish, cursor=finish,
                       inode=inode, row=dict(copy.deepcopy(row), offset=start, end=finish), rank=2)
        if isinstance(native, str) and native:
            message["native_id"] = native
        messages.append(message)
        if row["role"] == "user" and kind in ("user", "attachment") and not occurrences:
            attachment = event.get("attachment") if kind == "attachment" else None
            aliases = {}
            if isinstance(attachment, dict):
                for name, field in (("command_uuid", "source_uuid"), ("delivery_id", "delivery_id"),
                                    ("native_session_id", "session_id")):
                    if isinstance(attachment.get(field), str):
                        aliases[name] = attachment[field]
            if isinstance(event.get("uuid"), str):
                aliases["record_uuid"] = event["uuid"]
            occurrence = dict(shape, kind="queued" if attachment is not None else "user",
                              source="attachment.queued_command" if attachment is not None else "user",
                              key=_match_digest(row["text"]), aliases=aliases)
            if isinstance(event.get("uuid"), str):
                occurrence["native_id"] = event["uuid"]
            occurrences.append(occurrence)
    return occurrences, messages


def _fact(kind, occurrence, sid, attempt=None, **extra):
    fact = dict(kind=kind, sid=sid, event_id=f"{occurrence['base']}:{kind}", offset=occurrence["start"],
                end=occurrence["end"], timestamp=occurrence["stamp"], source=occurrence["source"],
                binding=BINDING if attempt is not None else "uncorrelated", rank=0 if kind == "accepted" else 1)
    if occurrence.get("native_id"):
        fact["native_id"] = occurrence["native_id"]
    if occurrence.get("aliases"):
        fact["aliases"] = occurrence["aliases"]
    if attempt is not None:
        fact.update(id=attempt["id"], attempt=attempt["attempt"])
    fact.update(extra)
    return fact


def _open(marks, current):
    return [(a, marks[a["marker"]]) for a in current.values()
            if not marks[a["marker"]]["started"] and not marks[a["marker"]]["popped"]]


def _bind(state, current, occurrences, notes):
    sid = state["sid"]
    marks = state["marks"]
    facts = []
    for occurrence in occurrences:
        if occurrence["kind"] == "popAll":
            for attempt, mark in _open(marks, current):
                if mark["accepted"] is not None and mark["accepted"]["start"] < occurrence["start"]:
                    mark["popped"] = True
                    notes.append(dict(kind="native_queue_cleared", id=attempt["id"], attempt=attempt["attempt"],
                                      event_id=occurrence["base"]))
            continue
        candidates = []
        for attempt, mark in _open(marks, current):
            if attempt["match"] != occurrence["key"] or attempt["handoff"] == "not_sent":
                continue
            if mark["accepted"] is None and attempt["boundary"] <= occurrence["start"]:
                candidates.append((attempt, mark, "accept"))
            elif mark["accepted"] is not None and occurrence["kind"] != "enqueue" and mark["accepted"]["start"] < occurrence["start"]:
                candidates.append((attempt, mark, "start"))
        if not candidates:
            continue
        if len(candidates) > 1:
            listed = [dict(id=attempt["id"], attempt=attempt["attempt"]) for attempt, _, _ in candidates]
            facts.append(_fact("accepted" if occurrence["kind"] == "enqueue" else "started", occurrence, sid,
                               ambiguous=True, candidates=listed))
            notes.append(dict(kind="ambiguous_native_occurrence", event_id=occurrence["base"], candidates=listed))
            continue
        attempt, mark, phase = candidates[0]
        if phase == "accept":
            mark["accepted"] = dict(event_id=f"{occurrence['base']}:accepted", start=occurrence["start"],
                                    source=occurrence["source"])
            facts.append(_fact("accepted", occurrence, sid, attempt))
        if occurrence["kind"] != "enqueue":
            mark["started"] = True
            facts.append(_fact("started", occurrence, sid, attempt))
    return facts


def poll(context, state, pending, services):
    context = context if isinstance(context, dict) else {}
    services = services if isinstance(services, dict) else {}
    sid = context.get("sid")
    state = _load(state, sid if isinstance(sid, str) else "")
    notes = []
    path, reason = _transcript(context, services)
    if reason is not None:
        return dict(facts=[], state=state, more=False, diagnostics=[dict(kind=reason)])
    attempts = _attempts(services, sid, notes)
    if attempts is None:
        return dict(facts=[], state=state, more=False, diagnostics=notes)
    try:
        with open(path, "rb") as source:
            info = os.fstat(source.fileno())
            current = {} if attempts is None else {
                marker: attempt for marker, attempt in attempts.items()
                if attempt["ino"] == info.st_ino and attempt["dev"] in (None, info.st_dev)
                and attempt["handoff"] != "not_sent"}
            offset = _cursor(state, current, source, info, notes)
            records, offset, more = _read(source, offset, info.st_size)
    except OSError:
        return dict(facts=[], state=state, more=False, diagnostics=[dict(kind="transcript_unreadable")])
    if attempts is not None:
        state["marks"] = {marker: state["marks"].get(marker, dict(accepted=None, started=False, popped=False))
                          for marker in attempts}
    occurrences, messages = [], []
    for start, finish, raw in records:
        found, spoken = _parse(start, finish, raw, sid, info.st_ino, services)
        occurrences.extend(found)
        messages.extend(spoken)
    facts = _bind(state, current, occurrences, notes) + messages
    facts.sort(key=lambda fact: (fact["offset"], fact["rank"]))
    for fact in facts:
        del fact["rank"]
    state["cursor"] = dict(dev=info.st_dev, ino=info.st_ino, offset=offset)
    if attempts is not None and isinstance(pending, list):
        known = {(str(attempt["id"]), str(attempt["attempt"])) for attempt in attempts.values()}
        for item in pending:
            if isinstance(item, dict) and _scalar(item.get("id")) and _scalar(item.get("attempt")):
                if (str(item["id"]), str(item["attempt"])) not in known:
                    notes.append(dict(kind="no_native_binding", id=item["id"], attempt=item["attempt"]))
    return dict(facts=facts, state=state, more=more, diagnostics=notes)
