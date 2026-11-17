# Live readings in the automation editor

The automation editor now shows the latest **observed** state beside each configured
WHEN/IF condition (including grouped conditions) and beside an action when its
state can be mapped to an existing, readable condition. Nested actions use the
same presentation. This is a read-only aid; no rule or vehicle command is run.

The existing `/api/automations/state` endpoint and 3-second, editor-only polling
provide the readings. Attribute selections (area, seat, units, etc.) are part of
the lookup key. An incomplete selection has no reading; a never-observed or expired
value is shown as unknown. The editor does not infer a successful actuator state
from a command or from a configured target value.

Action aliases are intentionally conservative: lock/unlock, power on/off, DRL,
hazards, AC power/setpoint/fan, individual windows (including presets), sunroof,
sunshade, steering heating, CPD, speed-limit warning, drive and powertrain modes.
A zero-attribute action with an exact condition ID also uses that condition.
An action without a verified mapping explicitly says that no readable vehicle
state is exposed; it does **not** invent a sensor. One-shot actions such as
notifications have no meaningful persistent reading. The hint shows the reported
value, not whether an action is permitted or whether it will succeed.

The display depends on the car actually publishing the corresponding state. No
new BYD getters or device telemetry are introduced by this change.
