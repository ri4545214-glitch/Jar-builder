# Jar Builder (Android)

Pick a Fabric mod source .zip -> the app uploads it to YOUR GitHub repo, GitHub builds it, the app downloads the .jar to Downloads.

## Get the app
1. Upload this whole folder to a new GitHub repo (keep `.github`).
2. Actions tab -> "Build app" runs -> Releases -> "Jar Builder app" -> download `app-debug.apk` and install.

## Use it
1. On GitHub: Settings -> Developer settings -> Personal access tokens -> Tokens (classic) -> Generate. Tick **repo** and **workflow**.
2. Open the app, paste the token, pick the mod .zip, tap Build .jar.
