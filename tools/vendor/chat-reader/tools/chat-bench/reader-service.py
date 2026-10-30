import importlib.util
import json
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("production_chat_reader", sys.argv[1])
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
for line in sys.stdin:
    job = {}
    try:
        job = json.loads(line)
        query = job["request"]
        if job["protocol"] == "history":
            query = json.loads(query)
            query["follow"] = False
            wire = reader.response(query)
        else:
            wire = reader.request(query)
        print(json.dumps({"job": job["job"], "wire": wire}), flush=True)
    except Exception as error:
        print(json.dumps({"job": job.get("job"), "error": str(error)}), flush=True)
