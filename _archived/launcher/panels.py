from pathlib import Path
from typing import Tuple

from PyQt6.QtCore import Qt
from PyQt6.QtSvgWidgets import QSvgWidget
from PyQt6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QTextEdit,
    QLineEdit,
    QSplitter,
    QSpinBox,
    QDoubleSpinBox,
    QButtonGroup,
)

from launcher.config import (
    PROMPT_INPUT_HEIGHT,
    LEFT_PANEL_MIN_WIDTH,
    LEFT_PANEL_MAX_WIDTH,
    CENTER_PANEL_MIN_WIDTH,
    CENTER_PANEL_MAX_WIDTH,
    RIGHT_PANEL_MIN_WIDTH,
    RIGHT_PANEL_MAX_WIDTH,
    LEFT_PANEL_WIDTH,
    RIGHT_PANEL_WIDTH,
    MAIN_MARGIN_LEFT,
    MAIN_MARGIN_TOP,
    MAIN_MARGIN_RIGHT,
    MAIN_MARGIN_BOTTOM,
    CARD_SPACING,
    OPTION_SPACING,
    DEFAULT_TEMPERATURE,
    DEFAULT_TOP_P,
    DEFAULT_NUM_CTX,
    DEFAULT_NUM_PREDICT,
)
from launcher.i18n import (
    ButtonText,
    CardTitle,
    LabelText,
    PlaceholderText,
    RequestText,
    PreviewText,
)
from launcher.services import ModelHelper
from launcher.widgets import (
    AutoHideScrollArea,
    AutoHideTextEdit,
    CollapsibleCard,
    BashSyntaxHighlighter,
    ChatPanel,
)
from launcher.components import (
    create_button,
    create_checkbox,
    create_text_input,
    create_spinbox,
    create_double_spinbox,
    create_label,
    create_option_row,
    create_option_row_with_input,
)
from launcher.theme import Theme


class MainLayoutManager:
    def __init__(self, parent: QWidget):
        self.parent = parent
        self._icons_dir = Path(__file__).parent / "icons"

    def create_main_layout(self, left_widget: QWidget, center_widget: QWidget, right_widget: QWidget) -> QWidget:
        central = QWidget()
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(MAIN_MARGIN_LEFT, MAIN_MARGIN_TOP, MAIN_MARGIN_RIGHT, MAIN_MARGIN_BOTTOM)
        main_layout.setSpacing(0)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setHandleWidth(3)
        splitter.setStyleSheet("""
            QSplitter::handle {
                background-color: transparent;
            }
            QSplitter::handle:hover {
                background-color: #000000;
            }
            QSplitter::handle:pressed {
                background-color: #000000;
            }
        """)

        left_widget.setObjectName("left-panel")
        right_widget.setObjectName("right-panel")

        left_widget.setMinimumWidth(LEFT_PANEL_MIN_WIDTH)
        left_widget.setMaximumWidth(LEFT_PANEL_MAX_WIDTH)
        center_widget.setMinimumWidth(CENTER_PANEL_MIN_WIDTH)
        center_widget.setMaximumWidth(CENTER_PANEL_MAX_WIDTH)
        right_widget.setMinimumWidth(RIGHT_PANEL_MIN_WIDTH)
        right_widget.setMaximumWidth(RIGHT_PANEL_MAX_WIDTH)

        splitter.addWidget(left_widget)
        splitter.addWidget(center_widget)
        splitter.addWidget(right_widget)
        splitter.setSizes([LEFT_PANEL_WIDTH, 600, RIGHT_PANEL_WIDTH])

        main_layout.addWidget(splitter)
        return central

    def create_left_panel(self, chat_panel: ChatPanel) -> QWidget:
        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.setSpacing(0)
        left_layout.addWidget(chat_panel)
        return left_widget

    def create_center_panel(self, main_window) -> QWidget:
        center_widget = QWidget()
        center_layout = QVBoxLayout(center_widget)
        center_layout.setSpacing(0)
        center_layout.addStretch(1)
        center_layout.setContentsMargins(40, 0, 40, 0)

        input_container = QWidget()
        input_container.setObjectName("input-container")
        input_layout = QVBoxLayout(input_container)
        input_layout.setContentsMargins(16, 12, 16, 36)
        input_layout.setSpacing(8)

        self._create_prompt_input(input_layout, main_window)
        self._create_toolbar(input_layout, main_window)

        center_layout.addWidget(input_container, 0)
        return center_widget

    def _create_prompt_input(self, layout: QVBoxLayout, main_window) -> None:
        prompt_input = QTextEdit()
        prompt_input.setObjectName("prompt-input-inner")
        prompt_input.setPlaceholderText(PlaceholderText.PROMPT_INPUT)
        prompt_input.setFixedHeight(PROMPT_INPUT_HEIGHT)
        prompt_input.setAcceptRichText(False)
        prompt_input.textChanged.connect(main_window._update_run_button)
        prompt_input.textChanged.connect(main_window._update_preview)
        layout.addWidget(prompt_input)
        main_window.prompt_input = prompt_input

    def _create_toolbar(self, layout: QVBoxLayout, main_window) -> None:
        toolbar = QWidget()
        toolbar_layout = QHBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(0, 0, 0, 0)
        toolbar_layout.setSpacing(12)

        self._create_cpu_info(toolbar_layout, main_window)
        self._create_mem_info(toolbar_layout, main_window)
        self._create_latency_info(toolbar_layout, main_window)
        toolbar_layout.addStretch()
        self._create_run_button(toolbar_layout, main_window)

        layout.addWidget(toolbar)

    def _create_cpu_info(self, layout: QHBoxLayout, main_window) -> None:
        cpu_container = QWidget()
        cpu_container.setObjectName("resource-info")
        cpu_layout = QHBoxLayout(cpu_container)
        cpu_layout.setContentsMargins(0, 0, 0, 0)
        cpu_layout.setSpacing(4)

        cpu_icon_path = self._icons_dir / "cpu.svg"
        if cpu_icon_path.exists():
            cpu_svg_widget = QSvgWidget(str(cpu_icon_path))
            cpu_svg_widget.setFixedSize(16, 16)
            cpu_svg_widget.setStyleSheet(f"color: {Theme.colors.text_disabled};")
            cpu_layout.addWidget(cpu_svg_widget)

        cpu_label = QLabel(RequestText.CPU_LABEL)
        cpu_label.setObjectName("resource-label")
        cpu_layout.addWidget(cpu_label)
        layout.addWidget(cpu_container)
        main_window.cpu_label = cpu_label

    def _create_mem_info(self, layout: QHBoxLayout, main_window) -> None:
        mem_container = QWidget()
        mem_container.setObjectName("resource-info")
        mem_layout = QHBoxLayout(mem_container)
        mem_layout.setContentsMargins(0, 0, 0, 0)
        mem_layout.setSpacing(4)

        mem_icon_path = self._icons_dir / "memory.svg"
        if mem_icon_path.exists():
            mem_svg_widget = QSvgWidget(str(mem_icon_path))
            mem_svg_widget.setFixedSize(16, 16)
            mem_svg_widget.setStyleSheet(f"color: {Theme.colors.text_disabled};")
            mem_layout.addWidget(mem_svg_widget)

        mem_label = QLabel(RequestText.MEM_LABEL_MB)
        mem_label.setObjectName("resource-label")
        mem_layout.addWidget(mem_label)
        layout.addWidget(mem_container)
        main_window.mem_label = mem_label

    def _create_latency_info(self, layout: QHBoxLayout, main_window) -> None:
        latency_container = QWidget()
        latency_container.setObjectName("resource-info")
        latency_layout = QHBoxLayout(latency_container)
        latency_layout.setContentsMargins(0, 0, 0, 0)
        latency_layout.setSpacing(4)

        latency_icon_path = self._icons_dir / "activity.svg"
        if latency_icon_path.exists():
            latency_svg_widget = QSvgWidget(str(latency_icon_path))
            latency_svg_widget.setFixedSize(16, 16)
            latency_svg_widget.setStyleSheet(f"color: {Theme.colors.text_disabled};")
            latency_layout.addWidget(latency_svg_widget)

        latency_label = QLabel(RequestText.LATENCY_LABEL)
        latency_label.setObjectName("resource-label")
        latency_layout.addWidget(latency_label)
        layout.addWidget(latency_container)
        main_window.latency_label = latency_label

    def _create_run_button(self, layout: QHBoxLayout, main_window) -> None:
        run_btn = QPushButton(ButtonText.RUN)
        run_btn.setObjectName("run-btn")
        run_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        run_btn.setFixedHeight(32)
        run_btn.clicked.connect(main_window._run_agent)
        layout.addWidget(run_btn)
        main_window.run_btn = run_btn

    def create_right_panel(self, main_window) -> QWidget:
        right_widget = QWidget()
        right_layout = QVBoxLayout(right_widget)
        right_layout.setContentsMargins(0, 0, 0, 0)
        right_layout.setSpacing(0)

        scroll = AutoHideScrollArea()
        scroll.setObjectName("right-panel-scroll")
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        scroll_content = QWidget()
        scroll_content.setObjectName("right-panel-content")
        scroll_content_layout = QVBoxLayout(scroll_content)
        scroll_content_layout.setContentsMargins(0, 0, 8, 0)
        scroll_content_layout.setSpacing(0)

        scroll_content_layout.addWidget(main_window._create_models_card())
        scroll_content_layout.addSpacing(CARD_SPACING)
        scroll_content_layout.addWidget(ModelHelper.create_separator())
        scroll_content_layout.addWidget(main_window._create_options_card())
        scroll_content_layout.addSpacing(CARD_SPACING)
        scroll_content_layout.addWidget(ModelHelper.create_separator())
        scroll_content_layout.addWidget(main_window._create_local_settings_card())
        scroll_content_layout.addSpacing(CARD_SPACING)
        scroll_content_layout.addWidget(ModelHelper.create_separator())
        scroll_content_layout.addWidget(main_window._create_request_panel())
        scroll_content_layout.addSpacing(CARD_SPACING)
        scroll_content_layout.addWidget(ModelHelper.create_separator())
        scroll_content_layout.addWidget(main_window._create_preview_card())
        scroll_content_layout.addStretch()

        scroll.setWidget(scroll_content)
        right_layout.addWidget(scroll)
        return right_widget


class CardCreator:
    def __init__(self, main_window):
        self.main_window = main_window

    def create_options_card(self) -> CollapsibleCard:
        options_card = CollapsibleCard(CardTitle.OPTIONS, self.main_window, icon_name="options.svg")
        content = options_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        context_check, context_row = create_option_row(
            LabelText.CONTEXT_MEMORY, LabelText.CONTEXT_MEMORY_DESC
        )
        context_check.toggled.connect(self.main_window._update_preview)
        content.addWidget(context_row)

        think_check, think_row = create_option_row(
            LabelText.THINK_MODE, LabelText.THINK_MODE_DESC
        )
        think_check.toggled.connect(self.main_window._update_preview)
        content.addWidget(think_row)

        timeout_check, timeout_input, timeout_row = create_option_row_with_input(
            LabelText.TIMEOUT, LabelText.TIMEOUT_DESC, "120"
        )
        timeout_check.toggled.connect(self.main_window._update_preview)
        timeout_input.textChanged.connect(self.main_window._update_preview)
        timeout_input.textChanged.connect(self.main_window._validate_timeout_input)
        timeout_input.editingFinished.connect(self.main_window._on_timeout_editing_finished)
        content.addWidget(timeout_row)

        self.main_window.options_card = options_card
        self.main_window.context_check = context_check
        self.main_window.think_check = think_check
        self.main_window.timeout_check = timeout_check
        self.main_window.timeout_input = timeout_input

        return options_card

    def create_models_card(self) -> CollapsibleCard:
        refresh_btn = QPushButton(ButtonText.REFRESH)
        refresh_btn.setObjectName("text-btn")
        refresh_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        refresh_btn.clicked.connect(self.main_window._fetch_models)

        model_card = CollapsibleCard(CardTitle.MODELS, self.main_window, refresh_btn, icon_name="model.svg")
        content = model_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        model_button_group = QButtonGroup(self.main_window)
        model_radio_container = QWidget()
        model_radio_layout = QVBoxLayout(model_radio_container)
        model_radio_layout.setContentsMargins(0, 0, 0, 0)
        model_radio_layout.setSpacing(10)

        fetching_label = QLabel(PlaceholderText.FETCHING_MODELS)
        fetching_label.setObjectName("model-fetching")
        model_radio_layout.addWidget(fetching_label)

        content.addWidget(model_radio_container)

        self.main_window.model_card = model_card
        self.main_window.refresh_btn = refresh_btn
        self.main_window.model_button_group = model_button_group
        self.main_window.model_radio_container = model_radio_container
        self.main_window.model_radio_layout = model_radio_layout

        return model_card

    def create_local_settings_card(self) -> CollapsibleCard:
        reset_btn = QPushButton(ButtonText.RESET)
        reset_btn.setObjectName("text-btn")
        reset_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        reset_btn.clicked.connect(self.main_window._reset_local_settings)

        local_settings_card = CollapsibleCard(CardTitle.LOCAL_SETTINGS, self.main_window, reset_btn, icon_name="settings.svg")
        content = local_settings_card.content_layout()
        content.setSpacing(OPTION_SPACING)

        self._create_temperature_row(content)
        self._create_top_p_row(content)
        self._create_context_window_row(content)
        self._create_max_tokens_row(content)

        self.main_window.local_settings_card = local_settings_card
        self.main_window.refresh_btn = reset_btn

        return local_settings_card

    def _create_temperature_row(self, content: QVBoxLayout) -> None:
        temp_container = QWidget()
        temp_layout = QHBoxLayout(temp_container)
        temp_layout.setContentsMargins(0, 0, 0, 0)
        temp_layout.setSpacing(8)

        temp_text_container = QWidget()
        temp_text_layout = QVBoxLayout(temp_text_container)
        temp_text_layout.setContentsMargins(0, 0, 0, 0)
        temp_text_layout.setSpacing(2)

        temp_label = QLabel(LabelText.TEMPERATURE)
        temp_label.setObjectName("option-title")
        temp_text_layout.addWidget(temp_label)

        temp_desc = QLabel(LabelText.TEMPERATURE_DESC)
        temp_desc.setObjectName("option-desc")
        temp_text_layout.addWidget(temp_desc)

        temp_layout.addWidget(temp_text_container, stretch=1)

        temp_spin = QDoubleSpinBox()
        temp_spin.setObjectName("option-spin")
        temp_spin.setRange(0.0, 2.0)
        temp_spin.setSingleStep(0.1)
        temp_spin.setValue(DEFAULT_TEMPERATURE)
        temp_spin.setFixedWidth(70)
        temp_spin.valueChanged.connect(self.main_window._update_preview)
        temp_layout.addWidget(temp_spin)

        content.addWidget(temp_container)
        self.main_window.temp_spin = temp_spin

    def _create_top_p_row(self, content: QVBoxLayout) -> None:
        top_p_container = QWidget()
        top_p_layout = QHBoxLayout(top_p_container)
        top_p_layout.setContentsMargins(0, 0, 0, 0)
        top_p_layout.setSpacing(8)

        top_p_text_container = QWidget()
        top_p_text_layout = QVBoxLayout(top_p_text_container)
        top_p_text_layout.setContentsMargins(0, 0, 0, 0)
        top_p_text_layout.setSpacing(1)

        top_p_label = QLabel(LabelText.TOP_P)
        top_p_label.setObjectName("option-title")
        top_p_text_layout.addWidget(top_p_label)

        top_p_desc = QLabel(LabelText.TOP_P_DESC)
        top_p_desc.setObjectName("option-desc")
        top_p_text_layout.addWidget(top_p_desc)

        top_p_layout.addWidget(top_p_text_container, stretch=1)

        top_p_spin = QDoubleSpinBox()
        top_p_spin.setObjectName("option-spin")
        top_p_spin.setRange(0.0, 1.0)
        top_p_spin.setSingleStep(0.1)
        top_p_spin.setValue(DEFAULT_TOP_P)
        top_p_spin.setFixedWidth(70)
        top_p_spin.valueChanged.connect(self.main_window._update_preview)
        top_p_layout.addWidget(top_p_spin)

        content.addWidget(top_p_container)
        self.main_window.top_p_spin = top_p_spin

    def _create_context_window_row(self, content: QVBoxLayout) -> None:
        ctx_container = QWidget()
        ctx_layout = QHBoxLayout(ctx_container)
        ctx_layout.setContentsMargins(0, 0, 0, 0)
        ctx_layout.setSpacing(8)

        ctx_text_container = QWidget()
        ctx_text_layout = QVBoxLayout(ctx_text_container)
        ctx_text_layout.setContentsMargins(0, 0, 0, 0)
        ctx_text_layout.setSpacing(1)

        ctx_label = QLabel(LabelText.CONTEXT_WINDOW)
        ctx_label.setObjectName("option-title")
        ctx_text_layout.addWidget(ctx_label)

        ctx_desc = QLabel(LabelText.CONTEXT_WINDOW_DESC)
        ctx_desc.setObjectName("option-desc")
        ctx_text_layout.addWidget(ctx_desc)

        ctx_layout.addWidget(ctx_text_container, stretch=1)

        ctx_spin = QSpinBox()
        ctx_spin.setObjectName("option-spin")
        ctx_spin.setRange(512, 32768)
        ctx_spin.setSingleStep(512)
        ctx_spin.setValue(DEFAULT_NUM_CTX)
        ctx_spin.setFixedWidth(70)
        ctx_spin.valueChanged.connect(self.main_window._update_preview)
        ctx_layout.addWidget(ctx_spin)

        content.addWidget(ctx_container)
        self.main_window.ctx_spin = ctx_spin

    def _create_max_tokens_row(self, content: QVBoxLayout) -> None:
        predict_container = QWidget()
        predict_layout = QHBoxLayout(predict_container)
        predict_layout.setContentsMargins(0, 0, 0, 0)
        predict_layout.setSpacing(8)

        predict_text_container = QWidget()
        predict_text_layout = QVBoxLayout(predict_text_container)
        predict_text_layout.setContentsMargins(0, 0, 0, 0)
        predict_text_layout.setSpacing(1)

        predict_label = QLabel(LabelText.MAX_TOKENS)
        predict_label.setObjectName("option-title")
        predict_text_layout.addWidget(predict_label)

        predict_desc = QLabel(LabelText.MAX_TOKENS_DESC)
        predict_desc.setObjectName("option-desc")
        predict_text_layout.addWidget(predict_desc)

        predict_layout.addWidget(predict_text_container, stretch=1)

        predict_spin = QSpinBox()
        predict_spin.setObjectName("option-spin")
        predict_spin.setRange(128, 8192)
        predict_spin.setSingleStep(128)
        predict_spin.setValue(DEFAULT_NUM_PREDICT)
        predict_spin.setFixedWidth(70)
        predict_spin.valueChanged.connect(self.main_window._update_preview)
        predict_layout.addWidget(predict_spin)

        content.addWidget(predict_container)
        self.main_window.predict_spin = predict_spin

    def create_request_panel(self) -> CollapsibleCard:
        button_container = QWidget()
        button_container.setStyleSheet("background-color: transparent;")
        button_layout = QHBoxLayout(button_container)
        button_layout.setContentsMargins(0, 0, 0, 0)
        button_layout.setSpacing(8)

        copy_requests_btn = QPushButton(ButtonText.COPY)
        copy_requests_btn.setObjectName("text-btn")
        copy_requests_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        copy_requests_btn.clicked.connect(self.main_window._copy_requests)
        button_layout.addWidget(copy_requests_btn)

        clear_requests_btn = QPushButton(ButtonText.CLEAR)
        clear_requests_btn.setObjectName("text-btn")
        clear_requests_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        clear_requests_btn.clicked.connect(self.main_window._clear_requests)
        button_layout.addWidget(clear_requests_btn)

        request_card = CollapsibleCard(CardTitle.REQUESTS, self.main_window, button_container, icon_name="requests.svg")
        content = request_card.content_layout()

        self._create_request_header(content)
        content.addSpacing(8)
        self._create_request_list(content)

        self.main_window.request_card = request_card
        self.main_window.copy_requests_btn = copy_requests_btn
        self.main_window.clear_requests_btn = clear_requests_btn

        return request_card

    def _create_request_header(self, content: QVBoxLayout) -> None:
        header_container = QWidget()
        header_layout = QHBoxLayout(header_container)
        header_layout.setContentsMargins(0, 0, 0, 0)
        header_layout.setSpacing(8)

        time_header = QLabel(LabelText.TIME_HEADER)
        time_header.setObjectName("request-header")
        time_header.setFixedWidth(70)
        time_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(time_header)

        code_header = QLabel(LabelText.CODE_HEADER)
        code_header.setObjectName("request-header")
        code_header.setFixedWidth(60)
        code_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(code_header)

        method_header = QLabel(LabelText.METHOD_HEADER)
        method_header.setObjectName("request-header")
        method_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(method_header, stretch=1)

        duration_header = QLabel(LabelText.DURATION_HEADER)
        duration_header.setObjectName("request-header")
        duration_header.setFixedWidth(60)
        duration_header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        header_layout.addWidget(duration_header)

        content.addWidget(header_container)

    def _create_request_list(self, content: QVBoxLayout) -> None:
        request_scroll = AutoHideScrollArea()
        request_scroll.setObjectName("request-scroll")
        request_scroll.setWidgetResizable(True)
        request_scroll.setMaximumHeight(200)
        viewport = request_scroll.viewport()
        if viewport:
            viewport.setStyleSheet("background-color: transparent;")

        request_list_container = QWidget()
        request_list_container.setStyleSheet("background-color: transparent;")
        request_list_layout = QVBoxLayout(request_list_container)
        request_list_layout.setContentsMargins(0, 0, 0, 0)
        request_list_layout.setSpacing(8)

        request_placeholder = QLabel(PlaceholderText.NO_REQUESTS)
        request_placeholder.setObjectName("request-placeholder")
        request_placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
        request_list_layout.addWidget(request_placeholder)
        request_list_layout.addStretch()

        request_scroll.setWidget(request_list_container)
        content.addWidget(request_scroll)

        self.main_window.request_scroll = request_scroll
        self.main_window.request_list_container = request_list_container
        self.main_window.request_list_layout = request_list_layout
        self.main_window.request_placeholder = request_placeholder

    def create_preview_card(self) -> CollapsibleCard:
        copy_btn = QPushButton(ButtonText.COPY)
        copy_btn.setObjectName("text-btn")
        copy_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        copy_btn.clicked.connect(self.main_window._copy_command)

        preview_card = CollapsibleCard(CardTitle.PREVIEW, self.main_window, copy_btn, icon_name="prompt.svg")
        content = preview_card.content_layout()

        unix_label = QLabel(LabelText.UNIX_MACOS)
        unix_label.setObjectName("preview-label")
        content.addWidget(unix_label)
        content.addSpacing(4)

        preview_text = AutoHideTextEdit()
        preview_text.setObjectName("preview-text")
        preview_text.setReadOnly(True)
        preview_text.setMinHeight(40)
        preview_text.setLineWrapMode(QTextEdit.LineWrapMode.NoWrap)
        highlighter = BashSyntaxHighlighter(preview_text.document())
        content.addWidget(preview_text)
        content.addSpacing(12)

        windows_label = QLabel(LabelText.WINDOWS)
        windows_label.setObjectName("preview-label")
        content.addWidget(windows_label)
        content.addSpacing(4)

        preview_text_windows = AutoHideTextEdit()
        preview_text_windows.setObjectName("preview-text")
        preview_text_windows.setReadOnly(True)
        preview_text_windows.setMinHeight(40)
        preview_text_windows.setLineWrapMode(QTextEdit.LineWrapMode.NoWrap)
        highlighter_windows = BashSyntaxHighlighter(preview_text_windows.document())
        content.addWidget(preview_text_windows)

        self.main_window.preview_card = preview_card
        self.main_window.copy_btn = copy_btn
        self.main_window.preview_text = preview_text
        self.main_window.highlighter = highlighter
        self.main_window.preview_text_windows = preview_text_windows
        self.main_window.highlighter_windows = highlighter_windows

        return preview_card
