import sys
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import Qt, QProcess, QDateTime, QTimer
from PyQt6.QtGui import QShortcut, QKeySequence
from PyQt6.QtWidgets import (
    QApplication,
    QMainWindow,
    QWidget,
    QHBoxLayout,
    QLabel,
    QVBoxLayout,
    QPushButton,
    QLineEdit,
    QSpinBox,
    QDoubleSpinBox,
    QButtonGroup,
    QTextEdit,
)

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from launcher.config import (
    WINDOW_WIDTH,
    WINDOW_HEIGHT,
    WINDOW_MIN_WIDTH,
    WINDOW_MIN_HEIGHT,
    MAX_HISTORY,
    MAX_PROMPT_CHARS,
    DEFAULT_TEMPERATURE,
    DEFAULT_TOP_P,
    DEFAULT_NUM_CTX,
    DEFAULT_NUM_PREDICT,
)
from launcher.i18n import (
    AppText,
    ButtonText,
    DialogText,
    ModelText,
    PlaceholderText,
    PreviewText,
)
from launcher.panels import CardCreator, MainLayoutManager
from launcher.services import (
    ModelFetcher,
    ModelHelper,
    OllamaService,
    ResourceMonitor,
    StyleManager,
)
from launcher.theme import Theme
from launcher.widgets import (
    ChatPanel,
    ConversationItem,
    ToastNotification,
)


class AgentLauncher(QMainWindow):
    request_list_layout: QVBoxLayout
    run_btn: QPushButton
    prompt_input: QTextEdit
    model_button_group: QButtonGroup
    temp_spin: QDoubleSpinBox
    top_p_spin: QDoubleSpinBox
    ctx_spin: QSpinBox
    predict_spin: QSpinBox
    local_settings_card: QWidget
    cpu_label: QLabel
    mem_label: QLabel
    latency_label: QLabel
    model_radio_layout: QVBoxLayout
    preview_text: QTextEdit
    preview_text_windows: QTextEdit
    context_check: QPushButton
    think_check: QPushButton
    timeout_check: QPushButton
    timeout_input: QLineEdit
    request_placeholder: Optional[QLabel]

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
        self.setWindowTitle(AppText.WINDOW_TITLE)
        self.setFixedSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        self.setMinimumSize(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)

        self.prompt_history: list[dict] = []
        self.agent_process: Optional[QProcess] = None

        self._layout_manager = MainLayoutManager(self)
        self._card_creator = CardCreator(self)
        self._ollama_service = OllamaService(self)
        self._model_fetcher = ModelFetcher(self)

        self._ollama_service.add_request_log_callback = self._add_request_log

        self.toast = ToastNotification(self, duration=4000)

        self.resource_timer = QTimer(self)
        self.resource_timer.timeout.connect(self._update_resource_info)
        self.resource_timer.start(1000)

        self._running_conv_item: Optional[ConversationItem] = None

        self._ollama_service.start()

    def _setup_ui(self) -> None:
        self.chat_panel = ChatPanel()

        left_widget = self._layout_manager.create_left_panel(self.chat_panel)
        center_widget = self._layout_manager.create_center_panel(self)
        right_widget = self._create_right_panel()

        central_widget = self._layout_manager.create_main_layout(left_widget, center_widget, right_widget)
        self.setCentralWidget(central_widget)

    def _create_right_panel(self) -> QWidget:
        return self._layout_manager.create_right_panel(self)

    def _create_options_card(self):
        return self._card_creator.create_options_card()

    def _create_models_card(self):
        return self._card_creator.create_models_card()

    def _create_local_settings_card(self):
        return self._card_creator.create_local_settings_card()

    def _create_request_panel(self):
        return self._card_creator.create_request_panel()

    def _create_preview_card(self):
        return self._card_creator.create_preview_card()

    def _copy_requests(self) -> None:
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
            if clipboard:
                clipboard.setText("\n".join(lines))

    def _clear_requests(self) -> None:
        while self.request_list_layout.count() > 0:
            item = self.request_list_layout.takeAt(0)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

        self.request_placeholder = QLabel(PlaceholderText.NO_AVAILABLE_REQUESTS)
        self.request_placeholder.setObjectName("request-placeholder")
        self.request_placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.request_list_layout.addWidget(self.request_placeholder)
        self.request_list_layout.addStretch()

    def _add_request_log(self, time: str, code: int, method: str, duration: str = "") -> None:
        if hasattr(self, 'request_placeholder') and self.request_placeholder:
            self.request_placeholder.deleteLater()
            self.request_placeholder = None

        if self.request_list_layout.count() > 0:
            item = self.request_list_layout.takeAt(self.request_list_layout.count() - 1)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

        row_container = QWidget()
        row_container.setStyleSheet("background-color: transparent;")
        row_layout = QHBoxLayout(row_container)
        row_layout.setContentsMargins(0, 0, 0, 0)
        row_layout.setSpacing(8)

        time_label = QLabel(time)
        time_label.setObjectName("request-time")
        time_label.setFixedWidth(70)
        time_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(time_label)

        code_container = QWidget()
        code_container.setStyleSheet("background-color: transparent;")
        code_container_layout = QHBoxLayout(code_container)
        code_container_layout.setContentsMargins(0, 0, 0, 0)
        code_container_layout.addStretch()

        code_widget = QWidget()
        code_widget.setFixedSize(36, 18)
        if code == 200:
            code_widget.setStyleSheet(f"background-color: {Theme.colors.success_bg}; color: {Theme.colors.success}; border-radius: 9px;")
        elif code >= 400:
            code_widget.setStyleSheet(f"background-color: {Theme.colors.danger_bg}; color: {Theme.colors.danger}; border-radius: 9px;")
        else:
            code_widget.setStyleSheet(f"background-color: {Theme.colors.info_bg}; color: {Theme.colors.info}; border-radius: 9px;")

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

        method_label = QLabel(method)
        method_label.setObjectName("request-method")
        method_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(method_label, stretch=1)

        duration_label = QLabel(duration)
        duration_label.setObjectName("request-duration")
        duration_label.setFixedWidth(60)
        duration_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row_layout.addWidget(duration_label)

        self.request_list_layout.addWidget(row_container)
        self.request_list_layout.addStretch()

        if self.request_list_layout.count() > 42:
            item = self.request_list_layout.takeAt(0)
            if item:
                widget = item.widget()
                if widget:
                    widget.deleteLater()

    def _apply_styles(self) -> None:
        css_path = Path(__file__).parent / "styles.txt"
        StyleManager.apply_styles(self, css_path)

    def _setup_shortcuts(self) -> None:
        run_shortcut = QShortcut(QKeySequence("Ctrl+Return"), self)
        run_shortcut.activated.connect(self._run_agent)

        close_shortcut = QShortcut(QKeySequence("Esc"), self)
        close_shortcut.activated.connect(self.close)

    def _update_run_button(self) -> None:
        if self.agent_process is not None:
            self.run_btn.setEnabled(True)
            return

        has_content = bool(self.prompt_input.toPlainText().strip())
        has_model = self.model_button_group.checkedButton() is not None
        char_count = len(self.prompt_input.toPlainText())
        within_limit = char_count <= MAX_PROMPT_CHARS
        self.run_btn.setEnabled(has_content and has_model and within_limit)

    def _update_local_settings_state(self) -> None:
        checked_btn = self.model_button_group.checkedButton()
        if not checked_btn:
            return

        model_name = checked_btn.property("originalName")
        is_cloud = "cloud" in model_name.lower()

        self.temp_spin.setEnabled(not is_cloud)
        self.top_p_spin.setEnabled(not is_cloud)
        self.ctx_spin.setEnabled(not is_cloud)
        self.predict_spin.setEnabled(not is_cloud)

        if is_cloud:
            self.local_settings_card.setStyleSheet("opacity: 0.5;")
        else:
            self.local_settings_card.setStyleSheet("")

    def _reset_local_settings(self) -> None:
        self.temp_spin.setValue(DEFAULT_TEMPERATURE)
        self.top_p_spin.setValue(DEFAULT_TOP_P)
        self.ctx_spin.setValue(DEFAULT_NUM_CTX)
        self.predict_spin.setValue(DEFAULT_NUM_PREDICT)

    def _clear_layout(self, layout) -> None:
        ModelHelper.clear_layout(layout)

    def _update_resource_info(self) -> None:
        cpu_text, mem_text = ResourceMonitor.get_resource_info()
        self.cpu_label.setText(cpu_text)
        self.mem_label.setText(mem_text)

        avg_latency = self._ollama_service.get_avg_latency_ms()
        latency_text = ResourceMonitor.format_latency(avg_latency)
        self.latency_label.setText(latency_text)

    def _fetch_models(self) -> None:
        self._model_fetcher.fetch_models(self._on_models_ready)

    def _on_models_ready(self, models=None, error=None) -> None:
        self._clear_layout(self.model_radio_layout)

        if error:
            self._show_model_error(error)
            return

        self._populate_models(models if models is not None else [])

    def _show_model_error(self, message: str) -> None:
        error_label = QLabel(message)
        error_label.setObjectName("model-fetching")
        self.model_radio_layout.addWidget(error_label)

    def _populate_models(self, models: list) -> None:
        local_models = [m for m in models if "cloud" not in m.get("name", "")]
        cloud_models = [m for m in models if "cloud" in m.get("name", "")]

        models_container = QWidget()
        models_layout = QVBoxLayout(models_container)
        models_layout.setContentsMargins(0, 0, 0, 0)
        models_layout.setSpacing(10)

        if cloud_models:
            self._add_model_section(models_layout, ModelText.CLOUD, cloud_models, 0)

        if local_models:
            start_id = len(cloud_models) if cloud_models else 0
            self._add_model_section(models_layout, ModelText.LOCAL, local_models, start_id)

        self.model_radio_layout.addWidget(models_container)

    def _add_model_section(
        self, layout: QVBoxLayout, title: str, models: list, start_id: int
    ) -> None:
        section = QWidget()
        section_layout = QVBoxLayout(section)
        section_layout.setContentsMargins(0, 0, 0, 0)
        section_layout.setSpacing(6)

        header = QLabel(title)
        header.setObjectName("model-section-header")
        section_layout.addWidget(header)

        for i, model in enumerate(models):
            model_name = model.get("name", "unknown")
            radio, widget = ModelHelper.create_model_radio(model_name)
            self.model_button_group.addButton(radio, start_id + i)
            radio.toggled.connect(self._update_run_button)
            radio.toggled.connect(self._update_preview)
            radio.toggled.connect(self._update_local_settings_state)
            section_layout.addWidget(widget)

            is_first_cloud_model = (title == ModelText.CLOUD and i == 0)
            is_first_local_model = (title == ModelText.LOCAL and start_id == 0 and i == 0)
            if is_first_cloud_model or is_first_local_model:
                radio.setChecked(True)

        section_layout.addStretch()
        layout.addWidget(section)

    def _update_preview(self) -> None:
        cmd_unix = self._build_command(is_windows=False)
        cmd_windows = self._build_command(is_windows=True)
        self.preview_text.setPlainText(cmd_unix)
        self.preview_text_windows.setPlainText(cmd_windows)

    def _build_command(self, is_windows: bool = False) -> str:
        if is_windows:
            parts = [PreviewText.PYTHON_CMD, PreviewText.AGENT_PY]
        else:
            parts = [sys.executable, PreviewText.AGENT_PY]

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
        text = self.timeout_input.text().strip()

        style = self.timeout_input.style()
        if not style:
            return

        if not text:
            self.timeout_input.setProperty("error", "false")
            style.unpolish(self.timeout_input)
            style.polish(self.timeout_input)
            return

        try:
            value = int(text)
            if value <= 0:
                raise ValueError("Timeout must be positive")

            self.timeout_input.setProperty("error", "false")
            style.unpolish(self.timeout_input)
            style.polish(self.timeout_input)
        except ValueError:
            self.timeout_input.setProperty("error", "true")
            style.unpolish(self.timeout_input)
            style.polish(self.timeout_input)

            self.toast.show_toast(DialogText.TIMEOUT_ERROR, self.timeout_input)

    def _on_timeout_editing_finished(self) -> None:
        text = self.timeout_input.text().strip()

        if not text:
            self.timeout_input.setText("120")

    def _copy_command(self) -> None:
        cmd_unix = self._build_command(is_windows=False)
        cmd_windows = self._build_command(is_windows=True)
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.setText(f"{cmd_unix}\n{cmd_windows}")

    def _refresh_history_list(self) -> None:
        pass

    def _add_history_item(self, item: dict) -> Optional[ConversationItem]:
        if hasattr(self, 'chat_panel'):
            return self.chat_panel.add_conversation_to_current_group(
                prompt=item.get("prompt", "")
            )
        return None

    def _run_agent(self) -> None:
        if self.agent_process and self.agent_process.state() != QProcess.ProcessState.NotRunning:
            self._stop_agent()
            return

        prompt = self.prompt_input.toPlainText().strip()
        if not prompt:
            return

        conv_item = self._add_to_history(prompt)
        self._start_agent_process(prompt, conv_item)

    def _add_to_history(self, prompt: str) -> Optional[ConversationItem]:
        now = QDateTime.currentDateTime()
        time_str = now.toString("yyyy-M-d  hh:mm")

        existing = next(
            (i for i, h in enumerate(self.prompt_history) if h["prompt"] == prompt), -1
        )
        if existing >= 0:
            self.prompt_history.pop(existing)

        self.prompt_history.insert(0, {"time": time_str, "prompt": prompt})
        self.prompt_history = self.prompt_history[:MAX_HISTORY]
        return self._add_history_item({"time": time_str, "prompt": prompt})

    def _start_agent_process(self, prompt: str, conv_item: Optional[ConversationItem] = None) -> None:
        cmd = [PreviewText.PYTHON3_CMD, PreviewText.AGENT_PY]

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

        self._running_conv_item = conv_item
        if self._running_conv_item:
            self._running_conv_item.set_loading(True)

        self.run_btn.setText(ButtonText.STOP)

        self.prompt_input.clear()

    def _stop_agent(self) -> None:
        if self.agent_process:
            self.agent_process.kill()
            self.agent_process.waitForFinished(3000)
        self.run_btn.setText(ButtonText.RUN)
        self._clear_running_state(show_complete=False)

    def _clear_running_state(self, show_complete: bool = False) -> None:
        if self._running_conv_item:
            if show_complete:
                self._running_conv_item.set_complete()
            else:
                self._running_conv_item.set_loading(False)
            self._running_conv_item = None

    def _on_process_finished(self) -> None:
        self.run_btn.setText(ButtonText.RUN)
        self.agent_process = None
        self._clear_running_state(show_complete=True)

    def closeEvent(self, a0) -> None:
        self._ollama_service.stop()
        if a0:
            a0.accept()


def run_app() -> None:
    app = QApplication(sys.argv)
    window = AgentLauncher()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    run_app()
