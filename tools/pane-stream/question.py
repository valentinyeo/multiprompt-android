#!/usr/bin/env python3
import datetime
import json
import uuid

QUESTION = dict(
    question="Which reply style do you want?",
    header="Reply",
    multiSelect=False,
    options=[
        dict(label="Concise", description="Short answers."),
        dict(label="Detailed", description="Long answers."),
    ],
)


def event(question=QUESTION):
    return dict(
        type="assistant",
        uuid=str(uuid.uuid4()),
        sessionId="25340000-0000-4000-8000-000000000002",
        timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        message=dict(
            role="assistant",
            content=[dict(type="tool_use", id="pane-stream-545", name="AskUserQuestion", input=dict(questions=[question]))],
        ),
    )


if __name__ == "__main__":
    print(json.dumps(event(), ensure_ascii=True))
