class AppText:
    WINDOW_TITLE = "Gradum"


class ButtonText:
    RUN = "Run"
    STOP = "Stop"
    REFRESH = "Refresh"
    RESET = "Reset"
    COPY = "Copy"
    CLEAR = "Clear"


class CardTitle:
    OPTIONS = "OPTIONS"
    MODELS = "MODELS"
    LOCAL_SETTINGS = "LOCAL SETTINGS"
    REQUESTS = "REQUESTS"
    PREVIEW = "PREVIEW"


class LabelText:
    CONTEXT_MEMORY = "Context Memory"
    CONTEXT_MEMORY_DESC = "Remember conversation history"
    THINK_MODE = "Think Mode"
    THINK_MODE_DESC = "Show step-by-step reasoning"
    TIMEOUT = "Timeout"
    TIMEOUT_DESC = "Request timeout in seconds"
    TEMPERATURE = "Temperature"
    TEMPERATURE_DESC = "Controls randomness (0.0-2.0)"
    TOP_P = "Top P"
    TOP_P_DESC = "Nucleus sampling threshold"
    CONTEXT_WINDOW = "Context Window"
    CONTEXT_WINDOW_DESC = "Maximum context length"
    MAX_TOKENS = "Max Tokens"
    MAX_TOKENS_DESC = "Maximum output tokens"
    TIME_HEADER = "Time"
    CODE_HEADER = "Code"
    METHOD_HEADER = "Method"
    DURATION_HEADER = "Duration"
    UNIX_MACOS = "Unix / macOS"
    WINDOWS = "Windows"


class PlaceholderText:
    PROMPT_INPUT = "What would you like me to do?"
    NO_REQUESTS = "No requests logged"
    NO_AVAILABLE_REQUESTS = "No available requests"
    FETCHING_MODELS = "FETCHING MODELS..."


class ModelText:
    CLOUD = "Cloud"
    LOCAL = "Local"
    FAILED_TO_FETCH = "Failed to fetch models"
    NO_MODELS_FOUND = "No models found"
    INVALID_RESPONSE = "Invalid response"
    OLLAMA_START_ERROR = "Failed to start Ollama service. Port may be in use."


class RequestText:
    CPU_LABEL = "0.0 %"
    MEM_LABEL_MB = "0.0 MB"
    LATENCY_LABEL = "0 ms"


class PreviewText:
    PYTHON_CMD = "python"
    AGENT_PY = "agent.py"
    PYTHON3_CMD = "python3"


class DialogText:
    TIMEOUT_ERROR = "Timeout must be a positive integer"
    DELETE_CONFIRM = "Are you sure you want to delete {count} selected item(s)?"
    DELETE_TITLE = "Delete Selected"
    DELETE_GROUP_TITLE = "Delete Group"
    DELETE_GROUP_MESSAGE = "Are you sure you want to delete '{name}'?"
    DELETE_CONV_TITLE = "Delete Conversation"
    DELETE_CONV_MESSAGE = "Are you sure you want to delete '{name}'?"
    NEW_GROUP_DIALOG = "New Conversation Group"
    RENAME_DIALOG = "Rename Group"
    GROUP_NAME_LABEL = "Group Name:"
    CONFIRM_OK = "OK"
    CONFIRM_CANCEL = "Cancel"
    RENAME_ACTION = "Rename"
    DELETE_ACTION = "Delete"
    INSERT_ACTION = "Insert"


class ChatText:
    CHATS_TITLE = "CHATS"
    NEW_GROUP = "New Group"
    SEARCH = "Search"
    NO_CONVERSATIONS = "No conversations yet"
    DELETE = "Delete"
    EXPAND_ALL = "Expand All"
    COLLAPSE_ALL = "Collapse All"
    DEFAULT_GROUP_NAME = "My Conversations"
    NEW_GROUP_DEFAULT = "New Group"
    NO_MESSAGE = "No message in this group"
    FOUND_MATCHES = "Found {count} matching conversation{suffix}"
