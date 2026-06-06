from dataclasses import dataclass


@dataclass
class ColorTokens:
    primary: str = "#000000"
    primary_hover: str = "#333333"
    primary_pressed: str = "#555555"
    primary_disabled: str = "#cccccc"
    background: str = "#ffffff"
    surface: str = "#fafafa"
    surface_hover: str = "#f5f5f5"
    border: str = "#e8e8e8"
    border_light: str = "#e0e0e0"
    border_hover: str = "#cccccc"
    text_primary: str = "#000000"
    text_secondary: str = "#333333"
    text_tertiary: str = "#666666"
    text_placeholder: str = "#aaaaaa"
    text_disabled: str = "#999999"
    danger: str = "#e46a76"
    danger_bg: str = "#fff2f3"
    danger_border: str = "#e74c3c"
    success: str = "#065f46"
    success_bg: str = "#d1fae5"
    info: str = "#1e40af"
    info_bg: str = "#dbeafe"


@dataclass
class SpacingTokens:
    xxs: int = 2
    xs: int = 4
    s: int = 8
    m: int = 12
    l: int = 16
    xl: int = 20
    xxl: int = 24


@dataclass
class RadiusTokens:
    s: int = 4
    m: int = 6
    l: int = 8
    xl: int = 12
    xxl: int = 16


@dataclass
class FontTokens:
    size_xs: int = 9
    size_s: int = 10
    size_m: int = 11
    size_l: int = 12
    size_xl: int = 13
    weight_regular: int = 400
    weight_medium: int = 500
    weight_semibold: int = 600


@dataclass
class Theme:
    colors = ColorTokens()
    spacing = SpacingTokens()
    radius = RadiusTokens()
    font = FontTokens()

    @staticmethod
    def font_family() -> str:
        return '"SF Pro", BlinkMacSystemFont, system-ui, "Segoe UI", Roboto, "Helvetica Neue", sans-serif'

    @staticmethod
    def font_mono() -> str:
        return '"Menlo", "Monaco", "Courier New", monospace'
