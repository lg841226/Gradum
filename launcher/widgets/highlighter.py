from typing import Optional
import re
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QTextCharFormat, QColor, QSyntaxHighlighter


class BashSyntaxHighlighter(QSyntaxHighlighter):
    """Syntax highlighter for bash commands."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._init_formats()

    def _init_formats(self) -> None:
        """Initialize text formats for different syntax elements."""
        self._python_format = QTextCharFormat()
        self._python_format.setForeground(QColor("#d4a017"))

        self._param_format = QTextCharFormat()
        self._param_format.setForeground(QColor("#888888"))

        self._number_format = QTextCharFormat()
        self._number_format.setForeground(QColor("#4caf50"))

    def highlightBlock(self, text: Optional[str]) -> None:
        """Apply syntax highlighting to a block of text."""
        if not text:
            return

        for keyword in ["python3", "python"]:
            pos = text.find(keyword)
            while pos != -1:
                self.setFormat(pos, len(keyword), self._python_format)
                pos = text.find(keyword, pos + len(keyword))

        for match in re.finditer(r'--[\w-]+', text):
            self.setFormat(match.start(), match.end() - match.start(), self._param_format)

        for match in re.finditer(r'(?<!\S)(-\w+)', text):
            self.setFormat(match.start(1), match.end(1) - match.start(1), self._param_format)

        timeout_match = re.search(r'--timeout\s+(\d+)', text)
        if timeout_match:
            start = timeout_match.start(1)
            end = timeout_match.end(1)
            self.setFormat(start, end - start, self._number_format)
