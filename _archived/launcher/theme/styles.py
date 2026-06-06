from launcher.theme.tokens import Theme


class ButtonVariant:
    PRIMARY = "primary"
    TEXT = "text"
    PILL = "pill"
    CHECKBOX = "checkbox"


class ButtonSize:
    XS = "xs"
    SM = "sm"
    MD = "md"


class InputType:
    TEXT = "text"
    PROMPT = "prompt"
    SPIN = "spin"


class LabelVariant:
    BODY = "body"
    TITLE = "title"
    CAPTION = "caption"
    SECONDARY = "secondary"
    GROUP_TITLE = "group_title"
    MONO = "mono"


def button_style(variant: str = ButtonVariant.PRIMARY, size: str = ButtonSize.MD) -> str:
    t = Theme
    c = t.colors
    s = t.spacing
    r = t.radius
    f = t.font

    size_map = {
        ButtonSize.XS: f"padding: {s.xs}px {s.s}px; font-size: {f.size_s}px;",
        ButtonSize.SM: f"padding: {s.s}px {s.m}px; font-size: {f.size_m}px;",
        ButtonSize.MD: f"padding: {s.s}px {s.l}px; font-size: {f.size_l}px;",
    }

    variants = {
        ButtonVariant.PRIMARY: f"""
            QPushButton {{
                background-color: {c.primary};
                color: {c.background};
                border: none;
                border-radius: {r.xxl}px;
                font-weight: {f.weight_semibold};
                letter-spacing: 0.6px;
                {size_map[size]}
            }}
            QPushButton:hover {{
                background-color: {c.primary_hover};
            }}
            QPushButton:pressed {{
                background-color: {c.primary_pressed};
            }}
            QPushButton:disabled {{
                background-color: {c.primary_disabled};
                color: {c.text_disabled};
            }}
        """,

        ButtonVariant.TEXT: f"""
            QPushButton {{
                background: transparent;
                border: none;
                color: {c.text_tertiary};
                font-weight: {f.weight_medium};
                letter-spacing: 0.2px;
                {size_map[size]}
            }}
            QPushButton:hover {{
                color: {c.text_primary};
            }}
        """,

        ButtonVariant.PILL: f"""
            QPushButton {{
                background-color: transparent;
                border: 1px solid {c.border_hover};
                border-radius: {r.xl}px;
                color: {c.text_tertiary};
                font-weight: {f.weight_medium};
                letter-spacing: 0.3px;
                {size_map[size]}
            }}
            QPushButton:hover {{
                background-color: {c.surface_hover};
                border-color: {c.text_tertiary};
                color: {c.text_secondary};
            }}
            QPushButton:pressed {{
                background-color: #e8e8e8;
            }}
        """,

        ButtonVariant.CHECKBOX: f"""
            QPushButton {{
                background-color: transparent;
                border: 1px solid {c.border_hover};
                border-radius: {r.s}px;
                color: transparent;
                font-weight: {f.weight_semibold};
                padding: 0;
                font-size: {f.size_s}px;
            }}
            QPushButton:hover {{
                border-color: {c.text_tertiary};
            }}
            QPushButton:checked {{
                background-color: {c.primary};
                border-color: {c.primary};
                color: {c.background};
            }}
        """,
    }

    return variants.get(variant, variants[ButtonVariant.PRIMARY])


def input_style(input_type: str = InputType.TEXT, has_error: bool = False, is_prompt: bool = False) -> str:
    t = Theme
    c = t.colors
    s = t.spacing
    r = t.radius
    f = t.font

    base = f"""
        QLineEdit, QTextEdit, QSpinBox, QDoubleSpinBox {{
            background-color: {c.surface};
            border: 1px solid {c.border};
            border-radius: {r.s}px;
            padding: {s.xs}px {s.s}px;
            color: {c.text_primary};
            font-size: {f.size_m}px;
            font-family: {t.font_mono()};
        }}
        QLineEdit:focus, QTextEdit:focus, QSpinBox:focus, QDoubleSpinBox:focus {{
            border-color: {c.border_hover};
            background-color: {c.background};
        }}
        QLineEdit::placeholder, QTextEdit::placeholder {{
            color: {c.text_placeholder};
            font-size: {f.size_l}px;
        }}
        QLineEdit:disabled, QTextEdit:disabled, QSpinBox:disabled, QDoubleSpinBox:disabled {{
            background-color: {c.surface_hover};
            color: {c.text_disabled};
        }}
    """

    if is_prompt or input_type == InputType.PROMPT:
        base = f"""
            QTextEdit {{
                background-color: transparent;
                border: none;
                color: {c.text_primary};
                font-size: {f.size_l}px;
                line-height: 1.6;
                font-family: {t.font_mono()};
            }}
            QTextEdit:focus {{
                border: none;
                background-color: transparent;
            }}
            QTextEdit::placeholder {{
                color: {c.text_placeholder};
                font-size: {f.size_l}px;
            }}
        """

    if has_error:
        base += f"""
            QLineEdit, QTextEdit, QSpinBox, QDoubleSpinBox {{
                border: 2px solid {c.danger_border};
                background-color: {c.danger_bg};
            }}
        """

    return base


def label_style(variant: str = LabelVariant.BODY) -> str:
    t = Theme
    c = t.colors
    f = t.font

    variants = {
        LabelVariant.BODY: f"""
            QLabel {{
                color: {c.text_primary};
                font-size: {f.size_l}px;
            }}
        """,
        LabelVariant.TITLE: f"""
            QLabel {{
                color: {c.text_primary};
                font-size: {f.size_m}px;
                font-weight: {f.weight_semibold};
            }}
        """,
        LabelVariant.CAPTION: f"""
            QLabel {{
                color: {c.text_secondary};
                font-size: {f.size_xs}px;
            }}
        """,
        LabelVariant.SECONDARY: f"""
            QLabel {{
                color: {c.text_secondary};
                font-size: {f.size_m}px;
            }}
        """,
        LabelVariant.GROUP_TITLE: f"""
            QLabel {{
                color: {c.text_secondary};
                font-size: {f.size_m}px;
                font-weight: {f.weight_semibold};
            }}
        """,
        LabelVariant.MONO: f"""
            QLabel {{
                color: {c.text_primary};
                font-size: {f.size_l}px;
                font-family: {t.font_mono()};
            }}
        """,
    }

    return variants.get(variant, variants[LabelVariant.BODY])


def preview_text_style() -> str:
    t = Theme
    c = t.colors
    s = t.spacing
    r = t.radius
    f = t.font

    return f"""
        QTextEdit {{
            background-color: {c.background};
            border: 1px solid {c.border};
            border-radius: {r.m}px;
            padding: {s.m}px;
            color: {c.text_primary};
            font-size: {f.size_m}px;
            line-height: 1.5;
            font-family: {t.font_mono()};
        }}
    """


def history_text_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_secondary};
            font-size: {f.size_xl}px;
            line-height: 1.4;
            letter-spacing: 0.4px;
        }}
    """


def request_time_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_primary};
            font-size: {f.size_l}px;
            font-family: {t.font_mono()};
        }}
    """


def request_code_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            font-size: {f.size_s}px;
            font-weight: {f.weight_semibold};
            font-family: {t.font_mono()};
        }}
    """


def request_method_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_secondary};
            font-size: {f.size_m}px;
            font-family: {t.font_mono()};
        }}
    """


def request_duration_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_tertiary};
            font-size: {f.size_s}px;
            font-family: {t.font_mono()};
        }}
    """


def model_badge_style() -> str:
    t = Theme
    c = t.colors
    s = t.spacing
    r = t.radius
    f = t.font

    return f"""
        QLabel {{
            border: 1px solid {c.primary};
            border-radius: {r.l}px;
            color: {c.primary};
            font-size: {f.size_xs}px;
            font-weight: {f.weight_semibold};
            padding: {s.xxs}px {s.s}px;
        }}
    """


def resource_label_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_primary};
            font-size: {f.size_s}px;
            font-weight: {f.weight_medium};
            font-family: {t.font_mono()};
        }}
    """


def option_title_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_primary};
            font-size: {f.size_m}px;
            font-weight: {f.weight_semibold};
            letter-spacing: 0.4px;
        }}
    """


def option_desc_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_tertiary};
            font-size: {f.size_xs}px;
            letter-spacing: 0.2px;
        }}
    """


def card_title_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_primary};
            font-size: {f.size_m}px;
            font-weight: {f.weight_semibold};
            letter-spacing: 1px;
        }}
    """


def history_time_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_placeholder};
            font-size: {f.size_l}px;
        }}
    """


def placeholder_style() -> str:
    t = Theme
    c = t.colors
    f = t.font
    s = t.spacing

    return f"""
        QLabel {{
            color: {c.text_disabled};
            font-size: {f.size_l}px;
            padding: {s.xl}px;
        }}
    """


def model_fetching_style() -> str:
    t = Theme
    c = t.colors
    f = t.font

    return f"""
        QLabel {{
            color: {c.text_tertiary};
            font-size: {f.size_l}px;
            font-style: italic;
        }}
    """
