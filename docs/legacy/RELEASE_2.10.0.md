# دفتر الغاز 2.10.0

- Six additional alert styles for success feedback and phone notifications: Tri-tone, Note, Glass, Chime, Complete and Rebound. These are original synthesized interpretations inspired by familiar iPhone alert styles, not Apple recordings. The three previous choices in each category and persisted selections remain available.
- Sound choices start collapsed under “صوت نجاح العملية” and “صوت إشعار الهاتف”. Press a heading to reveal its choices; opening one section closes the other. Each choice has an independent preview button that does not change the selected sound.
- Financial confirmations and debt reminders now use high-importance Android channels with the selected bundled sound. A separate channel per sound works with Android's immutable channel settings. Existing channel preferences and explicitly blocked legacy channels are respected. Phone audio is delivered by Android rather than played separately by the app.
- Every successful financial operation gets a separate notification and includes “عرض الحساب”. The existing success feedback, two-second confirmation delay, account navigation and amount privacy remain intact. Reminder batches alert once, avoiding a burst of interruptions.
- The expanded phone section includes a real test notification and a shortcut to the current Android notification channel. Heads-up display depends on device settings, channel permission and Do Not Disturb; the app does not use full-screen intents or bypass those settings.
- No database, financial posting, speech recognition or transaction-history changes.

## Validation

Run all unit, Room, speech and Compose tests, release lint and an optimized release build in the Android CI workflow. New coverage checks sound persistence, channel migration, user-muted channels, selected-resource delivery, notification privacy/actions, unique operation notification IDs, collapsed settings, preview independence and popup controls. Check the resulting APK contains all twelve playable WAV resources and verify its SHA-256 checksum.

Physical-device listening and heads-up appearance must be checked on the target phone. The repository still signs release builds with the runner's debug key. A build made on a fresh runner does not have the original installed APK's signing identity; the original keystore is required for an in-place update preserving existing app data.
