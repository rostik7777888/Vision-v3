# fishka typing 1.9.3 — verification

Confirmed checks:

- APK build succeeds; `apksigner verify` passes. Package ID and signing certificate
  match 1.9.2, so this build can update it without an uninstall.
- Original Canvas drawing/measurement and the B toolbar rendering are checked
  during packaging. The unsafe legacy IME service is replaced with a Java service.
- Unit checks pass for clipboard history, adaptive predictions, conservative live
  correction, and all 15 native layouts, including the 42-letter Kazakh alphabet.
- Seven audio checks pass against the supplied MP3. The original extracted click
  remains in the APK; runtime gain is fixed at 50%, with system mute/DND respected.
- Settings show fishka typing, only View/Input tabs, no feedback controls/footer,
  five themes and Telegram. Native instrumentation checks theme persistence,
  press animation and Kazakh selection; fresh screenshots are inspected.
- The full native instrumentation run passes on Android 10 AOSP: rapid EditText
  typing without IME dismissal, correction before space, Backspace undo, switching
  into a password field with no stale edit, and continued WebView typing. No
  AndroidRuntime/FishkaIME errors were observed in the checked log. Output is retained
  in `.hoplite/android-tools/fishka-ui.log` and captures in `build/evidence/`.

Limits:

- The original phone crash stack trace was not supplied. The old unguarded
  connection callbacks were identified as risks, not a proven phone-specific cause.
- The Android fixture uses synthetic EditText and WebView fields, not the live
  monkeytype.com website. No claim of cross-device or benchmark-wide reliability.
- Software emulator audio output is disabled; audible playback on the user's phone
  is not verified. The supplied sample's bytes and playback configuration are checked.
- This release supports 15 real layouts, not every world language or a CJK conversion
  IME. Russian/English have bundled correction dictionaries; other layouts use
  personal learned words for suggestions.
