# Release compliance checklist

Status as of 2026-11-18. ✅ = done in the app or repo. ☐ = something you need to do in the Play Console or outside the code.

## In the app (done)

| | Item | Where |
|---|---|---|
| ✅ | **Open-source notices.** The About screen lists every third-party component in the APK and shows its license verbatim, including the Apache PDFBox NOTICE (required by Apache 2.0 §4d), the Adobe Glyph List (BSD), Unicode data (Unicode License v3), and Liberation Sans and Noto fonts (OFL 1.1). | Home → ⓘ → Open-source licenses. Catalogue: `app/.../ui/about/OpenSourceLicenses.kt`; texts: `app/src/main/assets/licenses/`. A unit test fails if a referenced text is missing. |
| ✅ | **In-app privacy policy.** | Home → ⓘ → Read the privacy policy (`assets/legal/privacy-policy.txt`). |
| ✅ | **Disclaimers:** signatures aren't certificate-based digital signatures; whiteout removes content (check before sharing); saving flattens forms; originals are safe. | About → Good to know. |
| ✅ | **No network access, enforced.** The build fails if any dependency merges `INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE` into the manifest. | `app/build.gradle.kts`. Verified by temporarily adding INTERNET: the build failed as intended. |
| ✅ | **No permissions at all.** The merged release manifest has only AndroidX's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and the ProfileInstaller receiver (protected by `DUMP`). Neither gives any access to user data. | Checked on the merged release manifest. |
| ✅ | **No backups of user data.** `allowBackup=false` plus data-extraction rules exclude everything from cloud backup and device transfer. | `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` |
| ✅ | **No logging of document details in release.** R8 strips `Log.v/d/i/w`. Folio's own code doesn't log. | `app/proguard-rules.pro` |
| ✅ | **Removed Bouncy Castle.** It was only needed for certificate encryption and digital signatures, which Folio doesn't do, and R8 already stripped it from release builds. Debug and release now match, and there's less crypto to declare. Password-protected PDF tests still pass. | `core/editing/build.gradle.kts` |
| ✅ | **Only files the user picks are touched.** Storage Access Framework only, with no broad storage permissions. | — |

## In the Play Console (you)

| | Item | What to enter |
|---|---|---|
| ☐ | **Privacy policy URL** (required for every app) | Host `docs/PRIVACY_POLICY.md` at a public URL, for example GitHub Pages or a simple site, and paste the link. Keep it identical to the in-app copy. |
| ☐ | **Data safety form** | **Does your app collect or share any of the required user data types?** → **No.** Data that's processed only on the device and never leaves it doesn't count as "collected". Encryption in transit: not applicable. Account deletion: not applicable (no accounts). |
| ☐ | **Content rating questionnaire** | A utility with no user-generated content shared between users, no ads and no in-app purchases (the app itself is a one-time paid download). It should come out as Everyone / PEGI 3. |
| ☐ | **Paid app setup** | Fixed one-time price (see `PRODUCT_DECISIONS.md`). Link a Google Payments merchant profile to the developer account, set the price per country, and check tax settings. Refunds follow Google Play's refund policy, as the terms say. Never describe the app as free. |
| ☐ | **Target audience** | 18+, or 13+. Don't select children's age groups: the app isn't designed for them, and choosing them triggers the Families policy requirements. |
| ☐ | **Ads declaration** | No ads. |
| ☐ | **Store listing claims** | Keep claims to what's enforced: "No internet permission", "Files never leave your device", "No accounts, ads or tracking". Avoid "secure redaction certified", "legally binding signatures" and similar. The About disclaimers are the honest version. |
| ☐ | **Developer contact email** | Required on the store listing. The privacy policy points to it rather than including an address. |
| ☐ | **App signing** | Use Play App Signing and keep your upload key backed up somewhere other than this PC. |

## Worth doing before launch

- **Name check.** Several existing apps and products are called "Folio". Do a quick trademark search (USPTO / EUIPO) and a Play Store search before committing to the name and icon.
- **Export compliance (US EAR).** Folio only decrypts and encrypts PDFs with the standard password scheme, via Android's built-in crypto. That's normally covered by the mass-market exemption, and Play asks nothing about it. If you also distribute outside Play or to embargoed regions, check with someone qualified.
- **Keep this list current.** When adding a dependency, add it to `OpenSourceLicenses.kt` (with its license text in assets), and re-check the merged manifest. Run `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath` to see what ships.
