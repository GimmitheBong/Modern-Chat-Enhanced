# Modern Chat - Architecture

Modern Chat is a RuneLite plugin that replaces and augments the OSRS chat experience with a
modern, tabbed, resizable chat UI, chat show/hide toggling, a "peek" overlay, notifications,
slash commands, message history, and message filtering.

This document describes how the plugin is put together: its major subsystems, how they
communicate, and the recurring patterns used throughout the codebase.

- **Language/Toolchain:** Java 11 (`options.release = 11`), Gradle
- **Framework:** RuneLite Client API (`net.runelite:client:latest.release`, `compileOnly`)
- **Libraries:** Lombok 1.18.30 (annotations only), JUnit 4.12 (tests)
- **Root package:** `com.modernchat`

## Table of Contents

1. [High-Level Overview](#high-level-overview)
2. [Plugin Entry Point & Lifecycle](#plugin-entry-point--lifecycle)
3. [Feature System](#feature-system)
4. [Command Framework](#command-framework)
5. [Service Layer](#service-layer)
6. [Message Filter Chain](#message-filter-chain)
7. [Overlay & Rendering](#overlay--rendering)
8. [Draw Primitives](#draw-primitives)
9. [Event System](#event-system)
10. [Common Infrastructure](#common-infrastructure)
11. [Compat Layer](#compat-layer)
12. [Configuration](#configuration)
13. [Threading Model](#threading-model)
14. [Utilities](#utilities)
15. [Package Layout](#package-layout)

---

## High-Level Overview

```
                          ┌────────────────────────────┐
                          │      ModernChatPlugin      │  entry point, lifecycle,
                          │  (RuneLite Plugin class)   │  split-PM anchoring
                          └─────────────┬──────────────┘
                                        │ starts/stops
              ┌─────────────────────────┼─────────────────────────┐
              ▼                         ▼                         ▼
   ┌─────────────────────┐   ┌────────────────────┐   ┌───────────────────────┐
   │      Features       │   │      Services      │   │     Compat Layer      │
   │  (ChatFeature<T>)   │   │   (ChatService)    │   │  (KeyRemappingService)│
   ├─────────────────────┤   ├────────────────────┤   └───────────────────────┘
   │ ChatRedesignFeature ──┐ │ MessageService     │
   │ ToggleChatFeature   │ │ │ PrivateChatService │   ┌───────────────────────┐
   │ PeekChatFeature     ──┼─│ MessageFilterSvc   │   │  Common / Abstraction │
   │ CommandsChatFeature │ │ │ FontService        │   ├───────────────────────┤
   │ NotificationFeature │ │ │ SoundService       │   │ ChatProxy             │
   │ MessageHistoryFeat. │ │ │ ForceRecolorSvc    │   │ WidgetBucket          │
   └─────────────────────┘ │ │ SpamFilterService  │   │ NotificationService   │
                           │ │ ProfileService ... │   └───────────────────────┘
                           ▼ └────────────────────┘
                ┌─────────────────────┐
                │      Overlays       │       All cross-component communication
                ├─────────────────────┤       flows through RuneLite's EventBus
                │ ChatOverlay         │       (custom events in com.modernchat.event)
                │ ChatPeekOverlay     │
                │ MessageContainer(s) │
                │ ResizePanel         │
                └─────────────────────┘
```

Three architectural ideas define the codebase:

1. **Features are thin lifecycle/config wrappers.** Each user-facing capability is a
   `ChatFeature` that can be independently enabled/disabled at runtime purely through config
   changes. The heavy lifting (rendering, input) lives in overlays and services the feature owns.
2. **Everything communicates via the EventBus.** Custom events in `com.modernchat.event`
   decouple features, services, and overlays from each other (see [Event System](#event-system)).
3. **`ChatProxy` abstracts "which chat is active".** Consumers ask the proxy for visibility,
   bounds, and input operations without caring whether the modern overlay or the legacy OSRS
   chatbox is currently in use.

## Plugin Entry Point & Lifecycle

`ModernChatPlugin` (`src/main/java/com/modernchat/ModernChatPlugin.java`) is the RuneLite
`Plugin` subclass and the composition root. Everything is wired with Guice `@Inject`; the
config is provided via `@Provides ModernChatConfig`.

**`startUp()` order:**

1. Start core services (`ProfileService`, `WidgetBucket`, `FilterService`, `MessageService`,
   `PrivateChatService`, `SpamFilterService`, `FontService`, `SoundService`, `ImageService`,
   `ForceRecolorService`, `MessageFilterService`).
2. Build the sidebar panel (`ModernChatPanel`) and navigation button.
3. Register features, then run a **two-pass start**:
   - Pass 1: call `startUp()` on *every* feature, then immediately `shutDown(false)` on any
     feature whose config flag is off. This means every feature's config-change listener is
     always live, so a disabled feature can later re-enable itself (see
     [Feature System](#feature-system)).
   - Pass 2: call `onFeaturesStarted()` on enabled features (used for cross-feature ordering,
     e.g. key-listener priority).
4. Start `KeyRemappingService` and check for a conflicting "Key Remapping" plugin.
5. First-install intro: if `featureExample_Enabled` is false, show the welcome message /
   optional tutorial and set the flag. Note: `featureExample_Enabled` is repurposed as a
   persistent "intro already shown" marker - the actual `ExampleChatFeature` is never registered.

`shutDown()` reverses this: removes the panel, stops all services, then calls
`feature.shutDown(true)` (full shutdown) on each feature.

**Responsibilities kept in the plugin class itself** (rather than a feature):

- **Split-PM anchoring** - repositions the split private chat widget relative to the chatbox
  (`maybeReanchor`/`anchorSplitPm` using `PrivateChatAnchor`), polled on `PostClientTick`.
- **Dialog state tracking** - polls every `ClientTick` and posts
  `DialogOptionsOpenedEvent`/`DialogOptionsClosedEvent` when an NPC/options dialog appears.
- **Legacy chat visibility polling** - posts `LegacyChatVisibilityChangeEvent` when the real
  OSRS chatbox is shown/hidden.
- **Message-layer scripts** - translates `ScriptPostFired` (`MESSAGE_LAYER_OPEN`/`CLOSE`) into
  `MessageLayerOpenedEvent`/`MessageLayerClosedEvent`.
- **Notification fan-out** - on `ChatMessage`, if chat is hidden or the tab isn't open, posts a
  `NotificationEvent` consumed by `NotificationChatFeature`.
- **"Chat with" menu entry** - adds a right-click private-message option on players.

## Feature System

Package: `com.modernchat.feature`

### Contract

```java
public interface ChatFeature<T extends ChatFeatureConfig> {
    T getConfig();
    String getConfigGroup();          // config key prefix, e.g. "featureToggle"
    boolean isEnabled();              // reads <group>_Enabled
    void startUp();
    void shutDown(boolean fullShutdown);
    default void onFeaturesStarted() {}
}
```

The `fullShutdown` flag distinguishes a **soft** shutdown (feature toggled off at runtime -
the config listener stays registered so the feature can re-enable itself) from a **full**
shutdown (plugin unloading - everything is torn down).

### AbstractChatFeature: the self-toggle state machine

`AbstractChatFeature<T>` registers the feature on the EventBus and lazily registers a separate
inner `ConfigChangedHandler` subscriber. That handler watches for config keys matching
`<configGroup>_*`:

- Key ends with `_Enabled` and flipped **off** → `shutDown()` (soft) + post `FeatureStoppedEvent`
- Key ends with `_Enabled` and flipped **on** → `startUp()` + post `FeatureStartedEvent`
- Any matching key → `onFeatureConfigChanged(e)` hook + post `FeatureChangedEvent`

Feature enable/disable is therefore **not** orchestrated by the plugin - each feature manages
itself from config changes. The plugin reacts to `FeatureStartedEvent`/`FeatureStoppedEvent`
only to bounce `KeyRemappingService` (recomputing key-listener ordering).

### Config partitioning

`ChatFeatureConfig` is a marker interface. Each feature declares a nested config sub-interface
and implements `partitionConfig(ModernChatConfig)` returning a hand-written anonymous adapter
that forwards its getters to the global config. A feature only ever sees its own slice of the
~2000-line `ModernChatConfig`. `ChatRedesignFeature` partitions **twice**
(`ModernChatConfig` → feature config → `ChatOverlayConfig`), which is the single largest
forwarding surface in the codebase - keep it in sync when adding overlay config options.

### The features

| Feature | Config group | Responsibility |
|---|---|---|
| `ChatRedesignFeature` | `featureChat` | Flagship: replaces the legacy chatbox with the tabbed, resizable `ChatOverlay`. Manages legacy-chat hiding, tab refresh on clan/FC changes, per-profile chat size persistence, and feeds filtered `ChatMessage`s into the overlay. |
| `ToggleChatFeature` | `featureToggle` | Show/hide chat with a hotkey (default Enter); Escape-hides; optional camera lock while visible; a 5-tick deferred-hide state machine that waits for pending input to clear before hiding (so an in-flight message isn't swallowed). |
| `PeekChatFeature` | `featurePeek` | Chromeless fading read-only `ChatPeekOverlay` mirroring one chosen source tab while the main chat is hidden. Source selected via "Set as peek source" on a tab (`SetPeekSourceEvent`). |
| `CommandsChatFeature` | `featureCommands` | Slash-command dispatcher (see [Command Framework](#command-framework)). |
| `NotificationChatFeature` | `featureNotify` | Plays a sound (custom `Sfx` or RuneLite `Notifier`) on `NotificationEvent`, filtered per channel, throttled to one play per 300 ms. |
| `MessageHistoryChatFeature` | `featureMessageHistory` | Shell-style input history (prev/next hotkeys, `NavigateHistoryEvent` from the overlay's arrow keys), persisted as JSON per RS profile, with draft stashing while navigating. |
| `ExampleChatFeature` | `featureExample` | Scaffold only - never registered. Its `_Enabled` flag doubles as the install-intro marker. |

Both `ChatRedesignFeature` and `PeekChatFeature` subscribe to `ChatMessage` at **priority -3**
(after RuneLite's `ChatMessageManager`) and each independently runs the message through
`MessageFilterService` before display.

## Command Framework

Package: `com.modernchat.feature.command`

`CommandsChatFeature` hosts a registry of `ChatCommandHandler`s keyed by command word:

| Input | Handler | Behavior |
|---|---|---|
| `/r`, `/reply` | `ReplyChatCommand` | Reply to last received PM (fires live, as you type). |
| `/w`, `/whisper` | `WhisperChatCommand` | Open PM compose to a named player. |
| `/pm`, `/private` | `PrivateMessageChatCommand` | Set PM target and switch input into PM mode. |
| `/g`, `/gim`, `/group` | `GroupIronmanChatCommand` | Send the rest of the line to Group Ironman chat (submit only). |

Aliases are `ChatCommandLink` instances - lightweight pointers resolved with a single hop in
the registry (`whisper` → `w`).

**Dispatch happens on two paths:**

1. **Live path** - `VarClientStrChanged` (chatbox typed text): fires `handleInput(args)` and
   `handleInputOrSubmit(args, null)` on every keystroke. This is how `/r` reacts immediately.
2. **Submit path** - `ChatboxInput`: fires `handleSubmit(args, ev)` and
   `handleInputOrSubmit(args, ev)`. Handlers call `ev.consume()` to suppress the vanilla send.

Arg-count guards differ between paths (live requires target + body, submit requires only a
target) so commands don't misfire mid-typing. `AbstractChatCommand` registers each command on
the EventBus and KeyManager so commands can subscribe to events (e.g.
`PrivateMessageChatCommand` warns when split PM chat is disabled in OSRS settings).

## Service Layer

Package: `com.modernchat.service`

`ChatService` is a pure lifecycle interface (`startUp()`/`shutDown()`); nearly every singleton
implements it so the plugin can orchestrate start/stop uniformly.

| Service | Responsibility | Notable details |
|---|---|---|
| `MessageService` | Outbound sending + anti-spam rate limiting | `SEND_COOLDOWN_MS=900`, 5 "hot" messages allowed, escalating lock (`lockCount × 1250ms`, resets after 60s). `::` input is re-posted as a RuneLite `CommandExecuted` instead of sent. Sends run on the client thread via `ScriptID.CHAT_SEND`/`PRIVMSG`. Posts `ChatMessageSentEvent`/`ChatPrivateMessageSentEvent`/`SubmitHistoryEvent`; posts `ChatSendLockedEvent` when locked. |
| `PrivateChatService` | PM target/compose flow, reply-to-last | Defers PM opening to `PostClientTick` (avoids "scripts are not reentrant"); schedules chat reopen after a send-lock expires; Escape cancels. |
| `MessageFilterService` | Inbound filter chain orchestrator | See [Message Filter Chain](#message-filter-chain). |
| `FilterService` | **Outbound** URL/email blocking | Regex gate on the player's own outgoing text; distinct from inbound filtering. |
| `SpamFilterService` | User-managed spam/ham corpora | File-compatible with the SpamFilterPlugin (`.runelite/spam-filter/user_{bad,good}_corpus.txt`); persists on every mutation. |
| `ForceRecolorService` | Mirror of the Force Recolor plugin | Reads `forcerecolor`/`textrecolor` config groups, replicates its exact boundary regex; per-message recolor lookup used by `MessageContainer`. |
| `FontService` | Font registry | ~50 `FontStyle` entries → `LazyLoad<Font>` from bundled TTFs (`/com/modernchat/fonts/`); registered with `GraphicsEnvironment` on first use. |
| `SoundService` | Notification SFX | Bundled WAVs (`/com/modernchat/sounds/`), `LazyLoad<byte[]>`, caller supplies the `AudioPlayer`. |
| `ImageService` | Mod-icon sprites → `BufferedImage` | `ConcurrentHashMap` cache; parses `<img=N>` tags. |
| `ProfileService` | Named config profiles as JSON | `.runelite/modern-chat/*.json`; serializes via `ModernChatConfigBase.buildJsonFromConfig`; backs `ModernChatPanel`. |
| `TutorialService` | Interactive first-install tutorial | State machine (`TutorialState`): toggle → tab switch → send → PM → completed; driven by chat events. |

## Message Filter Chain

Inbound messages pass through `MessageFilterService.filterMessage(ChatMessage)`, a
`CopyOnWriteArrayList` chain of `MessageFilter`s applied in registration order:

```
ChatMessage → VanillaChatFilter → ChatFilterPluginFilter → AreaMutePluginFilter → SpamCorpusFilter
              (OSRS Game-filter    (Chat Filter plugin      (Area Mute plugin      (user spam/ham
               dropdown, hides      reimplementation:        reimplementation:      corpus via
               SPAM/game types)     censor/collapse)         region mutes)          SpamFilterService)
```

Each filter receives the possibly-modified text from the previous one and may return modified
text or `null` to **block** the message (short-circuits the chain). The chain is gated by
`filters_Enabled()`. `SpamCorpusFilter` deliberately matches against the *raw* message so
earlier censoring can't mask spam; the ham corpus acts as a whitelist override.

Filtering is invoked *per consumer* (`ChatRedesignFeature` for the main overlay,
`PeekChatFeature` for the peek overlay) rather than centrally - a new consumer of
`ChatMessage` must remember to run the chain itself.

## Overlay & Rendering

Package: `com.modernchat.overlay`

### ChatOverlay (~3,100 lines)

The main chat UI, an `OverlayPanel` at layer `ABOVE_WIDGETS`. It owns:

- **Tabs** - a `LinkedList<Tab>` (`tabOrder`) + `ConcurrentHashMap` by key. Fixed tabs
  (`ALL`, optional `GAME`/`TRADE`), channel tabs, and dynamic `private_<name>` tabs. Supports
  drag-reordering, close buttons, unread badges (capped at 99), mute, and a scrollable tab bar.
- **Message containers** - one `MessageContainer` per mode plus per-PM-target containers.
  The "All" tab is a synthetic aggregate (max 100 lines, applies channel filters). The three
  clan modes share one container; the *send* channel is derived from the active tab key.
- **Input box** - its own text editing implementation on a `StringBuilder`: caret, selection,
  word-jump (Ctrl+arrows), Home/End, copy/cut (paste is deliberately blocked - Jagex rules),
  horizontal scroll, blink. Enter submits via `MessageService`; Shift+Up/Down posts
  `NavigateHistoryEvent`; Tab cycles tabs.
- **Channel filter dropdown** - a `Dropdown<ChannelFilterType>` writing per-mode bitmasks
  through `ChannelFilterState`.
- **Context menus** - right-click menu entries on messages (copy, mark spam/ham, message
  player, clear) and on tabs (close, clear, move, mute, mark read, set as peek source, Game
  filter mode).

**Render pipeline** (per frame): resolve viewport from `WidgetBucket` → draw panel chrome →
compute layout (fonts from `FontService`) → draw tab bar → delegate the message area to the
active `MessageContainer.render()` → draw input box (prefix, selection, text, caret) → draw
filter dropdown → draw `ResizePanel` grips.

Input arrives through nested `ChatMouse` (`MouseListener` + wheel) and `InputKeys`
(`KeyListener`) handlers registered with RuneLite's `MouseManager`/`KeyManager`. A
`shouldBlockClickThrough` guard consumes clicks that would otherwise hit the game world when
click-through is disabled.

### MessageContainer

The rendering workhorse - a scrollable list of `RichLine`s:

1. `pushLine(...)` parses game markup (`<col>`, `<img=N>`, `<br>`, entities) into a `RichLine`
   of segments via `parseRich`, applying `ForceRecolorService` to the body only.
2. At render time each `RichLine` is word-wrapped into cached `VisualLine`s
   (binary-search char fitting for over-long words; images/timestamps/prefixes are unbreakable).
3. Lines are drawn segment-by-segment with `TextDrawUtil.drawTextWithShadow`, clipped to the
   viewport, with fade (easeOutCubic), auto-stick-to-bottom (disabled while the user has
   scrolled up), and an optional scrollbar with draggable thumb.

### ChatPeekOverlay

Extends `MessageContainer`; chromeless. Shows only when both modern and legacy chat are hidden
and no system widget is active. Suppresses fade-reset for noise (collapsed duplicates,
autotyper/spam, muted source tabs, optionally anything non-important at the Grand Exchange).

### ResizePanel

A separate `ALWAYS_ON_TOP` overlay drawing resize grips (right + bottom enabled in
`ChatOverlay`), managing hover cursors and drag, and reporting new sizes back through a
`ResizeListener` (persisted per RS profile via `ChatResizedEvent`).

## Draw Primitives

Package: `com.modernchat.draw`

Text model - how a chat message becomes pixels:

```
"<col=ff0000>Hello</col> <img=3>"          game markup
        │  parseRich (MessageContainer)
        ▼
RichLine { segs: [TimestampSegment, PrefixSegment, TextSegment, ImageSegment], ... }
        │  wrapRichLine (cached on the RichLine)
        ▼
List<VisualLine>   - one per wrapped on-screen row
        │  render
        ▼
segments drawn in order; ImageSegment reserves/draws icon, TextSegment via drawTextWithShadow
```

- `Segment` (interface) → `TextSegment` → `TimestampSegment` / `PrefixSegment` (marker
  subclasses recolored separately) and `ImageSegment` (inline mod-icon with retry-on-missing).
- `Tab` - key, title, bounds, unread count, muted/hidden/readOnly flags; equality by key;
  `private_` key prefix marks PM tabs.
- `Dropdown<T>`/`DropdownItem<T>`/`Checkbox` - small self-drawing UI widgets.
- `ChannelFilterType` - bitmask enum backing the per-mode channel filters (set bit = hidden).
- `Padding`/`Margin` - box-model value types; `ChatColors` - default per-channel colors.

## Event System

Package: `com.modernchat.event` - all posted on RuneLite's `EventBus`, mostly Lombok `@Value`.

| Event | Posted by | Consumed by | Purpose |
|---|---|---|---|
| `LegacyChatVisibilityChangeEvent` | `ModernChatPlugin` (ClientTick poll) | `ChatRedesignFeature` | Real OSRS chatbox shown/hidden |
| `ModernChatVisibilityChangeEvent` | `ChatOverlay` | `ChatRedesignFeature`, `PeekChatFeature` | Modern overlay shown/hidden |
| `ChatToggleEvent` | `ToggleChatFeature`, `ChatOverlay` | `TutorialService`, `KeyRemappingService`, `ChatOverlay` | Chat visibility toggled ("typing mode" coordination) |
| `ChatResizedEvent` | `ChatOverlay` | `ChatRedesignFeature` | Persist overlay size per RS profile |
| `TabChangeEvent` | `ChatOverlay` | `TutorialService` | Active tab changed |
| `TabClosedEvent` | `ChatOverlay` | `PeekChatFeature` | Tab closed (peek source reverts to ALL) |
| `SetPeekSourceEvent` | `ChatOverlay` (tab menu) | `PeekChatFeature` | Choose which tab feeds the peek overlay |
| `ChatMenuOpenedEvent` | `ChatOverlay` | `PeekChatFeature` | Overlay context menu opened (inject entries) |
| `NavigateHistoryEvent` | `ChatOverlay` (Shift+Up/Down) | `MessageHistoryChatFeature` | Navigate input history |
| `SubmitHistoryEvent` | `MessageService` | `MessageHistoryChatFeature` | Record a programmatic send (PMs) into history |
| `ChatMessageSentEvent` | `MessageService` | `TutorialService` | Public/channel message sent |
| `ChatPrivateMessageSentEvent` | `MessageService` | `TutorialService`, `ChatOverlay` | PM sent |
| `ChatSendLockedEvent` | `MessageService` | `ChatRedesignFeature`, `PrivateChatService` | Rate-limit lock hit |
| `NotificationEvent` | `ModernChatPlugin` | `NotificationChatFeature` | Message received while chat hidden/tab closed |
| `MessageLayerOpenedEvent` / `MessageLayerClosedEvent` | `ModernChatPlugin` (script hooks) | `ChatRedesignFeature` | Game message layer over the chatbox opened/closed |
| `DialogOptionsOpenedEvent` / `DialogOptionsClosedEvent` | `ModernChatPlugin` (tick poll) | `ModernChatPlugin`, `ToggleChatFeature`, `ChatOverlay` | NPC/options dialog opened/closed |
| `FeatureStartedEvent` / `FeatureStoppedEvent` | `AbstractChatFeature` | `ModernChatPlugin`, `ToggleChatFeature`, `TutorialService` | Feature soft-started/stopped via config toggle |
| `FeatureChangedEvent` | `AbstractChatFeature` | *(none - extension seam)* | Any feature config key changed |

Notable flows:

- **Visibility is dual-tracked**: `LegacyChatVisibilityChangeEvent` (polled from the real
  widget) vs `ModernChatVisibilityChangeEvent` (posted by the overlay). `ChatProxy` reconciles
  the two for consumers.
- **`ChatToggleEvent` is the coordination signal for "is the player typing"** - the compat
  key-remapper locks/unlocks WASD remapping based on it.
- **The overlay, not the feature, is the main event source**: `ChatRedesignFeature` posts
  almost nothing itself; `ChatOverlay` posts 9 of the event types.

## Common Infrastructure

Package: `com.modernchat.common`

- **`ChatProxy`** - the "which chat?" abstraction. Routes visibility checks, bounds, input
  get/set/clear, PM start, and submit to either `ChatOverlay` (when enabled) or the legacy
  chatbox widgets. Also exposes a thread-safe `isSystemWidgetActive()` snapshot refreshed
  every tick.
- **`WidgetBucket`** - lazily-resolved, event-invalidated cache of chatbox-related `Widget`
  references (viewport, message layer, PM widget, dialog widgets). Invalidated on
  `WidgetLoaded`/`WidgetClosed` and login/hop. Always fetch widgets through it - never cache a
  raw `Widget` yourself.
- **`NotificationService`** - notification/dialog facade: chat messages tagged `[ModernChat]`,
  helper notifications (config-gated), Swing dialogs, tray notifications.
- **`MessageLine`** - immutable DTO for a parsed inbound message (built by
  `ChatUtil.createMessageLine`, consumed by the overlays).
- **`ChatMessageBuilder`** - fluent builder for RuneLite-formatted chat strings.
- **`Anchor` / `PrivateChatAnchor`** - widget re-anchoring: capture original position, apply a
  new one relative to a target, restore on reset. Used for split-PM anchoring.
- **`LazyLoad<T>`** - minimal lazy supplier holder (not synchronized).
- **Enums** - `ChatMode` (send-mode ints for public/private/FC/clan/guest/GIM), `ClanType`,
  `FontStyle` (~48 entries), `NotifyType`, `Sfx`, `TutorialState`.

## Compat Layer

Package: `com.modernchat.compat`

Internal reimplementations of RuneLite plugins that would otherwise conflict with or be
bypassed by the custom chat. The shared pattern:

1. Read the original plugin's config group via `ConfigManager`/`ConfigUtil` (so user settings
   carry over unchanged).
2. Refresh cached values on `ConfigChanged`.
3. Gate behavior on whether the source plugin is enabled (checked via `PluginManager`).
4. Subscribe to `PluginChanged` to warn the user if a *conflicting* plugin is re-enabled.

Current members:

- **`compat/remapper/KeyRemappingService` + `KeyRemappingKeyListener`** - reimplements the
  Key Remapping plugin (WASD camera, F-key remap) so it cooperates with the custom input box.
  Locks/unlocks remapping on `ChatToggleEvent`; warns if the real Key Remapping plugin is on.
- The same config-mirroring pattern is followed outside the package by
  `ChatFilterPluginFilter`, `AreaMutePluginFilter` (in `service/filter`) and
  `ForceRecolorService` (in `service`), which mirror the Chat Filter, Area Mute, and Force
  Recolor plugins respectively.

## Configuration

- **Config group:** `modernchat`. `ModernChatConfig` (≈2,000 lines) extends RuneLite's
  `Config` plus `ModernChatConfigBase` (which holds the key constants, a `Field` enum
  enumerating every key, and JSON build/apply helpers used by profiles).
- **Nine sections:** Modern Design (beta), Modern Design Style, General, Chat Toggle,
  Peek Overlay, Filters, Chat Commands, Notifications, Message History.
- **Key naming convention:** `<featureGroup>_<Option>` (e.g. `featureToggle_ToggleKey`) - this
  prefix convention is load-bearing: `AbstractChatFeature.ConfigChangedHandler` matches on it
  for the self-enable/disable machinery.
- **Config flows through partition adapters**, not direct injection: features and overlays see
  narrow interfaces (`ChatOverlayConfig`, `MessageContainerConfig`) implemented as forwarding
  adapters over `ModernChatConfig`.
- **Per-RS-profile state** (chat size, message history) is stored via
  `ConfigManager.setRSProfileConfiguration`.
- **Profiles** - `ProfileService` + `ModernChatPanel` (sidebar) save/load the entire config as
  named JSON files under `.runelite/modern-chat/`.

## Threading Model

Three "threads" matter: the **client thread** (game logic/widgets), the **AWT/EDT** (Swing
dialogs, key/mouse listeners), and RuneLite's render pass (overlay `render()`).

Conventions used throughout:

- Anything touching widgets, varbits, or scripts goes through `clientThread.invoke()` /
  `invokeLater()` / `invokeAtTickEnd()`.
- Fields shared across threads are `volatile`; flags are `AtomicBoolean`/`AtomicInteger`;
  shared collections are `ConcurrentHashMap`/`CopyOnWriteArrayList`.
- Deferred work is tick-driven rather than sleep-driven (e.g. `ToggleChatFeature`'s
  deferred-hide counts ticks; `PrivateChatService` defers PM opens to `PostClientTick` to
  avoid script reentrancy).

## Utilities

Package: `com.modernchat.util` - stateless static helpers:

| Class | Purpose |
|---|---|
| `ChatUtil` | Chat domain: type→mode mapping, sender/icon extraction, `MessageLine` factory, type predicates, marker constants |
| `ClientUtil` | Client-thread helpers: dialog/system-input detection, chat input get/set, PM scripts, GE proximity, online check |
| `ConfigUtil` | Typed `ConfigManager` readers with defaults (backbone of the compat config-mirroring) |
| `TextDrawUtil` | `drawTextWithShadow` - fast outline/shadow text rendering |
| `WidgetUtil` | Absolute widget location (scroll-aware), chatbox widget test |
| `GeometryUtil` | Chat bounds sanity check |
| `FormatUtil` | Epoch millis → `HH:mm[:ss]` strings |
| `ColorUtil` | Hex string → `Color` |
| `MathUtil` | Clamps |
| `StringUtil` | `isNullOrEmpty` |

## Package Layout

```
com.modernchat
├── ModernChatPlugin.java        # entry point / composition root
├── ModernChatConfig.java        # @ConfigItem definitions (9 sections)
├── ModernChatConfigBase.java    # key constants, Field enum, JSON profile helpers
├── ModernChatPanel.java         # sidebar profile-manager panel
├── common/                      # ChatProxy, WidgetBucket, NotificationService, enums, anchors
├── compat/
│   └── remapper/                # Key Remapping plugin reimplementation
├── draw/                        # rendering primitives: segments, lines, tabs, widgets
├── event/                       # custom EventBus events (21)
├── feature/                     # ChatFeature system + concrete features
│   └── command/                 # slash-command framework + commands
├── overlay/                     # ChatOverlay, MessageContainer, peek, resize
├── service/                     # ChatService implementations
│   └── filter/                  # MessageFilter chain implementations
└── util/                        # stateless helpers

src/main/resources/com/modernchat/
├── fonts/                       # bundled TTFs (Roboto, Open Sans, Fira Sans, ...)
├── images/                      # icons
└── sounds/                      # notification WAVs
```

---

*Generated July 2026. If you change lifecycle wiring, events, or the filter chain, please
update the relevant section here.*
