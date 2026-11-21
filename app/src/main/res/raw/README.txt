BUILT-IN SONGS

Place MP3 files in this folder (app/src/main/res/raw/).

Use lowercase filenames with underscores only, for example:
  radhimaa.mp3
  unnodu_ naan.mp3  (avoid spaces; prefer unnodu_ naan without the space)

Then register the song in BuiltInSongs.kt. The resource name is the filename
without the .mp3 extension. The app only lists a built-in song if its audio
resource exists.

Only bundle audio you own or have permission to distribute.
