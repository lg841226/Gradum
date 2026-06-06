import json
import re
import sys
import urllib.request
import urllib.error
from pathlib import Path
from typing import Optional, Callable

import psutil

from PyQt6.QtCore import Qt, QProcess, QUrl
from PyQt6.QtGui import QFont
from PyQt6.QtNetwork import QNetworkAccessManager, QNetworkRequest
from PyQt6.QtWidgets import (
    QApplication,
    QWidget,
    QHBoxLayout,
    QLabel,
    QRadioButton,
    QFrame,
    QSizePolicy,
)

from launcher.config import (
    MODEL_NAME_MAPPING,
    OLLAMA_API_URL,
)
from launcher.i18n import (
    ModelText,
    RequestText,
)
from launcher.theme import Theme


class ModelNameFormatter:
    @staticmethod
    def format_model_name(model_name: str) -> str:
        base_name = model_name.split(":")[0].lower()
        formatted = MODEL_NAME_MAPPING.get(base_name, base_name.title())
        return formatted.replace("-", " ")

    @staticmethod
    def extract_model_size(model_name: str) -> str:
        match = re.search(r"(\d+)[bB]", model_name)
        return f"{match.group(1)}b" if match else ""


class ModelHelper:
    @staticmethod
    def create_model_radio(model_name: str) -> tuple[QRadioButton, QWidget]:
        container = QWidget()
        layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)
        layout.setAlignment(Qt.AlignmentFlag.AlignVCenter)

        formatted_name = ModelNameFormatter.format_model_name(model_name)
        radio = QRadioButton(formatted_name)
        radio.setCursor(Qt.CursorShape.PointingHandCursor)
        radio.setProperty("originalName", model_name)

        size = ModelNameFormatter.extract_model_size(model_name)
        if size:
            badge = QLabel(size)
            badge.setObjectName("model-badge")
            badge.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            layout.addWidget(radio)
            layout.addWidget(badge)
        else:
            layout.addWidget(radio)

        return radio, container

    @staticmethod
    def create_separator() -> QFrame:
        separator = QFrame()
        separator.setFixedHeight(1)
        separator.setStyleSheet(f"background-color: {Theme.colors.border_light}; margin: 0 20px;")
        return separator

    @staticmethod
    def clear_layout(layout) -> None:
        while layout.count():
            item = layout.takeAt(0)
            if item and (widget := item.widget()):
                widget.deleteLater()


class OllamaService:
    def __init__(self, parent: Optional[QWidget] = None):
        self.parent = parent
        self.ollama_process: Optional[QProcess] = None
        self.ollama_psutil_process: Optional[psutil.Process] = None
        self.request_latencies: list[float] = []
        self.max_latency_samples = 10
        self._add_request_log_callback: Optional[Callable[[str, int, str, str], None]] = None
        self._using_existing_service = False

    @property
    def add_request_log_callback(self) -> Optional[Callable[[str, int, str, str], None]]:
        return self._add_request_log_callback

    @add_request_log_callback.setter
    def add_request_log_callback(self, value: Optional[Callable[[str, int, str, str], None]]) -> None:
        self._add_request_log_callback = value

    def _check_existing_ollama(self) -> bool:
        try:
            with urllib.request.urlopen(OLLAMA_API_URL, timeout=2) as response:
                return response.status == 200
        except (urllib.error.URLError, urllib.error.HTTPError, Exception):
            return False

    def start(self) -> None:
        if self._check_existing_ollama():
            print("Ollama service is already running, using existing service")
            self._using_existing_service = True
            return

        self._using_existing_service = False
        self.ollama_process = QProcess(self.parent)

        assert self.ollama_process is not None

        self.ollama_process.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.ollama_process.readyReadStandardOutput.connect(self._read_output)
        self.ollama_process.readyReadStandardError.connect(self._read_output)

        self.ollama_process.start("ollama", ["serve"])

        if not self.ollama_process.waitForStarted(3000):
            print(ModelText.OLLAMA_START_ERROR)
            self.ollama_process = None
            self.ollama_psutil_process = None
        else:
            pid = self.ollama_process.processId()
            if pid != -1:
                try:
                    self.ollama_psutil_process = psutil.Process(pid)
                except psutil.NoSuchProcess:
                    self.ollama_psutil_process = None

    def stop(self) -> None:
        if self._using_existing_service:
            return
        
        if self.ollama_process:
            self.ollama_process.kill()
            self.ollama_process.waitForFinished(3000)

    def _read_output(self) -> None:
        if not self.ollama_process:
            return

        while self.ollama_process.canReadLine():
            line = self.ollama_process.readLine().data().decode('utf-8').strip()
            if line:
                self._parse_log(line)

    def _parse_log(self, line: str) -> None:
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

            method_path = parts[4].strip()
            method_parts = method_path.split()
            if len(method_parts) >= 2:
                method = method_parts[0]
                if self._add_request_log_callback:
                    self._add_request_log_callback(time_str, code, method, duration)
        except (IndexError, ValueError):
            pass

    @staticmethod
    def _format_duration(duration_str: str) -> str:
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

    @staticmethod
    def _extract_latency_ms(duration_str: str) -> float:
        try:
            if "µs" in duration_str:
                value = float(duration_str.replace("µs", ""))
                return value / 1000
            elif "ms" in duration_str:
                value = float(duration_str.replace("ms", ""))
                return value
            elif "s" in duration_str:
                value = float(duration_str.replace("s", ""))
                return value * 1000
        except ValueError:
            pass
        return 0.0

    def get_avg_latency_ms(self) -> float:
        if not self.request_latencies:
            return 0.0
        return sum(self.request_latencies) / len(self.request_latencies)


class ResourceMonitor:
    @staticmethod
    def get_resource_info() -> tuple[str, str]:
        try:
            total_cpu = psutil.cpu_percent(interval=None)

            mem = psutil.virtual_memory()
            total_mem_used = mem.used / (1024 * 1024 * 1024)

            cpu_text = f"{total_cpu:.1f} %"

            if total_mem_used >= 1.0:
                mem_text = f"{total_mem_used:.1f} GB"
            else:
                mem_text = f"{mem.used / (1024 * 1024):.1f} MB"

            return cpu_text, mem_text
        except Exception:
            return RequestText.CPU_LABEL, RequestText.MEM_LABEL_MB

    @staticmethod
    def format_latency(avg_latency: float) -> str:
        if avg_latency >= 1000:
            return f"{avg_latency / 1000:.1f} s"
        else:
            return f"{avg_latency:.0f} ms"


class ModelFetcher:
    def __init__(self, parent: Optional[QWidget] = None):
        self.parent = parent
        self.network_manager = QNetworkAccessManager(self.parent)

    def fetch_models(self, callback: Callable[..., None]) -> None:
        url = QUrl(OLLAMA_API_URL)
        request = QNetworkRequest(url)
        reply = self.network_manager.get(request)

        assert reply is not None

        if reply:
            reply.finished.connect(lambda: self._on_models_fetched(reply, callback))

    @staticmethod
    def _on_models_fetched(reply, callback: Callable[..., None]) -> None:
        if reply.error() != reply.NetworkError.NoError:
            callback(error=ModelText.FAILED_TO_FETCH)
            reply.deleteLater()
            return

        data = reply.readAll().data()
        reply.deleteLater()

        try:
            result = json.loads(data.decode("utf-8"))
            models = result.get("models", [])

            if not models:
                callback(error=ModelText.NO_MODELS_FOUND)
                return

            callback(models=models)

        except json.JSONDecodeError:
            callback(error=ModelText.INVALID_RESPONSE)


class StyleManager:
    @staticmethod
    def apply_styles(window: QWidget, styles_file_path: Path) -> None:
        app = QApplication.instance()
        if app:
            font = QFont()
            if sys.platform == "darwin":
                font.setFamily("SF Pro Display")
                if not font.exactMatch():
                    font.setFamily("Helvetica Neue")
                    if not font.exactMatch():
                        font.setFamily("Helvetica")
            elif sys.platform == "win32":
                font.setFamily("Segoe UI")
            else:
                font.setFamily("Sans Serif")

            font.setPointSize(12)
            QApplication.setFont(font)

        if styles_file_path.exists():
            window.setStyleSheet(styles_file_path.read_text())
