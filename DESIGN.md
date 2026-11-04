# DESIGN.md: UI style for the ACH Payments demo

The screens should look like a credit union's internal banking application: plain, readable and trustworthy. The bank is a fictional **Demo Credit Union**. Don't use any real institution's name, logo or trademarks.

All tokens live in `frontend/src/app.css` (`:root`). Use them; don't hard-code new colours.

## Colours
| Token | Value | Use |
| --- | --- | --- |
| `--navy` | `#0b2a55` | Header bar, page titles, active menu item |
| `--blue` | `#1f5fbf` | Primary buttons, focus ring |
| `--page` / `--surface` | `#f3f5f8` / `#ffffff` | Page background / cards, tables, modals |
| `--border`, `--border-strong` | `#d9dee6`, `#b8c0cc` | Dividers / input borders |
| `--text`, `--text-muted` | `#1b2433`, `#5a6577` | Body text / labels, headers, captions |
| `--error` (+ `-bg`) | `#b42318` | Inline field errors, invalid fields, page errors |
| `--success` (+ `-bg`) | `#067647` | Success toasts, healthy status |

## Layout
- Navy top bar (64 px) with the DCU mark, "Demo Credit Union" and the product name.
- A white menu bar below it. The current page is underlined in navy.
- Content is centred, max 1280 px wide, with 32 px side padding.
- A status bar at the bottom shows the database health.

## Components
- **Buttons:** 6 px radius, semibold. There is one primary (blue, filled) per view; the rest are white with a border.
- **Tables:** white card with a border. Headers are small uppercase muted text on `#f8f9fb`. Rows highlight on hover. When a table is empty, it shows a centred `.empty` message.
- **Inputs:** 6 px radius, with a blue focus ring. When invalid, the field gets a red border and a pale red fill, and the message appears below it in `.field-error`.
- **Modals:** white, 8 px radius, with a soft shadow over a navy overlay.
- **Toasts:** green-tinted, bottom right.

## Type
- System UI font (Segoe UI / -apple-system / Roboto), 15 px body.
- Monospace for codes and values (parameter names, routing numbers, trace numbers).
- Account numbers are always masked to the last 4 digits (DB-MODEL Q6).
