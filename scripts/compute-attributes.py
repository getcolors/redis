#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["python-hcl2==7.3.1", "PyYAML==6.0.2"]
# ///
"""Compare evaluated compute bodies using the canonical manifest evaluator."""
from pathlib import Path
import runpy
import sys

if __name__ == '__main__':
    core = runpy.run_path(str(Path(__file__).with_name('compute-manifest.py')))
    sys.exit(core['main']('attributes'))
