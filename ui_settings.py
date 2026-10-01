"""Searchable settings shell, independent of Friday's service handlers."""
from PyQt6.QtCore import Qt, QPropertyAnimation, QEasingCurve
from PyQt6.QtGui import QKeySequence, QShortcut
from PyQt6.QtWidgets import (
    QWidget, QFrame, QLabel, QLineEdit, QPushButton, QVBoxLayout, QHBoxLayout,
    QScrollArea, QStackedWidget, QGraphicsOpacityEffect, QComboBox,
)


CATEGORIES = {
    "AI & Vision": "Choose your AI provider and configure access keys.",
    "Voice": "Set your voice, microphone and hands-free controls.",
    "Devices": "Pair your phone and manage PC connections.",
    "Integrations": "Connect the services you use with Friday.",
    "Automation": "Manage startup, notifications and recovery.",
    "Appearance": "Personalize colors, sound and motion.",
    "Profile & System": "Edit your profile and manage the application.",
}


class SettingsShell(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("SettingsShell")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self._entries = []
        self._pages = {}
        self._buttons = {}
        self._current = next(iter(CATEGORIES))
        self._reduced_motion = False
        self.setStyleSheet("""
            QWidget#SettingsShell { background: rgba(16,20,29,235); border-radius: 22px; }
            QWidget { color: #F5F5F7; font-family: 'Segoe UI'; font-size: 13px; }
            QFrame#SettingsNavigation { background: rgba(255,255,255,9); border: 1px solid rgba(255,255,255,20); border-radius: 18px; }
            QFrame#SettingsCard { background: qlineargradient(x1:0,y1:0,x2:1,y2:1,stop:0 rgba(255,255,255,15),stop:1 rgba(255,255,255,7)); border: 1px solid rgba(255,255,255,23); border-radius: 18px; }
            QLabel { background: transparent; border: none; }
            QLabel#SettingsTitle { font-size: 28px; font-weight: 600; }
            QLabel#SettingsDescription, QLabel#SettingsNote { color: #A2ACBD; }
            QLabel#SettingsNote { font-size: 12px; }
            QLabel[settingsHeading="true"] { font-size: 17px; font-weight: 600; }
            QLineEdit, QComboBox, QTextEdit { background: rgba(0,0,0,38); color: #F5F5F7; border: 1px solid rgba(255,255,255,32); border-radius: 10px; padding: 8px; min-height: 24px; selection-background-color: #0A84FF; }
            QLineEdit:focus, QComboBox:focus, QTextEdit:focus { border-color: #0A84FF; }
            QPushButton { background: rgba(255,255,255,13); border: 1px solid rgba(255,255,255,24); border-radius: 10px; padding: 9px 12px; }
            QPushButton:hover { background: rgba(10,132,255,35); border-color: rgba(10,132,255,110); }
            QPushButton:focus { border-color: #0A84FF; }
            QPushButton:disabled { color: #687182; }
            QPushButton[settingsNav="true"] { text-align: left; border: none; background: transparent; padding: 12px; }
            QPushButton[settingsNav="true"]:checked { background: rgba(10,132,255,40); color: #72BBFF; }
            QCheckBox { spacing: 10px; padding: 8px 0; }
            QCheckBox::indicator { width: 20px; height: 20px; border-radius: 6px; background: rgba(255,255,255,16); border: 1px solid rgba(255,255,255,55); }
            QCheckBox::indicator:checked { background: #0A84FF; border-color: #72BBFF; }
            QScrollArea { background: transparent; border: none; }
            QWidget#SettingsPage { background: transparent; }
            QScrollBar:vertical { background: transparent; width: 8px; }
            QScrollBar::handle:vertical { background: rgba(255,255,255,48); border-radius: 4px; min-height: 32px; }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
            QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background: transparent; }
        """)
        root = QVBoxLayout(self)
        root.setContentsMargins(22, 22, 22, 18)
        root.setSpacing(18)
        header = QHBoxLayout()
        title = QLabel("Settings")
        title.setObjectName("SettingsTitle")
        header.addWidget(title)
        header.addStretch()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search settings  ·  Ctrl+F")
        self.search.setAccessibleName("Search settings")
        self.search.setClearButtonEnabled(True)
        self.search.setMaximumWidth(340)
        header.addWidget(self.search)
        root.addLayout(header)
        body = QHBoxLayout()
        body.setSpacing(18)
        nav = QFrame()
        nav.setObjectName("SettingsNavigation")
        nav.setFixedWidth(176)
        nav_layout = QVBoxLayout(nav)
        nav_layout.setContentsMargins(10, 12, 10, 12)
        nav_layout.setSpacing(5)
        body.addWidget(nav)
        content = QVBoxLayout()
        self.heading = QLabel(self._current)
        self.heading.setProperty("settingsHeading", True)
        self.description = QLabel(CATEGORIES[self._current])
        self.description.setObjectName("SettingsDescription")
        self.description.setWordWrap(True)
        content.addWidget(self.heading)
        content.addWidget(self.description)
        self.stack = QStackedWidget()
        for name in CATEGORIES:
            button = QPushButton(name.replace("&", "&&"))
            button.setAccessibleName(name)
            button.setProperty("settingsNav", True)
            button.setCheckable(True)
            button.setCursor(Qt.CursorShape.PointingHandCursor)
            button.clicked.connect(lambda checked=False, category=name: self.select_category(category))
            nav_layout.addWidget(button)
            self._buttons[name] = button
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            host = QWidget()
            host.setObjectName("SettingsPage")
            layout = QVBoxLayout(host)
            layout.setContentsMargins(0, 4, 8, 4)
            layout.setSpacing(14)
            layout.addStretch()
            scroll.setWidget(host)
            self.stack.addWidget(scroll)
            self._pages[name] = (scroll, layout)
        nav_layout.addStretch()
        content.addWidget(self.stack, 1)
        self.empty = QLabel("No matching settings. Try voice, Gemini, startup or theme.")
        self.empty.setWordWrap(True)
        self.empty.setObjectName("SettingsDescription")
        self.empty.hide()
        content.addWidget(self.empty)
        body.addLayout(content, 1)
        root.addLayout(body, 1)
        note = QLabel("Toggles save automatically. Use Save or Connect for account setup.")
        note.setObjectName("SettingsNote")
        note.setWordWrap(True)
        root.addWidget(note)
        self._fade_effect = QGraphicsOpacityEffect(self.stack)
        self.stack.setGraphicsEffect(self._fade_effect)
        self._fade = QPropertyAnimation(self._fade_effect, b"opacity", self)
        self._fade.setDuration(140)
        self._fade.setEasingCurve(QEasingCurve.Type.OutCubic)
        self.search.textChanged.connect(self._filter)
        shortcut = QShortcut(QKeySequence("Ctrl+F"), self)
        shortcut.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
        shortcut.activated.connect(self.search.setFocus)
        self.select_category(self._current)

    def add_card(self, category, title, card):
        # Service widgets retain their signals/state; presentation belongs to
        # this shell rather than dozens of conflicting inline styles.
        card.setStyleSheet("")
        for child in card.findChildren(QWidget):
            child.setStyleSheet("")
        for combo in card.findChildren(QComboBox):
            combo.setSizeAdjustPolicy(QComboBox.SizeAdjustPolicy.AdjustToMinimumContentsLengthWithIcon)
            combo.setMinimumContentsLength(12)
        card.setObjectName("SettingsCard")
        labels = card.findChildren(QLabel)
        for label in labels:
            if label.minimumWidth() == label.maximumWidth():
                continue
            label.setWordWrap(True)
            label.setMinimumWidth(0)
        if labels:
            labels[0].setProperty("settingsHeading", True)
        layout = self._pages[category][1]
        layout.insertWidget(layout.count() - 1, card)
        # Search labels and placeholders only. Never index input values, which
        # may contain passwords, tokens or private account information.
        keywords = " ".join([category, title] + [label.text() for label in labels] +
                            [edit.placeholderText() for edit in card.findChildren(QLineEdit)])
        self._entries.append((category, card, keywords.casefold()))

    def set_reduced_motion(self, enabled):
        self._reduced_motion = bool(enabled)
        if enabled:
            self._fade.stop()
            self._fade_effect.setOpacity(1)

    def select_category(self, name):
        self._current = name
        self.heading.setText(name)
        self.description.setText(CATEGORIES[name])
        self.stack.setCurrentWidget(self._pages[name][0])
        for category, button in self._buttons.items():
            button.setChecked(category == name)
        self.empty.setVisible(not any(c == name and not card.isHidden() for c, card, _ in self._entries))
        if self.isVisible() and not self._reduced_motion:
            self._fade.stop()
            self._fade.setStartValue(.65)
            self._fade.setEndValue(1.0)
            self._fade.start()

    def _filter(self, query):
        tokens = query.casefold().split()
        matches = set()
        for category, card, keywords in self._entries:
            visible = all(token in keywords for token in tokens)
            card.setVisible(visible)
            if visible:
                matches.add(category)
        for category, button in self._buttons.items():
            button.setEnabled(category in matches)
        if self._current not in matches and matches:
            self.select_category(next(name for name in CATEGORIES if name in matches))
        self.empty.setVisible(not matches)
