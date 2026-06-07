"""Backward-compatibility shim.

The canonical entry point is now ``python -m gradum`` (or the
``gradum`` console script installed via ``pip install -e .``).
This file exists so that older invocations such as
``python agent.py "..."`` keep working.
"""

from gradum.agent import main


if __name__ == "__main__":
    main()
