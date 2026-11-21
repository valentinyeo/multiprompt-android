import re


class Unreachable(ValueError):
    pass


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if not match:
        raise Unreachable("missing or invalid bounds")
    rect = tuple(map(int, match.groups()))
    if rect[0] >= rect[2] or rect[1] >= rect[3]:
        raise Unreachable("empty bounds")
    return rect


def inside(rect, viewport):
    return viewport[0] <= rect[0] < rect[2] <= viewport[2] and viewport[1] <= rect[1] < rect[3] <= viewport[3]


def intersection(a, b):
    return max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])


def plan(root, mode, value, direction="forward", search_axis=None):
    parents = {child: parent for parent in root.iter() for child in parent}
    windows = [n for n in root if n.tag == "node" and n.get("visible-to-user") != "false"]
    if not windows:
        raise Unreachable("active window is missing")
    window = windows[-1]
    screen = bounds(window)
    nodes = list(window.iter("node"))

    def chain(node):
        while node in parents and node is not window:
            yield node
            node = parents[node]
        yield window

    def enabled(node):
        return all(n.get("enabled") != "false" and n.get("visible-to-user") != "false" for n in chain(node))

    def viewport(node):
        rect = screen
        for ancestor in chain(node):
            if ancestor.get("scrollable") == "true":
                rect = intersection(rect, bounds(ancestor))
                for panel in nodes:
                    if not enabled(panel) or not above(panel, ancestor):
                        continue
                    left, top, right, bottom = bounds(panel)
                    if left <= rect[0] and right >= rect[2]:
                        if top <= rect[1] < bottom:
                            rect = rect[0], bottom, rect[2], rect[3]
                        elif top < rect[3] <= bottom:
                            rect = rect[0], rect[1], rect[2], top
        return rect

    def scroll(node, axis, forward):
        rect = intersection(bounds(node), viewport(node))
        x1, y1, x2, y2 = rect
        if not enabled(node) or x2 - x1 < 10 or y2 - y1 < 10:
            raise Unreachable("scroll container has no enabled visible viewport")
        if axis == "horizontal":
            inset = (x2 - x1) // 5
            start, end = (x2 - inset, (y1 + y2) // 2), (x1 + inset, (y1 + y2) // 2)
        else:
            inset = (y2 - y1) // 5
            start, end = ((x1 + x2) // 2, y2 - inset), ((x1 + x2) // 2, y1 + inset)
        if not forward:
            start, end = end, start
        return "swipe " + " ".join(map(str, (*start, *end)))

    def above(other, target):
        for front, back in zip(reversed(list(chain(other))), reversed(list(chain(target)))):
            if front is not back:
                siblings = list(parents[front])
                return int(front.get("drawing-order", siblings.index(front))) > int(back.get("drawing-order", siblings.index(back)))
        return False

    def tap(rect, relatives, target=None):
        x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
        for other in nodes:
            if other in relatives or not enabled(other) or not (other.get("clickable") == "true" or other.get("selected") == "true"):
                continue
            if target is not None and not above(other, target):
                continue
            other_rect = bounds(other)
            if other_rect[0] <= x < other_rect[2] and other_rect[1] <= y < other_rect[3]:
                raise Unreachable("clickable center overlaps another control; reveal the complete button before tapping")
        return f"tap {x} {y}"

    if mode == "terminal":
        anchors = []
        for description in ("Show keyboard", "Esc key"):
            matches = [n for n in nodes if n.get("content-desc") == description and enabled(n)]
            if len(matches) != 1 or not inside(bounds(matches[0]), viewport(matches[0])):
                raise Unreachable(f"terminal typing anchor is missing, ambiguous or clipped: {description}")
            anchors.append(bounds(matches[0]))
        toolbar, keys = anchors
        area = keys[0], toolbar[3], toolbar[2], keys[1]
        if not inside(area, screen):
            raise Unreachable("terminal typing area is not visible between toolbar and key row")
        x, y = (area[0] + area[2]) // 2, (area[1] + area[3]) // 2
        return tap((x, y, x + 1, y + 1), set())

    matches = []
    for node in nodes:
        if mode == "text" and node.get("text") == value:
            matches.append(node)
        elif mode == "desc" and node.get("content-desc") == value:
            matches.append(node)
        elif mode == "text-containing" and value in node.get("text", ""):
            matches.append(node)
        elif mode == "editor" and node.get("class") == "android.widget.EditText":
            matches.append(node)
        elif mode == "field" and node.get("class") == "android.widget.EditText" and any(value in (n.get("text"), n.get("content-desc")) for n in node.iter()):
            matches.append(node)
    if matches and not any(enabled(n) for n in matches):
        raise Unreachable("label or its ancestor is disabled or hidden")
    matches = [n for n in matches if enabled(n)]
    if len(matches) > 1:
        raise Unreachable("ambiguous enabled label in active window")
    if not matches:
        if search_axis:
            scrolls = [n for n in nodes if n.get("scrollable") == "true" and enabled(n)
                       and (n.get("class") == "android.widget.HorizontalScrollView") == (search_axis == "horizontal")]
            if len(scrolls) != 1:
                raise Unreachable("label missing or disabled; no unique active scroll container on requested axis")
            return scroll(scrolls[0], search_axis, direction == "forward")
        scrolls = [n for n in nodes if n.get("scrollable") == "true" and n.get("class") != "android.widget.HorizontalScrollView" and enabled(n)]
        if len(scrolls) == 1:
            return scroll(scrolls[0], "vertical", direction == "forward")
        horizontal = [n for n in nodes if n.get("scrollable") == "true" and n.get("class") == "android.widget.HorizontalScrollView" and enabled(n)]
        if not scrolls and len(horizontal) == 1:
            return scroll(horizontal[0], "horizontal", direction == "forward")
        raise Unreachable("label missing or disabled; no unique active scroll container")
    label = matches[0]
    target = next((n for n in chain(label) if n.get("clickable") == "true"), label)
    if mode == "desc" and value.startswith("Robot "):
        session = value.removeprefix("Robot ")
        text_labels = [n for n in target.iter("node") if n.get("text") == session and enabled(n)]
        if len(text_labels) == 1:
            label = text_labels[0]
    if target is label and label.get("clickable") != "true" and mode not in ("field", "editor") and any(n.get("package", "").startswith("dev.multiprompt.companion") for n in chain(label)):
        raise Unreachable("app label has no enabled clickable ancestor")
    rect = bounds(target)
    visible = viewport(target)
    for candidate in (label, target):
        if not inside(bounds(candidate), visible):
            candidate_rect = bounds(candidate)
            axis = "horizontal" if candidate_rect[0] < visible[0] or candidate_rect[2] > visible[2] else "vertical"
            scrolls = [n for n in chain(candidate) if n.get("scrollable") == "true"
                       and (n.get("class") == "android.widget.HorizontalScrollView") == (axis == "horizontal")]
            if not scrolls:
                raise Unreachable("clickable bounds are clipped outside the active viewport; no ancestor can scroll on that axis")
            offset = 0 if axis == "horizontal" else 1
            return scroll(scrolls[0], axis, candidate_rect[offset + 2] > visible[offset + 2])
    return tap(rect, set(chain(target)) | set(target.iter()), target)
