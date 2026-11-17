#!/usr/bin/env python3
"""Rebuild the public guide's option index from English app UI resources.

Run from any directory. No credentials, build outputs or device files are read.
The coverage report stays outside the publicly served landing directory.
"""

from __future__ import annotations

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "app/src/main/assets/web"
RES = ROOT / "app/src/main/res"
OUTPUT = ROOT / "vps/web/landing/guide/reference.js"
REPORT = ROOT / "docs/setup-guide-coverage.json"
ANDROID = "{http://schemas.android.com/apk/res/android}"

CATEGORIES = {
    "index": ("Dashboard", "personalise"),
    "recording": ("Recording", "recording"),
    "surveillance": ("Surveillance", "sentry"),
    "storage": ("Storage", "storage"),
    "remote-access": ("Proximity access", "proximity"),
    "assistant": ("Assistant & voice", "ai"),
    "road-sense": ("RoadSense & maps", "maps"),
    "safekeep": ("SafeKeep backup", "integrations"),
    "mqtt": ("MQTT & Home Assistant", "integrations"),
    "abrp": ("ABRP", "integrations"),
    "byd-cloud": ("BYD Cloud", "byd-cloud"),
    "wireguard": ("WireGuard", "tunnels"),
    "daemons": ("Daemons", "daemons"),
    "network": ("Network & hotspot", "tunnels"),
    "telegram": ("Telegram", "notifications"),
    "notifications": ("Notifications", "notifications"),
    "widgets": ("Widgets", "personalise"),
    "widget-builder": ("Widget builder", "personalise"),
    "automations": ("Automations", "automations"),
    "key-mapping": ("Key mapping", "automations"),
    "vehicle-control": ("Vehicle control", "vehicle-control"),
    "seat-positions": ("Seat positions", "vehicle-control"),
    "charging": ("Charging", "everyday"),
    "trips": ("Trips", "everyday"),
    "parking": ("Parking", "everyday"),
    "events": ("Events & recordings", "recording"),
    "live-view": ("Live view", "cameras"),
    "factory-dvr": ("Factory DVR", "recording"),
    "communicate": ("Communication", "communicate"),
    "sessions": ("Sessions", "remote"),
    "login": ("Web login", "remote"),
    "about": ("About & updates", "maintenance"),
    "performance": ("Performance", "diagnostics"),
    "remote-dev-view": ("Remote developer view", "diagnostics"),
    "module-faults": ("Module faults", "diagnostics"),
}

NATIVE_FILES = {
    "fragment_settings_appearance.xml": ("Appearance & navigation", "personalise"),
    "fragment_settings_overlay.xml": ("Overlays", "personalise"),
    "fragment_settings_adb.xml": ("ADB", "adb"),
    "fragment_settings_security.xml": ("Security", "first-launch"),
    "fragment_settings_privacy.xml": ("Privacy & reset", "maintenance"),
    "fragment_settings_about.xml": ("About", "maintenance"),
    "fragment_projection.xml": ("Projection", "personalise"),
    "activity_roadsense_map.xml": ("Map & navigation", "maps"),
    "dialog_camera_mapping.xml": ("Camera mapping", "cameras"),
    "dialog_camera_selection.xml": ("Camera selection", "cameras"),
    "dialog_vehicle_setup.xml": ("Vehicle setup", "first-launch"),
    "dialog_vehicle_capacity.xml": ("Battery capacity", "first-launch"),
    "dialog_setup_guide.xml": ("First launch", "first-launch"),
    "dialog_zrok_token.xml": ("Zrok", "tunnels"),
    "dialog_cloudflared_settings.xml": ("Cloudflare", "tunnels"),
    "dialog_tailscale_settings.xml": ("Tailscale", "tunnels"),
    "dialog_wireguard_settings.xml": ("WireGuard", "tunnels"),
    "dialog_scratch_root.xml": ("Daemon storage", "diagnostics"),
    "dialog_language_picker.xml": ("Language", "personalise"),
    "dialog_reset_data.xml": ("Reset data", "maintenance"),
    "dialog_send_log.xml": ("Send logs", "maintenance"),
    "dialog_battery_health.xml": ("Battery health", "everyday"),
    "fragment_diagnostics.xml": ("Diagnostics", "diagnostics"),
    "fragment_adb_console.xml": ("ADB Console", "diagnostics"),
}

PAGE_PATHS = {
    "index": "Dashboard",
    "assistant": "Assistant",
    "recording": "Settings → Recording",
    "surveillance": "Settings → Surveillance",
    "storage": "Settings → Storage",
    "remote-access": "Main menu → Proximity",
    "road-sense": "Main menu → RoadSense",
    "widgets": "Main menu → Widgets",
    "widget-builder": "Car web interface → Settings → Widget Builder",
    "notifications": "Main menu → Notifications",
    "daemons": "Settings → Daemons",
    "network": "Main menu → Network",
    "wireguard": "Car web interface → Integrations → WireGuard",
    "byd-cloud": "Integrations → BYD Cloud",
    "telegram": "Integrations → Telegram",
    "abrp": "Integrations → ABRP",
    "mqtt": "Integrations → MQTT",
    "safekeep": "Integrations → SafeKeep",
    "vehicle-control": "Main menu → Vehicle / Vehicle Control",
    "seat-positions": "Main menu → Seat Positions",
    "automations": "Main menu → Automations",
    "key-mapping": "Main menu → Key Mapping",
    "trips": "Main menu → Trips",
    "charging": "Main menu → Charging",
    "parking": "Main menu → Parking",
    "events": "Main menu → Recordings",
    "live-view": "Main menu → Live View",
    "factory-dvr": "Recordings → Factory DVR",
    "communicate": "Car web interface → Communicate",
    "sessions": "Car web interface → Settings → Signed-in Browsers",
    "login": "Car web interface → Login",
    "performance": "Car web interface → Diagnostics → Performance",
    "remote-dev-view": "Car web interface → Diagnostics → Remote Dev View",
    "module-faults": "Diagnostics → Module faults",
    "about": "Main menu → About",
}

NATIVE_PATHS = {
    "fragment_settings_appearance.xml": "Settings → Appearance",
    "fragment_settings_overlay.xml": "Settings → Status overlay",
    "fragment_settings_adb.xml": "Settings → ADB",
    "fragment_settings_security.xml": "Settings → Security",
    "fragment_settings_privacy.xml": "Settings → Privacy & data",
    "fragment_settings_about.xml": "Main menu → About",
    "fragment_projection.xml": "Main menu → Projection",
    "activity_roadsense_map.xml": "On-car main menu → Map",
    "dialog_camera_mapping.xml": "Diagnostics → Camera probe → Camera Mapping",
    "dialog_camera_selection.xml": "Diagnostics → Camera probe → Camera Mapping → Manual camera IDs — advanced",
    "dialog_vehicle_setup.xml": "Diagnostics → Vehicle setup → Run vehicle setup",
    "dialog_vehicle_capacity.xml": "Dashboard → Vehicle → Set battery capacity",
    "dialog_setup_guide.xml": "First-launch setup",
    "dialog_zrok_token.xml": "Settings → Daemons → Zrok Tunnel → Settings",
    "dialog_cloudflared_settings.xml": "Settings → Daemons → Cloudflared Tunnel → Settings",
    "dialog_tailscale_settings.xml": "Settings → Daemons → Tailscale Tunnel → Settings",
    "dialog_wireguard_settings.xml": "Settings → Daemons → WireGuard Tunnel → Settings",
    "dialog_scratch_root.xml": "Diagnostics → Tools → Daemon storage",
    "dialog_language_picker.xml": "Settings → Appearance → Language",
    "dialog_reset_data.xml": "Settings → Privacy & data → Reset data",
    "dialog_send_log.xml": "Settings → Privacy & data → Send logs",
    "dialog_battery_health.xml": "Diagnostics → Tools → Battery health",
    "fragment_diagnostics.xml": "Diagnostics → Tools",
    "fragment_adb_console.xml": "Diagnostics → Tools → ADB Console",
}


def flatten(value, prefix=""):
    result = {}
    for key, item in value.items():
        full = f"{prefix}.{key}" if prefix else key
        if isinstance(item, dict):
            result.update(flatten(item, full))
        elif isinstance(item, str):
            result[full] = item
    return result


TRANSLATIONS = flatten(json.loads((WEB / "i18n/en.json").read_text()))
STRINGS = {}
for resource in sorted((RES / "values").glob("*.xml")):
    try:
        tree = ET.parse(resource)
    except ET.ParseError:
        continue
    for element in tree.findall("string"):
        STRINGS[element.get("name")] = "".join(element.itertext()).replace("\\n", " ")


def clean(value):
    value = re.sub(r"<[^>]*>", " ", value or "")
    value = re.sub(r"\$\{[^}]*\}", "", value)
    return re.sub(r"\s+", " ", value).strip()


def humanise(value):
    value = re.sub(r"^(?:sw|et|tv|btn|edit|input|select|switch|slider|toggle)(?=[A-Z_-])", "", value)
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", value).replace("_", " ").replace("-", " ")
    value = re.sub(r"\b(?:Toggle|Slider|Input|Select|Switch|Field)\b", "", value, flags=re.I)
    return clean(value).capitalize()


class Node:
    def __init__(self, tag="", attrs=(), parent=None):
        self.tag, self.attrs, self.parent = tag, dict(attrs), parent
        self.children = []

    @property
    def classes(self):
        return set(self.attrs.get("class", "").split())

    def descendants(self):
        for child in self.children:
            if isinstance(child, Node):
                yield child
                yield from child.descendants()

    def text(self):
        translated = TRANSLATIONS.get(self.attrs.get("data-i18n", ""))
        if translated:
            return clean(translated)
        if self.tag in {"script", "style", "svg"}:
            return ""
        return clean(" ".join(child.text() if isinstance(child, Node) else child for child in self.children))


class Tree(HTMLParser):
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.root = self.current = Node()
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        child = Node(tag, attrs, self.current)
        self.current.children.append(child)
        if tag not in self.VOID:
            self.current = child

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        current = self.current
        while current.parent:
            if current.tag == tag:
                self.current = current.parent
                return
            current = current.parent

    def handle_data(self, data):
        self.current.children.append(data)


def closest(node, predicate):
    while node.parent:
        node = node.parent
        if predicate(node):
            return node
    return None


def first_text(node, classes):
    if node:
        for child in node.descendants():
            if child.classes.intersection(classes) and child.text():
                return child.text()
    return ""


def html_location(source, node, label):
    """Use the actual menu, tab, card and setting names, not the index category."""
    stem = source.stem
    parts = [PAGE_PATHS[stem]]
    page_source = (WEB / "local" / f"{stem}.html").read_text()
    tabs = {}
    for identifier, title, key in re.findall(
        r"\{\s*id:\s*['\"]([^'\"]+)['\"],\s*label:\s*['\"]([^'\"]+)['\"],\s*i18n:\s*['\"]([^'\"]+)['\"]",
        page_source,
    ):
        tabs[identifier] = TRANSLATIONS.get(key, title)
    tab = node if "data-tab" in node.attrs else closest(node, lambda item: "data-tab" in item.attrs)
    if tab is not None and tab.attrs["data-tab"] in tabs:
        parts.append(tabs[tab.attrs["data-tab"]])
    # Vehicle panels use data-panel instead of the shared bottom-tab manifest.
    panel = closest(node, lambda item: item.attrs.get("id", "").startswith("panel"))
    if panel is not None:
        panel_id = panel.attrs["id"]
        for candidate in Tree(page_source).root.descendants():
            if candidate.attrs.get("data-panel") == panel_id:
                parts.append(candidate.text())
                break
    card = closest(node, lambda item: bool(item.classes.intersection({"card", "r-hero", "notif-section"})))
    title = first_text(card, {"card-title", "r-hero-title"})
    if not title and card is not None and "notif-section" in card.classes:
        title = next((item.text() for item in card.descendants() if item.tag in {"h2", "h3"}), "")
    title = re.sub(r"\s+(?:OFF|ON|—|● unsaved)$", "", title)
    if title:
        parts.append(title)
    if source.suffix == ".js" and stem == "mqtt":
        parts.append("Add connection / Edit connection")
    elif source.suffix == ".js" and stem == "automations":
        parts.append("Add → selected condition / action")
    if label not in parts:
        parts.append(label)
    return " → ".join(parts)


def html_category(source, node, identifier, category, chapter):
    if source.stem == "assistant":
        tab = closest(node, lambda item: "data-tab" in item.attrs)
        if identifier.lower().startswith("voice"):
            return "Hands-free voice", "voice"
        if identifier.startswith("tb") or (tab is not None and tab.attrs["data-tab"] == "briefing"):
            return "Trip Briefing", "briefing"
    return category, chapter


def native_location(source, element, parents, resolved, label):
    parts = [NATIVE_PATHS[source.name]]
    parent = element
    while parent in parents:
        parent = parents[parent]
        if "CardView" in parent.tag:
            title = next((resolved(item, "text") for item in parent.iter()
                          if item.tag.rsplit(".", 1)[-1] == "TextView" and resolved(item, "text")), "")
            if title and not parts[0].endswith(title):
                parts.append(title)
            break
    identifier = element.get(ANDROID + "id", "").rsplit("/", 1)[-1]
    if source.name == "dialog_camera_mapping.xml" and identifier in {
        "spinnerCameraRole", "btnPrevCandidate", "btnNextCandidate",
        "btnClearCameraRoleMapping", "btnSaveCameraRoleMapping",
    }:
        parts.append(STRINGS["camera_section_slice_calibration"])
    if label not in parts and not parts[-1].endswith(f"→ {label}"):
        parts.append(label)
    return " → ".join(parts)


def label_text(node):
    """Avoid merging a label's title, help text and select options."""
    for child in node.descendants():
        key = child.attrs.get("data-i18n", "")
        if key and not re.search(r"desc|hint|help|support|note", key) and child.tag not in {"option", "input"}:
            return child.text()
    return node.text()


FIELD_KEYS = {
    "formName": "mqtt.field_name", "formBrokerUrl": "mqtt.field_broker_url",
    "formPort": "mqtt.field_port", "formTopic": "mqtt.field_topic",
    "formUsername": "mqtt.field_username", "formPassword": "mqtt.field_password",
    "formClientId": "mqtt.field_client_id", "formQos": "mqtt.field_qos",
    "formMinInterval": "mqtt.field_min_interval", "formMaxInterval": "mqtt.field_max_interval",
    "formParkedInterval": "mqtt.field_parked_interval", "formChargingInterval": "mqtt.field_charging_interval",
    "formChangeOnly": "mqtt.change_only", "formHaDiscovery": "mqtt.ha_discovery",
    "formDiscoveryPrefix": "mqtt.discovery_prefix", "formHeartbeatSendAll": "mqtt.heartbeat_send_all",
    "formFlushOnStateChange": "mqtt.flush_on_state_change", "formAllowControl": "mqtt.allow_control",
    "formRetain": "mqtt.field_retain", "formEnabled": "mqtt.field_enabled",
}

FIELD_LABELS = {
    "formName": "Connection name", "formBrokerUrl": "Broker address",
    "formPort": "Port", "formTopic": "Telemetry topic",
    "formUsername": "Username", "formPassword": "Password",
    "formClientId": "Client ID", "formQos": "Delivery quality (QoS)",
    "formMinInterval": "Minimum publish interval", "formMaxInterval": "Heartbeat interval",
    "formParkedInterval": "Parked publish interval", "formChargingInterval": "Charging publish interval",
    "formChangeOnly": "Publish changed values only", "formHaDiscovery": "Home Assistant discovery",
    "formDiscoveryPrefix": "Discovery prefix", "formHeartbeatSendAll": "Send all values at heartbeat",
    "formFlushOnStateChange": "Publish immediately on state change",
    "formAllowControl": "Allow vehicle control", "formRetain": "Retain messages", "formEnabled": "Enable connection",
}

FIELD_HELP = {
    "formName": "A friendly label so you can recognise this MQTT connection.",
    "formBrokerUrl": "The hostname or IP of your MQTT broker. Use the network route configured for this connection.",
    "formPort": "Your broker's listening port, commonly 1883 for plain MQTT or 8883 for TLS.",
    "formTopic": "The topic under which OverDrive publishes vehicle telemetry.",
    "formClientId": "A unique broker client name; leave the app-generated value unless your broker needs another.",
    "formQos": "0 sends once; 1 confirms delivery and may repeat; 2 uses an extra handshake. Match your broker.",
    "formMinInterval": "The fastest interval at which changed telemetry can be published.",
    "formMaxInterval": "The longest interval before a heartbeat refresh.",
    "formParkedInterval": "Override the publish interval while parked. Zero keeps the normal behaviour.",
    "formChargingInterval": "Override the publish interval while charging. Zero keeps the normal behaviour.",
    "formChangeOnly": "Avoid repeatedly publishing values that have not changed.",
    "formHaDiscovery": "Advertise OverDrive's entities so Home Assistant can add them automatically.",
    "formDiscoveryPrefix": "Match the discovery prefix used by Home Assistant, usually homeassistant.",
    "formHeartbeatSendAll": "Refresh every value at the heartbeat, including values that have not changed.",
    "formFlushOnStateChange": "Publish promptly when the vehicle changes state.",
    "formAllowControl": "Accept Home Assistant commands for supported vehicle functions. Starts off.",
    "formRetain": "Ask the broker to keep the last message for new subscribers.",
    "formEnabled": "Enable or pause this connection without deleting its details.",
}

FIELD_DEFAULTS = {
    "formMinInterval": "5 seconds", "formMaxInterval": "300 seconds",
    "formParkedInterval": "0", "formChargingInterval": "0", "formChangeOnly": "On",
    "formHaDiscovery": "Off", "formDiscoveryPrefix": "homeassistant",
    "formHeartbeatSendAll": "Off", "formFlushOnStateChange": "On",
    "formAllowControl": "Off", "formRetain": "Off", "formEnabled": "On",
    "genAiEnabled": "Off", "recordingMode": "None",
    "swAdbAutomaticRestore": "Off", "swDiLink5AdbDialogAutoConfirm": "Off",
    "swNativeVehicleProfile": "Off",
}


def platform_for(identifier, label):
    # Historical DiLink5 IDs also back these manual OEM tools on other modes.
    if identifier in {
        "btnDiLink5AdbEnable", "btnDiLink5AdbPage", "btnDiLink5AdbDiagnostics",
        "btnDiLink5AdbRestore", "btnDiLink5AdbExport",
    }:
        return ["di3", "di4", "di5"]
    text = f"{identifier} {label}".lower()
    if "camera2" in text:
        # This is a separate ingestion mode, not the native DI5 path.
        return ["camera2"]
    if re.search(r"di(?:link)?5|di(?:link)? 5", text):
        return ["di5"]
    if re.search(r"di(?:link)?4|di(?:link)? 4", text):
        return ["di4"]
    return ["di3", "di4", "di5"]


entries, coverage, seen = [], [], {}


def add(entry, source, identifier):
    label = clean(entry["label"])
    if not label or label in {"—", "--", "…"}:
        label = humanise(identifier)
    entry["label"] = label
    identity = (entry["category"], label, identifier, tuple(entry.get("choices", [])))
    # Keep provenance of duplicate orientations/templates in the private coverage report.
    coverage.append({"source": str(source.relative_to(ROOT)), "control": identifier,
                     "label": label, "path": entry["path"], "chapter": entry["chapter"]})
    if identity in seen:
        previous = seen[identity]
        # An orientation variant may omit supporting copy. Keep the useful version.
        if previous["description"].startswith(("Use ", "Configure ")) and not entry["description"].startswith(("Use ", "Configure ")):
            previous["description"] = entry["description"]
        return
    seen[identity] = entry
    entry["id"] = hashlib.sha256(f"{entry['category']}:{label}:{identifier}".encode()).hexdigest()[:12]
    entry["platforms"] = platform_for(identifier, label)
    if identifier in FIELD_DEFAULTS:
        entry["default"] = FIELD_DEFAULTS[identifier]
    entries.append(entry)


def html_fields(source, category, chapter, content=None):
    tree = Tree(content if content is not None else source.read_text())
    nodes = list(tree.root.descendants())
    by_id = {node.attrs["id"]: node for node in nodes if "id" in node.attrs}
    labels = {node.attrs["for"]: label_text(node) for node in nodes if node.tag == "label" and "for" in node.attrs}
    for index, node in enumerate(nodes):
        if node.tag not in {"input", "select", "textarea"}:
            continue
        kind = node.attrs.get("type", "text") if node.tag == "input" else node.tag
        if kind == "hidden":
            continue
        identifier = node.attrs.get("id") or node.attrs.get("name") or f"field-{index}"
        if "${" in identifier:
            identifier = re.sub(r"\$\{[^}]+\}", "item", identifier)
        row = closest(node, lambda item: bool(item.classes.intersection({
            "setting-row", "form-group", "md-field", "ra-setting", "control-row",
            "settings-row", "input-group", "field", "mode-option", "form-field", "notif-row",
        })))
        wrapper = closest(node, lambda item: item.tag == "label" and bool(item.text()))
        aria_label = " ".join(by_id[value].text() for value in node.attrs.get("aria-labelledby", "").split() if value in by_id)
        sibling_label = next((child for child in node.parent.children if isinstance(child, Node) and child.tag == "label" and child.text()), None)
        label = labels.get(identifier) or aria_label or first_text(row, {
            "setting-name", "md-label", "form-label", "control-label", "field-label",
            "mode-name", "ra-label", "setting-label", "label", "notif-row-label",
        }) or (label_text(wrapper) if wrapper else "") or node.attrs.get("aria-label") or (label_text(sibling_label) if sibling_label else "") or ""
        if not label:
            key = node.attrs.get("data-i18n-placeholder", "")
            label = TRANSLATIONS.get(key) or node.attrs.get("placeholder") or humanise(identifier)
        description = " ".join(by_id[value].text() for value in node.attrs.get("aria-describedby", "").split() if value in by_id) or first_text(row, {
            "setting-desc", "md-supporting", "field-hint", "form-hint",
            "control-desc", "mode-desc", "setting-hint", "hint-text", "notif-row-note",
        })
        if not description and wrapper:
            description = " ".join(child.text() for child in wrapper.descendants()
                                   if re.search(r"desc|hint|help", child.attrs.get("data-i18n", "")))
        if identifier in FIELD_LABELS:
            label = TRANSLATIONS.get(FIELD_KEYS.get(identifier, "")) or FIELD_LABELS[identifier]
            description = FIELD_HELP.get(identifier, description)
        elif source.stem == "notifications" and node.attrs.get("aria-label"):
            label = node.attrs["aria-label"]
        # Small "Applies live"/"Next clip" badges are not part of the setting name.
        label = re.sub(r"\s+(?:Applies live|Next clip)$", "", label)
        choices = [child.text() for child in node.descendants() if child.tag == "option" and child.text()]
        if kind == "checkbox":
            choices = ["On", "Off"]
        elif kind in {"range", "number"}:
            lo, hi = node.attrs.get("min"), node.attrs.get("max")
            if lo is not None and hi is not None:
                choices = [f"{lo}–{hi}" + (f" · step {node.attrs['step']}" if "step" in node.attrs else "")]
        elif kind == "radio":
            choices = [node.attrs.get("value", "Select this option")]
        if len(label) > 110:
            label = humanise(identifier)
        field_category, field_chapter = html_category(source, node, identifier, category, chapter)
        add({
            "label": label,
            "description": description or f"Configure {label.lower()} on the {category} page.",
            "category": field_category,
            "chapter": field_chapter,
            "path": html_location(source, node, label),
            "kind": kind,
            "choices": choices,
        }, source, identifier)

    # Button-only choice groups (layout, codec, quality presets, tabs with saved values).
    for node in nodes:
        if not node.classes.intersection({"btn-group", "segmented-control", "quality-presets", "notif-seg", "vc-segmented"}) and node.attrs.get("role") != "radiogroup":
            continue
        buttons = [child for child in node.descendants() if child.attrs.get("role") == "radio" or
                   (child.tag == "button" and (child.attrs.get("data-value") or child.attrs.get("data-state") or "btn-toggle" in child.classes))]
        if len(buttons) < 2 or any(child.tag == "select" for child in node.descendants()):
            continue
        row = closest(node, lambda item: bool(item.classes.intersection({"setting-row", "notif-row", "vc-block"})))
        aria_label = " ".join(by_id[value].text() for value in node.attrs.get("aria-labelledby", "").split() if value in by_id)
        label = first_text(row, {"setting-name", "setting-label", "notif-row-label", "vc-block-title"}) or aria_label or node.attrs.get("aria-label") or humanise(node.attrs.get("id", "Choice presets"))
        identifier = node.attrs.get("id", label)
        field_category, field_chapter = html_category(source, node, identifier, category, chapter)
        add({
            "label": label,
            "description": first_text(row, {"setting-desc", "notif-row-note"}) or f"Choose a preset for {label.lower()}.",
            "category": field_category, "chapter": field_chapter,
            "path": html_location(source, node, label),
            "kind": "choice", "choices": [
                child.text() or first_text(closest(child, lambda item: "notif-row" in item.classes), {"notif-row-label"})
                for child in buttons
            ],
        }, source, identifier)


for source in sorted((WEB / "local").glob("*.html")):
    if source.stem not in CATEGORIES:
        continue
    category, chapter = CATEGORIES[source.stem]
    html_fields(source, category, chapter)

for stem, (category, chapter) in CATEGORIES.items():
    source = WEB / "shared" / f"{stem}.js"
    if not source.exists():
        continue
    # Dialogs generated in JavaScript also contain configuration controls.
    for fragment in re.findall(r"`([^`]+)`", source.read_text(), re.S):
        if not re.search(r"<(?:input|select|textarea)\b", fragment):
            continue
        fragment = re.sub(
            r"\$\{(?:I18n\.t|t)\(['\"]([\w.]+)['\"][^}]*\)\}",
            lambda match: TRANSLATIONS.get(match[1], match[1]), fragment,
        )
        html_fields(source, category, chapter, fragment)

for filename, (category, chapter) in NATIVE_FILES.items():
    # Both orientations use the same reference category.
    for source in sorted(RES.glob(f"layout*/{filename}")):
        try:
            root = ET.parse(source).getroot()
        except ET.ParseError:
            continue
        parents = {child: parent for parent in root.iter() for child in parent}

        def resolved(element, attr):
            raw = element.get(ANDROID + attr, "")
            return clean(STRINGS.get(raw[8:], "") if raw.startswith("@string/") else raw)

        for element in root.iter():
            tag = element.tag.rsplit(".", 1)[-1]
            interactive = tag in {"EditText", "TextInputEditText", "Spinner", "SeekBar",
                                  "Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch",
                                  "RadioButton", "CheckBox", "Button", "MaterialButton", "Chip",
                                  "ImageButton", "FloatingActionButton"}
            interactive |= element.get(ANDROID + "clickable") == "true"
            if not interactive:
                continue
            identifier = element.get(ANDROID + "id", "").rsplit("/", 1)[-1]
            label = resolved(element, "text") or resolved(element, "hint") or resolved(element, "contentDescription")
            parent = parents.get(element)
            nearby = []
            if parent is not None:
                nearby = [item for item in parent.iter() if item.tag.rsplit(".", 1)[-1] == "TextView"]
            if not label and nearby:
                own = [item for item in element.iter()
                       if item.tag.rsplit(".", 1)[-1] == "TextView" and resolved(item, "text")]
                label = resolved(own[0], "text") if own else resolved(nearby[0], "text")
            if not label:
                label = humanise(identifier or tag)
            description = " ".join(resolved(item, "text") for item in nearby[1:3])
            if description == label:
                description = ""
            if element.get(ANDROID + "clickable") == "true":
                own = [item for item in element.iter()
                       if item.tag.rsplit(".", 1)[-1] == "TextView" and resolved(item, "text")]
                if own:
                    label = resolved(own[0], "text")
                    description = " ".join(resolved(item, "text") for item in own[1:3])
            switch = "Switch" in tag or tag == "CheckBox"
            add({
                "label": label,
                "description": description or f"Use {label.lower()} in {category}.",
                "category": category, "chapter": chapter,
                "path": native_location(source, element, parents, resolved, label),
                "kind": "checkbox" if switch else "text" if "EditText" in tag else "action",
                "choices": ["On", "Off"] if switch else [],
            }, source, identifier or label)

entries.sort(key=lambda item: (item["category"].lower(), item["label"].lower()))
version_source = (ROOT / "app/build.gradle.kts").read_text()
version = re.search(r'versionName\s*=.*?:\s*"([^"]+)"', version_source)[1]
payload = {
    "sourceVersion": version,
    "count": len(entries),
    "categories": sorted(set(item["category"] for item in entries)),
    "options": entries,
}
if OUTPUT.exists():
    previous_count = re.search(r'"count":\s*(\d+)', OUTPUT.read_text())
    if previous_count and len(entries) < int(previous_count[1]) * 0.9:
        raise RuntimeError("Source coverage unexpectedly shrank; refusing to overwrite the complete option index.")
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text("/* Generated by tools/build_setup_guide_reference.py. */\n"
                  "window.OVERDRIVE_REFERENCE = " + json.dumps(payload, ensure_ascii=False, indent=2) + ";\n")
REPORT.write_text(json.dumps({
    "sourceVersion": version,
    "indexedOptions": len(entries),
    "coveredControls": len(coverage),
    "scope": "Static web forms, literal JS form templates, saved button choices, selected native settings and setup layouts.",
    "limits": [
        "Installed build and firmware determine which options are visible.",
        "Dynamic schemas, code-built native dialogs and automation action/condition parameters require chapter documentation.",
        "Runtime values and credentials are never read; app-loaded defaults take precedence.",
        "Index coverage is not proof of on-car feature or firmware compatibility.",
    ],
    "controls": coverage,
}, ensure_ascii=False, indent=2) + "\n")
print(f"Indexed {len(entries)} options across {len(payload['categories'])} categories "
      f"from {len(coverage)} controls (source v{version}).")
