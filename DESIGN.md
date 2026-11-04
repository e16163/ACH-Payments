# DESIGN.md: UI style for the ACH Payments demo

The screens use an x.ai-inspired look (from `getdesign add x.ai`): a near-black canvas, white text, hairline borders, white pill buttons and clean sans-serif type. The bank is a fictional **Demo Credit Union**. Don't use any real institution's name, logo or trademarks.

All tokens live in `frontend/src/app.css` (`:root`). Use them; don't hard-code new colours.

## Colours
| Token | Value | Use |
| --- | --- | --- |
| `--canvas` | `#0a0a0a` | Page, header, menu bar, modals |
| `--canvas-card` / `--canvas-soft` | `#191919` / `#1a1c20` | Cards and tables / inputs, table headers, row hover |
| `--hairline` | `#212327` | All borders and dividers |
| `--pill-border` | white at 25% | Buttons, active menu item, input focus |
| `--ink` / `--body` / `--mute` | `#ffffff` / `#dadbdf` / `#7d8187` | Headings and values / body text / labels and captions |
| `--error` (+ `-bg`) | `#ff8a80` | Inline field errors, invalid fields, page errors |
| `--success` (+ `-bg`) | `#7ee2a8` | Bank-name confirmation, toasts, healthy status |

x.ai's palette has no error or success colours. These two are additions, because banking forms need them.

## Layout
- A 64 px header with the round DCU mark, "Demo Credit Union", and the product name in muted text.
- A menu bar below it. Items are pills, and the current page has a white outline.
- Content is centred, max 1200 px wide, with 32 px side padding.
- A small muted status bar at the bottom shows the database health.

## Components
- **Buttons:** pills (9999 px radius), weight 400. There is one primary per view (white fill, black text); the rest are black with a 25% white outline.
- **Labels:** field labels, table headers and summary terms are 13 px sentence case, weight 500, in `--mute`. Section titles are 17 px, weight 500, in `--ink`. No all-caps.
- **Tables:** `--canvas-card` with hairline borders, 8 px radius. Rows highlight on hover. An empty table shows a centred `.empty` message.
- **Inputs:** `--canvas-soft` fill, hairline border, 8 px radius. The border brightens on focus. `.invalid` gives a red border and a faint red fill, with the message below it in `.field-error`.
- **Modals:** black, hairline border, 8 px radius, over a 70% black overlay.
- **Date picker:** the selected day is a white pill. Disabled days are struck through, and holidays are red.

## Type
- Inter / system UI at 15 px, weight 400. Headings are 32 px with negative tracking.
- Monospace only for codes and numbers: parameter names, routing and account numbers, reference and trace numbers.
- Account numbers are always masked to the last 4 digits (DB-MODEL Q6).
