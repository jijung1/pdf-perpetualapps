# Google Play graphics

Made on 2026-11-18 from the v1.0.0 debug build running on the emulator (1080×2400). All demo documents are fictional and were made by `tools/make_demo.py` and `tools/make-photos.ps1`.

## Upload

| File | Play Console field | Spec |
|---|---|---|
| `feature-graphic.png` | Feature graphic | 1024×500, 24-bit PNG |
| `screenshots/01…07.png` | Phone screenshots, in this order | 1080×1920 (9:16), 24-bit PNG |

**Alt text for the feature graphic (140 characters max):**
PDF Editor by Perpetual Apps: edit, sign, merge and split PDFs on your phone, offline.

**Alt text for each screenshot (140 characters max):**

1. Home screen with Open a PDF, Merge & organize, Fill & sign, saved signatures and recent documents.
2. Whiteout box over an account number, with the characters to be deleted outlined and a note that 58 will be removed.
3. A volunteer form filled in with typed text, check marks, today's date and a handwritten signature.
4. Merge screen combining a 3-page PDF, an invoice, a receipt photo and a whiteboard photo, colour-coded by file.
5. One page selected in the organizer, with rotate, duplicate, extract and delete actions.
6. Split sheet set to page ranges 1-3, 4, 5-6, previewing the three PDFs it will create.
7. About screen: no internet access, no accounts, ads or tracking, and data kept on the device.

## Notes

- **Headline area:** the headline and subline take the top 17% of each image. Play asks for taglines to cover no more than 20%.
- **No promotional words:** the taglines avoid price, "free", "no ads" and calls to action.
- **Clean status bar:** it's cropped out of every capture, because the emulator's demo mode couldn't hide its VPN and 3G icons.
- **App info screenshot not used:** `raw/appinfo.png` (Android's "No permissions requested" screen) is not in the set, because Play wants screenshots of the app itself. The website already uses it as evidence.
- **To rebuild:** after recapturing into `raw/`, run `powershell -ExecutionPolicy Bypass -File design\play\tools\compose.ps1`. Edit the headlines in that script.
