# Robot faces and workspace colors

## Face identity

Both apps hash the raw tmux session name. Editable titles and hosts never affect the face. Hash exact UTF-8 bytes using unsigned FNV-1a. Start at `2166136261`, XOR each byte, then multiply by `16777619`, wrapping at 32 bits. Face index is `hash % 20`.

Reference faces: `agent-one` → 15; `agent-two` → 13; `机器人` → 9. Local desktop consoles use their names.

## Workspace colors

The shared account workspace order assigns default colors. Index is workspace position modulo seven. Local filters and session ordering never affect colors. Every session within one workspace shares its color. Colors repeat only after exhausting the palette.

Palette order:

| Index | Color | Hex |
|---|---|---|
| 0 | Sage | `#A9C29A` |
| 1 | Clay | `#C98F72` |
| 2 | Sand | `#D4BE94` |
| 3 | Mist | `#93A9C4` |
| 4 | Lilac | `#B7A6D6` |
| 5 | Rose | `#D9A0A8` |
| 6 | Mint | `#9DD3C4` |

White and grey are never palette choices. Eyes and hardware retain their existing colors.

## Change a color

Open Settings and find the workspace. Choose one of its seven palette dots. Auto restores its position-based default. Picks recolor its faces immediately. Account sync carries picks between desktop and phone. Offline edits persist until account sync succeeds.

Workspace JSON carries an optional `color` integer, from zero through six. An absent field means automatic color. Clients advertise `workspace_color` through `X-Layout-Features`. The service hides colors from legacy clients. Legacy writes preserve previously stored overrides. Three-way merging retains unrelated remote color changes. Local picks and explicit resets win conflicts.

## Visual evidence

The visual workflows use Cedar, Orbit, and Lagoon. They capture three default colors and Settings pickers. Picker interactions also verify recoloring. Desktop proof checks persisted color metadata. Android proof checks inbox and reader colors. Both workflows accept `ceo/IGSH-1172-generic` pushes.
