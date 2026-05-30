from typing import Optional
from pathlib import Path

from PyQt6.QtCore import Qt, QTimer, QPropertyAnimation, QEasingCurve, QSize
from PyQt6.QtSvgWidgets import QSvgWidget
from PyQt6.QtGui import QColor
from PyQt6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QScrollArea,
    QTextEdit,
    QFrame,
    QGraphicsOpacityEffect,
    QGraphicsDropShadowEffect,
)


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

    def show_toast(self, message: Optional[str] = None, target_widget: Optional[QWidget] = None) -> None:
        """Show the toast notification.

        Args:
            message: The message to display
            target_widget: The widget to position the toast next to (optional)
        """
        if message:
            self.label.setText(message)

        self.adjustSize()

        if target_widget:
            parent = self.parent()
            if parent and isinstance(parent, QWidget):
                target_pos = target_widget.mapTo(parent, target_widget.rect().topLeft())

                center_offset = (target_widget.width() - self.width()) // 2
                x = target_pos.x() + center_offset
                y = target_pos.y() - self.height() - 8

                parent_rect = parent.rect()
                if x < 0:
                    x = 0
                if x + self.width() > parent_rect.width():
                    x = parent_rect.width() - self.width()
                if y < 0:
                    y = target_pos.y() + target_widget.height() + 8

                self.move(x, y)
        elif self.parent():
            parent = self.parent()
            if parent and isinstance(parent, QWidget):
                parent_rect = parent.rect()
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

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWidgetResizable(True)
        self.setStyleSheet("""
            QScrollArea {
                border: none;
                background-color: transparent;
            }
            QScrollArea > QWidget > QWidget {
                background-color: transparent;
            }
        """)

    def event(self, event) -> bool:
        if event.type() == event.Type.Resize:
            self._update_scrollbars()
        return super().event(event)

    def setWidget(self, widget: QWidget) -> None:
        super().setWidget(widget)
        self._update_scrollbars()

    def _update_scrollbars(self) -> None:
        widget = self.widget()
        if not widget:
            return

        viewport_size = self.viewport().size()
        size_hint = widget.sizeHint()

        if size_hint.height() <= viewport_size.height():
            self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        else:
            self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)

        if size_hint.width() <= viewport_size.width():
            self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        else:
            self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)


class AutoHideTextEdit(QTextEdit):
    """TextEdit that automatically adjusts height to fit content."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.textChanged.connect(self._adjust_height)
        self._min_height = 60
        self._max_height = 200

    def setMinHeight(self, height: int) -> None:
        self._min_height = height
        self._adjust_height()

    def setMaxHeight(self, height: int) -> None:
        self._max_height = height
        self._adjust_height()

    def _adjust_height(self) -> None:
        doc_height = int(self.document().size().height())
        new_height = max(self._min_height, min(doc_height + 10, self._max_height))
        self.setFixedHeight(new_height)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._adjust_height()


class CollapsibleCard(QWidget):
    """A collapsible card widget with title and content."""

    def __init__(self, title: str, parent: Optional[QWidget] = None, action_widget: Optional[QWidget] = None, icon_name: Optional[str] = None):
        super().__init__(parent)
        self._is_expanded = True
        self._title = title
        self._action_widget = action_widget
        self._icon_name = icon_name
        self._setup_ui()

    def _setup_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Header
        self._header = QFrame()
        self._header.setCursor(Qt.CursorShape.PointingHandCursor)
        self._header.setObjectName("collapsible-header")
        header_layout = QHBoxLayout(self._header)
        header_layout.setContentsMargins(16, 12, 16, 12)
        header_layout.setSpacing(8)

        if self._icon_name:
            icon_path = Path(__file__).resolve().parent.parent / "icons" / self._icon_name
            if icon_path.exists():
                self._icon_widget = QSvgWidget(str(icon_path))
                self._icon_widget.setFixedSize(16, 16)
                header_layout.addWidget(self._icon_widget)

        self._header_label = QLabel(self._title)
        self._header_label.setObjectName("card-title")
        header_layout.addWidget(self._header_label)

        header_layout.addStretch()

        if self._action_widget:
            header_layout.addWidget(self._action_widget)

        arrow_icon_path = Path(__file__).resolve().parent.parent / "icons" / "arrow-down.svg"
        self._arrow_widget = QSvgWidget(str(arrow_icon_path))
        self._arrow_widget.setFixedSize(16, 16)
        header_layout.addWidget(self._arrow_widget)

        self._header.mousePressEvent = self._toggle_expand

        layout.addWidget(self._header)

        # Content container
        self._content_container = QFrame()
        self._content_container.setObjectName("collapsible-content")
        self._content_layout = QVBoxLayout(self._content_container)
        self._content_layout.setContentsMargins(16, 8, 16, 16)
        self._content_layout.setSpacing(10)

        layout.addWidget(self._content_container)

    def content_layout(self) -> QVBoxLayout:
        return self._content_layout

    def setContentLayout(self, layout) -> None:
        while self._content_layout.count():
            item = self._content_layout.takeAt(0)
            widget = item.widget()
            if widget:
                widget.deleteLater()

        self._content_layout.addLayout(layout)

    def addWidget(self, widget: QWidget) -> None:
        self._content_layout.addWidget(widget)

    def _toggle_expand(self, event) -> None:
        self._is_expanded = not self._is_expanded
        self._content_container.setVisible(self._is_expanded)
        arrow_icon = "arrow-down.svg" if self._is_expanded else "arrow-right.svg"
        arrow_icon_path = Path(__file__).resolve().parent.parent / "icons" / arrow_icon
        self._arrow_widget.load(str(arrow_icon_path))

    def isExpanded(self) -> bool:
        return self._is_expanded

    def setExpanded(self, expanded: bool) -> None:
        self._is_expanded = expanded
        self._content_container.setVisible(expanded)
        arrow_icon = "arrow-down.svg" if expanded else "arrow-right.svg"
        arrow_icon_path = Path(__file__).resolve().parent.parent / "icons" / arrow_icon
        self._arrow_widget.load(str(arrow_icon_path))
