"""Project paths shared across modules.

Paths are resolved relative to this file's location so the package
remains relocatable (works from source tree, editable install, or
site-packages install).
"""

from pathlib import Path

# this file:  src/gradum/paths.py
# package:    src/gradum/
# project:    <repo root>
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
_PACKAGE_ROOT = Path(__file__).resolve().parent

PROJECT_ROOT: Path = _PROJECT_ROOT
OUTPUT_DIR: Path = _PROJECT_ROOT / "output"
PROMPTS_DIR: Path = _PACKAGE_ROOT / "prompts"
