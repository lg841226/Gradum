from typing import Optional, Callable, Tuple, Any, Union

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QWidget,
    QPushButton,
    QLineEdit,
    QTextEdit,
    QSpinBox,
    QDoubleSpinBox,
    QLabel,
    QHBoxLayout,
    QVBoxLayout,
)

from launcher.theme.styles import (
    button_style,
    input_style,
    label_style,
    ButtonVariant,
    ButtonSize,
    InputType,
    LabelVariant,
)


def create_button(
    text: str,
    variant: str = ButtonVariant.PRIMARY,
    size: str = ButtonSize.MD,
    parent: Optional[QWidget] = None,
    on_click: Optional[Callable[[], Any]] = None,
    enabled: bool = True,
    fixed_height: Optional[int] = None,
) -> QPushButton:
    """
    Create a QPushButton instance with the specified variant, size, and parent.

    Args:
        text: Button text
        variant: Button variant, one of "primary", "text", "pill"
        size: Button size, one of "xs", "sm", "md"
        parent: Parent widget
        on_click: Click callback
        enabled: Whether to enable the button
        fixed_height: Fixed height in pixels

    Returns:
        QPushButton 实例

    Example:
        run_btn = create_button("Run", variant="primary", on_click=lambda: print("clicked"))
        refresh_btn = create_button("Refresh", variant="text", size="sm")
    """
    btn = QPushButton(text, parent)
    btn.setStyleSheet(button_style(variant=variant, size=size))
    btn.setCursor(Qt.CursorShape.PointingHandCursor)
    btn.setEnabled(enabled)

    if fixed_height:
        btn.setFixedHeight(fixed_height)

    if on_click:
        btn.clicked.connect(on_click)

    return btn


def create_checkbox(
    checked: bool = False,
    parent: Optional[QWidget] = None,
    on_toggle: Optional[Callable[[bool], Any]] = None,
    fixed_size: Optional[Tuple[int, int]] = None,
) -> QPushButton:
    """
    Create a checkbox QPushButton instance.

    Args:
        checked: Default checked state
        parent: Parent widget
        on_toggle: State change callback
        fixed_size: Fixed size (width, height)

    Returns:
        QPushButton instance with checkable=True

    Example:
        checkbox = create_checkbox(checked=False, on_toggle=lambda v: print(v))
    """
    checkbox = QPushButton("✓", parent)
    checkbox.setObjectName("option-check")
    checkbox.setStyleSheet(button_style(variant=ButtonVariant.CHECKBOX))
    checkbox.setCheckable(True)
    checkbox.setChecked(checked)
    checkbox.setCursor(Qt.CursorShape.PointingHandCursor)

    if fixed_size:
        checkbox.setFixedSize(fixed_size[0], fixed_size[1])
    else:
        checkbox.setFixedSize(16, 16)

    if on_toggle:
        checkbox.toggled.connect(on_toggle)

    return checkbox


def create_text_input(
    placeholder: str = "",
    text: str = "",
    parent: Optional[QWidget] = None,
    has_error: bool = False,
    on_text_changed: Optional[Callable[[str], Any]] = None,
    on_editing_finished: Optional[Callable[[], Any]] = None,
    enabled: bool = True,
    fixed_width: Optional[int] = None,
    fixed_height: Optional[int] = None,
    max_length: Optional[int] = None,
) -> QLineEdit:
    """
    Create a QLineEdit instance.

    Args:
        placeholder: Placeholder text
        text: Initial text
        parent: Parent widget
        has_error: Whether to show error state
        on_text_changed: Text change callback
        on_editing_finished: Editing finished callback
        enabled: Whether to enable the input
        fixed_width: Fixed width
        fixed_height: Fixed height
        max_length: Maximum length of the input text

    Returns:
        QLineEdit instance

    Example:
        input = create_text_input(placeholder="Search...", on_text_changed=lambda t: print(t))
    """
    input_field = QLineEdit(parent)
    input_field.setPlaceholderText(placeholder)
    input_field.setText(text)
    input_field.setStyleSheet(input_style(input_type=InputType.TEXT, has_error=has_error))
    input_field.setCursor(Qt.CursorShape.IBeamCursor)
    input_field.setEnabled(enabled)

    if fixed_width:
        input_field.setFixedWidth(fixed_width)
    if fixed_height:
        input_field.setFixedHeight(fixed_height)
    if max_length:
        input_field.setMaxLength(max_length)

    if on_text_changed:
        input_field.textChanged.connect(on_text_changed)
    if on_editing_finished:
        input_field.editingFinished.connect(on_editing_finished)

    return input_field


def create_spinbox(
    value: int = 0,
    min_value: int = 0,
    max_value: int = 999999,
    step: int = 1,
    parent: Optional[QWidget] = None,
    on_value_changed: Optional[Callable[[int], Any]] = None,
    enabled: bool = True,
    fixed_width: Optional[int] = None,
) -> QSpinBox:
    """
    Create a QSpinBox instance.

    Args:
        value: Default value
        min_value: Minimum value
        max_value: Maximum value
        step: Step value
        parent: Parent widget
        on_value_changed: Value change callback
        enabled: Whether to enable the spinbox
        fixed_width: Fixed width in pixels

    Returns:
        QSpinBox instance

    Example:
        ctx_spin = create_spinbox(value=8192, min_value=512, max_value=32768, step=512)
    """
    spin = QSpinBox(parent)
    spin.setStyleSheet(input_style(input_type=InputType.SPIN))
    spin.setRange(min_value, max_value)
    spin.setSingleStep(step)
    spin.setValue(value)
    spin.setEnabled(enabled)

    if fixed_width:
        spin.setFixedWidth(fixed_width)

    if on_value_changed:
        spin.valueChanged.connect(on_value_changed)

    return spin


def create_double_spinbox(
    value: float = 0.0,
    min_value: float = 0.0,
    max_value: float = 1.0,
    step: float = 0.1,
    decimals: int = 1,
    parent: Optional[QWidget] = None,
    on_value_changed: Optional[Callable[[float], Any]] = None,
    enabled: bool = True,
    fixed_width: Optional[int] = None,
) -> QDoubleSpinBox:
    """
    Create a QDoubleSpinBox instance.

    Args:
        value: Default value
        min_value: Minimum value
        max_value: Maximum value
        step: Step value
        decimals: Decimal value
        parent: Parent widget
        on_value_changed: Value change callback
        enabled: Whether to enable the spinbox
        fixed_width: Fixed width in pixels

    Returns:
        QDoubleSpinBox instance

    Example:
        temp_spin = create_double_spinbox(value=0.7, min_value=0.0, max_value=2.0, step=0.1)
    """
    spin = QDoubleSpinBox(parent)
    spin.setStyleSheet(input_style(input_type=InputType.SPIN))
    spin.setRange(min_value, max_value)
    spin.setSingleStep(step)
    spin.setDecimals(decimals)
    spin.setValue(value)
    spin.setEnabled(enabled)
    if fixed_width:
        spin.setFixedWidth(fixed_width)
    if on_value_changed:
        spin.valueChanged.connect(on_value_changed)
    return spin


def create_text_edit(
    placeholder: str = "",
    text: str = "",
    parent: Optional[QWidget] = None,
    is_prompt: bool = False,
    read_only: bool = False,
    fixed_height: Optional[int] = None,
    on_text_changed: Optional[Callable[[], Any]] = None,
    accept_rich_text: bool = False,
    line_wrap_mode: Optional[QTextEdit.LineWrapMode] = None,
) -> QTextEdit:
    """
    Create a QTextEdit instance.

    Args:
        placeholder: Placeholder text
        text: Initial text
        parent: Parent widget
        is_prompt: Whether to use prompt input style (no border)
        read_only: Whether to make read-only
        fixed_height: Fixed height in pixels
        on_text_changed: Text change callback
        accept_rich_text: Whether to accept rich text
        line_wrap_mode: Line wrap mode

    Returns:
        QTextEdit instance

    Example:
        prompt_input = create_text_edit(placeholder="Enter your prompt...", is_prompt=True)
    """
    text_edit = QTextEdit(parent)
    text_edit.setPlaceholderText(placeholder)
    text_edit.setText(text)
    text_edit.setStyleSheet(input_style(input_type=InputType.PROMPT if is_prompt else InputType.TEXT))
    text_edit.setReadOnly(read_only)
    text_edit.setAcceptRichText(accept_rich_text)

    if fixed_height:
        text_edit.setFixedHeight(fixed_height)
    if line_wrap_mode:
        text_edit.setLineWrapMode(line_wrap_mode)
    if on_text_changed:
        text_edit.textChanged.connect(on_text_changed)

    return text_edit


def create_label(
    text: str,
    variant: str = LabelVariant.BODY,
    parent: Optional[QWidget] = None,
    alignment: Optional[Qt.AlignmentFlag] = None,
    fixed_width: Optional[int] = None,
) -> QLabel:
    """
    Create a label widget.

    Args:
        text: Label text
        variant: Style variant: "body", "title", "caption", "secondary", "mono"
        parent: Parent widget
        alignment: Alignment flag
        fixed_width: Fixed width in pixels

    Returns:
        QLabel instance

    Example:
        title = create_label("Temperature", variant="title")
        desc = create_label("Controls randomness", variant="caption")
    """
    label = QLabel(text, parent)
    label.setStyleSheet(label_style(variant=variant))

    if alignment:
        label.setAlignment(alignment)
    if fixed_width:
        label.setFixedWidth(fixed_width)

    return label


def create_option_row(
    title: str,
    description: str = "",
    parent: Optional[QWidget] = None,
    on_checkbox_toggled: Optional[Callable[[bool], Any]] = None,
    checkbox_checked: bool = False,
    ) -> Tuple[QPushButton, QWidget]:
    """
    Create an option row (checkbox + title + description).

    Args:
        title: Option title
        description: Option description
        parent: Parent widget
        on_checkbox_toggled: Checkbox state change callback
        checkbox_checked: Checkbox default state
    Returns:
        (checkbox, container_widget) tuple

    Example:
        checkbox, row = create_option_row(
            "Context Memory",
            "Enable context memory for multi-turn conversations",
            on_checkbox_toggled=lambda v: print(v)
        )
    """
    container = QWidget(parent)
    layout = QHBoxLayout(container)
    layout.setContentsMargins(0, 0, 0, 0)
    layout.setSpacing(8)
    layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

    checkbox = create_checkbox(
        checked=checkbox_checked,
        on_toggle=on_checkbox_toggled
    )
    layout.addWidget(checkbox, alignment=Qt.AlignmentFlag.AlignVCenter)

    text_container = QWidget()
    text_layout = QVBoxLayout(text_container)
    text_layout.setContentsMargins(0, 0, 0, 0)
    text_layout.setSpacing(1)

    title_label = create_label(title, variant=LabelVariant.SECONDARY)
    text_layout.addWidget(title_label)

    if description:
        desc_label = create_label(description, variant=LabelVariant.CAPTION)
        text_layout.addWidget(desc_label)

    layout.addWidget(text_container, stretch=1, alignment=Qt.AlignmentFlag.AlignVCenter)

    return checkbox, container


def create_option_row_with_input(
    title: str,
    description: str = "",
    default_input_value: str = "",
    parent: Optional[QWidget] = None,
    on_checkbox_toggled: Optional[Callable[[bool], Any]] = None,
    on_text_changed: Optional[Callable[[str], Any]] = None,
    on_editing_finished: Optional[Callable[[], Any]] = None,
    checkbox_checked: bool = False,
    input_enabled: bool = False,
    input_fixed_width: Optional[int] = None,
) -> Tuple[QPushButton, QLineEdit, QWidget]:
    """
    Create an option row (checkbox + title + description + input field).

    Args:
        title: Option title
        description: Option description
        default_input_value: Default input value
        parent: Parent widget
        on_checkbox_toggled: Checkbox state change callback
        on_text_changed: Input field change callback
        on_editing_finished: Input field editing finished callback
        checkbox_checked: Checkbox default state
        input_enabled: Input field enabled state
        input_fixed_width: Fixed width in pixels

    Returns:
        (checkbox, input_field, container_widget) tuple

    Example:
        checkbox, input_field, row = create_option_row_with_input(
            "Timeout",
            "Request timeout in seconds",
            default_input_value="120"
        )
    """
    container = QWidget(parent)
    layout = QHBoxLayout(container)
    layout.setContentsMargins(0, 0, 0, 0)
    layout.setSpacing(8)
    layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

    checkbox = create_checkbox(
        checked=checkbox_checked,
        on_toggle=on_checkbox_toggled
    )
    layout.addWidget(checkbox, alignment=Qt.AlignmentFlag.AlignVCenter)

    text_container = QWidget()
    text_layout = QVBoxLayout(text_container)
    text_layout.setContentsMargins(0, 0, 0, 0)
    text_layout.setSpacing(1)

    title_label = create_label(title, variant=LabelVariant.SECONDARY)
    text_layout.addWidget(title_label)

    if description:
        desc_label = create_label(description, variant=LabelVariant.CAPTION)
        text_layout.addWidget(desc_label)

    layout.addWidget(text_container, stretch=1, alignment=Qt.AlignmentFlag.AlignVCenter)

    input_field = create_text_input(
        text=default_input_value,
        on_text_changed=on_text_changed,
        on_editing_finished=on_editing_finished,
        enabled=input_enabled,
        fixed_width=input_fixed_width
    )

    checkbox.toggled.connect(lambda checked: input_field.setEnabled(checked))

    layout.addWidget(input_field, alignment=Qt.AlignmentFlag.AlignVCenter)

    return checkbox, input_field, container
