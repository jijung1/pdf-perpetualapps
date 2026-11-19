# Website: PDF Editor: Perpetual Apps

Live at https://pdf.perpetualapps.dev/. A static site with no build step, no executable JavaScript, no web fonts, no cookies and no third-party requests. Upload the folder as-is to the static host. The host serves clean URLs: `/privacy.html` redirects to `/privacy`, so links, canonical tags and the sitemap use the clean form.

| File | Purpose |
|---|---|
| `index.html` | Landing page. Includes `SoftwareApplication` JSON-LD (a data block, not a script that runs) with the US$6.99 offer. |
| `privacy.html` | Privacy policy. Its URL goes in the Play Console (required). **Generated:** run `website\build-privacy.ps1` after changing `app/src/main/assets/legal/privacy-policy.txt`. |
| `terms.html` | Terms of use (draft) |
| `sitemap.xml`, `robots.txt` | For search engines. Cloudflare may prepend its managed content-signals text to `robots.txt`. |
| `assets/` | Stylesheet, icon, Play badge, app screenshots, `share.png` (1200×630 link-preview image) |

## Keep in sync

- **Public name:** "PDF Editor: Perpetual Apps", the same as the Play title. On the phone the launcher label is "PDF Editor: pA", so instructions that refer to Android Settings use that.
- **Price:** US$6.99, paid once. It appears in the hero, the promise bar, the "Pay once" section, the FAQ, the meta and og descriptions, the JSON-LD offer and `share.png`. If the price changes, update all of them.
- **Never** call the app "free", and never promise updates, support, "lifetime" or "forever".
- **Sitemap:** update `lastmod` in `sitemap.xml` when a page changes meaningfully.

## Before you publish

- [ ] Have **`terms.html`** reviewed by a lawyer, and consider adding a governing-law clause.
- [ ] The Google Play links (`id=perpetualapps.pdf`) only work once the app is published.
- [ ] After deploying, submit `https://pdf.perpetualapps.dev/sitemap.xml` in Google Search Console.

## Google Play requirements this covers

- **Privacy policy:** a public, non-PDF web page that is always reachable, names the app and developer as on the listing, and matches the in-app policy and the Data safety answers ("no data collected").
- **Account deletion URL:** not required, because the app has no accounts.
- **Google Play badge:** the official, unmodified badge, linked to the app's listing, with the required trademark attribution in the footer.
- **Truthful marketing:** every claim matches the app and the store listing:
  - US$6.99, paid once
  - no subscription, in-app purchases or ads
  - no internet permission
- **Developer contact:** the support email, matching the Play Console.
