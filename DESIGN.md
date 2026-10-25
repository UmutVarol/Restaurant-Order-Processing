---
name: Sofra
description: Real-time restaurant point of sale with a till, a kitchen display, a live floor plan and a manager dashboard.
export: dtcg
colors:
  bg: "#F6F2EC"
  surface: "#FFFFFF"
  surface-2: "#FAF7F3"
  surface-3: "#F2EDE6"
  border: "#EBE4DA"
  border-strong: "#DDD3C6"
  text: "#1F1A17"
  text-2: "#4A423C"
  muted: "#7A7068"
  faint: "#A59C93"
  brand: "#E4572E"
  brand-strong: "#C9441D"
  brand-soft: "#FDEBE4"
  saffron: "#F2A541"
  green: "#2F9E6B"
  amber: "#D98B0B"
  blue: "#3B6FD8"
  violet: "#7A55D6"
  teal: "#12908E"
  red: "#D64545"
  slate: "#6B7280"
  sidebar-bg: "#1B1613"
typography:
  body:
    fontFamily: Inter
    fontSize: 14px
    lineHeight: 21px
    fontWeight: 400
  display:
    fontFamily: Fraunces
    fontSize: 24px
    lineHeight: 29px
    fontWeight: 600
  figure:
    fontFamily: Fraunces
    fontSize: 32px
    lineHeight: 32px
    fontWeight: 600
  mono:
    fontFamily: ui-monospace
    fontSize: 12.5px
    lineHeight: 19px
rounded:
  xs: 6px
  sm: 10px
  base: 14px
  lg: 20px
  xl: 28px
spacing:
  gutter: 28px
  gutter-mobile: 16px
---

# Sofra design language

Sofra looks like a warm Turkish *lokanta*: cream paper, a tomato-red brand colour, saffron highlights, and a
dark espresso sidebar. Dishes appear as illustrated cards, and kitchen tickets are slips with a coloured edge
for their state. The tokens live as custom properties in `src/main/resources/static/assets/css/app.css`. The
YAML above holds the light theme, and the dark theme redefines the same names under `[data-theme="dark"]`.

## Colour

| Role | Token | Used for |
| --- | --- | --- |
| Brand, action | `brand` (tomato) | Primary buttons, the selected table, quantity bubbles, chart lines |
| Highlight | `saffron` | Gradients with the brand colour, kitchen notes, free-delivery progress |
| Order states | `amber` New, `blue` Preparing, `green` Ready, `teal` Served, `violet` On the way, `slate` Completed, `red` Cancelled | Status pills, kitchen columns, ticket edges, timeline |
| Shell | `sidebar-bg` | The espresso sidebar that frames every screen |

Status is always shown as a dot and a word, never as colour alone.

## Typography

- **Fraunces** (an optical-size serif) for page titles, the brand name, large figures and totals.
- **Inter** for everything else. Numbers that change or are compared use tabular figures.
- The receipt uses the system monospace font, imitating a thermal printer.

## Shape and depth

- Generous radii: 10px for controls, 14–20px for cards, 28px for modals.
- Soft, warm-tinted shadows on cards. Larger shadows are reserved for toasts, drawers and modals.

## Motion

- Easing is `cubic-bezier(.2, .8, .2, 1)` throughout.
- Buttons and chips press to `scale(.97)`.
- Only a newly arrived kitchen ticket slides in, and only once; a re-render never replays the animation.
- Toasts and dialogs slide in, and fade out when closed. At most three toasts are shown, and hovering one
  pauses its timer.
- Transitions list their properties explicitly; `transition: all` is never used.
- On touch screens (`hover: none`), hover lifts are switched off so a tap does not leave a card raised.
- `prefers-reduced-motion` shortens all animation to a single frame.

## Accessibility and input

- There is a skip link. Icon-only buttons have `aria-label`s, form fields have linked labels, and dialogs are
  labelled by their headings.
- While a dialog is open, the app behind it is `inert`. Escape closes the dialog and returns focus to where
  it was.
- Text uses Intl for currency, times and plurals (`Intl.PluralRules`, so the app says "1 item", not "1 items").
  Initials split by grapheme (`Intl.Segmenter`).
- The orders filters are kept in the URL (`#/orders?status=active&q=ayşe`).
- Inputs are 16px on touch screens so iOS does not zoom. Controls use `touch-action: manipulation`.

## Content rules

These rules were checked against worst-case data: 80-character names, 140-character line notes,
255-character addresses and notes, quantity 50 and a ₺600,000 ticket.

- Long names wrap or are truncated with an ellipsis. Figures are never truncated.
- Scroll containers that hold `.sr-only` text are positioned, so the hidden text cannot widen the page on phones.
- The till enforces the same limits as the API (at most 50 of one dish per line), so a ticket can always be sent.
- Clearing the ticket can be undone from the toast. Cancelling an order or archiving a dish asks for confirmation.
