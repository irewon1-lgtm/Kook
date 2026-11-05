# KR4 4.3.210 final evidence attachment

Administrative delivery branch only. The released Android source stays at main commit `8b1b45671072636dff59f1ced48aa311204fe2ff` and the original 11 release assets must remain unchanged.

The registered Android workflow path is used only on this branch to attach these independently verified files after cloud upload-host authentication returned HTTP 401. This branch does not rebuild, replace, or sign the APK. It has no push trigger and is dispatched explicitly.

Every attachment is checked against attachment-manifest.json before upload. The original release asset identities are checked before and after upload. The public APK and update manifest must still bind the original main commit and version 20210.
