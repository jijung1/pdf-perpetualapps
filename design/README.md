# App name and icon options

Open **`index.html`** in a browser to compare all six side by side. Each one is shown in the circle, squircle and rounded-square masks Android launchers use, at small sizes, and on a home screen. The icons in `icons/` are 108 × 108 dp adaptive-icon canvases. Launchers show only the centre 72 dp.

Priority order for the message: **basic PDF editing → private → pay once (no subscription) → no ads.**

| # | Name | Play Store title (≤ 30) | Icon idea | Leads with |
|---|---|---|---|---|
| 1 | Folio | Folio: Private PDF Editor | Page + pen nib (current colours) | Editing, brand continuity |
| 2 | **PlainPDF** ★ | PlainPDF: Simple PDF Editor | The word PDF on a plain page, one pencil mark, light icon | Editing + "no clutter" |
| 3 | OwnPDF | OwnPDF: Offline PDF Editor | Page with an infinity loop | Pay once, yours forever |
| 4 | LocalPDF | LocalPDF: Private PDF Editor | Page + green shield with check | Privacy |
| 5 | Quiet PDF | Quiet PDF: Private PDF Editor | Page with a closed eye | No ads, no tracking |
| 6 | PDF Offline | PDF Offline: Edit & Sign | Page + crossed-out cloud | Search keywords, literal privacy |

## My recommendation: PlainPDF

- **It says "PDF editor" first,** as you asked, and "plain" quietly carries the rest: no ads, no upsells, nothing extra.
- **The light icon is distinctive.** Most PDF apps use red or dark icons. It also stays legible at 24 px because of the bold "PDF".
- **It makes no promotional claim,** so it's safe under Play's metadata policy.

Runner-up: **OwnPDF**, if you want "foreverware" to be the headline rather than the subtitle.

## Words kept out of titles and icons on purpose

Google Play's metadata policy doesn't allow price or promotional claims in the **title, icon or developer name**. That includes "free", "no ads", "best", "#1", "sale", and badges like "AD-FREE" on the icon. So "pay once, no ads, no subscription, private" goes in:
- the **short description** (≤ 80 characters; drafts are in `index.html`),
- the first lines of the **full description**,
- the **screenshots** (e.g. a caption like "No ads. No account. No internet permission.").

## Before you decide

1. **Search the name:** Play Store, the [USPTO trademark search](https://tmsearch.uspto.gov/) and [EUIPO eSearch](https://euipo.europa.eu/eSearch/). Check each name alone and with "PDF". I haven't checked any of these. "Folio" in particular is used by several existing products.
2. **Say the name aloud** and type it into Play search: it should be easy to spell after hearing it once.
3. **Check it at the real size** in `index.html` (the 40 and 24 columns are what notifications and the share sheet show).

## When you've picked one

I can turn the chosen SVG into the app's adaptive icon:
- foreground and background vector drawables, plus a monochrome layer for Android 13+ themed icons,
- the 512 × 512 Play Store icon,
- the app name in `strings.xml`, the About screen and the docs.

Option 2's "PDF" text would be converted to outlined paths so it renders the same on every phone.
