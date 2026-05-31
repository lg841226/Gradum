import json
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import Qt, QDateTime, pyqtSignal, QTimer
from PyQt6.QtGui import QIcon
from PyQt6.QtSvgWidgets import QSvgWidget
from PyQt6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QMenu,
    QDialog,
    QLineEdit,
    QDialogButtonBox,
    QMessageBox,
    QSizePolicy,
)

from launcher.widgets.custom_widgets import AutoHideScrollArea, RotatingLoaderIcon


class DeleteButton(QWidget):
    """Custom delete button with SVG icon and text."""

    clicked = pyqtSignal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

        self.setStyleSheet("""
            QWidget {
                background-color: transparent;
                border: none;
            }
        """)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        icons_dir = Path(__file__).parent.parent / "icons"
        self.icon = QSvgWidget(str(icons_dir / "list-x.svg"))
        self.icon.setFixedSize(14, 14)
        layout.addWidget(self.icon)

        self.label = QLabel("Delete")
        self.label.setStyleSheet("color: #666666; font-size: 12px; font-weight: 500;")
        layout.addWidget(self.label)

    def mousePressEvent(self, a0) -> None:
        if a0 and a0.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(a0)


class ConversationItem(QWidget):
    """Single conversation item widget with custom styling."""

    clicked = pyqtSignal()
    double_clicked = pyqtSignal()
    context_menu_requested = pyqtSignal(object)
    checkbox_state_changed = pyqtSignal(bool)

    def __init__(self, title: str, conv_id: str, group_id: str, prompt: str = "", parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.conv_id = conv_id
        self.group_id = group_id
        self.prompt = prompt
        self.setFixedHeight(32)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(lambda pos: self.context_menu_requested.emit(pos))
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self._in_selection_mode = False
        self._is_loading = False
        self._is_complete = False
        self._icons_dir = Path(__file__).parent.parent / "icons"
        self._original_title = title

        layout = QHBoxLayout(self)
        layout.setContentsMargins(48, 0, 16, 0)
        layout.setSpacing(8)

        self._icon_container = QWidget()
        self._icon_container.setFixedSize(16, 16)
        icon_layout = QHBoxLayout(self._icon_container)
        icon_layout.setContentsMargins(0, 0, 0, 0)
        icon_layout.setSpacing(0)

        self._message_icon = QSvgWidget(str(self._icons_dir / "messages-square.svg"))
        self._message_icon.setFixedSize(16, 16)
        icon_layout.addWidget(self._message_icon)

        self._check_icon = QSvgWidget(str(self._icons_dir / "check.svg"))
        self._check_icon.setFixedSize(16, 16)
        self._check_icon.hide()
        icon_layout.addWidget(self._check_icon)

        self._loader_icon = RotatingLoaderIcon(str(self._icons_dir / "loader-circle.svg"), size=16)
        self._loader_icon.hide()
        icon_layout.addWidget(self._loader_icon)

        layout.addWidget(self._icon_container)

        self._normal_style = "color: #333333; font-size: 12px;"
        display_title = title if len(title) <= 25 else title[:25] + "..."
        self._display_title = display_title
        self.title_label = QLabel(display_title)
        self.title_label.setStyleSheet(self._normal_style)
        self.title_label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.title_label.setToolTip(title)
        self.title_label.setTextInteractionFlags(Qt.TextInteractionFlag.NoTextInteraction)
        layout.addWidget(self.title_label)
        layout.addStretch()

        self._is_checked = False

        self._checkbox_widget = QWidget()
        self._checkbox_widget.setFixedSize(16, 16)
        self._checkbox_widget.setCursor(Qt.CursorShape.PointingHandCursor)
        self._checkbox_widget.hide()
        self._checkbox_widget.mousePressEvent = lambda a0: self._on_cb_toggle()

        cb_layout = QHBoxLayout(self._checkbox_widget)
        cb_layout.setContentsMargins(0, 0, 0, 0)
        cb_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self._cb_icon = QSvgWidget(str(self._icons_dir / "minus.svg"))
        self._cb_icon.setFixedSize(16, 16)
        self._cb_icon.hide()
        cb_layout.addWidget(self._cb_icon)

        self._update_cb_style()
        layout.addWidget(self._checkbox_widget)

    def _show_icon(self, icon_type: str) -> None:
        """Show the specified icon and hide others.

        Args:
            icon_type: 'message', 'check', or 'loader'
        """
        self._message_icon.setVisible(icon_type == "message")
        self._check_icon.setVisible(icon_type == "check")
        self._loader_icon.setVisible(icon_type == "loader")

        if icon_type == "loader":
            self._loader_icon.start()
        else:
            if self._loader_icon.is_spinning():
                self._loader_icon.stop()

    def set_loading(self, loading: bool) -> None:
        """Set the loading state and switch icons accordingly."""
        if self._is_loading == loading:
            return

        self._is_loading = loading

        if loading:
            self._is_complete = False
            self._show_icon("loader")
        else:
            if self._is_complete:
                self._show_icon("check")
            else:
                self._show_icon("message")

    def set_complete(self) -> None:
        """Mark as complete: show check icon briefly, then return to message icon."""
        self._is_complete = True
        self._is_loading = False

        if self._loader_icon.is_spinning():
            self._loader_icon.stop()
        self._show_icon("check")

        QTimer.singleShot(1000, self._on_complete_timeout)

    def _on_complete_timeout(self) -> None:
        """Timer callback: switch from check icon back to message icon."""
        self._is_complete = False
        self._show_icon("message")

    def is_loading(self) -> bool:
        return self._is_loading

    def is_complete(self) -> bool:
        return self._is_complete

    def _on_cb_toggle(self) -> None:
        self._is_checked = not self._is_checked
        self._update_cb_style()

    def _update_cb_style(self) -> None:
        if self._is_checked:
            self._checkbox_widget.setStyleSheet(
                "background-color: #000000; "
                "border: 1px solid #000000; "
                "border-radius: 3px;"
            )
            self._cb_icon.show()
        else:
            self._checkbox_widget.setStyleSheet(
                "background-color: transparent; "
                "border: 1px solid #cccccc; "
                "border-radius: 3px;"
            )
            self._cb_icon.hide()

    def set_selection_mode(self, enabled: bool) -> None:
        self._in_selection_mode = enabled
        self._checkbox_widget.setVisible(enabled)

    def is_checked(self) -> bool:
        return self._is_checked

    def set_checked(self, checked: bool) -> None:
        self._is_checked = checked
        self._update_cb_style()

    def mousePressEvent(self, a0) -> None:
        if self._in_selection_mode:
            self._on_cb_toggle()
            return
        if a0 and a0.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(a0)

    def mouseDoubleClickEvent(self, a0) -> None:
        if self._in_selection_mode:
            return
        if a0 and a0.button() == Qt.MouseButton.LeftButton:
            self.double_clicked.emit()
        super().mouseDoubleClickEvent(a0)


class ConversationGroup(QWidget):
    """Conversation group widget with expandable conversations."""

    context_menu_requested = pyqtSignal(object)
    clicked = pyqtSignal()
    title_changed = pyqtSignal(str)

    def __init__(self, title: str, group_id: str, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.group_id = group_id
        self.is_expanded = True
        self.conversations: list[ConversationItem] = []
        self._is_selected = False
        self._is_editing = False
        self._in_selection_mode = False
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(lambda pos: self.context_menu_requested.emit(pos))

        self._setup_ui(title)

    def _setup_ui(self, title: str) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

        self.header = QWidget()
        self.header.setObjectName("conversation-group-header")
        self.header.setFixedHeight(32)
        self.header.setCursor(Qt.CursorShape.PointingHandCursor)
        self.header.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.header.mousePressEvent = self._on_header_press
        self.header.mouseDoubleClickEvent = self._on_header_double_click
        header_layout = QHBoxLayout(self.header)
        header_layout.setContentsMargins(16, 0, 16, 0)
        header_layout.setSpacing(8)

        self.arrow = QSvgWidget(str(Path(__file__).parent.parent / "icons" / "arrow-down.svg"))
        self.arrow.setFixedSize(16, 16)
        header_layout.addWidget(self.arrow)

        self.folder_icon = QSvgWidget(str(Path(__file__).parent.parent / "icons" / "folder-open.svg"))
        self.folder_icon.setFixedSize(16, 16)
        header_layout.addWidget(self.folder_icon)

        self.title_label = QLabel(title)
        self.title_label.setStyleSheet("color: #333333; font-size: 12px; font-weight: 500;")
        self.title_label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        header_layout.addWidget(self.title_label)

        self.title_edit = QLineEdit(title)
        self.title_edit.setObjectName("group-title-edit")
        self.title_edit.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.title_edit.setMaxLength(50)
        self.title_edit.hide()
        self.title_edit.textChanged.connect(self._validate_title_input)
        self.title_edit.editingFinished.connect(self._finish_editing)
        header_layout.addWidget(self.title_edit)

        self.count_label = QLabel("")
        self.count_label.setStyleSheet("color: #999999; font-size: 11px;")
        header_layout.addWidget(self.count_label)

        header_layout.addStretch()

        self._group_cb_checked = False
        self._group_cb_widget = QWidget()
        self._group_cb_widget.setFixedSize(16, 16)
        self._group_cb_widget.setCursor(Qt.CursorShape.PointingHandCursor)
        self._group_cb_widget.hide()
        self._group_cb_widget.mousePressEvent = lambda a0: self._on_group_cb_toggle()

        gcb_layout = QHBoxLayout(self._group_cb_widget)
        gcb_layout.setContentsMargins(0, 0, 0, 0)
        gcb_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self._group_cb_icon = QSvgWidget(str(Path(__file__).parent.parent / "icons" / "plus.svg"))
        self._group_cb_icon.setFixedSize(16, 16)
        self._group_cb_icon.hide()
        gcb_layout.addWidget(self._group_cb_icon)

        self._update_group_cb_style()
        header_layout.addWidget(self._group_cb_widget)

        layout.addWidget(self.header)

        self.content_widget = QWidget()
        self.content_widget.setObjectName("conversation-group-content")
        self.content_widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.content_layout = QVBoxLayout(self.content_widget)
        self.content_layout.setContentsMargins(0, 0, 0, 0)
        self.content_layout.setSpacing(0)
        layout.addWidget(self.content_widget)

        self._setup_empty_placeholder()

    def _on_group_cb_toggle(self) -> None:
        self._group_cb_checked = not self._group_cb_checked
        self._update_group_cb_style()
        for conv in self.conversations:
            conv.set_checked(self._group_cb_checked)

    def _update_group_cb_style(self) -> None:
        if self._group_cb_checked:
            self._group_cb_widget.setStyleSheet(
                "background-color: #000000; "
                "border: 1px solid #000000; "
                "border-radius: 3px;"
            )
            self._group_cb_icon.show()
        else:
            self._group_cb_widget.setStyleSheet(
                "background-color: transparent; "
                "border: 1px solid #cccccc; "
                "border-radius: 3px;"
            )
            self._group_cb_icon.hide()

    def set_selection_mode(self, enabled: bool) -> None:
        self._in_selection_mode = enabled
        self._group_cb_widget.setVisible(enabled)
        for conv in self.conversations:
            conv.set_selection_mode(enabled)

    def is_checked(self) -> bool:
        return self._group_cb_checked

    def set_checked(self, checked: bool) -> None:
        self._group_cb_checked = checked
        self._update_group_cb_style()

    def get_checked_conversations(self) -> list:
        return [conv for conv in self.conversations if conv.is_checked()]

    def _setup_empty_placeholder(self) -> None:
        self.empty_placeholder = QWidget()
        self.empty_placeholder.setFixedHeight(32)
        self.empty_placeholder.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.empty_placeholder.hide()

        empty_layout = QHBoxLayout(self.empty_placeholder)
        empty_layout.setContentsMargins(48, 0, 8, 0)
        empty_layout.setSpacing(8)

        empty_icon = QSvgWidget(str(Path(__file__).parent.parent / "icons" / "message-square-dashed.svg"))
        empty_icon.setFixedSize(16, 16)
        empty_layout.addWidget(empty_icon)

        empty_label = QLabel("No message in this group")
        empty_label.setStyleSheet("color: #999999; font-size: 12px;")
        empty_label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        empty_layout.addWidget(empty_label)
        empty_layout.addStretch()

        self.content_layout.addWidget(self.empty_placeholder)

    def _toggle_expand(self, a0) -> None:
        if a0 and a0.button() == Qt.MouseButton.LeftButton:
            self.is_expanded = not self.is_expanded
            self.content_widget.setVisible(self.is_expanded)
            arrow_icon = "arrow-down.svg" if self.is_expanded else "arrow-right.svg"
            self.arrow.load(str(Path(__file__).parent.parent / "icons" / arrow_icon))
            folder_icon_name = "folder-open.svg" if self.is_expanded else "folder.svg"
            self.folder_icon.load(str(Path(__file__).parent.parent / "icons" / folder_icon_name))
            self.clicked.emit()

    def _on_header_press(self, a0) -> None:
        if self._in_selection_mode:
            self._on_group_cb_toggle()
            return
        if not self._is_editing:
            self._toggle_expand(a0)

    def _on_header_double_click(self, a0) -> None:
        if self._in_selection_mode:
            return
        if a0 and a0.button() == Qt.MouseButton.LeftButton:
            self._start_editing()

    def _validate_title_input(self) -> None:
        text = self.title_edit.text()
        style = self.title_edit.style()
        if not style:
            return

        if len(text) >= 45:
            self.title_edit.setProperty("error", "true")
            style.unpolish(self.title_edit)
            style.polish(self.title_edit)
        else:
            self.title_edit.setProperty("error", "false")
            style.unpolish(self.title_edit)
            style.polish(self.title_edit)

    def _start_editing(self) -> None:
        self._is_editing = True
        self.title_label.hide()
        self.title_edit.setText(self.title_label.text())
        self.title_edit.show()
        self.title_edit.setFocus()
        self.title_edit.selectAll()

    def _finish_editing(self) -> None:
        new_title = self.title_edit.text().strip()
        if new_title:
            self.title_label.setText(new_title)
            self.title_changed.emit(new_title)
        self.title_edit.hide()
        self.title_label.show()
        self._is_editing = False

    def set_selected(self, selected: bool) -> None:
        self._is_selected = selected
        if selected:
            self.header.setStyleSheet("QWidget#conversation-group-header { background-color: #E8E8E8; border-radius: 0px; }")
        else:
            self.header.setStyleSheet("QWidget#conversation-group-header { background-color: transparent; }")

    def add_conversation(self, conv_id: str, title: str, prompt: str = "") -> ConversationItem:
        conv_item = ConversationItem(title, conv_id, self.group_id, prompt)
        self.conversations.append(conv_item)
        self.content_layout.addWidget(conv_item)
        self._update_count()
        return conv_item

    def clear_conversations(self) -> None:
        for conv in self.conversations:
            conv.deleteLater()
        self.conversations.clear()
        self._update_count()

    def _update_count(self) -> None:
        count = len(self.conversations)
        self.count_label.setText(f"{count} requests")
        self.empty_placeholder.setVisible(count == 0)

    def set_expanded(self, expanded: bool) -> None:
        """Set the expanded state of this group.

        Args:
            expanded: True to expand, False to collapse
        """
        self.is_expanded = expanded
        self.content_widget.setVisible(expanded)
        arrow_icon = "arrow-down.svg" if expanded else "arrow-right.svg"
        self.arrow.load(str(Path(__file__).parent.parent / "icons" / arrow_icon))
        folder_icon_name = "folder-open.svg" if expanded else "folder.svg"
        self.folder_icon.load(str(Path(__file__).parent.parent / "icons" / folder_icon_name))

    def filter_conversations(self, search_text: str) -> bool:
        """Filter conversations to show only those matching the search text.

        Supports multiple keywords (space-separated). All keywords must match (AND logic).
        Search is case-insensitive and matches in both title and prompt.

        Args:
            search_text: The search text to match. Empty string shows all conversations.

        Returns:
            True if any conversations in this group match, False otherwise
        """
        if not search_text:
            for conv in self.conversations:
                conv.show()
            return len(self.conversations) > 0

        keywords = [kw.strip().lower() for kw in search_text.split() if kw.strip()]
        if not keywords:
            for conv in self.conversations:
                conv.show()
            return len(self.conversations) > 0

        has_match = False

        for conv in self.conversations:
            full_text = f"{conv._original_title} {conv.prompt}".lower()
            if all(kw in full_text for kw in keywords):
                conv.show()
                has_match = True
            else:
                conv.hide()

        return has_match


class CreateGroupDialog(QDialog):
    """Dialog for creating/renaming conversation groups."""

    def __init__(self, parent: Optional[QWidget] = None, initial_name: str = ""):
        super().__init__(parent)
        self.setWindowTitle("New Conversation Group" if not initial_name else "Rename Group")
        self.setFixedSize(300, 120)
        self._setup_ui(initial_name)

    def _setup_ui(self, initial_name: str) -> None:
        """Initialize the dialog UI."""
        layout = QVBoxLayout(self)
        layout.setSpacing(12)

        label = QLabel("Group Name:")
        layout.addWidget(label)

        self.name_input = QLineEdit()
        self.name_input.setObjectName("dialog-input")
        self.name_input.setText(initial_name)
        self.name_input.selectAll()
        layout.addWidget(self.name_input)

        button_box = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        button_box.accepted.connect(self.accept)
        button_box.rejected.connect(self.reject)
        layout.addWidget(button_box)

    def get_group_name(self) -> str:
        """Return the entered group name."""
        return self.name_input.text().strip()


class ChatPanel(QWidget):
    """Chat panel with custom file tree for managing conversation groups."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("chat-panel")
        self.conversations_dir = Path(__file__).parent.parent / "conversations"
        self.conversations_dir.mkdir(exist_ok=True)
        self.groups: list[ConversationGroup] = []
        self.current_selected_group: Optional[ConversationGroup] = None
        self._in_selection_mode = False
        self._all_expanded = True
        self._in_search_mode = False
        self._search_text = ""
        self._icons_dir = Path(__file__).parent.parent / "icons"
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self._setup_ui()
        self._load_conversations()

    def keyPressEvent(self, a0) -> None:
        if a0 is None:
            return

        if a0.key() == Qt.Key.Key_Return or a0.key() == Qt.Key.Key_Enter:
            if self._in_selection_mode:
                self._delete_selected_items()
                return

        super().keyPressEvent(a0)

    def _on_search_text_changed(self, text: str) -> None:
        """Handle search input text change.

        Perform real-time search as user types.

        Args:
            text: The current text in the search input field
        """
        self._search_text = text
        self._perform_search()

    def _perform_search(self) -> None:
        """Perform search and filter to show only matching conversations.

        Search is case-insensitive and matches in both full prompt and display title.
        Groups containing matches are shown and expanded, others are hidden.
        """
        if not self._search_text:
            self._clear_search_filter()
            return

        match_count = 0

        for group in self.groups:
            has_match = group.filter_conversations(self._search_text)
            group.setVisible(has_match)
            if has_match:
                group.set_expanded(True)
                for conv in group.conversations:
                    if conv.isVisible():
                        match_count += 1

        self.search_count_label.setText(f"Found {match_count} matching conversation{'s' if match_count != 1 else ''}")
        self.search_count_label.show()

    def _clear_search_filter(self) -> None:
        """Clear all search filters and show all conversations."""
        for group in self.groups:
            group.show()
            group.filter_conversations("")
        self.search_count_label.hide()

    def _setup_ui(self) -> None:
        """Initialize the chat panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        header = self._create_header()
        layout.addWidget(header)

        self.scroll_area = AutoHideScrollArea()
        self.scroll_area.setObjectName("chat-scroll")
        self.scroll_area.setWidgetResizable(True)
        self.scroll_area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        self.tree_widget = QWidget()
        self.tree_widget.setObjectName("chat-tree-container")
        self.tree_layout = QVBoxLayout(self.tree_widget)
        self.tree_layout.setContentsMargins(0, 0, 0, 0)
        self.tree_layout.setSpacing(0)

        self.placeholder = QLabel("No conversations yet")
        self.placeholder.setObjectName("history-placeholder")
        self.placeholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.tree_layout.addWidget(self.placeholder)
        self.tree_layout.addStretch()

        self.scroll_area.setWidget(self.tree_widget)
        layout.addWidget(self.scroll_area)

        footer = self._create_footer()
        layout.addWidget(footer)

    def _create_footer(self) -> QWidget:
        """
        Create the footer with pill buttons.

        Buttons are left-aligned with margin.
        """
        footer = QWidget()
        footer.setObjectName("chat-footer")
        footer_layout = QHBoxLayout(footer)
        footer_layout.setContentsMargins(16, 12, 16, 20)
        footer_layout.setSpacing(8)

        self.delete_btn = DeleteButton()
        self.delete_btn.clicked.connect(self._toggle_selection_mode)
        footer_layout.addWidget(self.delete_btn)

        self.expand_collapse_btn = self._create_expand_collapse_btn()
        footer_layout.addWidget(self.expand_collapse_btn)

        footer_layout.addStretch()

        return footer

    def _create_expand_collapse_btn(self) -> QWidget:
        """Create the expand/collapse all button with icon and text."""
        btn = QWidget()
        btn.setCursor(Qt.CursorShape.PointingHandCursor)

        btn.setStyleSheet("""
            QWidget {
                background-color: transparent;
                border: none;
            }
        """)

        layout = QHBoxLayout(btn)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        icons_dir = Path(__file__).parent.parent / "icons"
        self.expand_icon = QSvgWidget(str(icons_dir / "list-chevrons-up-down.svg"))
        self.expand_icon.setFixedSize(14, 14)
        self.collapse_icon = QSvgWidget(str(icons_dir / "list-chevrons-down-up.svg"))
        self.collapse_icon.setFixedSize(14, 14)
        self.collapse_icon.hide()

        layout.addWidget(self.expand_icon)
        layout.addWidget(self.collapse_icon)

        self.expand_label = QLabel("Collapse All")
        self.expand_label.setStyleSheet("color: #666666; font-size: 12px; font-weight: 500;")
        layout.addWidget(self.expand_label)

        btn.mousePressEvent = lambda a0: self._toggle_expand_all() if a0 and a0.button() == Qt.MouseButton.LeftButton else None  # type: ignore

        return btn

    def _toggle_expand_all(self) -> None:
        """Toggle expand/collapse all groups.

        Logic:
        - If current state is "all expanded", collapse all
        - If current state is "all collapsed", expand all
        - Ignore intermediate states (some expanded, some collapsed)
        """
        if self._all_expanded:
            for group in self.groups:
                group.set_expanded(False)
            self._all_expanded = False
            self._update_expand_btn(True)
        else:
            for group in self.groups:
                group.set_expanded(True)
            self._all_expanded = True
            self._update_expand_btn(False)

    def _update_expand_btn(self, is_collapsed: bool) -> None:
        """Update the expand/collapse button appearance.

        Args:
            is_collapsed: True if all groups are collapsed, False if all are expanded
        """
        if is_collapsed:
            self.collapse_icon.hide()
            self.expand_icon.show()
            self.expand_label.setText("Expand All")
        else:
            self.expand_icon.hide()
            self.collapse_icon.show()
            self.expand_label.setText("Collapse All")

    def _create_header(self) -> QWidget:
        """Create the header with title, search input, and new chat button.

        Layout: [Icon] CHATS [Search Input] [New Group]
        """
        header = QWidget()
        header.setObjectName("chat-panel-header")
        header_layout = QVBoxLayout(header)
        header_layout.setContentsMargins(16, 12, 16, 12)
        header_layout.setSpacing(10)

        top_row = QWidget()
        top_layout = QHBoxLayout(top_row)
        top_layout.setContentsMargins(0, 0, 0, 0)
        top_layout.setSpacing(8)

        history_icon_path = Path(__file__).parent.parent / "icons" / "history.svg"
        if history_icon_path.exists():
            history_icon = QSvgWidget(str(history_icon_path))
            history_icon.setFixedSize(16, 16)
            top_layout.addWidget(history_icon)

        title = QLabel("CHATS")
        title.setObjectName("card-title")
        top_layout.addWidget(title)
        top_layout.addStretch()

        new_btn = QPushButton("New Group")
        new_btn.setObjectName("text-btn")
        new_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        new_btn.clicked.connect(self._create_new_group)

        top_layout.addWidget(new_btn)

        header_layout.addWidget(top_row)

        search_container = QWidget()
        search_container.setObjectName("chat-search-container")
        search_layout = QHBoxLayout(search_container)
        search_layout.setContentsMargins(0, 0, 0, 0)
        search_layout.setSpacing(4)

        self.search_input = QLineEdit()
        self.search_input.setFixedHeight(32)
        self.search_input.setPlaceholderText("Search")
        self.search_input.setCursor(Qt.CursorShape.IBeamCursor)

        search_icon_path = Path(__file__).parent.parent / "icons" / "text-search.svg"


        if search_icon_path.exists():
            search_icon = QIcon(str(search_icon_path))
            self.search_input.addAction(search_icon, QLineEdit.ActionPosition.LeadingPosition)

        self.search_input.setStyleSheet("""
            QLineEdit {
                background-color: #ffffff;
                border: 1px solid #e8e8e8;
                border-radius: 16px;
                padding: 6px 0 6px 0;
                color: #000000;
                font-size: 12px;
            }
            QLineEdit:focus {
                border-color: #cccccc;
                background-color: #ffffff;
            }
            QLineEdit::placeholder {
                color: #000000;
                padding: 6px 0 6px 0;
                font-size: 12px;
            }
        """)
        self.search_input.textChanged.connect(self._on_search_text_changed)
        search_layout.addWidget(self.search_input, stretch=1)

        header_layout.addWidget(search_container)

        self.search_count_label = QLabel("")
        self.search_count_label.setStyleSheet("color: #666666; font-size: 11px; padding-left: 0px;")
        self.search_count_label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.search_count_label.hide()
        header_layout.addWidget(self.search_count_label)

        return header

    def _toggle_selection_mode(self) -> None:
        """Toggle selection mode for bulk deletion."""
        self._in_selection_mode = not self._in_selection_mode
        for group in self.groups:
            group.set_selection_mode(self._in_selection_mode)
        if self._in_selection_mode:
            self.setFocus()

    def _exit_selection_mode(self) -> None:
        """Exit selection mode."""
        self._in_selection_mode = False
        for group in self.groups:
            group.set_selection_mode(False)

    def _delete_selected_items(self) -> None:
        """Delete all selected groups and conversations."""
        groups_to_delete = []
        conversations_to_delete = []

        for group in self.groups:
            if group.is_checked():
                groups_to_delete.append(group)
            else:
                checked_convs = group.get_checked_conversations()
                for conv in checked_convs:
                    conversations_to_delete.append((group, conv))

        if not groups_to_delete and not conversations_to_delete:
            self._exit_selection_mode()
            return

        total_count = len(groups_to_delete) + len(conversations_to_delete)
        reply = QMessageBox.question(
            self,
            "Delete Selected",
            f"Are you sure you want to delete {total_count} selected item(s)?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return

        for group in groups_to_delete:
            group_file = self.conversations_dir / f"{group.group_id}.json"
            if group_file.exists():
                group_file.unlink()
            self.groups.remove(group)
            group.deleteLater()

        for group, conv in conversations_to_delete:
            group_file = self.conversations_dir / f"{group.group_id}.json"
            if group_file.exists():
                with open(group_file, 'r', encoding='utf-8') as f:
                    group_data = json.load(f)
                group_data["conversations"] = [
                    c for c in group_data.get("conversations", [])
                    if c.get("id") != conv.conv_id
                ]
                with open(group_file, 'w', encoding='utf-8') as f:
                    json.dump(group_data, f, indent=2, ensure_ascii=False)
            group.conversations.remove(conv)
            conv.deleteLater()
            group._update_count()

        self._exit_selection_mode()
        self._update_placeholder()

    def _load_conversations(self) -> None:
        """Load all conversation groups from disk."""
        for group in self.groups:
            group.deleteLater()
        self.groups.clear()

        for group_file in sorted(self.conversations_dir.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True):
            try:
                with open(group_file, 'r', encoding='utf-8') as f:
                    group_data = json.load(f)
                    self._add_group_to_tree(group_data, group_file.stem)
            except Exception as e:
                print(f"Error loading conversation group {group_file}: {e}")

        self._update_placeholder()

    def _add_group_to_tree(self, group_data: dict, group_id: str) -> ConversationGroup:
        """Add a conversation group to the tree.

        Returns:
            The newly created ConversationGroup widget.
        """
        group_widget = ConversationGroup(group_data.get("title", "Untitled"), group_id)
        group_widget.context_menu_requested.connect(lambda pos: self._show_group_context_menu(pos, group_widget))
        group_widget.clicked.connect(lambda: self._select_group(group_widget))
        group_widget.title_changed.connect(lambda title: self._save_group_title(group_widget, title))

        conversations = group_data.get("conversations", [])
        for conv in conversations:
            conv_item = group_widget.add_conversation(
                conv.get("id", ""),
                conv.get("title", "Untitled"),
                conv.get("prompt", "")
            )
            conv_item.context_menu_requested.connect(lambda pos, item=conv_item: self._show_conversation_context_menu(pos, item))
            conv_item.clicked.connect(lambda: self._select_group(group_widget))
            conv_item.double_clicked.connect(lambda item=conv_item: self._insert_conversation(item))

        group_widget._update_count()

        self.groups.append(group_widget)
        self.tree_layout.insertWidget(self.tree_layout.count() - 1, group_widget)
        self._update_placeholder()

        return group_widget

    def _create_new_group(self) -> None:
        """Create a new conversation group with default name and start editing."""
        group_id = QDateTime.currentDateTime().toString("yyyyMMddHHmmss")
        group_data = {
            "title": "New Group",
            "created_at": QDateTime.currentDateTime().toString(Qt.DateFormat.ISODate),
            "conversations": []
        }

        group_file = self.conversations_dir / f"{group_id}.json"
        with open(group_file, 'w', encoding='utf-8') as f:
            json.dump(group_data, f, indent=2, ensure_ascii=False)

        group_widget = self._add_group_to_tree(group_data, group_id)
        group_widget._start_editing()

    def _show_group_context_menu(self, position, group_widget: ConversationGroup) -> None:
        """Show context menu for group."""
        menu = QMenu(self)

        rename_action = menu.addAction("Rename")
        delete_action = menu.addAction("Delete")

        action = menu.exec(group_widget.header.mapToGlobal(position))
        if action == rename_action:
            self._rename_group(group_widget)
        elif action == delete_action:
            self._delete_group(group_widget)

    def _show_conversation_context_menu(self, position, conv_item: ConversationItem) -> None:
        """Show context menu for conversation."""
        menu = QMenu(self)

        insert_action = menu.addAction("Insert")
        delete_action = menu.addAction("Delete")

        action = menu.exec(conv_item.mapToGlobal(position))
        if action == insert_action:
            self._insert_conversation(conv_item)
        elif action == delete_action:
            self._delete_conversation(conv_item)

    def _rename_group(self, group_widget: ConversationGroup) -> None:
        """Rename a conversation group."""
        dialog = CreateGroupDialog(self, initial_name=group_widget.title_label.text())
        if dialog.exec() == QDialog.DialogCode.Accepted:
            new_name = dialog.get_group_name()
            if new_name:
                self._save_group_title(group_widget, new_name)

    def _save_group_title(self, group_widget: ConversationGroup, new_title: str) -> None:
        """Save group title to file."""
        group_file = self.conversations_dir / f"{group_widget.group_id}.json"
        if group_file.exists():
            with open(group_file, 'r', encoding='utf-8') as f:
                group_data = json.load(f)
            group_data["title"] = new_title
            with open(group_file, 'w', encoding='utf-8') as f:
                json.dump(group_data, f, indent=2, ensure_ascii=False)

    def _delete_group(self, group_widget: ConversationGroup) -> None:
        """Delete a conversation group."""
        reply = QMessageBox.question(
            self,
            "Delete Group",
            f"Are you sure you want to delete '{group_widget.title_label.text()}'?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply == QMessageBox.StandardButton.Yes:
            group_file = self.conversations_dir / f"{group_widget.group_id}.json"
            if group_file.exists():
                group_file.unlink()
            self.groups.remove(group_widget)
            group_widget.deleteLater()
            self._update_placeholder()

    def _insert_conversation(self, conv_item: ConversationItem) -> None:
        """Insert conversation prompt into the main window."""
        if conv_item.prompt:
            main_window = self.window()
            if main_window and hasattr(main_window, 'prompt_input'):
                prompt_input = getattr(main_window, 'prompt_input')
                if prompt_input:
                    prompt_input.setPlainText(conv_item.prompt)

    def _delete_conversation(self, conv_item: ConversationItem) -> None:
        """Delete a conversation."""
        reply = QMessageBox.question(
            self,
            "Delete Conversation",
            f"Are you sure you want to delete '{conv_item.title_label.text()}'?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if reply == QMessageBox.StandardButton.Yes:
            group_file = self.conversations_dir / f"{conv_item.group_id}.json"
            if group_file.exists():
                with open(group_file, 'r', encoding='utf-8') as f:
                    group_data = json.load(f)

                group_data["conversations"] = [
                    conv for conv in group_data.get("conversations", [])
                    if conv.get("id") != conv_item.conv_id
                ]

                with open(group_file, 'w', encoding='utf-8') as f:
                    json.dump(group_data, f, indent=2, ensure_ascii=False)

                for group in self.groups:
                    if group.group_id == conv_item.group_id:
                        group.conversations.remove(conv_item)
                        group._update_count()
                        break
                conv_item.deleteLater()

    def add_conversation_to_current_group(self, prompt: str, title: Optional[str] = None) -> Optional[ConversationItem]:
        """Add a new conversation to the selected group (or first group if none selected).

        Returns:
            The newly created ConversationItem, or None if creation failed.
        """
        if not self.groups:
            self._create_default_group()

        if not self.groups:
            return None

        group_widget = self.current_selected_group if self.current_selected_group else self.groups[0]
        group_file = self.conversations_dir / f"{group_widget.group_id}.json"
        if not group_file.exists():
            return None

        with open(group_file, 'r', encoding='utf-8') as f:
            group_data = json.load(f)

        conv_id = QDateTime.currentDateTime().toString("yyyyMMddHHmmsszzz")
        conv_title = title if title else (prompt[:50] + "..." if len(prompt) > 50 else prompt)
        new_conv = {
            "id": conv_id,
            "title": conv_title,
            "prompt": prompt,
            "created_at": QDateTime.currentDateTime().toString(Qt.DateFormat.ISODate)
        }

        group_data["conversations"].insert(0, new_conv)

        with open(group_file, 'w', encoding='utf-8') as f:
            json.dump(group_data, f, indent=2, ensure_ascii=False)

        conv_item = group_widget.add_conversation(conv_id, new_conv["title"], prompt)
        conv_item.context_menu_requested.connect(lambda pos, item=conv_item: self._show_conversation_context_menu(pos, item))
        conv_item.clicked.connect(lambda: self._select_group(group_widget))
        conv_item.double_clicked.connect(lambda item=conv_item: self._insert_conversation(item))

        return conv_item

    def find_conversation_by_id(self, conv_id: str) -> Optional[ConversationItem]:
        """Find a conversation item by its ID.

        Args:
            conv_id: The conversation ID to search for.

        Returns:
            The ConversationItem if found, None otherwise.
        """
        for group in self.groups:
            for conv in group.conversations:
                if conv.conv_id == conv_id:
                    return conv
        return None

    def _create_default_group(self) -> None:
        """Create a default conversation group automatically."""
        group_id = QDateTime.currentDateTime().toString("yyyyMMddHHmmss")
        group_name = "My Conversations"
        group_data = {
            "title": group_name,
            "created_at": QDateTime.currentDateTime().toString(Qt.DateFormat.ISODate),
            "conversations": []
        }

        group_file = self.conversations_dir / f"{group_id}.json"
        with open(group_file, 'w', encoding='utf-8') as f:
            json.dump(group_data, f, indent=2, ensure_ascii=False)

        self._add_group_to_tree(group_data, group_id)

    def _select_group(self, group_widget: ConversationGroup) -> None:
        """Select a conversation group."""
        if self.current_selected_group:
            self.current_selected_group.set_selected(False)
        group_widget.set_selected(True)
        self.current_selected_group = group_widget

    def _update_placeholder(self) -> None:
        """Update the visibility of the placeholder."""
        if self.groups:
            self.placeholder.hide()
        else:
            self.placeholder.show()
        self.scroll_area.updateScrollbars()