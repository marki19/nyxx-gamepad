# Gamepad — Feature Expansion, Multi-Profile System & Visual Design

Companion doc to `gamepad-ui-ux-spec.md` (which covers the overlap bug, deadzone, and the
sidebar/fullscreen architecture). This one covers: sidebar content, the edit-mode property
panel, multi-console profiles, and a full visual redesign to fix the contrast/legibility issues
visible in the latest screenshot.

---

## 1. Sidebar / Home Navigation — Content Map

| Section | Purpose |
|---|---|
| **Home** | Connection status (IP, latency, connected/disconnected), active profile name, quick reconnect button. This is prime real estate — don't bury connection state in a submenu. |
| **Controller Profiles** | Switch active layout: Generic, PlayStation, PSP, Switch, GameCube, Arcade/Fightstick, etc. (§4) |
| **Edit Layout** | Toggles edit mode (already implemented) |
| **Button Mapping** | Remap which virtual button sends which output bit/code — useful since not every emulator expects the same button order |
| **Vibration & Haptics** | Toggle touch press haptics; toggle rumble pass-through from PC if the bridge supports force feedback |
| **Sensitivity** | Global default deadzone %, trigger curve (linear vs exponential) |
| **Appearance** | Color skin selection (§5), global opacity preset |
| **Connection Settings** | Manual IP/port entry, USB vs Wi-Fi toggle, auto-reconnect on launch |
| **Help** | Replay first-run tutorial, gesture hints |
| **About** | Version, changelog |
| **Disconnect & Exit** | Clean shutdown of the UDP/WebSocket session before backgrounding |

Group these visually as: Connection (Home, Connection Settings) → Layout (Profiles, Edit Layout,
Button Mapping) → Feel (Vibration, Sensitivity) → Look (Appearance) → Info (Help, About, Exit).

---

## 2. Edit Mode — Selected-Button Property Panel

Every button already supports **Position** via drag — that's universal, no need to list it below.
Beyond that, settings should vary by button role:

| Button type | Settings |
|---|---|
| **Joystick (L/R)** | Size, deadzone %, analog curve (linear / exponential), show/hide |
| **D-Pad** | Size, spacing, show/hide, "allow diagonals" toggle (simultaneous U+R etc.) |
| **ABXY / face buttons** | Size, spacing, per-button color override, show/hide |
| **Shoulders (L1/R1)** | Size, opacity, shape (circle/pill), turbo (rapid-fire) toggle, show/hide |
| **Triggers (L2/R2)** | Size, opacity, **analog trigger mode** (digital on/off vs. pressure-based on drag distance), turbo toggle, show/hide |
| **Select/Start** | Size, opacity, label style (text vs. icon), show/hide |
| **Menu (☰)** | Opacity only — this is app chrome, not a game input, so it shouldn't be draggable or hideable |

**Analog trigger mode** is worth calling out: right now `L2`/`R2` are wired as simple digital
on/off (`lt`/`rt` set to `0` or `-1`). If you want real pressure sensitivity for racing/flight
games, add a per-trigger flag and, when enabled, compute pressure from touch-drag distance from
the button's rest position (0–255) instead of a binary value — same pattern as the joystick's
radial math, just 1-dimensional.

**Data model** — extend the existing `groupOffsets` pattern into a per-button config object
instead of scattering more loose variables:

```kotlin
data class ButtonConfig(
    var scale: Float = 1.0f,
    var opacity: Float = 1.0f,
    var visible: Boolean = true,
    var turbo: Boolean = false,
    var analogTrigger: Boolean = false, // L2/R2 only
    var colorOverride: Int? = null
)

private val buttonConfigs = mutableMapOf<String, ButtonConfig>() // keyed by group name
```

Persist the same way `joyScale`/`btnScale` already are (one `SharedPreferences` key per field per
group), and the property panel is just a form bound to whichever `ButtonConfig` matches the
currently-selected group in edit mode.

---

## 3. Fullscreen — Recap & the Cropped-Joystick Bug

The code fix (from the companion doc) is an `onWindowFocusChanged` override that re-hides system
bars every time focus returns to the Activity — this is the actual fix for "opening settings
pops the nav bar back in," and it also matters here: **if system bars are visible when
`onSizeChanged` first measures the view, `h` is smaller than the true screen height**, and every
position formula in `applyScales()` is computed against that shrunken `h`. If immersive mode
then kicks in afterward without re-triggering a layout pass, the drawn content (including the
joystick near the bottom) can end up positioned for a screen that's now taller than what was
measured — visually reading as "cropped" or oddly placed.

One caveat on the latest screenshot specifically: the browser chrome around the phone view (tab
strip, address bar, the icon row) indicates this was captured through a browser-based remote
device viewer, not the app running natively on the phone. That viewer's own frame could be
clipping the bottom independently of anything in your code. **Verify on the physical device
first** before spending time on this — if it doesn't reproduce natively, it was the viewer.

If it does reproduce on-device, confirm with a log line:
```kotlin
override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    Log.d("GamepadView", "measured size: ${w}x${h}")
    applyScales()
}
```
Compare that logged `h` against the device's actual display height (`Settings > About Phone >
resolution`, accounting for density). If they don't match, the fullscreen timing is the cause.

---

## 4. Multi-Console Gamepad Profiles

| Profile | Sticks | Face buttons | Shoulders | Notes |
|---|---|---|---|---|
| **Generic (current)** | Dual analog | A/B/X/Y diamond | L1/L2/R1/R2 | Keep as the default/fallback |
| **PlayStation (PS1/PS2)** | Dual analog | Triangle/Circle/Cross/Square | L1/L2/R1/R2 | Same physical layout as Generic, different labels/colors |
| **PSP** | Single analog nub | Triangle/Circle/Cross/Square | L/R only, no triggers | For PPSSPP — hide L2/R2 entirely rather than leaving them dead |
| **Nintendo Switch** | Dual analog | A/B/X/Y (mirrored vs. Xbox) | L/R + ZL/ZR, plus Capture/Home | For Ryujinx/Eden — Start/Select become +/− |
| **GameCube** | Main stick + small C-stick | Large A, small B/X/Y | Analog L/R + digital Z | Distinct proportions — A is dominant, C-stick is visually smaller/offset |
| **Arcade / Fightstick** | Stick or D-pad only (no second stick) | 6 buttons, two rows of 3 | — | Recommended specifically for Tekken and other fighting games — a 4-button diamond is a poor fit for 6-button inputs |
| **Retro (SNES/Genesis)** | None | D-pad + 4-6 face buttons | Optional L/R | Good minimal profile for older-console emulators |

N64 and Dreamcast are worth mentioning but I'd treat as stretch goals — their physical controllers
(3-pronged handle, oddly-placed C-buttons) don't map cleanly onto a flat touchscreen without
feeling awkward, so the ergonomic payoff is lower than the other profiles above.

### Architecture: data-driven profiles

Right now every button is hardcoded in `init {}`. Adding six more consoles by copy-pasting that
block six times will get unmaintainable fast. Move to a data-driven definition instead, so
switching consoles means loading a different profile object, not rewriting `GamepadView`:

```kotlin
data class ButtonSpec(
    val name: String,
    val bit: Int,
    val group: String,
    val defaultXFraction: Float,
    val defaultYFraction: Float,
    val isTrigger: Boolean = false,
    val isDpad: Boolean = false
)

data class GamepadProfile(
    val id: String,
    val displayName: String,
    val hasSecondStick: Boolean,
    val hasTriggers: Boolean,
    val buttons: List<ButtonSpec>
)

object GamepadProfiles {
    val GENERIC = GamepadProfile(
        id = "generic",
        displayName = "Generic",
        hasSecondStick = true,
        hasTriggers = true,
        buttons = listOf(
            ButtonSpec("A", 0x0002, "ABXY", 0.85f, 0.40f),
            ButtonSpec("B", 0x0001, "ABXY", 0.85f, 0.40f)
            // ...
        )
    )
    val PSP = GamepadProfile(
        id = "psp",
        displayName = "PSP",
        hasSecondStick = false,
        hasTriggers = false,
        buttons = listOf(/* Triangle/Circle/Cross/Square, no L2/R2 entries at all */)
    )
    // SWITCH, GAMECUBE, ARCADE, RETRO ...
}
```

`GamepadView` then takes a `GamepadProfile` in its constructor (or a `setProfile()` method) and
builds its internal `buttons` list from `profile.buttons` instead of the hardcoded block. The
"Controller Profiles" sidebar entry just calls `setProfile(GamepadProfiles.PSP)` and the view
rebuilds itself.

---

## 5. Visual Design — Fixing the "Telemetry" Look

### The actual bug behind it

In `onDraw`, the `when (btn.name)` block only assigns colors for `"A"`, `"B"`, `"X"`, `"Y"`.
Every other button — both shoulders, both triggers, Select, Start, and the entire D-pad — falls
through to the default case:

```kotlin
var baseColor = Color.parseColor("#111111")
var topColor = Color.parseColor("#444444")
var textColor = Color.parseColor("#111111")
```

`textColor` here is near-black, drawn on top of a dark gray fill (`topColor`). That's not a
color-taste issue, it's a contrast bug — those labels are close to unreadable, which is exactly
what the screenshot shows. Only 4 of ~14 buttons ever got a real color scheme.

### Fix: a token object instead of scattered hex + defaults

```kotlin
object GamepadColors {
    // Neutral family — D-Pad, shoulders, triggers, joystick chassis
    val neutralBase = Color.parseColor("#1C1F24")
    val neutralTop = Color.parseColor("#3D4249")
    val neutralStroke = Color.parseColor("#5B6270")
    val neutralText = Color.parseColor("#EDEEF0")

    // Select/Start — same neutral fill, distinguishing warm ring
    val systemStroke = Color.parseColor("#C9A15A")

    // Face buttons — richer than the current pastel, still soft
    val yBase = Color.parseColor("#1F7A4D"); val yTop = Color.parseColor("#3DDC84"); val yText = Color.parseColor("#0B3320")
    val xBase = Color.parseColor("#1B6FA0"); val xTop = Color.parseColor("#4FC3F7"); val xText = Color.parseColor("#0A2E42")
    val aBase = Color.parseColor("#A33131"); val aTop = Color.parseColor("#FF5C5C"); val aText = Color.parseColor("#4A1414")
    val bBase = Color.parseColor("#A67B14"); val bTop = Color.parseColor("#FFD54F"); val bText = Color.parseColor("#4A3607")
}
```

Then in `onDraw`, replace the `when` block:
```kotlin
var baseColor = GamepadColors.neutralBase
var topColor = GamepadColors.neutralTop
var textColor = GamepadColors.neutralText

when (btn.name) {
    "A" -> { baseColor = GamepadColors.aBase; topColor = GamepadColors.aTop; textColor = GamepadColors.aText }
    "B" -> { baseColor = GamepadColors.bBase; topColor = GamepadColors.bTop; textColor = GamepadColors.bText }
    "X" -> { baseColor = GamepadColors.xBase; topColor = GamepadColors.xTop; textColor = GamepadColors.xText }
    "Y" -> { baseColor = GamepadColors.yBase; topColor = GamepadColors.yTop; textColor = GamepadColors.yText }
}
```
Every button now gets a properly contrasting fill/text pair — this alone should resolve most of
the "hard to read / messy" complaint, independent of any further palette taste adjustments.

### Visual hierarchy rationale

- **D-Pad and shoulders stay neutral** (graphite family) — they're directional/utility inputs,
  not "action" buttons, so they should visually recede a bit next to the face buttons.
- **ABXY carries the color** — this is where the eye should land first, matching how physical
  controllers use color to draw attention to the primary action buttons.
- **Select/Start get a distinct warm stroke** (`systemStroke`), not a fill change — they're
  "system" buttons (pause, menu-adjacent), and a subtle ring is enough to separate them from
  gameplay buttons without competing with ABXY for attention.

### Icon glyphs — replace emoji with drawn vectors

`☰`, `💾`, and `❌` are currently raw emoji characters. Emoji rendering varies significantly
between manufacturer skins (Vivo's Funtouch OS renders differently from stock Android, Samsung's
One UI, etc.), which is part of why the chrome buttons can look inconsistent or low-quality on a
specific device. Draw them instead:

```kotlin
private fun drawHamburgerIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
    paint.color = GamepadColors.neutralText
    paint.strokeWidth = r * 0.15f
    paint.style = Paint.Style.STROKE
    val lineWidth = r * 0.9f
    for (i in -1..1) {
        val y = cy + i * (r * 0.4f)
        canvas.drawLine(cx - lineWidth / 2, y, cx + lineWidth / 2, y, paint)
    }
}
```
Same approach for save (a simple floppy-outline path) and cancel (two crossing lines) — small
effort, consistent look on every device.

---

## 6. Priority Order

1. §5 contrast fix (token object) — this is a correctness bug, not just polish, and cheapest to fix.
2. §3 verify fullscreen timing on a real device before assuming the crop is a code bug.
3. §1 sidebar content + §2 property panel — feature work, do together since edit mode already has the UI shell.
4. §4 data-driven profile architecture — do this *before* adding PSP/Switch/etc., otherwise each new console means another hardcoded copy of `init {}`.
5. §5 icon vector replacement — low-priority polish, but very cheap once you're already in `onDraw`.
