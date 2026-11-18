#!/usr/bin/env python3
import os
import sys
from pathlib import Path

os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve().with_name("mpa")), "legacy", *sys.argv[1:]])
