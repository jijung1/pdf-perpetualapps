# Stage 1: Audit, launch checklist, Google Play listing

Prepared 2026-11-18. Covers the first two deliverables. Later stages go in separate files in this folder.

Labels used in this file:
- **[Verified]**: checked today against the live site, the codebase or an official Google page.
- **[Hypothesis]**: a reasonable guess that still needs data.
- **[Rec]**: a recommendation.

---

## 0. Audit

### 0.1 Product claims against the v1.0.0 build

| Claim (from the brief) | Status | Notes |
|---|---|---|
| Works offline / no Internet permission | **[Verified]** | Merged manifest has no `INTERNET`. App info screen shows "No permissions requested". The one manifest entry, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is an internal AndroidX signature permission, not a user-facing one. Re-check on the **release AAB from Play**, not a debug build. |
| No account, ads, tracking, analytics | **[Verified]** | Without network access these aren't possible. No SDKs for them in the build. |
| One-time $6.99, no subscription | **[Verified]** in design | No billing library in the app. It's a paid app on Play. |
| Whiteout removes underlying content | **[Verified], with limits** | Removes glyphs (including OCR layers and shared form XObjects), image pixels and vector drawings under the box, then checks the result. **It does not** scrub document metadata (title/author/XMP), bookmarks, file names, or the same text elsewhere in the file. Never call it "secure redaction" or "sanitization". |
| Edit existing text | **[Verified]** | Original glyphs are removed. A bundled font is used when the original font can't render the new text. |
| "Filling PDF forms" | **Needs rewording** | The app **types onto** forms, with snapping and auto-sizing to boxes. It does **not** fill interactive form fields, and every save **flattens** fields and comments. Say "fill in forms by typing on them", never "fillable form support". |
| Handwritten signatures and initials | **[Verified]** | They are images. Not certificate-based digital signatures. The site FAQ already says so. |
| Merge PDFs, combine with photos | **[Verified]** | JPG and PNG. HEIC needs Android 9+. |
| Split; reorder, rotate, duplicate, delete, extract | **[Verified]** | |
| Password-protected PDFs | **Unsupported** | The app refuses them cleanly. Disclose this. |
| Android 8.0+ | **[Verified]** | `minSdk 26`. |
| Edit saves can make files 2–3× larger | **Known limitation** | Don't claim "small files". Fix planned after v1. |
| English only | **[Verified]** | Only `values/strings.xml` exists. |

### 0.2 Live website (https://pdf.perpetualapps.dev/)

**What already works, so keep it:**
- The H1 "Edit, sign and organize PDFs. Right on your phone." is clear.
- The four promise icons (one-time, no account, no ads, no internet permission) sit right under the hero.
- The privacy section is evidence-based. The app-info screenshot and the "check it yourself" line are the strongest proof on the page.
- The FAQ is honest about signatures, flattening and updates.
- No cookies, analytics or third-party resources. The page is small (12 KB HTML, Cloudflare-served).
- Privacy policy and terms are live, dated, and consistent with the no-promises rule on updates.

**Update 2026-11-18:** W2–W8 are fixed in `website/` (approved) and still need to be deployed. The changes:
- public name
- price shown
- FAQ "No."
- absolute og:image plus a new `share.png`
- canonical tags, `sitemap.xml`, `robots.txt` with a Sitemap line
- JSON-LD
- "complete" removed from the hero

W1 waits on approval.

**Issues, most important first:**

| # | Issue | Evidence | Fix [Rec] |
|---|---|---|---|
| W1 | Every Google Play link returns **404** while review is pending | `play.google.com/...id=perpetualapps.pdf` → 404 today | Fine if approval is days away. If you share the site before approval, change the badge to "Coming soon to Google Play" (no link). Restore it the day the app goes live. |
| W2 | Product name differs in three places | Launcher: "PDF Editor: pA". Site: "PDF Editor by Perpetual Apps". Privacy/Terms: "PDF Editor: Perpetual Apps". | **Decided 2026-11-18:** the public name is **"PDF Editor: Perpetual Apps"** everywhere (Play, site, policy, terms). The launcher stays "PDF Editor: pA", an abbreviation to fit the home screen. Site text still to align, pending approval. |
| W3 | **No price shown** | Only "One-time purchase" | We decided earlier not to show a price, because Play shows the local one. Now that the price is fixed at $6.99 and price transparency is the pitch, I recommend reversing that: show "US$6.99, once" near the badge, with a note that local prices are on Google Play. **Your call.** |
| W4 | FAQ "Can it open password-protected PDFs? **Not yet.**" | Live FAQ | "Not yet" implies a future update, which breaks the no-promises rule. Change to "No. The app tells you clearly when a file is password-protected." |
| W5 | `og:image` is relative | `content="assets/icon-512.png"` | Use an absolute URL. Also add a 1200×630 share image; the square icon looks poor in link previews. |
| W6 | No canonical URL, sitemap or structured data | `sitemap.xml` → 404. No `rel=canonical`. No JSON-LD. | Add a canonical, `sitemap.xml`, a `Sitemap:` line in robots.txt, and `SoftwareApplication` JSON-LD (name, OS, category, offers price/currency). **No** `aggregateRating` until real ratings exist. |
| W7 | robots.txt is Cloudflare's managed content-signals text | Comments only, no directives | Search crawling isn't blocked, so this is OK. Check in Cloudflare which signals (search / ai-input / ai-train) you actually want. |
| W8 | "A **complete** PDF editor" in the lede | Hero | It overclaims for a two-tool app. Use "A PDF editor that never goes online." |
| W9 | Lede says "Fill in forms" | Hero | This is acceptable because the FAQ explains flattening, but the features list should say "type onto forms". |
| W10 | Only 4 screenshots, all 540×1200 | Assets | Enough for the site. Play needs larger captures (see checklist C6). There are no merge or split screenshots yet. |
| W11 | Single page, no search-intent pages | Site structure | Handled in Stage 6 (SEO architecture). Not a launch blocker. |

### 0.3 Positioning

- "PDF editing. Without the baggage." is good, but on its own it's vague. The concrete proof points carry the message:
  - no Internet permission (anyone can verify it)
  - whiteout that deletes content
  - US$6.99 once
- **[Rec]** Lead with *privacy you can verify* plus *pay once*. Use *whiteout that deletes* as the differentiator demo. Treat edit, sign, merge and split as the expected baseline, not the pitch.
- **Competitive context [Hypothesis, light research only]:**
  - The Play "PDF editor" results are crowded with free, ad- or subscription-funded apps (Adobe Acrobat, Xodo, iLovePDF, Lumin and many generic "PDF Editor: …" titles).
  - A few apps already pitch offline / no ads / one-time pricing:
    - "PDF Plain and Simple" (com.noadspdf) markets itself as offline with no upload.
    - RevPDF lists a $9.99 lifetime licence.
    - MyPDF has a one-time model, about $4–5 per AlternativeTo.
  - So "offline" and "no subscription" alone won't set you apart. The **no-Internet-permission proof** and **real whiteout** are rarer. Verify competitors' current prices and permissions on Play before naming them anywhere, and never name them in the listing.

---

## 1. Launch-readiness checklist

Effort: S < 1 h, M = half a day, L = 1–2 days. "Owner action" means something only you can do, such as a console, DNS or account step.

### P0: before approval / launch day

| ID | Task | Effort | Depends on | Deliverable | Done when |
|---|---|---|---|---|---|
| C1 | ~~Choose the public name~~ Decided: "PDF Editor: Perpetual Apps". Align the site text (needs approval) | S | — | One name used everywhere | Site, policy, terms and Play match |
| C2 | Verify claims on the **Play-delivered release build**: airplane-mode run of every tool; app info shows "No permissions requested"; whiteout on a test PDF with text extracted afterwards; password PDF refusal message | M | Internal testing track | Signed-off claims list | Every claim in §0.1 is ticked on the release build |
| C3 | Test purchase and install: add yourself as a **license tester**, install from the internal or closed track, then check purchase, reinstall and a second device on the same account | S | Merchant account, price set | Notes | Purchase completes; reinstall doesn't charge again |
| C4 | Pricing: set US$6.99 and **review Play's auto-converted local prices** in Play Console. Adjust any that look out of line with local purchasing power, rather than accepting them blindly | S | C3 | Price table | Each target country has a price you chose |
| C5 | Play Console app content: Data safety (no data collected or shared), content rating questionnaire, target audience (not children), privacy policy URL, category **Productivity**, tags, contact email | S | — | Completed forms | No warnings in the console |
| C6 | Recapture screenshots at **≥1080 px wide** (the emulator's native 1080×2400 is fine; the long side is ≤2.3× the short side). Play requires ≥4 at ≥1080 px to be eligible for recommendation formats | M | Stage 3 brief | 6 PNG/JPEG files | Uploaded, alt text added |
| C7 | Feature graphic, 1024×500, JPEG or 24-bit PNG, little text, no device frames or badges | S–M | Stage 3 | 1 image | Uploaded |
| C8 | Listing text pasted (§2) | S | C1 | Live listing | Passes review |
| C9 | Website fixes W2–W6 and W8 (copy edits, canonical, absolute og:image, sitemap, JSON-LD, price) | M | **Your approval** | Edited `website/` files, deployed | Rich Results Test passes; sitemap returns 200 |
| C10 | Google Search Console: add a **Domain property** for `perpetualapps.dev` (DNS TXT record in Cloudflare) and submit the sitemap | S | C9, owner action | Verified property | Homepage shows "URL is on Google" |
| C11 | Play badge behaviour before approval (W1) | S | Approval timing | Decision | No 404 for visitors |
| C12 | Metrics tracker set up with baseline rows (Stage 7) | S | — | Sheet | First weekly row filled |

### P1: first two weeks

| ID | Task | Effort | Depends on | Deliverable | Done when |
|---|---|---|---|---|---|
| C13 | Record the first 3 demo videos (whiteout, offline proof, sign a form) | M | C2 | 3 vertical clips | Published on YouTube Shorts and at least one other platform |
| C14 | Draft launch posts (Stage 5) and read each community's current rules | M | — | Drafts | Posted only where the rules allow |
| C15 | Preview video (optional): YouTube, ads off, public or unlisted, real footage in the first 10 s; only the first 30 s autoplay | M | C13 | YouTube URL | Added to the listing |
| C16 | Bing Webmaster Tools (it can import from Search Console) | S | C10 | Verified | Sitemap fetched |
| C17 | Share image (1200×630) for link previews | S | — | Image + meta | Preview looks right in a messaging app |
| C18 | Plan for reading and replying to reviews (§2.7) | S | Launch | Routine | Every review answered within 48 h |

### P2: later, based on evidence

- C19: Store listing experiments (once traffic allows; see §2.6).
- C20: SEO landing pages (Stage 6).
- C21: Localization (§2.5).
- C22: Directories and launch platforms (Stage 5).

---

## 2. Google Play listing draft

**Rules checked today on support.google.com:**
- Title ≤ 30 characters, short description ≤ 80, full description ≤ 4,000.
- No price or promotion terms ("free", "no ads", "sale"), rankings, emoji or ALL CAPS in the **title, icon or developer name**.
- Short description: no calls to action ("download now"), no "best / #1 / top / new", no emoji or special symbols.
- Full description: no unattributed testimonials, no comparisons with other apps.
- Repetitive keywords can get the app suspended.

### 2.1 Title options

**Decided 2026-11-18: the Play title is "PDF Editor: Perpetual Apps" (26 characters).** The phone launcher stays "PDF Editor: pA". With the brand in the title, the keywords "sign", "merge" and "split" have to come from the short description and full description. Play indexes both, and the drafts below already lead with those verbs. The keyword titles below are kept for reference, in case search data later suggests trying one.

| # | Title | Characters | Notes |
|---|---|---|---|
| **Chosen** | **PDF Editor: Perpetual Apps** | 26 | Brand-led and consistent with the site and policies. |
| A | **PDF Editor: Edit, Sign & Merge** | 30 | Covers the three highest-intent verbs ("edit / sign / merge PDF"). Reads naturally. |
| B | PDF Editor: Sign & Merge | 24 | Your proposal. Clean, but loses "edit". |
| C | Offline PDF Editor & Signer | 27 | Leads with the differentiator, but "signer" is a weaker term than "sign". |
| D | PDF Editor Offline: Sign PDF | 28 | Puts "offline" in the title but repeats "PDF". |

- Titles can be changed later; every change goes through review.

### 2.2 Short description options (≤ 80 characters)

1. **[Rec]** `Edit, sign and merge PDFs offline. No accounts, ads or subscriptions.`
2. `Edit, sign, merge and split PDFs on your phone. Works offline, no account.`
3. `Private PDF editor: sign, merge, split and white out. No internet needed.`
4. `Fill and sign PDFs, merge and split pages. Offline, with no account or ads.`

- Option 1 is your proposal and it's strong.
- "No ads" is allowed here (the ban covers title, icon and developer name), but it is promotional. If review objects, use option 2.
- Character counts are checked at the end of this file.

### 2.3 Full description (paste-ready)

```
A private PDF editor for Android that works entirely on your phone. Edit and sign documents, white out text, and merge or split PDFs, without an account, a subscription or an internet connection.

The app doesn't request Android's Internet permission, so your documents can't be uploaded anywhere. You can check this in Settings › Apps › PDF Editor: pA › Permissions.

FILL & SIGN
• Type onto any PDF form. Tap inside a box and your text lines up and sizes itself to fit.
• Sign with a saved signature, or add initials, ✓, ✗ or today's date.
• Fix a typo in the document's own text. The old words are removed, not just covered.
• White out text, images or drawings. Before you save, the app outlines exactly which characters will be removed. When you save, the content under the box is deleted from the page, not hidden under a white rectangle.
• The whiteout colour matches the paper automatically, or pick your own.

MERGE & ORGANIZE
• Combine PDFs with JPG, PNG and HEIC photos in any order. Photos become clean A4 or Letter pages.
• Drag pages into order. Rotate, duplicate, extract or delete pages, with undo and redo.
• Split by page ranges (like 1-3, 5, 8-), every few pages, or one file per page.
• Pages are copied, not re-rendered, so text stays sharp and selectable.

SAFE WITH YOUR FILES
• Edits are saved as a new copy. Your original is never changed unless you choose to overwrite it.
• Every result is reopened and checked before you save or share it.
• Recent files, saved signatures and drafts stay in the app's private storage and are excluded from cloud backup.

PAY ONCE
One price, paid once on Google Play. No subscription, no in-app purchases, no ads, no account and no analytics. The app works offline and doesn't depend on any server.

GOOD TO KNOW
• Saving flattens form fields and comments into the page, so the result looks the same in every app. The saved copy can't be filled in again as an interactive form.
• Signatures are images of your handwriting. They aren't certificate-based digital signatures.
• Whiteout removes content from the page area you cover. It doesn't remove document metadata or the same text elsewhere in the file. For sensitive documents, check the saved copy before sharing.
• Password-protected PDFs aren't supported.
• Saved files can be larger than the original.
• Requires Android 8.0 or newer. Adding HEIC photos needs Android 9 or newer.
• Future updates and support aren't guaranteed. The version you buy keeps working offline.

Questions or problems: use the contact email on this page.
```

- "Settings › Apps › PDF Editor: pA" uses the launcher name on purpose, because that is what Android Settings shows.
- The ✓ and ✗ characters are fine in the full description; the ban on special symbols covers the short description.

### 2.4 Keyword strategy [Hypothesis: no volume data yet]

No search-volume data is claimed here. Groups are ordered by **relevance × purchase intent**. Competition for generic terms is visibly very high: Adobe and dozens of free apps rank for them.

| Intent group | Terms | Relevance | Competition (observed) | Purchase intent | Where to place |
|---|---|---|---|---|---|
| Core task | pdf editor, edit pdf, pdf editor android | High | Very high | Medium | Title, first sentence |
| Sign | sign pdf, fill and sign, add signature to pdf | High | High | High (task in hand) | Title, short description, Fill & Sign section |
| Assemble | merge pdf, combine pdf, photos to pdf, split pdf | High | High | High | Title, Merge section |
| Differentiator | offline pdf editor, pdf editor no internet, private pdf editor | High | Medium [Hypothesis] | High (deliberate choice) | Short description, opening line |
| Pricing | pdf editor no subscription, pdf editor one time purchase | High | Low–medium [Hypothesis] | Very high | Description "Pay once"; website page |
| Whiteout | pdf whiteout, remove text from pdf, redact pdf | Medium–high | Medium | Medium | Description; website page. Use "redact" only on the website, with caveats. |
| Organize | rotate pdf pages, delete pdf pages, reorder pdf | Medium | Medium | Medium | Description |

**How to check demand cheaply:**
1. Type each seed term into Play's search box on a phone and note the autocomplete suggestions. Suggestions roughly reflect popular queries, and they cost nothing.
2. Use Google Trends to compare the relative interest of phrasings such as "merge pdf" vs "combine pdf".
3. **After launch, the real source:** Play Console → Grow users → Store performance → **Store analysis / search terms**, which shows the actual terms that brought listing visitors and conversions. Also Search Console queries for the website.
4. Optional paid tools for estimates (AppTweak, Appfigures, data.ai) have trials. Treat their volumes as model estimates, not facts.
5. Revisit after 30 days of data. Keep the terms that convert, and stop optimising for terms that bring visitors who don't buy.

### 2.5 Localization [Rec]

- The **app UI is English-only**. Localising the listing alone produces visitors who then find an English app, which risks refunds and low ratings.
- **Phase 1 (launch): English only**, plus a quick check that Play's automatic machine translation of the listing reads acceptably in the top 2–3 non-English markets that show visitors.
- **Phase 2 (after day 30, and only if Store analysis shows meaningful non-English traffic):**
  - Translate the app and listing together. Do German, Spanish, Portuguese (Brazil), French and Japanese in the order data supports. These are large paid-app markets, but this is a [Hypothesis] to confirm against your own data.
  - Pay a human reviewer for the store text.
- **Pricing comes before translation:** check local prices (C4). A high price after conversion in a lower-income market hurts more than missing translations.

### 2.6 Conversion improvement plan

- **Baseline first.** Record listing visitors and the visitor-to-buyer rate weekly for the first 2–4 weeks before changing anything.
- **Experiments [Verified, mechanics]:**
  - Play supports one default-graphics experiment (icon, feature graphic or screenshots) or up to 5 localized experiments at once, each with up to 2 variants.
  - The metric is unique install clicks / installs.
  - [Unverified] Google's experiments page doesn't explicitly cover paid apps. Confirm it in your console after launch.
- **Order to test** (one variable at a time):
  1. First screenshot: privacy proof vs "pay once" message.
  2. Short description option 1 vs 3 (a localized-text experiment on en-US).
  3. Feature graphic.
  4. Icon, last, since it carries brand recognition.
- **Stopping rule:** keep a test running until Play reports a confident result, or for at least 2–4 weeks. If the listing has fewer than about 1,000 visitors a month, skip experiments and change one thing at a time, comparing month over month while knowing that comparison is noisy.

### 2.7 Reviews and ratings

- **Ask at a good moment, in the app:**
  - Use the Play **In-App Review API** after a successful save or share, at most once per user, and only after a few successful sessions.
  - Never ask after an error, and never gate the prompt on the user's rating ("Do you like the app?").
  - [Check before building] whether the Play Core review library adds any permission or network dependency. It must not change the "No permissions requested" claim. This is a later product change and needs your approval.
- **Reply to every review**, especially negative ones, within 48 h. Be factual and say what will happen, without promising dates or updates.
- Tag each review with a theme (compatibility, whiteout, forms, price, size growth, bug) in the tracker. Recurring themes feed the product backlog.
- **Never** offer anything in exchange for reviews, ask friends to post reviews, or post or reply to reviews yourself under other accounts.

---

### Character-count check

Counted by script on 2026-11-18. Every item is within its limit.

| Item | Characters | Limit |
|---|---|---|
| Title (chosen) / A / B / C / D | 26 / 30 / 24 / 27 / 28 | 30 |
| Short description 1 / 2 / 3 / 4 | 69 / 74 / 73 / 75 | 80 |
| Full description | 2,543 | 4,000 |

The full description leaves about 1,450 characters of headroom. Don't fill it with keywords.

**Sources:** [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842) · [Preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) · [Listing best practices](https://support.google.com/googleplay/android-developer/answer/13393723) · [Store listing experiments](https://support.google.com/googleplay/android-developer/answer/12053285) · [Custom store listings](https://support.google.com/googleplay/android-developer/answer/9867158) · Competitor context: [RevPDF](https://revpdf.com/index.html), [PDF Plain and Simple](https://play.google.com/store/apps/details?id=com.noadspdf), [MyPDF on AlternativeTo](https://alternativeto.net/software/mypdf-offline-scanner-and-edit/about)
