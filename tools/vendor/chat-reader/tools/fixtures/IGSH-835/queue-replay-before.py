def queue_apply(state, event):
    queue, sent = state
    operation = event.get("operation")
    content = event.get("content")
    if operation == "enqueue" and isinstance(content, str):
        if content.lstrip().startswith("<task-notification>"):
            return
        if not queue:
            sent.clear()
        queue.append(content)
    elif operation in ("dequeue", "remove") and queue:
        if isinstance(content, str) and content:
            key = queue_key(content)
            index = next((i for i, item in enumerate(queue) if queue_key(item) == key), None)
            if index is None:
                return
        else:
            index = 0
        sent.append(queue.pop(index))
        del sent[:-QUEUE_SHOWN]
    elif operation == "popAll":
        queue.clear()
        sent.clear()


def queue_replay(source, size):
    key = (str(Path(source.name).resolve()), os.fstat(source.fileno()).st_ino)
    previous = QUEUE_CACHE.get(key)
    start, state = 0, ([], [])
    resumed = False
    if previous is not None and previous[0] <= size:
        source.seek(max(0, previous[0] - 64))
        if hashlib.sha256(source.read(min(previous[0], 64))).hexdigest() == previous[2]:
            start, state, resumed = previous[0], (list(previous[1][0]), list(previous[1][1])), True
    trim = not resumed and size > QUEUE_SCAN_BYTES
    if trim:
        start = size - QUEUE_SCAN_BYTES
    source.seek(start)
    data = source.read(size - start)
    if trim:
        data = data[data.find(b"\n") + 1:] if b"\n" in data else b""
        start = size - len(data)
    end = data.rfind(b"\n") + 1
    for raw in data[:end].splitlines():
        if b'"queue-operation"' not in raw:
            continue
        try:
            event = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            continue
        if isinstance(event, dict) and event.get("type") == "queue-operation" and not event.get("isSidechain"):
            queue_apply(state, event)
    scanned = start + end
    source.seek(max(0, scanned - 64))
    probe = hashlib.sha256(source.read(min(scanned, 64))).hexdigest()
    QUEUE_CACHE[key] = (scanned, state, probe)
    if len(QUEUE_CACHE) > 256:
        del QUEUE_CACHE[next(iter(QUEUE_CACHE))]
    return state
