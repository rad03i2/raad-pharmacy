# Raad Pharmacy transformation

This document records the v3.0.0 conversion to دفتر صيدلية رعد.

- Independent package/application ID: com.radwan.raadpharmacy
- Core names changed to PharmacyLedgerApp, PharmacyLedgerViewModel, PharmacyLedgerDatabase and PharmacyLedgerDao.
- New debt workflow is amount-first and supports optional purchases/medicines details.
- Gas/bottle UI was removed from the active pharmacy workflow.
- Legacy Room fields bottles and bottle_price remain in schema v1 solely for old-data compatibility.
- Backup writer uses raad-pharmacy-backup and parser accepts the previous abosmra-backup format.
- Material 3 visual identity changed to medical blue, turquoise and white with matching dark mode.
- App icon, splash identity, notifications, lock text, statement image and About identity use Raad Pharmacy branding.
- Cairo remains the default with Tajawal, Noto Kufi Arabic and Noto Sans Arabic available.
