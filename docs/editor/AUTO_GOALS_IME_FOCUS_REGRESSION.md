# Auto Goals portrait IME focus regression

## Symptom

On the API-33 Samsung tablet in portrait, with **Goals pane position → Auto**, tapping the Lean editor briefly opened a docked standard or split keyboard and then closed it. The editor lost focus and its cursor. During the transition, Goals briefly appeared on the right before returning to the bottom. Samsung's floating keyboard did not reproduce the failure.

## Cause

The workspace applied `imePadding()` before `BoxWithConstraints`, then resolved Auto placement from `maxHeight >= maxWidth` inside that IME-reduced box.

A docked IME substantially reduces the reported content height. A physically portrait 1600×2560 window therefore temporarily looked wider than tall to this inner layout. Auto changed from Bottom to Right, replacing the Goals/editor `Column` with a `Row`. That branch replacement disposed and recreated the focused `BasicTextField`, invalidated Android's input connection, and closed the IME. Once the IME closed, full height returned and Auto changed back to Bottom. This produced the observed right-pane flash and focus/keyboard loop. Floating keyboards do not resize content, which explains why they remained usable.

## Fix and invariant

Auto now resolves from `LocalConfiguration.current.orientation`, which represents the stable window configuration and does not change when a docked IME consumes workspace height. IME insets may resize and suppress subordinate panes, but must never be interpreted as an orientation change or replace the editor branch that owns focus.

Regression validation covers the portrait-window/docked-IME case where the remaining workspace itself is wider than tall. Physical acceptance must confirm both Samsung standard and split keyboards remain open, the editor retains focus and accepts text, and Goals stays on the bottom in Auto mode.
