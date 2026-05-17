"""PyQt6 GUI launcher for Gradum Agent - Minimalist Black & White Theme."""

import sys
import json
import re
from pathlib import Path


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
    " WizardLM": "WizardLM",
    "yi": "Yi",
}

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QTextEdit, QScrollArea, QRadioButton, QButtonGroup, QFrame, QSizePolicy
)
from PyQt6.QtCore import Qt, QProcess, QDateTime, QUrl
from PyQt6.QtGui import QKeySequence, QShortcut
from PyQt6.QtNetwork import QNetworkAccessManager, QNetworkRequest


class AgentLauncher(QMainWindow):
    """Modern minimalist launcher with black & white theme."""

    def __init__(self):
        super().__init__()
        self.setWindowTitle("Gradum")
        self.setFixedSize(780, 680)

        self.prompt_history = []
        self.max_history = 10
        self.agent_process = None
        self.network_manager = QNetworkAccessManager(self)

        self._setup_ui()
        self._apply_styles()
        self._setup_shortcuts()
        self._refresh_history_list()
        self._update_run_button()
        self._fetch_models()

        self.prompt_input.textChanged.connect(self._update_run_button)

    def _setup_ui(self):
        """Initialize UI components."""
        central = QWidget()
        self.setCentralWidget(central)

        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(24, 20, 24, 20)
        main_layout.setSpacing(16)

        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.setSpacing(0)

        self.title = QLabel("Gradum")
        self.title.setObjectName("title")

        self.subtitle = QLabel("AI Agent Launcher")
        self.subtitle.setObjectName("subtitle")

        prompt_card = QWidget()
        prompt_card.setObjectName("card")
        prompt_layout = QVBoxLayout(prompt_card)
        prompt_layout.setContentsMargins(14, 14, 14, 14)
        prompt_layout.setSpacing(8)

        prompt_header = QWidget()
        prompt_header_layout = QHBoxLayout(prompt_header)
        prompt_header_layout.setContentsMargins(0, 0, 0, 0)

        prompt_label = QLabel("Prompt")
        prompt_label.setObjectName("card-title")

        self.clear_btn = QPushButton("Clear")
        self.clear_btn.setObjectName("text-btn")
        self.clear_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.clear_btn.clicked.connect(self._clear_prompt)

        prompt_header_layout.addWidget(prompt_label)
        prompt_header_layout.addStretch()
        prompt_header_layout.addWidget(self.clear_btn)

        self.prompt_input = QTextEdit()
        self.prompt_input.setObjectName("prompt-input")
        self.prompt_input.setPlaceholderText("What would you like me to do?")
        self.prompt_input.setFixedHeight(100)

        prompt_layout.addWidget(prompt_header)
        prompt_layout.addWidget(self.prompt_input)

        options_card = QWidget()
        options_card.setObjectName("card")
        options_layout = QVBoxLayout(options_card)
        options_layout.setContentsMargins(14, 14, 14, 14)
        options_layout.setSpacing(10)

        options_label = QLabel("Options")
        options_label.setObjectName("card-title")
        options_layout.addWidget(options_label)

        self.context_check, self.context_row = self._create_option_row(
            "Context Memory",
            "Remember conversation history"
        )
        options_layout.addWidget(self.context_row)

        self.think_check, self.think_row = self._create_option_row(
            "Think Mode",
            "Show step-by-step reasoning"
        )
        options_layout.addWidget(self.think_row)

        model_card = QWidget()
        model_card.setObjectName("card")
        model_card_layout = QVBoxLayout(model_card)
        model_card_layout.setContentsMargins(14, 14, 14, 14)
        model_card_layout.setSpacing(10)

        model_header = QWidget()
        model_header_layout = QHBoxLayout(model_header)
        model_header_layout.setContentsMargins(0, 0, 0, 0)

        model_title = QLabel("Models")
        model_title.setObjectName("card-title")

        self.refresh_btn = QPushButton("Refresh")
        self.refresh_btn.setObjectName("text-btn")
        self.refresh_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.refresh_btn.clicked.connect(self._fetch_models)

        model_header_layout.addWidget(model_title)
        model_header_layout.addStretch()
        model_header_layout.addWidget(self.refresh_btn)

        model_card_layout.addWidget(model_header)

        self.model_button_group = QButtonGroup(self)
        self.model_radio_container = QWidget()
        self.model_radio_layout = QHBoxLayout(self.model_radio_container)
        self.model_radio_layout.setContentsMargins(0, 0, 0, 0)
        self.model_radio_layout.setSpacing(12)

        fetching_label = QLabel("Fetching models...")
        fetching_label.setObjectName("model-fetching")
        self.model_radio_layout.addWidget(fetching_label)

        model_card_layout.addWidget(self.model_radio_container)

        self.run_btn = QPushButton("Run Agent")
        self.run_btn.setObjectName("run-btn")
        self.run_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.run_btn.setFixedHeight(40)
        self.run_btn.clicked.connect(self._run_agent)

        status_bar = QWidget()
        status_layout = QHBoxLayout(status_bar)
        status_layout.setContentsMargins(0, 0, 0, 0)

        self.status_label = QLabel("Ready")
        self.status_label.setObjectName("status")

        status_layout.addWidget(self.status_label)
        status_layout.addStretch()

        left_layout.addWidget(self.title)
        left_layout.addWidget(self.subtitle)
        left_layout.addSpacing(16)
        left_layout.addWidget(prompt_card)
        left_layout.addSpacing(16)
        left_layout.addWidget(options_card)
        left_layout.addSpacing(12)
        left_layout.addWidget(model_card)
        left_layout.addSpacing(16)
        left_layout.addWidget(self.run_btn)
        left_layout.addSpacing(8)
        left_layout.addWidget(status_bar)
        left_layout.addStretch()

        right_widget = QWidget()
        right_layout = QVBoxLayout(right_widget)
        right_layout.setContentsMargins(0, 28, 0, 0)
        right_layout.setSpacing(8)

        history_header = QLabel("History")
        history_header.setObjectName("history-header")

        self.history_scroll = QScrollArea()
        self.history_scroll.setObjectName("history-scroll")
        self.history_scroll.setWidgetResizable(True)
        self.history_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.history_scroll.setFixedWidth(240)

        self.history_content = QWidget()
        self.history_content.setObjectName("history-content")
        self.history_list_layout = QVBoxLayout(self.history_content)
        self.history_list_layout.setContentsMargins(0, 0, 0, 0)
        self.history_list_layout.setSpacing(10)
        self.history_list_layout.addStretch()

        self.history_scroll.setWidget(self.history_content)

        right_layout.addWidget(history_header)
        right_layout.addWidget(self.history_scroll)

        main_layout.addWidget(left_widget, stretch=1)
        main_layout.addWidget(right_widget)

    def _create_option_row(self, title: str, description: str):
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

        desc_label = QLabel(description)
        desc_label.setObjectName("option-desc")

        text_layout.addWidget(title_label)
        text_layout.addWidget(desc_label)

        layout.addWidget(checkbox, alignment=Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(text_container, stretch=1, alignment=Qt.AlignmentFlag.AlignVCenter)

        return checkbox, container

    def _format_model_name(self, model_name: str) -> str:
        """Format model name for display with mapping."""
        base_name = model_name.split(":")[0].lower()
        formatted = MODEL_NAME_MAPPING.get(base_name, base_name.title())
        formatted = formatted.replace("-", " ")
        return formatted

    def _extract_model_size(self, model_name: str) -> str:
        """Extract parameter size from model name (e.g., 70b, 14b)."""
        match = re.search(r'(\d+)[bB]', model_name)
        return f"{match.group(1)}b" if match else ""

    def _create_model_radio(self, model_name: str) -> tuple:
        """Create radio button with model name and size badge."""
        container = QWidget()
        layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

        formatted_name = self._format_model_name(model_name)
        radio = QRadioButton(formatted_name)
        radio.setCursor(Qt.CursorShape.PointingHandCursor)

        size = self._extract_model_size(model_name)
        if size:
            badge = QLabel(f"{size}")
            badge.setObjectName("model-badge")
            badge.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            layout.addWidget(radio)
            layout.addWidget(badge)
        else:
            layout.addWidget(radio)

        return radio, container

    def _apply_styles(self):
        """Apply black & white stylesheet."""
        self.setStyleSheet("""
            QMainWindow {
                background-color: #ffffff;
            }

            #title {
                color: #000000;
                font-size: 22px;
                font-weight: 700;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #subtitle {
                color: #666666;
                font-size: 11px;
                font-weight: 400;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #card {
                background-color: #f8f8f8;
                border: 1px solid #e8e8e8;
                border-radius: 10px;
            }

            #card-title {
                color: #000000;
                font-size: 11px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #text-btn {
                background: transparent;
                border: none;
                color: #666666;
                font-size: 12px;
                font-weight: 500;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                padding: 4px 8px;
            }

            #text-btn:hover {
                color: #000000;
            }

            #prompt-input {
                background-color: #ffffff;
                border: 1px solid #dddddd;
                border-radius: 6px;
                padding: 8px;
                color: #000000;
                font-size: 12px;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                line-height: 1.4;
            }

            #prompt-input:focus {
                border: 1.5px solid #000000;
            }

            #prompt-input::placeholder {
                color: #999999;
            }

            #option-check {
                background-color: transparent;
                border: 1px solid #cccccc;
                border-radius: 3px;
                color: transparent;
                font-size: 10px;
                font-weight: bold;
                padding: 0;
            }

            #option-check:hover {
                border: 1px solid #999999;
            }

            #option-check:checked {
                background-color: #000000;
                border: 1px solid #000000;
                color: #ffffff;
            }

            #option-title {
                color: #000000;
                font-size: 11px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #option-desc {
                color: #888888;
                font-size: 9px;
                font-weight: 400;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            QRadioButton {
                spacing: 8px;
                color: #333333;
                font-size: 12px;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            QRadioButton::indicator {
                width: 14px;
                height: 14px;
                border-radius: 8px;
                border: 1px solid #cccccc;
                background-color: #ffffff;
            }

            QRadioButton::indicator:hover {
                border: 1px solid #999999;
            }

            QRadioButton::indicator:checked {
                border: 1px solid #000000;
                background-color: qradialgradient(cx:0.5, cy:0.5, radius:0.4, fx:0.5, fy:0.5, stop:0 #000000, stop:0.5 #000000, stop:0.6 #ffffff, stop:1 #ffffff);
            }

            #model-fetching {
                color: #999999;
                font-size: 11px;
                font-style: italic;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #model-badge {
                background-color: transparent;
                border: 1px solid #000000;
                border-radius: 8px;
                color: #000000;
                font-size: 9px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                padding: 2px 8px;
            }

            #model-section-header {
                color: #888888;
                font-size: 9px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                text-transform: uppercase;
                letter-spacing: 0.5px;
                margin-top: 8px;
            }

            #model-separator {
                background-color: #e0e0e0;
                margin: 8px 0;
            }

            #run-btn {
                background-color: #000000;
                color: #ffffff;
                border: none;
                border-radius: 8px;
                font-size: 13px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #run-btn:hover {
                background-color: #333333;
            }

            #run-btn:pressed {
                background-color: #555555;
            }

            #run-btn:disabled {
                background-color: #cccccc;
                color: #999999;
            }

            #status {
                color: #888888;
                font-size: 10px;
                font-weight: 400;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #status[status="success"] {
                color: #000000;
            }

            #status[status="error"] {
                color: #cc0000;
            }

            #status[status="running"] {
                color: #666666;
            }

            QScrollBar:vertical {
                background-color: transparent;
                width: 5px;
                border-radius: 3px;
            }

            QScrollBar::handle:vertical {
                background-color: #dddddd;
                border-radius: 3px;
                min-height: 20px;
            }

            QScrollBar::handle:vertical:hover {
                background-color: #bbbbbb;
            }

            #history-scroll {
                background-color: transparent;
                border: none;
            }

            #history-content {
                background-color: transparent;
            }

            #history-text-container {
                background-color: transparent;
            }

            #history-header {
                color: #999999;
                font-size: 10px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                text-transform: uppercase;
                letter-spacing: 0.5px;
            }

            #history-item {
                background-color: transparent;
                border-radius: 6px;
            }

            #history-item:hover {
                background-color: #f5f5f5;
            }

            #history-time {
                color: #aaaaaa;
                font-size: 10px;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
            }

            #history-text {
                color: #444444;
                font-size: 12px;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                line-height: 1.4;
            }

            #history-placeholder {
                color: #cccccc;
                font-size: 11px;
                font-style: italic;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                padding: 20px;
            }

            #insert-btn {
                background-color: transparent;
                border: 1px solid #dddddd;
                border-radius: 4px;
                color: #888888;
                font-size: 10px;
                font-weight: 500;
                font-family: -apple-system, BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                padding: 4px 8px;
            }

            #insert-btn:hover {
                background-color: #f5f5f5;
                border-color: #cccccc;
                color: #555555;
            }
        """)

    def _setup_shortcuts(self):
        """Setup keyboard shortcuts."""
        run_shortcut = QShortcut(QKeySequence("Ctrl+Return"), self)
        run_shortcut.activated.connect(self._run_agent)

        close_shortcut = QShortcut(QKeySequence("Esc"), self)
        close_shortcut.activated.connect(self.close)

    def _update_run_button(self):
        """Update run button state based on prompt content."""
        has_content = bool(self.prompt_input.toPlainText().strip())
        self.run_btn.setEnabled(has_content)

    def _fetch_models(self):
        """Fetch available models from Ollama API."""
        url = QUrl("http://localhost:11434/api/tags")
        request = QNetworkRequest(url)
        reply = self.network_manager.get(request)
        reply.finished.connect(lambda: self._on_models_fetched(reply))

    def _on_models_fetched(self, reply):
        """Handle models fetch response."""
        while self.model_radio_layout.count():
            item = self.model_radio_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if reply.error() != reply.NetworkError.NoError:
            error_label = QLabel("Failed to fetch models")
            error_label.setObjectName("model-fetching")
            self.model_radio_layout.addWidget(error_label)
            reply.deleteLater()
            return

        data = reply.readAll().data()
        reply.deleteLater()

        try:
            result = json.loads(data.decode("utf-8"))
            models = result.get("models", [])

            if not models:
                no_models_label = QLabel("No models found")
                no_models_label.setObjectName("model-fetching")
                self.model_radio_layout.addWidget(no_models_label)
                return

            local_models = [m for m in models if not ("cloud" in m.get("name", ""))]
            cloud_models = [m for m in models if "cloud" in m.get("name", "")]

            models_container = QWidget()
            models_layout = QHBoxLayout(models_container)
            models_layout.setContentsMargins(0, 0, 0, 0)
            models_layout.setSpacing(20)

            if cloud_models:
                cloud_col = QWidget()
                cloud_col_layout = QVBoxLayout(cloud_col)
                cloud_col_layout.setContentsMargins(0, 0, 0, 0)
                cloud_col_layout.setSpacing(6)

                cloud_header = QLabel("Cloud")
                cloud_header.setObjectName("model-section-header")
                cloud_col_layout.addWidget(cloud_header)

                start_id = 0
                for i, model in enumerate(cloud_models):
                    model_name = model.get("name", "unknown")
                    radio, widget = self._create_model_radio(model_name)
                    self.model_button_group.addButton(radio, start_id + i)
                    cloud_col_layout.addWidget(widget)
                    if i == 0:
                        radio.setChecked(True)

                cloud_col_layout.addStretch()
                models_layout.addWidget(cloud_col)

            if local_models:
                if cloud_models:
                    separator = QFrame()
                    separator.setObjectName("model-separator")
                    separator.setFixedWidth(1)
                    separator.setStyleSheet("background-color: #e0e0e0; margin: 8px 0;")
                    models_layout.addWidget(separator)

                local_col = QWidget()
                local_col_layout = QVBoxLayout(local_col)
                local_col_layout.setContentsMargins(0, 0, 0, 0)
                local_col_layout.setSpacing(6)

                local_header = QLabel("Local")
                local_header.setObjectName("model-section-header")
                local_col_layout.addWidget(local_header)

                start_id = len(cloud_models) if cloud_models else 0
                for i, model in enumerate(local_models):
                    model_name = model.get("name", "unknown")
                    radio, widget = self._create_model_radio(model_name)
                    self.model_button_group.addButton(radio, start_id + i)
                    local_col_layout.addWidget(widget)
                    if not cloud_models and i == 0:
                        radio.setChecked(True)

                local_col_layout.addStretch()
                models_layout.addWidget(local_col)

            self.model_radio_layout.addWidget(models_container)

        except json.JSONDecodeError:
            error_label = QLabel("Invalid response")
            error_label.setObjectName("model-fetching")
            self.model_radio_layout.addWidget(error_label)

    def _clear_prompt(self):
        """Clear the prompt input."""
        self.prompt_input.clear()
        self._set_status("Ready", "")

    def _refresh_history_list(self):
        """Refresh the history list display."""
        while self.history_list_layout.count() > 1:
            item = self.history_list_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if not self.prompt_history:
            placeholder = QLabel("No history yet")
            placeholder.setObjectName("history-placeholder")
            placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.history_list_layout.insertWidget(0, placeholder)
            return

        for item in self.prompt_history:
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

            text_label = QLabel(item["prompt"])
            text_label.setObjectName("history-text")
            text_label.setWordWrap(True)

            vlayout.addWidget(time_label)
            vlayout.addWidget(text_label)

            insert_btn = QPushButton("Insert")
            insert_btn.setObjectName("insert-btn")
            insert_btn.setCursor(Qt.CursorShape.PointingHandCursor)
            insert_btn.clicked.connect(lambda checked, p=item["prompt"]: self._insert_history(p))

            hlayout.addWidget(text_container, stretch=1)
            hlayout.addWidget(insert_btn, alignment=Qt.AlignmentFlag.AlignVCenter)

            self.history_list_layout.insertWidget(self.history_list_layout.count() - 1, item_widget)

    def _insert_history(self, prompt):
        """Insert a history item into the prompt input."""
        self.prompt_input.clear()
        self.prompt_input.setPlainText(prompt)
        self.prompt_input.setFocus()

    def _set_status(self, text: str, status_type: str):
        """Update status label with styling."""
        self.status_label.setText(text)
        self.status_label.setProperty("status", status_type)
        self.status_label.style().unpolish(self.status_label)
        self.status_label.style().polish(self.status_label)

    def _run_agent(self):
        """Execute agent.py with configured parameters."""
        if self.agent_process is not None and self.agent_process.state() != QProcess.ProcessState.NotRunning:
            self._stop_agent()
            return

        prompt = self.prompt_input.toPlainText().strip()

        if not prompt:
            self._set_status("Please enter a prompt", "error")
            return

        now = QDateTime.currentDateTime()
        time_str = now.toString("yyyy-M-d  hh:mm")

        existing = next((i for i, h in enumerate(self.prompt_history) if h["prompt"] == prompt), -1)
        if existing >= 0:
            self.prompt_history.pop(existing)

        self.prompt_history.insert(0, {"time": time_str, "prompt": prompt})
        self.prompt_history = self.prompt_history[:self.max_history]
        self._refresh_history_list()

        cmd = ["python3", "agent.py"]

        if self.context_check.isChecked():
            cmd.append("--context")

        if self.think_check.isChecked():
            cmd.append("--think")

        checked_btn = self.model_button_group.checkedButton()
        if checked_btn:
            cmd.extend(["--model", checked_btn.text()])

        cmd.append(prompt)

        self._set_status("Running...", "running")
        self.run_btn.setText("Stop")

        script_dir = Path(__file__).parent
        self.agent_process = QProcess(self)
        self.agent_process.setProgram(cmd[0])
        self.agent_process.setArguments(cmd[1:])
        self.agent_process.setWorkingDirectory(str(script_dir))
        self.agent_process.setStandardOutputFile("/dev/null")
        self.agent_process.setStandardErrorFile("/dev/null")
        self.agent_process.finished.connect(self._on_process_finished)
        self.agent_process.start()

    def _stop_agent(self):
        """Stop the running agent process."""
        if self.agent_process is not None:
            self.agent_process.kill()
            self.agent_process.waitForFinished(3000)
            self._set_status("Stopped", "error")

    def _on_process_finished(self):
        """Handle process completion."""
        self.run_btn.setText("Run Agent")
        self._set_status("Agent finished", "success")
        self.agent_process = None


def main():
    """Application entry point."""
    app = QApplication(sys.argv)
    app.setStyle("Fusion")

    window = AgentLauncher()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
