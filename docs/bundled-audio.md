# Adding bundled audio

To bundle an MP3 that you own or have permission to distribute:

1. Copy the original MP3 into `app/src/main/res/raw/`.
2. Rename the file to a lowercase resource name using only `a-z`, `0-9`, and underscores (for example `radhimaa.mp3`).
3. Add a matching entry to `BuiltInSongCatalog` in `BuiltInSongs.kt`.
4. Keep the original audio bytes unchanged. Bundled files are included in every APK, so only use audio you are allowed to redistribute.
