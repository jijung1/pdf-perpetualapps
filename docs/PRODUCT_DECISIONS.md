# Product decisions: pricing, promises and naming

Last updated 2026-11-18.

## Pricing and promises

**Decided (2026-11-18): a fixed price, paid once up front, and you own the app.** No in-app purchases, no ads, no subscription, no account, and full privacy. The app is **never free**: no "free" or "free to download" wording anywhere. (An earlier version of this file wrongly recorded "free to download".)

- **What the buyer owns:** a permanent licence to the version they bought. It doesn't rely on any server or account, so nothing expires.
- **Updates and support are not promised.** The app may stop being updated or supported in the future. Don't write "free updates", "lifetime updates", "forever" or "always supported".
- Where this is stated: website (promise strip, "Pay once. It's yours to keep.", FAQ "How much does it cost?" and "Will the app keep getting updates?"), terms ("Purchase and licence", "Updates and support"), privacy policy ("Purchases": Google Play processes payment and shares limited order information).
- The exact price is set in the Play Console. The website never states a number, so it can't go out of date.
- **Google Play rule:** a paid app can later be made free, but a free app can never be made paid.

### How to word it (store listing, screenshots, About screen)

- State today's facts, not future promises:
  > No ads. No in-app purchases. No subscription. No account.
  > Works offline: no internet permission, so your files never leave your phone.
- **Not in the title, icon or developer name:** "free", "no ads", "best", "#1" or similar. Google Play's metadata policy bans price and promotional claims there. Play shows the price and the "Contains ads" / "In-app purchases" labels itself.
- Prefer the concrete "no internet permission" over the vague "full privacy". The build enforces it (see `PLAY_COMPLIANCE.md`).
- If anything ever changes, update the listing in the same release, so it never says something untrue.

### Still to do

- [ ] Short **terms of use** ("provided as is", limit of liability, e.g. if a whiteout or edit doesn't meet someone's legal requirements), shown next to the privacy policy in About. Have a lawyer glance at it.
- [ ] Set the price in the Play Console (a paid app needs a Google Payments merchant account linked to the developer account).
- [ ] Optionally add "One-time purchase. No ads, no in-app purchases, no subscription." to the About screen.

## Naming (decided 2026-11-18)

| Where | Decided | Notes |
|---|---|---|
| Play Store title | **PDF Editor: Perpetual Apps** (26/30) | No company or product called "PerpetualApps" found in a web search. Unrelated "Perpetual…" apps exist (Perpetual Health, Perpetual Foreigners, Perpetual Media Network), so the risk is low. "Perpetual" is a common word, so the brand is weak to protect. |
| Name under the icon (`app_name`) | **PDF Editor: pA** (14 chars) | Applied in `strings.xml`. Known trade-offs, accepted: many launchers truncate to about 10–12 characters ("PDF Editor: …"); screen readers say "p A"; differs from the store title. |
| In-app branding | "PDF Editor" with "by Perpetual Apps" | Home header and About screen. Body text says "the app" / "this app". Privacy policy: "Privacy Policy for PDF Editor: Perpetual Apps". Internal code names (`app.folio…`, `FolioApplication`) are unchanged and invisible to users. |

Also decided 2026-11-18:
- **Icon:** design option A3 (page and pen with a gold "pA" roundel in EB Garamond outlines). It's applied as the adaptive icon with a monochrome layer. The Play Store icon is `design/perpetual-apps/play-store-icon-512.png`.
- **Application ID:** `perpetualapps.pdf` (was `app.folio.pdf`). It's permanent from the first upload and appears in the Play Store URL. The code namespace `app.folio` is unchanged and invisible to users.

Still to do before publishing: USPTO / EUIPO / Play Store searches for "Perpetual Apps".

### Earlier naming proposals (superseded)

| Where | Proposed | Notes |
|---|---|---|
| Play Store title (≤ 30 chars) | **PDF Editor: Perpetual Software** (30/30) | No app or trademark found under that name in a web search (2026-11-18). "Perpetual software" is a common industry term for one-time-licence software, so it's weak as a brand and hard to protect. Exactly 30 characters, with no room to adjust. |
| Name under the icon on the phone | **PDF Editor: PS** | Concerns: most launchers show about 10–12 characters, so it appears as "PDF Editor: …" and looks like every other PDF editor. "PS" is commonly read as PostScript (another Adobe document format) or PlayStation. It differs from the store name, so people searching their phone for "Perpetual" won't find it. |

Also considered (2026-11-18):

| Name | Findings | Assessment |
|---|---|---|
| **PDF Editor: Foreverware** (store, 23 chars) / **Foreverware** (phone, 11 chars, fits under the icon) | No app found with that name. **Possible conflict:** "FOREVER WARE" (two words), USPTO serial **97237688**, filed 2022-01-25 by Forever Ware Reusable PBC for *downloadable software*, the same class as an app. Status unknown. Forever, Inc. (a storage app) also holds many FOREVER marks and has opposed similar ones. | The best fit for the message, and short enough for the phone. **Check serial 97237688 on the USPTO before choosing it.** If it's live, choose another name. |
| **PDFEditor-fW** | No app or trademark found. | No legal issue, but hard to say or remember, "fW" means nothing to users, it looks like a file name, and Play search won't match "PDF editor" well without the space. Not recommended. |

Earlier candidates and why they were set aside:
- **PlainPDF:** an app with that name exists on the App Store, and "PDF Plain and Simple" on Google Play has nearly the same pitch.
- **LocalPDF:** crowded (an iOS "Local PDF" plus several web and desktop tools).
- **Banksy:** a real person's pseudonym, with trademarks held by Pest Control Office Ltd. Risks infringement and false-endorsement claims, and would break Play's impersonation policy.
- **ForeverLeaf:** available, but "Plainleaf" is an ad-free PDF app on Play (risk of confusion), and the name doesn't say PDF.
- **PerpetuityPDF:** available, but long, hard to spell, and sounds like finance jargon.
- **OwnPDF, Quiet PDF:** no conflicts found; still viable.

Before publishing, whatever the final name: search the Google Play app itself, [USPTO](https://tmsearch.uspto.gov/) and [EUIPO](https://euipo.europa.eu/eSearch/). Web searches aren't a trademark clearance.
