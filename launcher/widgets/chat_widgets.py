import json
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import Qt, QDateTime, pyqtSignal
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

from launcher.widgets.custom_widgets import AutoHideScrollArea, CollapsibleCard


class DeleteButton(QWidget):
    """Custom delete button with SVG icon and text."""

    clicked = pyqtSignal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("pill-btn")
        self.setMinimumHeight(36)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

        self._icons_dir = Path(__file__).parent.parent / "icons"

        self.setStyleSheet("""
            QWidget#pill-btn {
                background-color: transparent;
                border: 1px solid #CCCCCC;
                border-radius: 12px;
            }
        """)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(0, 8, 0, 8)
        layout.setSpacing(8)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.icon = QSvgWidget(str(self._icons_dir / "list-x.svg"))
        self.icon.setFixedSize(16, 16)
        layout.addWidget(self.icon)

        self.label = QLabel("Delete Conversations")
        self.label.setStyleSheet("color: #666666; font-size: 13px; font-weight: 500;")
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

        layout = QHBoxLayout(self)
        layout.setContentsMargins(48, 0, 16, 0)
        layout.setSpacing(8)

        message_icon = QSvgWidget(str(Path(__file__).parent.parent / "icons" / "messages-square.svg"))
        message_icon.setFixedSize(16, 16)
        layout.addWidget(message_icon)

        display_title = title if len(title) <= 25 else title[:25] + "..."
        self.title_label = QLabel(display_title)
        self.title_label.setStyleSheet("color: #333333; font-size: 12px;")
        self.title_label.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.title_label.setToolTip(title)
        layout.addWidget(self.title_label)
        layout.addStretch()

        self.checkbox = QPushButton("✓")
        self.checkbox.setObjectName("option-check")
        self.checkbox.setCursor(Qt.CursorShape.PointingHandCursor)
        self.checkbox.setCheckable(True)
        self.checkbox.setFixedSize(16, 16)
        self.checkbox.hide()
        self.checkbox.clicked.connect(self._on_checkbox_clicked)
        layout.addWidget(self.checkbox)

    def _on_checkbox_clicked(self, checked: bool) -> None:
        self.checkbox_state_changed.emit(checked)

    def set_selection_mode(self, enabled: bool) -> None:
        self._in_selection_mode = enabled
        self.checkbox.setVisible(enabled)

    def is_checked(self) -> bool:
        return self.checkbox.isChecked()

    def set_checked(self, checked: bool) -> None:
        self.checkbox.setChecked(checked)

    def mousePressEvent(self, a0) -> None:
        if self._in_selection_mode:
            self.checkbox.setChecked(not self.checkbox.isChecked())
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

        self.group_checkbox = QPushButton("✓")
        self.group_checkbox.setObjectName("option-check")
        self.group_checkbox.setCursor(Qt.CursorShape.PointingHandCursor)
        self.group_checkbox.setCheckable(True)
        self.group_checkbox.setFixedSize(16, 16)
        self.group_checkbox.hide()
        self.group_checkbox.clicked.connect(self._on_group_checkbox_clicked)
        header_layout.addWidget(self.group_checkbox)

        layout.addWidget(self.header)

        self.content_widget = QWidget()
        self.content_widget.setObjectName("conversation-group-content")
        self.content_widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.content_layout = QVBoxLayout(self.content_widget)
        self.content_layout.setContentsMargins(0, 0, 0, 0)
        self.content_layout.setSpacing(0)
        layout.addWidget(self.content_widget)

        self._setup_empty_placeholder()

    def _on_group_checkbox_clicked(self, checked: bool) -> None:
        for conv in self.conversations:
            conv.set_checked(checked)

    def set_selection_mode(self, enabled: bool) -> None:
        self._in_selection_mode = enabled
        self.group_checkbox.setVisible(enabled)
        for conv in self.conversations:
            conv.set_selection_mode(enabled)

    def is_checked(self) -> bool:
        return self.group_checkbox.isChecked()

    def set_checked(self, checked: bool) -> None:
        self.group_checkbox.setChecked(checked)

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
            self.group_checkbox.setChecked(not self.group_checkbox.isChecked())
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
            self.header.setStyleSheet("background-color: #E8E8E8; border-radius: 6px;")
        else:
            self.header.setStyleSheet("background-color: transparent;")

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
        self.name_input.setText(initial_name)
        self.name_input.selectAll()
        self.name_input.setStyleSheet("""
            QLineEdit {
                padding: 6px;
                border: 1px solid #CCCCCC;
                border-radius: 4px;
            }
            QLineEdit:focus {
                border-color: #000000;
            }
        """)
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

    def _setup_ui(self) -> None:
        """Initialize the chat panel UI."""
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        header = self._create_header()
        layout.addWidget(header)

        scroll = AutoHideScrollArea()
        scroll.setObjectName("chat-scroll")
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

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

        scroll.setWidget(self.tree_widget)
        layout.addWidget(scroll)

        footer = self._create_footer()
        layout.addWidget(footer)

    def _create_footer(self) -> QWidget:
        """Create the footer with pill buttons."""
        footer = QWidget()
        footer.setObjectName("chat-footer")
        footer_layout = QHBoxLayout(footer)
        footer_layout.setContentsMargins(16, 12, 16, 12)
        footer_layout.setSpacing(8)

        self.delete_btn = DeleteButton()
        self.delete_btn.clicked.connect(self._toggle_selection_mode)
        footer_layout.addWidget(self.delete_btn)

        return footer

    def _create_header(self) -> QWidget:
        """Create the header with title and new chat button."""
        header = QWidget()
        header.setObjectName("chat-panel-header")
        header_layout = QHBoxLayout(header)
        header_layout.setContentsMargins(16, 12, 16, 12)
        header_layout.setSpacing(8)

        history_icon_path = Path(__file__).parent.parent / "icons" / "history.svg"
        if history_icon_path.exists():
            history_icon = QSvgWidget(str(history_icon_path))
            history_icon.setFixedSize(16, 16)
            header_layout.addWidget(history_icon)

        title = QLabel("CHAT")
        title.setObjectName("card-title")
        header_layout.addWidget(title)
        header_layout.addStretch()

        new_btn = QPushButton("New")
        new_btn.setObjectName("text-btn")
        new_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        new_btn.clicked.connect(self._create_new_group)

        header_layout.addWidget(new_btn)

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

    def _add_group_to_tree(self, group_data: dict, group_id: str) -> None:
        """Add a conversation group to the tree."""
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

    def _create_new_group(self) -> None:
        """Create a new conversation group."""
        dialog = CreateGroupDialog(self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            group_name = dialog.get_group_name()
            if group_name:
                group_id = QDateTime.currentDateTime().toString("yyyyMMddHHmmss")
                group_data = {
                    "title": group_name,
                    "created_at": QDateTime.currentDateTime().toString(Qt.DateFormat.ISODate),
                    "conversations": []
                }

                group_file = self.conversations_dir / f"{group_id}.json"
                with open(group_file, 'w', encoding='utf-8') as f:
                    json.dump(group_data, f, indent=2, ensure_ascii=False)

                self._add_group_to_tree(group_data, group_id)

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

    def add_conversation_to_current_group(self, prompt: str, title: Optional[str] = None) -> None:
        """Add a new conversation to the selected group (or first group if none selected)."""
        if not self.groups:
            self._create_default_group()

        if not self.groups:
            return

        group_widget = self.current_selected_group if self.current_selected_group else self.groups[0]
        group_file = self.conversations_dir / f"{group_widget.group_id}.json"
        if not group_file.exists():
            return

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