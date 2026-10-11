# fishka typing 1.9.4 — verification

## Backspace crash

The unmodified 1.9.3 model was reproduced with three learned words (`hello`,
`help`, `helicopter`) and the query `he`. It threw ConcurrentModificationException
in PredictionModel.suggest: score() read an access-ordered LinkedHashMap while
that same map was being iterated. The corrected model uses insertion ordering
for words/bigrams, including restored personal data. The same reproduction now
returns suggestions successfully. Repeated prefix deletion and restored-data
regression tests pass. This is a confirmed crash mechanism, not only a hypothesis.

## Checks

- Unit checks cover RU/EN spelling/prefixes/transpositions, context after repeated
  bigrams, long typo suggestions, preservation of valid words/prefixes, personal
  words in languages without a bundled corpus, and serialized learning.
- Full bundled Russian and English corpora are loaded in the model tests.
- Language-pack tests reject malformed JSON/UTF-8, oversize packs, duplicate codes,
  control/bidi characters and invalid rows; invalid updates preserve the last cache.
- The published HTTPS catalog responds successfully and matches the shipped data.
  Android refresh/cache behavior is included in the native UI test.
- APK compilation and signature verification pass; the signer/package are retained
  for an update over 1.9.3. Original MP3 sample bytes are unchanged.
- Android instrumentation checks real native/WebView typing, live correction/undo,
  learned-prefix deletion, repeated Backspace, holding deletion, overlapping
  letter/delete touches, protected-field transitions, settings and Internet refresh.
  The final combined run passed (`INSTRUMENTATION_CODE: -1`), including HTTPS
  refresh, rapid/held/multi-touch Backspace and browser input, with no
  FishkaIME/AndroidRuntime errors in the captured final log. Result is retained
  in `.hoplite/android-tools/fishka-ui.log` and screenshots
  in `android-keyboard/build/evidence/fishka-1.9.4/`.

## Limits

The phone's original crash log was not supplied, so this cannot prove that every
possible phone-specific issue is eliminated. Correction is deliberately
conservative rather than guaranteed perfect. Downloading language layouts does
not provide correction dictionaries for every language: full dictionaries remain
Russian/English, while other supported languages use personal word suggestions.
There is no external online spelling service and no transmission of typed text.
Emulator audio output is disabled; audible playback on the phone is not verified.
The browser fixture is synthetic WebView, not the live monkeytype.com website.
