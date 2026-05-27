"""PyQt6 GUI launcher for Gradum Agent."""

import json
import re
import sys
from pathlib import Path
from typing import Optional, Tuple

import psutil

from PyQt6.QtCore import Qt, QProcess, QDateTime, QUrl, QTimer, QPropertyAnimation, QEasingCurve
from PyQt6.QtGui import QKeySequence, QShortcut, QTextCharFormat, QColor, QFont, QSyntaxHighlighter
from PyQt6.QtNetwork import QNetworkAccessManager, QNetworkRequest
from PyQt6.QtSvgWidgets import QSvgWidget
from PyQt6.QtWidgets import (
    QApplication,
    QMainWindow,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QTextEdit,
    QLineEdit,
    QScrollArea,
    QRadioButton,
    QButtonGroup,
    QFrame,
    QSizePolicy,
    QSpinBox,
    QDoubleSpinBox,
    QSplitter,
    QGraphicsOpacityEffect,
    QGraphicsDropShadowEffect,
)

MODEL_NAME_MAPPING = {
    "qwen": "QwenX",
    "llama": "Llama",
    "deepseek": "DeepSeek",
    "glm": "GLM",
    "mistral": "Mistral",
    "claude": "Claude",
    "gpt": "ChatGPT",
    "gpt-oss": "ChatGPT OpenSource",
    "phi": "Phi",
    "codellama": "CodeLlama",
    "airoboros": "Airoboros",
    "mythomax": "MythoMax",
    "wizardlm": "WizardLM",
    "yi": "Yi",
}

# Window Configuration
WINDOW_WIDTH = 1400
WINDOW_HEIGHT = 820
WINDOW_MIN_WIDTH = 1100
WINDOW_MIN_HEIGHT = 700

# Layout Configuration
LEFT_PANEL_WIDTH = 400
RIGHT_PANEL_WIDTH = 300

# Panel Width Constraints
LEFT_PANEL_MIN_WIDTH = 280
LEFT_PANEL_MAX_WIDTH = 360
CENTER_PANEL_MIN_WIDTH = 400
CENTER_PANEL_MAX_WIDTH = 800
RIGHT_PANEL_MIN_WIDTH = 280
RIGHT_PANEL_MAX_WIDTH = 360

# Margins and Spacing
MAIN_MARGIN_LEFT = 20
MAIN_MARGIN_TOP = 10
MAIN_MARGIN_RIGHT = 20
MAIN_MARGIN_BOTTOM = 20
MAIN_SPACING = 24

CARD_SPACING = 8
CARD_INTERNAL_SPACING = 8
OPTION_SPACING = 8

# Component Sizes
PROMPT_INPUT_HEIGHT = 60
PREVIEW_HEIGHT = 60
RUN_BUTTON_HEIGHT = 40
HISTORY_WIDTH = 400

# History Configuration
MAX_HISTORY = 10

# API Configuration
OLLAMA_API_URL = "http://localhost:11434/api/tags"

# Character Limits
MAX_PROMPT_CHARS = 5000

# Local Model Settings Defaults
DEFAULT_TEMPERATURE = 0.7
DEFAULT_TOP_P = 0.9
DEFAULT_NUM_CTX = 4096
DEFAULT_NUM_PREDICT = 2048


class ToastNotification(QWidget):
    """A toast notification widget that shows error messages."""

    def __init__(self, parent=None, message: str = "", duration: int = 3000):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.ToolTip)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)

        self._duration = duration
        self._setup_ui(message)
        self._setup_animation()

    def _setup_ui(self, message: str) -> None:
        """Initialize the toast UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 12, 16, 12)

        container = QWidget()
        container.setObjectName("toast-container")
        container.setStyleSheet("""
            #toast-container {
                background-color: #FFF2F3;
                border: 1px solid #E46A76;
                border-radius: 8px;
            }
        """)

        shadow = QGraphicsDropShadowEffect()
        shadow.setBlurRadius(8)
        shadow.setColor(QColor(0, 0, 0, 25))
        shadow.setOffset(0, 2)
        container.setGraphicsEffect(shadow)

        container_layout = QVBoxLayout(container)
        container_layout.setContentsMargins(12, 8, 12, 8)

        self.label = QLabel(message)
        self.label.setObjectName("toast-message")
        self.label.setStyleSheet("""
            #toast-message {
                color: #000000;
                font-size: 11px;
                font-family: "Menlo", "Courier New", monospace;
            }
        """)
        self.label.setWordWrap(False)
        container_layout.addWidget(self.label)

        layout.addWidget(container)

    def _setup_animation(self) -> None:
        """Setup fade in/out animation."""
        self.opacity_effect = QGraphicsOpacityEffect(self)
        self.setGraphicsEffect(self.opacity_effect)

        self.animation = QPropertyAnimation(self.opacity_effect, b"opacity")
        self.animation.setDuration(200)
        self.animation.setEasingCurve(QEasingCurve.Type.InOutQuad)

    def show_toast(self, message: str = None, target_widget: QWidget = None) -> None:
        """Show the toast notification.

        Args:
            message: The message to display
            target_widget: The widget to position the toast next to (optional)
        """
        if message:
            self.label.setText(message)

        self.adjustSize()

        if target_widget:
            target_pos = target_widget.mapTo(self.parent(), target_widget.rect().topLeft())

            center_offset = (target_widget.width() - self.width()) // 2
            x = target_pos.x() + center_offset
            y = target_pos.y() - self.height() - 8

            if self.parent():
                parent_rect = self.parent().rect()
                if x < 0:
                    x = 0
                if x + self.width() > parent_rect.width():
                    x = parent_rect.width() - self.width()
                if y < 0:
                    y = target_pos.y() + target_widget.height() + 8

            self.move(x, y)
        elif self.parent():
            # Default position: center bottom of parent
            # pyrefly: ignore [missing-attribute]
            parent_rect = self.parent().rect()
            x = (parent_rect.width() - self.width()) // 2
            y = parent_rect.height() - self.height() - 100
            self.move(x, y)

        # Fade in
        self.animation.setStartValue(0.0)
        self.animation.setEndValue(1.0)
        self.show()
        self.animation.start()

        # Auto hide after duration
        QTimer.singleShot(self._duration, self.hide_toast)

    def hide_toast(self) -> None:
        """Hide the toast with fade out animation."""
        self.animation.setStartValue(1.0)
        self.animation.setEndValue(0.0)
        self.animation.start()

        # Hide after animation completes
        QTimer.singleShot(200, self.hide)


class AutoHideScrollArea(QScrollArea):
    """ScrollArea that automatically hides scrollbars when content fits."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

    def updateGeometry(self):
        """Update scrollbar visibility based on content size."""
        super().updateGeometry()

        widget = self.widget()
        if widget:
            content_height = widget.sizeHint().height()
            viewport_height = self.viewport().height()

            if content_height > viewport_height:
                self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
            else:
                self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)


class AutoHideTextEdit(QTextEdit):
    """TextEdit that automatically hides scrollbars when content fits."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)

    def updateGeometry(self):
        """Update scrollbar visibility based on content size."""
        super().updateGeometry()

        document_height = self.document().size().height()
        viewport_height = self.viewport().height()

        if document_height > viewport_height:
            self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        else:
            self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        document_width = self.document().size().width()
        viewport_width = self.viewport().width()

        if document_width > viewport_width:
            self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        else:
            self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)


class CollapsibleCard(QWidget):
    """A collapsible card widget with toggle arrow header."""

    ICONS_DIR = Path(__file__).parent / "icons"

    def __init__(
        self,
        title: str,
        parent: Optional[QWidget] = None,
        right_widget: Optional[QWidget] = None,
        icon_name: Optional[str] = None,
    ):
        super().__init__(parent)
        self.setObjectName("card")
        self._expanded = True
        self._icon_name = icon_name
        self._setup_ui(title, right_widget)

    def _setup_ui(self, title: str, right_widget: Optional[QWidget]) -> None:
        """Initialize the card UI structure."""
        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        self._header = self._create_header(title, right_widget)
        main_layout.addWidget(self._header)

        self._content = QWidget()
        self._content_layout = QVBoxLayout(self._content)
        self._content_layout.setContentsMargins(0, 0, 0, 10)
        self._content_layout.setSpacing(0)
        main_layout.addWidget(self._content)

    def _create_header(self, title: str, right_widget: Optional[QWidget]) -> QWidget:
        """Create the header with icon, title, arrow, and optional right widget."""
        header = QWidget()
        header.setCursor(Qt.CursorShape.PointingHandCursor)
        header_layout = QHBoxLayout(header)
        header_layout.setContentsMargins(0, 10, 0, 10)

        if self._icon_name:
            icon = QSvgWidget(str(self.ICONS_DIR / self._icon_name))
            icon.setFixedSize(16, 16)
            header_layout.addWidget(icon)
            header_layout.addSpacing(2)

        self._title_label = QLabel(title)
        self._title_label.setObjectName("card-title")
        header_layout.addWidget(self._title_label)
        header_layout.addStretch()

        if right_widget:
            header_layout.addWidget(right_widget)
            header_layout.addSpacing(6)

        self._arrow = QSvgWidget(str(self.ICONS_DIR / "arrow-down.svg"))
        self._arrow.setFixedSize(12, 12)
        header_layout.addWidget(self._arrow)

        return header

    def content_layout(self) -> QVBoxLayout:
        """Return the content layout for adding child widgets."""
        return self._content_layout

    def toggle(self) -> None:
        """Toggle the expanded/collapsed state."""
        self._expanded = not self._expanded
        self._content.setVisible(self._expanded)

        icon_name = "arrow-down.svg" if self._expanded else "arrow-right.svg"
        self._arrow.load(str(self.ICONS_DIR / icon_name))

    def mousePressEvent(self, a0) -> None:
        """Handle mouse click to toggle state."""
        if a0.button() == Qt.MouseButton.LeftButton:
            self.toggle()


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


class AgentLauncher(QMainWindow):
    """Modern minimalist launcher with black & white theme."""

    def __init__(self):
        super().__init__()
        self._init_state()
        self._setup_ui()
        self._apply_styles()
        self._setup_shortcuts()
        self._refresh_history_list()
        self._update_preview()
        self._update_run_button()
        self._fetch_models()

    def _init_state(self) -> None:
        """Initialize application state."""
        self.setWindowTitle("Gradum")
        self.setFixedSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        self.setMinimumSize(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)

        self.prompt_history: list[dict] = []
        self.agent_process: Optional[QProcess] = None
        self.ollama_process: Optional[QProcess] = None
        self.network_manager = QNetworkAccessManager(self)

        self.toast = ToastNotification(self, duration=4000)

        self.resource_timer = QTimer(self)
        self.resource_timer.timeout.connect(self._update_resource_info)
        self.resource_timer.start(1000)

        self.request_latencies: list[float] = []
        self.max_latency_samples = 10

        self._start_ollama_service()

    def _setup_ui(self) -> None:
        """Initialize UI components."""
        central = QWidget()
        self.setCentralWidget(central)

        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(MAIN_MARGIN_LEFT, MAIN_MARGIN_TOP, MAIN_MARGIN_RIGHT, MAIN_MARGIN_BOTTOM)
        main_layout.setSpacing(0)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setHandleWidth(3)
        splitter.setStyleSheet("""
            QSplitter::handle {
                background-color: transparent;
                margin: 0 10px;
            }
            QSplitter::handle:hover {
                background-color: #000000;
                margin: 0 10px;
            }
        """)

        left_widget = self._create_left_panel()
        center_widget = self._create_center_panel()
        right_widget = self._create_right_panel()

        left_widget.setMinimumWidth(LEFT_PANEL_MIN_WIDTH)
        left_widget.setMaximumWidth(LEFT_PANEL_MAX_WIDTH)

        center_widget.setMinimumWidth(CENTER_PANEL_MIN_WIDTH)
        center_widget.setMaximumWidth(CENTER_PANEL_MAX_WIDTH)

        right_widget.setMinimumWidth(RIGHT_PANEL_MIN_WIDTH)
        right_widget.setMaximumWidth(RIGHT_PANEL_MAX_WIDTH)

        splitter.addWidget(left_widget)
        splitter.addWidget(center_widget)
        splitter.addWidget(right_widget)

        # Set initial sizes (left: 400, center: 600, right: 300)
        splitter.setSizes([LEFT_PANEL_WIDTH, 600, RIGHT_PANEL_WIDTH])

        main_layout.addWidget(splitter)

    def _create_left_panel(self) -> QWidget:
        """Create the left panel with options, models, and preview."""
        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.setSpacing(0)

        left_layout.addWidget(self._create_options_card())
        left_layout.addSpacing(CARD_SPACING)
        left_layout.addWidget(self._create_separator())

        left_layout.addWidget(self._create_models_card())
        left_layout.addSpacing(CARD_SPACING)
        left_layout.addWidget(self._create_separator())

        left_layout.addWidget(self._create_preview_card())
        left_layout.addStretch()

        return left_widget

    def _create_center_panel(self) -> QWidget:
        """Create the center panel with prompt input and run button."""
        center_widget = QWidget()
        center_layout = QVBoxLayout(center_widget)
        center_layout.setContentsMargins(0, 0, 0, 0)
        center_layout.setSpacing(0)

        center_layout.addStretch(1)

        input_container = QWidget()
        input_container.setObjectName("input-container")
        input_layout = QVBoxLayout(input_container)
        input_layout.setContentsMargins(16, 12, 16, 12)
        input_layout.setSpacing(8)

        self.prompt_input = QTextEdit()
        self.prompt_input.setObjectName("prompt-input-inner")
        self.prompt_input.setPlaceholderText("What would you like me to do?")
        self.prompt_input.setFixedHeight(PROMPT_INPUT_HEIGHT)
        self.prompt_input.setAcceptRichText(False)
        self.prompt_input.textChanged.connect(self._update_run_button)
        self.prompt_input.textChanged.connect(self._update_preview)
        input_layout.addWidget(self.prompt_input)

        toolbar = QWidget()
        toolbar_layout = QHBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(0, 0, 0, 0)
        toolbar_layout.setSpacing(12)

        cpu_container = QWidget()
        cpu_container.setObjectName("resource-info")
        cpu_layout = QHBoxLayout(cpu_container)
        cpu_layout.setContentsMargins(0, 0, 0, 0)
        cpu_layout.setSpacing(4)

        cpu_icon_path = Path(__file__).parent / "icons" / "cpu.svg"
        if cpu_icon_path.exists():
            cpu_svg_widget = QSvgWidget(str(cpu_icon_path))
            cpu_svg_widget.setFixedSize(16, 16)
            cpu_svg_widget.setStyleSheet("color: #999999;")
            cpu_layout.addWidget(cpu_svg_widget)

        self.cpu_label = QLabel("0.0 %")
        self.cpu_label.setObjectName("resource-label")
        cpu_layout.addWidget(self.cpu_label)

        toolbar_layout.addWidget(cpu_container)

        # Memory usage info (only for Ollama)
        mem_container = QWidget()
        mem_container.setObjectName("resource-info")
        mem_layout = QHBoxLayout(mem_container)
        mem_layout.setContentsMargins(0, 0, 0, 0)
        mem_layout.setSpacing(4)

        mem_icon_path = Path(__file__).parent / "icons" / "memory.svg"
        if mem_icon_path.exists():
            mem_svg_widget = QSvgWidget(str(mem_icon_path))
            mem_svg_widget.setFixedSize(16, 16)
            mem_svg_widget.setStyleSheet("color: #999999;")
            mem_layout.addWidget(mem_svg_widget)

        self.mem_label = QLabel("0.0 MB")
        self.mem_label.setObjectName("resource-label")
        mem_layout.addWidget(self.mem_label)

        toolbar_layout.addWidget(mem_container)

        # Latency info (only for Ollama)
        latency_container = QWidget()
        latency_container.setObjectName("resource-info")
        latency_layout = QHBoxLayout(latency_container)
        latency_layout.setContentsMargins(0, 0, 0, 0)
        latency_layout.setSpacing(4)

        latency_icon_path = Path(__file__).parent / "icons" / "activity.svg"
        if latency_icon_path.exists():
            latency_svg_widget = QSvgWidget(str(latency_icon_path))

            latency_svg_widget.setFixedSize(16, 16)
            latency_svg_widget.setStyleSheet("color: #999999;")
            latency_layout.addWidget(latency_svg_widget)

        self.latency_label = QLabel("0 ms")
        self.latency_label.setObjectName("resource-label")
        latency_layout.addWidget(self.latency_label)

        toolbar_layout.addWidget(latency_container)

        toolbar_layout.addStretch()

        # Run/Stop button
        self.run_btn = QPushButton("Run")
        self.run_btn.setObjectName("run-btn")
        self.run_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.run_btn.setFixedHeight(32)
        self.run_btn.clicked.connect(self._run_agent)
        toolbar_layout.addWidget(self.run_btn)

        input_layout.addWidget(toolbar)

        # Add input container to center layout with stretch factor 0
        center_layout.addWidget(input_container, 0)

        return center_widget

    def _create_options_card(self) -> CollapsibleCard:
        """Create the options card with checkboxes."""
        self.options_card = CollapsibleCard("OPTIONS", self, icon_name="options.svg")
        content = self.options_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        self.context_check, context_row = self._create_option_row(
            "Context Memory", "Remember conversation history"
        )
        self.context_check.toggled.connect(self._update_preview)
        content.addWidget(context_row)

        self.think_check, think_row = self._create_option_row(
            "Think Mode", "Show step-by-step reasoning"
        )
        self.think_check.toggled.connect(self._update_preview)
        content.addWidget(think_row)

        self.timeout_check, self.timeout_input, timeout_row = self._create_option_row_with_input(
            "Timeout", "Request timeout in seconds", "120"
        )
        self.timeout_check.toggled.connect(self._update_preview)
        self.timeout_input.textChanged.connect(self._update_preview)
        self.timeout_input.textChanged.connect(self._validate_timeout_input)
        self.timeout_input.editingFinished.connect(self._on_timeout_editing_finished)
        content.addWidget(timeout_row)

        return self.options_card

    def _create_models_card(self) -> CollapsibleCard:
        """Create the models selection card."""
        self.refresh_btn = QPushButton("Refresh")
        self.refresh_btn.setObjectName("text-btn")
        self.refresh_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.refresh_btn.clicked.connect(self._fetch_models)

        self.model_card = CollapsibleCard("MODELS", self, self.refresh_btn, icon_name="model.svg")
        content = self.model_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        self.model_button_group = QButtonGroup(self)
        self.model_radio_container = QWidget()
        self.model_radio_layout = QVBoxLayout(self.model_radio_container)
        self.model_radio_layout.setContentsMargins(0, 0, 0, 0)
        self.model_radio_layout.setSpacing(10)

        fetching_label = QLabel("FETCHING MODELS...")
        fetching_label.setObjectName("model-fetching")
        self.model_radio_layout.addWidget(fetching_label)

        content.addWidget(self.model_radio_container)

        return self.model_card

    def _create_local_settings_card(self) -> CollapsibleCard:
        """Create the local model settings card."""
        self.refresh_btn = QPushButton("Reset")
        self.refresh_btn.setObjectName("text-btn")
        self.refresh_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.refresh_btn.clicked.connect(self._reset_local_settings)

        self.local_settings_card = CollapsibleCard("LOCAL SETTINGS", self, self.refresh_btn, icon_name="settings.svg")
        content = self.local_settings_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        # Temperature setting
        temp_container = QWidget()
        temp_layout = QHBoxLayout(temp_container)
        temp_layout.setContentsMargins(0, 0, 0, 0)
        temp_layout.setSpacing(8)

        temp_text_container = QWidget()
        temp_text_layout = QVBoxLayout(temp_text_container)
        temp_text_layout.setContentsMargins(0, 0, 0, 0)
        temp_text_layout.setSpacing(2)

        temp_label = QLabel("Temperature")
        temp_label.setObjectName("option-title")
        temp_text_layout.addWidget(temp_label)

        temp_desc = QLabel("Controls randomness (0.0-2.0)")
        temp_desc.setObjectName("option-desc")
        temp_text_layout.addWidget(temp_desc)

        temp_layout.addWidget(temp_text_container, stretch=1)

        self.temp_spin = QDoubleSpinBox()
        self.temp_spin.setObjectName("option-spin")
        self.temp_spin.setRange(0.0, 2.0)
        self.temp_spin.setSingleStep(0.1)
        self.temp_spin.setValue(DEFAULT_TEMPERATURE)
        self.temp_spin.setFixedWidth(70)
        self.temp_spin.valueChanged.connect(self._update_preview)
        temp_layout.addWidget(self.temp_spin)

        content.addWidget(temp_container)

        # Top P setting
        top_p_container = QWidget()
        top_p_layout = QHBoxLayout(top_p_container)
        top_p_layout.setContentsMargins(0, 0, 0, 0)
        top_p_layout.setSpacing(8)

        top_p_text_container = QWidget()
        top_p_text_layout = QVBoxLayout(top_p_text_container)
        top_p_text_layout.setContentsMargins(0, 0, 0, 0)
        top_p_text_layout.setSpacing(1)

        top_p_label = QLabel("Top P")
        top_p_label.setObjectName("option-title")
        top_p_text_layout.addWidget(top_p_label)

        top_p_desc = QLabel("Nucleus sampling threshold")
        top_p_desc.setObjectName("option-desc")
        top_p_text_layout.addWidget(top_p_desc)

        top_p_layout.addWidget(top_p_text_container, stretch=1)

        self.top_p_spin = QDoubleSpinBox()
        self.top_p_spin.setObjectName("option-spin")
        self.top_p_spin.setRange(0.0, 1.0)
        self.top_p_spin.setSingleStep(0.1)
        self.top_p_spin.setValue(DEFAULT_TOP_P)
        self.top_p_spin.setFixedWidth(70)
        self.top_p_spin.valueChanged.connect(self._update_preview)
        top_p_layout.addWidget(self.top_p_spin)

        content.addWidget(top_p_container)

        # Context Window setting
        ctx_container = QWidget()
        ctx_layout = QHBoxLayout(ctx_container)
        ctx_layout.setContentsMargins(0, 0, 0, 0)
        ctx_layout.setSpacing(8)

        ctx_text_container = QWidget()
        ctx_text_layout = QVBoxLayout(ctx_text_container)
        ctx_text_layout.setContentsMargins(0, 0, 0, 0)
        ctx_text_layout.setSpacing(1)

        ctx_label = QLabel("Context Window")
        ctx_label.setObjectName("option-title")
        ctx_text_layout.addWidget(ctx_label)

        ctx_desc = QLabel("Maximum context length")
        ctx_desc.setObjectName("option-desc")
        ctx_text_layout.addWidget(ctx_desc)

        ctx_layout.addWidget(ctx_text_container, stretch=1)

        self.ctx_spin = QSpinBox()
        self.ctx_spin.setObjectName("option-spin")
        self.ctx_spin.setRange(512, 32768)
        self.ctx_spin.setSingleStep(512)
        self.ctx_spin.setValue(DEFAULT_NUM_CTX)
        self.ctx_spin.setFixedWidth(70)
        self.ctx_spin.valueChanged.connect(self._update_preview)
        ctx_layout.addWidget(self.ctx_spin)

        content.addWidget(ctx_container)

        # Max Tokens setting
        predict_container = QWidget()
        predict_layout = QHBoxLayout(predict_container)
        predict_layout.setContentsMargins(0, 0, 0, 0)
        predict_layout.setSpacing(8)

        predict_text_container = QWidget()
        predict_text_layout = QVBoxLayout(predict_text_container)
        predict_text_layout.setContentsMargins(0, 0, 0, 0)
        predict_text_layout.setSpacing(1)

        predict_label = QLabel("Max Tokens")
        predict_label.setObjectName("option-title")
        predict_text_layout.addWidget(predict_label)

        predict_desc = QLabel("Maximum output tokens")
        predict_desc.setObjectName("option-desc")
        predict_text_layout.addWidget(predict_desc)

        predict_layout.addWidget(predict_text_container, stretch=1)

        self.predict_spin = QSpinBox()
        self.predict_spin.setObjectName("option-spin")
        self.predict_spin.setRange(128, 8192)
        self.predict_spin.setSingleStep(128)
        self.predict_spin.setValue(DEFAULT_NUM_PREDICT)
        self.predict_spin.setFixedWidth(70)
        self.predict_spin.valueChanged.connect(self._update_preview)
        predict_layout.addWidget(self.predict_spin)

        content.addWidget(predict_container)

        return self.local_settings_card

    def _create_request_panel(self) -> CollapsibleCard:
        """Create the request monitoring panel."""
        # Button container
        button_container = QWidget()
        button_container.setStyleSheet("background-color: transparent;")
        button_layout = QHBoxLayout(button_container)
        button_layout.setContentsMargins(0, 0, 0, 0)
        button_layout.setSpacing(8)

        # Copy button
        self.copy_requests_btn = QPushButton("Copy")
        self.copy_requests_btn.setObjectName("text-btn")
        self.copy_requests_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.copy_requests_btn.clicked.connect(self._copy_requests)
        button_layout.addWidget(self.copy_requests_btn)

        # Clear button
        self.clear_requests_btn = QPushButton("Clear")
        self.clear_requests_btn.setObjectName("text-btn")
        self.clear_requests_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.clear_requests_btn.clicked.connect(self._clear_requests)
        button_layout.addWidget(self.clear_requests_btn)

        self.request_card = CollapsibleCard("REQUESTS", self, button_container, icon_name="requests.svg")
        content = self.request_card.content_layout()

        # Header row
        header_container = QWidget()
        header_layout = QHBoxLayout(header_container)
        header_layout.setContentsMargins(0, 0, 0, 0)
        header_layout.setSpacing(8)

        time_header = QLabel("Time")
        time_header.setObjectName("request-header")
        time_header.setFixedWidth(70)
        time_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(time_header)

        code_header = QLabel("Code")
        code_header.setObjectName("request-header")
        code_header.setFixedWidth(60)
        code_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(code_header)

        method_header = QLabel("Method")
        method_header.setObjectName("request-header")
        method_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(method_header, stretch=1)

        duration_header = QLabel("Duration")
        duration_header.setObjectName("request-header")
        duration_header.setFixedWidth(60)
        duration_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(duration_header)

        content.addWidget(header_container)

        # Add spacing between header and list
        content.addSpacing(8)

        # Request list scroll area
        self.request_scroll = AutoHideScrollArea()
        self.request_scroll.setObjectName("request-scroll")
        self.request_scroll.setWidgetResizable(True)
        self.request_scroll.setMaximumHeight(200)
        self.request_scroll.viewport().setStyleSheet("background-color: transparent;")

        # Request list container
        self.request_list_container = QWidget()
        self.request_list_container.setStyleSheet("background-color: transparent;")
        self.request_list_layout = QVBoxLayout(self.request_list_container)
        self.request_list_layout.setContentsMargins(0, 0, 0, 0)
        self.request_list_layout.setSpacing(8)

        # Placeholder
        self.request_placeholder = QLabel("No requests logged")
        self.request_placeholder.setObjectName("request-placeholder")
        self.request_placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.request_list_layout.addWidget(self.request_placeholder)

        self.request_list_layout.addStretch()

        self.request_scroll.setWidget(self.request_list_container)
        content.addWidget(self.request_scroll)

        return self.request_card

    def _copy_requests(self) -> None:
        """Copy all request logs to clipboard."""
        lines = []

        for i in range(self.request_list_layout.count()):
            item = self.request_list_layout.itemAt(i)
            if item:
                widget = item.widget()
                if widget and widget.objectName() != "request-placeholder":
                    row_parts = []
                    for child in widget.findChildren(QLabel):
                        text = child.text()
                        if text:
                            row_parts.append(text)
                    if row_parts:
                        lines.append("\t".join(row_parts))

        if lines:
            clipboard = QApplication.clipboard()
            clipboard.setText("\n".join(lines))

    def _clear_requests(self) -> None:
        """Clear all request logs."""
        while self.request_list_layout.count() > 0:
            item = self.request_list_layout.takeAt(0)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

        # Add placeholder back
        self.request_placeholder = QLabel("No available requests")
        self.request_placeholder.setObjectName("request-placeholder")
        self.request_placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.request_list_layout.addWidget(self.request_placeholder)
        self.request_list_layout.addStretch()

    def _add_request_log(self, time: str, code: int, method: str, duration: str = "") -> None:
        """Add a request log entry."""
        # Remove placeholder if exists
        if hasattr(self, 'request_placeholder') and self.request_placeholder:
            self.request_placeholder.deleteLater()
            self.request_placeholder = None

        # Remove stretch
        if self.request_list_layout.count() > 0:
            item = self.request_list_layout.takeAt(self.request_list_layout.count() - 1)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

        # Create request row
        row_container = QWidget()
        row_container.setStyleSheet("background-color: transparent;")
        row_layout = QHBoxLayout(row_container)
        row_layout.setContentsMargins(0, 0, 0, 0)
        row_layout.setSpacing(8)

        # Time
        time_label = QLabel(time)
        time_label.setObjectName("request-time")
        time_label.setFixedWidth(70)
        time_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(time_label)

        # Code badge container
        code_container = QWidget()
        code_container.setStyleSheet("background-color: transparent;")
        code_container_layout = QHBoxLayout(code_container)
        code_container_layout.setContentsMargins(0, 0, 0, 0)
        code_container_layout.addStretch()

        code_widget = QWidget()
        code_widget.setFixedSize(36, 18)
        if code == 200:
            code_widget.setStyleSheet("background-color: #d1fae5; color: #065f46; border-radius: 9px;")
        elif code >= 400:
            code_widget.setStyleSheet("background-color: #fee2e2; color: #991b1b; border-radius: 9px;")
        else:
            code_widget.setStyleSheet("background-color: #dbeafe; color: #1e40af; border-radius: 9px;")

        code_layout = QHBoxLayout(code_widget)
        code_layout.setContentsMargins(0, 0, 0, 0)
        code_label = QLabel(str(code))
        code_label.setObjectName("request-code")
        code_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        code_layout.addWidget(code_label)

        code_container_layout.addWidget(code_widget)
        code_container_layout.addStretch()
        code_container.setFixedWidth(60)
        row_layout.addWidget(code_container)

        # Method
        method_label = QLabel(method)
        method_label.setObjectName("request-method")
        method_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(method_label, stretch=1)

        # Duration
        duration_label = QLabel(duration)
        duration_label.setObjectName("request-duration")
        duration_label.setFixedWidth(60)
        duration_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(duration_label)

        self.request_list_layout.addWidget(row_container)
        self.request_list_layout.addStretch()

        # Limit to last 20 requests
        if self.request_list_layout.count() > 42:  # 20 rows + 1 stretch
            item = self.request_list_layout.takeAt(0)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

    def _create_preview_card(self) -> CollapsibleCard:
        """Create the command preview card."""
        self.copy_btn = QPushButton("Copy")
        self.copy_btn.setObjectName("text-btn")
        self.copy_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.copy_btn.clicked.connect(self._copy_command)

        self.preview_card = CollapsibleCard("PREVIEW", self, self.copy_btn, icon_name="prompt.svg")
        content = self.preview_card.content_layout()

        unix_label = QLabel("Unix / macOS")
        unix_label.setObjectName("preview-label")
        content.addWidget(unix_label)

        content.addSpacing(4)

        self.preview_text = AutoHideTextEdit()
        self.preview_text.setObjectName("preview-text")
        self.preview_text.setReadOnly(True)
        self.preview_text.setFixedHeight(PREVIEW_HEIGHT)
        self.preview_text.setLineWrapMode(QTextEdit.LineWrapMode.NoWrap)

        self.highlighter = BashSyntaxHighlighter(self.preview_text.document())

        content.addWidget(self.preview_text)

        content.addSpacing(12)

        windows_label = QLabel("Windows")
        windows_label.setObjectName("preview-label")
        content.addWidget(windows_label)

        content.addSpacing(4)

        self.preview_text_windows = AutoHideTextEdit()
        self.preview_text_windows.setObjectName("preview-text")
        self.preview_text_windows.setReadOnly(True)
        self.preview_text_windows.setFixedHeight(PREVIEW_HEIGHT)
        self.preview_text_windows.setLineWrapMode(QTextEdit.LineWrapMode.NoWrap)

        self.highlighter_windows = BashSyntaxHighlighter(self.preview_text_windows.document())

        content.addWidget(self.preview_text_windows)

        return self.preview_card

    def _create_right_panel(self) -> QWidget:
        """Create the right panel with history and local settings."""
        right_widget = QWidget()
        right_layout = QVBoxLayout(right_widget)
        right_layout.setContentsMargins(0, 0, 0, 0)
        right_layout.setSpacing(0)

        # History card
        self.history_card = CollapsibleCard("HISTORY", self, icon_name="history.svg")
        history_content = self.history_card.content_layout()

        self.history_scroll = AutoHideScrollArea()
        self.history_scroll.setObjectName("history-scroll")
        self.history_scroll.setWidgetResizable(True)


        self.history_content = QWidget()
        self.history_content.setObjectName("history-content")
        self.history_list_layout = QVBoxLayout(self.history_content)
        self.history_list_layout.setContentsMargins(0, 0, 0, 0)
        self.history_list_layout.setSpacing(10)
        self.history_list_layout.addStretch()

        self.history_scroll.setWidget(self.history_content)
        history_content.addWidget(self.history_scroll)

        right_layout.addWidget(self.history_card)

        right_layout.addSpacing(CARD_SPACING)
        right_layout.addWidget(self._create_separator())

        right_layout.addWidget(self._create_local_settings_card())
        right_layout.addSpacing(CARD_SPACING)
        right_layout.addWidget(self._create_separator())
        right_layout.addSpacing(CARD_SPACING)

        right_layout.addWidget(self._create_request_panel())
        right_layout.addStretch()

        return right_widget

    @staticmethod
    def _create_option_row(title: str, description: str) -> Tuple[QPushButton, QWidget]:
        """Create an option row with checkbox and labels."""
        container = QWidget()
        layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

        checkbox = QPushButton("✓")
        checkbox.setObjectName("option-check")
        checkbox.setCursor(Qt.CursorShape.PointingHandCursor)
        checkbox.setCheckable(True)
        checkbox.setFixedSize(16, 16)

        text_container = QWidget()
        text_layout = QVBoxLayout(text_container)
        text_layout.setContentsMargins(0, 0, 0, 0)
        text_layout.setSpacing(1)

        title_label = QLabel(title)
        title_label.setObjectName("option-title")
        text_layout.addWidget(title_label)

        desc_label = QLabel(description)
        desc_label.setObjectName("option-desc")
        text_layout.addWidget(desc_label)

        layout.addWidget(checkbox, alignment=Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(text_container, stretch=1, alignment=Qt.AlignmentFlag.AlignVCenter)

        return checkbox, container

    @staticmethod
    def _create_option_row_with_input(
        title: str, description: str, default_value: str = ""
    ) -> Tuple[QPushButton, QLineEdit, QWidget]:
        """Create an option row with checkbox, labels, and input field."""
        container = QWidget()
        layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(8)
        layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

        checkbox = QPushButton("✓")
        checkbox.setObjectName("option-check")
        checkbox.setCursor(Qt.CursorShape.PointingHandCursor)
        checkbox.setCheckable(True)
        checkbox.setFixedSize(16, 16)

        text_container = QWidget()
        text_layout = QVBoxLayout(text_container)
        text_layout.setContentsMargins(0, 0, 0, 0)
        text_layout.setSpacing(1)

        title_label = QLabel(title)
        title_label.setObjectName("option-title")
        text_layout.addWidget(title_label)

        desc_label = QLabel(description)
        desc_label.setObjectName("option-desc")
        text_layout.addWidget(desc_label)

        layout.addWidget(checkbox, alignment=Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(text_container, stretch=1, alignment=Qt.AlignmentFlag.AlignVCenter)

        input_field = QLineEdit()
        input_field.setObjectName("option-input")
        input_field.setText(default_value)
        input_field.setFixedWidth(80)
        input_field.setEnabled(False)

        checkbox.toggled.connect(lambda checked: input_field.setEnabled(checked))

        layout.addWidget(input_field, alignment=Qt.AlignmentFlag.AlignVCenter)

        return checkbox, input_field, container

    @staticmethod
    def _format_model_name(model_name: str) -> str:
        """Format model name for display with mapping."""
        base_name = model_name.split(":")[0].lower()
        formatted = MODEL_NAME_MAPPING.get(base_name, base_name.title())
        return formatted.replace("-", " ")

    @staticmethod
    def _extract_model_size(model_name: str) -> str:
        """Extract parameter size from model name (e.g., 70b, 14b)."""
        match = re.search(r"(\d+)[bB]", model_name)
        return f"{match.group(1)}b" if match else ""

    @staticmethod
    def _create_model_radio(model_name: str) -> Tuple[QRadioButton, QWidget]:
        """Create radio button with model name and size badge."""
        container = QWidget()
        layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

        formatted_name = AgentLauncher._format_model_name(model_name)
        radio = QRadioButton(formatted_name)
        radio.setCursor(Qt.CursorShape.PointingHandCursor)
        radio.setProperty("originalName", model_name)

        size = AgentLauncher._extract_model_size(model_name)
        if size:
            badge = QLabel(size)
            badge.setObjectName("model-badge")
            badge.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            layout.addWidget(radio)
            layout.addWidget(badge)
        else:
            layout.addWidget(radio)

        return radio, container

    def _apply_styles(self) -> None:
        """Apply stylesheet from CSS file and set application font."""
        # Set application font
        app = QApplication.instance()
        if app:
            # Try to use system font on macOS
            font = QFont()
            if sys.platform == "darwin":  # macOS
                font.setFamily("SF Pro Display")
                if not font.exactMatch():
                    font.setFamily("Helvetica Neue")
                    if not font.exactMatch():
                        font.setFamily("Helvetica")
            elif sys.platform == "win32":  # Windows
                font.setFamily("Segoe UI")
            else:  # Linux and others
                font.setFamily("Sans Serif")

            font.setPointSize(12)  # Default font size
            app.setFont(font)

        # Apply stylesheet
        css_path = Path(__file__).parent / "styles.txt"
        if css_path.exists():
            self.setStyleSheet(css_path.read_text())

    def _setup_shortcuts(self) -> None:
        """Setup keyboard shortcuts."""
        run_shortcut = QShortcut(QKeySequence("Ctrl+Return"), self)
        run_shortcut.activated.connect(self._run_agent)

        close_shortcut = QShortcut(QKeySequence("Esc"), self)
        close_shortcut.activated.connect(self.close)

    def _update_run_button(self) -> None:
        """Update run button state based on prompt content and model selection."""
        # If agent is running, always enable the button (for stopping)
        if self.agent_process is not None:
            self.run_btn.setEnabled(True)
            return

        # Otherwise, check if we can start a new run
        has_content = bool(self.prompt_input.toPlainText().strip())
        has_model = self.model_button_group.checkedButton() is not None
        char_count = len(self.prompt_input.toPlainText())
        within_limit = char_count <= MAX_PROMPT_CHARS
        self.run_btn.setEnabled(has_content and has_model and within_limit)

    def _update_local_settings_state(self) -> None:
        """Update local model settings card state based on model selection."""
        checked_btn = self.model_button_group.checkedButton()
        if not checked_btn:
            return

        model_name = checked_btn.property("originalName")
        is_cloud = "cloud" in model_name.lower()

        # Enable/disable local settings based on model type
        self.temp_spin.setEnabled(not is_cloud)
        self.top_p_spin.setEnabled(not is_cloud)
        self.ctx_spin.setEnabled(not is_cloud)
        self.predict_spin.setEnabled(not is_cloud)

        # Update card appearance
        if is_cloud:
            self.local_settings_card.setStyleSheet("opacity: 0.5;")
        else:
            self.local_settings_card.setStyleSheet("")

    def _reset_local_settings(self) -> None:
        """Reset local model settings to default values."""
        self.temp_spin.setValue(DEFAULT_TEMPERATURE)
        self.top_p_spin.setValue(DEFAULT_TOP_P)
        self.ctx_spin.setValue(DEFAULT_NUM_CTX)
        self.predict_spin.setValue(DEFAULT_NUM_PREDICT)


    def _clear_layout(self, layout) -> None:
        """Clear all items from a layout."""
        while layout.count():
            item = layout.takeAt(0)
            if item and (widget := item.widget()):
                widget.deleteLater()

    def _start_ollama_service(self) -> None:
        """Start Ollama service and monitor its output."""
        self.ollama_process = QProcess(self)

        assert self.ollama_process is not None

        self.ollama_process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.ollama_process.readyReadStandardOutput.connect(self._read_ollama_output)
        self.ollama_process.readyReadStandardError.connect(self._read_ollama_output)

        self.ollama_process.start("ollama", ["serve"])

        if not self.ollama_process.waitForStarted(3000):
            print("Failed to start Ollama service. Port may be in use.")
            self.ollama_process = None

    def _update_resource_info(self) -> None:
        """Update CPU and memory usage info for Ollama process."""
        if not self.ollama_process:
            self.cpu_label.setText("0.0 %")
            self.mem_label.setText("0.0 MB")
            return

        try:
            # Get Ollama process PID
            pid = self.ollama_process.processId()
            if pid == -1:
                self.cpu_label.setText("0.0 %")
                self.mem_label.setText("0.0 MB")
                return

            # Get main process
            main_process = psutil.Process(pid)

            # Get all child processes
            children = main_process.children(recursive=True)
            all_processes = [main_process] + children

            # Calculate total CPU and memory usage
            total_cpu = 0.0
            total_mem = 0.0

            for proc in all_processes:
                try:
                    total_cpu += proc.cpu_percent(interval=0)
                    total_mem += proc.memory_info().rss / 1024 / 1024
                except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                    continue

            # Update CPU label
            self.cpu_label.setText(f"{total_cpu:.1f} %")

            # Update memory label
            if total_mem >= 1024:
                self.mem_label.setText(f"{total_mem / 1024:.1f} GB")
            else:
                self.mem_label.setText(f"{total_mem:.1f} MB")

        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            self.cpu_label.setText("0.0 %")
            self.mem_label.setText("0.0 MB")

    def _read_ollama_output(self) -> None:
        """Read and parse Ollama service output."""
        if not self.ollama_process:
            return

        while self.ollama_process.canReadLine():
            line = self.ollama_process.readLine().data().decode('utf-8').strip()
            if line:
                self._parse_ollama_log(line)

    def _parse_ollama_log(self, line: str) -> None:
        """Parse Ollama log line and extract request info.

        Format: [GIN] 2026/05/24 - 08:04:37 | 200 | 34.169952417s | 127.0.0.1 | POST "/api/chat"
        """
        if not line.startswith("[GIN]"):
            return

        try:
            parts = line.split("|")
            if len(parts) < 5:
                return

            time_part = parts[0].split("-")[1].strip()
            time_str = time_part

            code = int(parts[1].strip())

            duration_str = parts[2].strip()
            duration = self._format_duration(duration_str)

            latency_ms = self._extract_latency_ms(duration_str)
            if latency_ms > 0:
                self.request_latencies.append(latency_ms)
                if len(self.request_latencies) > self.max_latency_samples:
                    self.request_latencies.pop(0)
                self._update_latency_display()

            method_path = parts[4].strip()
            method_parts = method_path.split()
            if len(method_parts) >= 2:
                method = method_parts[0]
                self._add_request_log(time_str, code, method, duration)
        except (IndexError, ValueError):
            pass

    def _format_duration(self, duration_str: str) -> str:
        """Format duration string to two decimal places.

        Examples:
            34.169952417s -> 34.17s
            659.25µs -> 0.66ms
            287.808875ms -> 287.81ms
        """
        try:
            if "µs" in duration_str:
                value = float(duration_str.replace("µs", ""))
                return f"{value / 1000:.2f} ms"
            elif "ms" in duration_str:
                value = float(duration_str.replace("ms", ""))
                return f"{value:.2f} ms"
            elif "s" in duration_str:
                value = float(duration_str.replace("s", ""))
                return f"{value:.2f} s"
        except ValueError:
            pass
        return ""

    def _extract_latency_ms(self, duration_str: str) -> float:
        """Extract latency in milliseconds from duration string.

        Examples:
            34.169952417s -> 34169.95 ms
            659.25µs -> 0.66 ms
            287.808875ms -> 287.81 ms
        """
        try:
            if "µs" in duration_str:
                value = float(duration_str.replace("µs", ""))
                return value / 1000  # Convert to ms
            elif "ms" in duration_str:
                value = float(duration_str.replace("ms", ""))
                return value
            elif "s" in duration_str:
                value = float(duration_str.replace("s", ""))
                return value * 1000  # Convert to ms
        except ValueError:
            pass
        return 0.0

    def _update_latency_display(self) -> None:
        """Update average latency display."""
        if not self.request_latencies:
            self.latency_label.setText("0 ms")
            return

        avg_latency = sum(self.request_latencies) / len(self.request_latencies)

        # Format the latency display
        if avg_latency >= 1000:
            self.latency_label.setText(f"{avg_latency / 1000:.1f} s")
        else:
            self.latency_label.setText(f"{avg_latency:.0f} ms")

    def _fetch_models(self) -> None:
        """Fetch available models from Ollama API."""
        url = QUrl(OLLAMA_API_URL)
        request = QNetworkRequest(url)
        reply = self.network_manager.get(request)

        assert reply is not None

        if reply:
            reply.finished.connect(lambda: self._on_models_fetched(reply))

    def _on_models_fetched(self, reply) -> None:
        """Handle models fetch response."""
        self._clear_layout(self.model_radio_layout)

        if reply.error() != reply.NetworkError.NoError:
            self._show_model_error("Failed to fetch models")
            reply.deleteLater()
            return

        data = reply.readAll().data()
        reply.deleteLater()

        try:
            result = json.loads(data.decode("utf-8"))
            models = result.get("models", [])

            if not models:
                self._show_model_error("No models found")
                return

            self._populate_models(models)

        except json.JSONDecodeError:
            self._show_model_error("Invalid response")

    def _show_model_error(self, message: str) -> None:
        """Show an error message in the models section."""
        error_label = QLabel(message)
        error_label.setObjectName("model-fetching")
        self.model_radio_layout.addWidget(error_label)

    def _populate_models(self, models: list) -> None:
        """Populate the models section with fetched models."""
        local_models = [m for m in models if "cloud" not in m.get("name", "")]
        cloud_models = [m for m in models if "cloud" in m.get("name", "")]

        models_container = QWidget()
        models_layout = QVBoxLayout(models_container)
        models_layout.setContentsMargins(0, 0, 0, 0)
        models_layout.setSpacing(10)

        if cloud_models:
            self._add_model_section(models_layout, "Cloud", cloud_models, 0)

        if local_models:
            start_id = len(cloud_models) if cloud_models else 0
            self._add_model_section(models_layout, "Local", local_models, start_id)

        self.model_radio_layout.addWidget(models_container)

    def _add_model_section(
        self, layout: QVBoxLayout, title: str, models: list, start_id: int
    ) -> None:
        """Add a section of models to the layout."""
        section = QWidget()
        section_layout = QVBoxLayout(section)
        section_layout.setContentsMargins(0, 0, 0, 0)
        section_layout.setSpacing(6)

        header = QLabel(title)
        header.setObjectName("model-section-header")
        section_layout.addWidget(header)

        for i, model in enumerate(models):
            model_name = model.get("name", "unknown")
            radio, widget = self._create_model_radio(model_name)
            self.model_button_group.addButton(radio, start_id + i)
            radio.toggled.connect(self._update_run_button)
            radio.toggled.connect(self._update_preview)
            radio.toggled.connect(self._update_local_settings_state)
            section_layout.addWidget(widget)

            is_first_cloud_model = (title == "Cloud" and i == 0)
            is_first_local_model = (title == "Local" and start_id == 0 and i == 0)
            if is_first_cloud_model or is_first_local_model:
                radio.setChecked(True)

        section_layout.addStretch()
        layout.addWidget(section)

    @staticmethod
    def _create_separator() -> QFrame:
        """Create a horizontal separator line."""
        separator = QFrame()
        separator.setFixedHeight(1)
        separator.setStyleSheet("background-color: #e0e0e0; margin: 0 20px;")
        return separator

    def _update_preview(self) -> None:
        """Update the command preview."""
        cmd_unix = self._build_command(is_windows=False)
        cmd_windows = self._build_command(is_windows=True)
        self.preview_text.setPlainText(cmd_unix)
        self.preview_text_windows.setPlainText(cmd_windows)

    def _build_command(self, is_windows: bool = False) -> str:
        """Build the command string from current settings."""
        if is_windows:
            parts = ["python", "agent.py"]
        else:
            parts = [sys.executable, "agent.py"]

        if self.context_check.isChecked():
            parts.append("--context")

        if self.think_check.isChecked():
            parts.append("--think")

        if self.timeout_check.isChecked():
            timeout_value = self.timeout_input.text().strip()
            if timeout_value:
                parts.extend(["--timeout", timeout_value])

        checked_btn = self.model_button_group.checkedButton()
        if checked_btn:
            model_name = checked_btn.property("originalName")
            parts.extend(["--model", model_name])

            # Add local model settings only for local models
            is_cloud = "cloud" in model_name.lower()
            if not is_cloud:
                parts.extend(["--temperature", str(self.temp_spin.value())])
                parts.extend(["--top-p", str(self.top_p_spin.value())])
                parts.extend(["--num-ctx", str(self.ctx_spin.value())])
                parts.extend(["--num-predict", str(self.predict_spin.value())])

        prompt = self.prompt_input.toPlainText().strip()
        if prompt:
            parts.append(f'"{prompt}"')

        return " ".join(parts)

    def _validate_timeout_input(self) -> None:
        """Validate timeout input in real-time."""
        text = self.timeout_input.text().strip()

        # Empty is valid (will use default)
        if not text:
            self.timeout_input.setProperty("error", "false")
            self.timeout_input.style().unpolish(self.timeout_input)
            self.timeout_input.style().polish(self.timeout_input)
            return

        # Validate: must be a positive integer
        try:
            value = int(text)
            if value <= 0:
                raise ValueError("Timeout must be positive")

            # Valid input - remove error state
            self.timeout_input.setProperty("error", "false")
            self.timeout_input.style().unpolish(self.timeout_input)
            self.timeout_input.style().polish(self.timeout_input)
        except ValueError:
            # Invalid input - set error state
            self.timeout_input.setProperty("error", "true")
            self.timeout_input.style().unpolish(self.timeout_input)
            self.timeout_input.style().polish(self.timeout_input)

            # Show toast notification
            self.toast.show_toast("Timeout must be a positive integer", self.timeout_input)

    def _on_timeout_editing_finished(self) -> None:
        """Handle timeout input editing finished - restore default if empty."""
        text = self.timeout_input.text().strip()

        # If empty, restore default
        if not text:
            self.timeout_input.setText("120")

    def _copy_command(self) -> None:
        """Copy both commands to clipboard."""
        cmd_unix = self._build_command(is_windows=False)
        cmd_windows = self._build_command(is_windows=True)
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.setText(f"{cmd_unix}\n{cmd_windows}")

    def _refresh_history_list(self) -> None:
        """Refresh the history list display."""
        while self.history_list_layout.count() > 1:
            item = self.history_list_layout.takeAt(0)
            if item and (widget := item.widget()):
                widget.deleteLater()

        if not self.prompt_history:
            placeholder = QLabel("No current history available")
            placeholder.setObjectName("history-placeholder")
            placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.history_list_layout.insertWidget(0, placeholder)
            return

        for item in self.prompt_history:
            self._add_history_item(item)

    def _add_history_item(self, item: dict) -> None:
        """Add a history item to the list."""
        item_widget = QWidget()
        item_widget.setObjectName("history-item")

        hlayout = QHBoxLayout(item_widget)
        hlayout.setContentsMargins(10, 8, 10, 8)
        hlayout.setSpacing(8)

        text_container = QWidget()
        text_container.setObjectName("history-text-container")
        vlayout = QVBoxLayout(text_container)
        vlayout.setContentsMargins(0, 0, 0, 0)
        vlayout.setSpacing(4)

        time_label = QLabel(item["time"])
        time_label.setObjectName("history-time")
        vlayout.addWidget(time_label)

        text_label = QLabel(item["prompt"])
        text_label.setObjectName("history-text")
        text_label.setWordWrap(True)
        vlayout.addWidget(text_label)

        insert_btn = QPushButton("Insert")
        insert_btn.setObjectName("insert-btn")
        insert_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        insert_btn.clicked.connect(
            lambda checked, p=item["prompt"]: self._insert_history(p)
        )

        hlayout.addWidget(text_container, stretch=1)
        hlayout.addWidget(insert_btn, alignment=Qt.AlignmentFlag.AlignVCenter)

        self.history_list_layout.insertWidget(self.history_list_layout.count() - 1, item_widget)

    def _insert_history(self, prompt: str) -> None:
        """Insert a history item into the prompt input."""
        self.prompt_input.clear()
        self.prompt_input.setPlainText(prompt)
        self.prompt_input.setFocus()

    def _run_agent(self) -> None:
        """Execute agent.py with configured parameters."""
        if self.agent_process and self.agent_process.state() != QProcess.ProcessState.NotRunning:
            self._stop_agent()
            return

        prompt = self.prompt_input.toPlainText().strip()
        if not prompt:
            return

        self._add_to_history(prompt)
        self._start_agent_process(prompt)

    def _add_to_history(self, prompt: str) -> None:
        """Add prompt to history."""
        now = QDateTime.currentDateTime()
        time_str = now.toString("yyyy-M-d  hh:mm")

        existing = next(
            (i for i, h in enumerate(self.prompt_history) if h["prompt"] == prompt), -1
        )
        if existing >= 0:
            self.prompt_history.pop(existing)

        self.prompt_history.insert(0, {"time": time_str, "prompt": prompt})
        self.prompt_history = self.prompt_history[:MAX_HISTORY]
        self._refresh_history_list()

    def _start_agent_process(self, prompt: str) -> None:
        """Start the agent process."""
        cmd = ["python3", "agent.py"]

        if self.context_check.isChecked():
            cmd.append("--context")

        if self.think_check.isChecked():
            cmd.append("--think")

        if self.timeout_check.isChecked():
            timeout_value = self.timeout_input.text().strip()
            if timeout_value:
                cmd.extend(["--timeout", timeout_value])

        checked_btn = self.model_button_group.checkedButton()
        if checked_btn:
            model_name = checked_btn.property("originalName")
            cmd.extend(["--model", model_name])

        cmd.append(prompt)

        self.agent_process = QProcess(self)
        self.agent_process.setStandardOutputFile("/dev/null")
        self.agent_process.setStandardErrorFile("/dev/null")
        self.agent_process.finished.connect(self._on_process_finished)
        self.agent_process.start(cmd[0], cmd[1:])

        self.run_btn.setText("Stop")

        self.prompt_input.clear()

    def _stop_agent(self) -> None:
        """Stop the running agent process."""
        if self.agent_process:
            self.agent_process.kill()
            self.agent_process.waitForFinished(3000)
        self.run_btn.setText("Run")

    def _on_process_finished(self) -> None:
        """Handle process completion."""
        self.run_btn.setText("Run")
        self.agent_process = None

    def closeEvent(self, event) -> None:
        """Handle application close event."""
        if self.ollama_process:
            self.ollama_process.kill()
            self.ollama_process.waitForFinished(3000)
        event.accept()


def main() -> None:
    """Application entry point."""
    app = QApplication(sys.argv)
    window = AgentLauncher()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
