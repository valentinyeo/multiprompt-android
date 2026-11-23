import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location("production_chat_reader", sys.argv[1])
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


def restamp_tmux(wire, tmux):
    head, newline, rest = wire.partition("\n")
    fields = head.split("\t")
    if not newline or len(fields) < 11 or fields[2] != "history" or fields[3] != "ok":
        return wire
    fields[10] = tmux or "-"
    return "\n".join(("\t".join(fields), rest))


for line in sys.stdin:
    job = {}
    try:
        job = json.loads(line)
        query = job["request"]
        if job["protocol"] == "history":
            query = json.loads(query)
            query["follow"] = False
            tmux = query.get("tmux") or ""
            query["tmux"] = ""
            wire = reader.response(query)
            wire = restamp_tmux(wire, tmux)
        else:
            wire = reader.request(query)
        print(json.dumps({"job": job["job"], "wire": wire}), flush=True)
    except Exception as error:
        print(json.dumps({"job": job.get("job"), "error": str(error)}), flush=True)
