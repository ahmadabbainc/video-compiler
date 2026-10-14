# Build SRT Studio with GitHub

GitHub builds the APK for you in the cloud. You don't need Android Studio.

## A. Put the code on GitHub

**Option 1: in the browser (easiest, use a computer or "Desktop site" on your phone)**

1. Sign in at github.com, click **+ → New repository**. Name it `srt-studio`, keep it **Private** if you like, click **Create repository**.
2. Unzip `srt-studio.zip` on your computer.
3. On the new repo page click **uploading an existing file**. Drag the *contents* of the unzipped folder in (including the hidden `.github` folder and `gradle` folder). Click **Commit changes**.
   - If the browser skips `.github`, create the file manually: **Add file → Create new file**, type `.github/workflows/build.yml`, and paste the contents of that file.

**Option 2: with git**

```bash
cd srt-studio
git add -A
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<your-username>/srt-studio.git
git push -u origin main
```

## B. Get the APK

1. Open the repo's **Actions** tab. The **Build APK** workflow starts by itself (first run ~5-8 minutes).
2. When the run shows a green tick, open it and scroll to **Artifacts**. Download **SRT-Studio-apk** (a zip containing the `.apk`).
3. If the run shows a red cross, open it, click the failing step and copy the error text. Send it to me and I'll fix it.

## C. Install on your phone

1. Copy the `.apk` to the phone, tap it, and allow **Install unknown apps** for your file manager/browser when asked.
2. Open **SRT Studio**, pick a `.srt`, optionally images and audio, and tap **Generate video**. Results land in **Movies / SRT Studio**.

## D. Make proper releases (optional)

Tag a version and GitHub publishes the APK on the repo's **Releases** page:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## E. Sign with your own key (optional, recommended before sharing the app)

Without this, the APK is signed with a throwaway debug key. That is fine for you, but updates can only be installed over the same key, so set this up once and keep the key safe.

1. Create a key (needs Java): 
   `keytool -genkeypair -v -keystore release.jks -alias mykey -keyalg RSA -keysize 2048 -validity 10000`
2. Convert it to text: `base64 -w0 release.jks` (macOS: `base64 -i release.jks`).
3. In the repo go to **Settings → Secrets and variables → Actions → New repository secret** and add:
   - `KEYSTORE_BASE64`: the base64 text from step 2
   - `KEYSTORE_PASSWORD`: the keystore password
   - `KEY_ALIAS`: `mykey`
   - `KEY_PASSWORD`: the key password
4. Re-run the workflow. Never commit `release.jks` to the repo (it is in `.gitignore`).

## F. Make it yours

| What | Where |
| --- | --- |
| App name | `app/src/main/res/values/strings.xml` |
| Package / app id | `namespace` and `applicationId` in `app/build.gradle.kts` (also rename the `com/srtstudio/app` folders and the `package` lines if you change the namespace) |
| Colours | `app/src/main/java/com/srtstudio/app/ui/Theme.kt` |
| Icon | `app/src/main/res/drawable/ic_launcher_foreground.xml` |
| Version | `versionCode` / `versionName` in `app/build.gradle.kts` (bump both for each release) |

## G. Troubleshooting

- **Video is black or the app says the encoder failed**: try 720p / 30 fps first; some budget phones can't encode 1080p60.
- **Audio file rejected**: the phone has to be able to play it; files with more than 2 channels are not supported yet.
- **Caption font looks wrong for Arabic**: use **Custom font…** and pick a `.ttf` that supports Arabic.
- **No progress notification**: allow notifications for the app. Rendering still works without them.

## Status

The parser, encoding detection and natural sorting have been run and pass. The Android parts (renderer, encoder, UI) were written carefully but **could not be compiled or run in the authoring environment**, so expect the first GitHub build or first test on a phone to turn up small fixes. Send me whatever the Actions log or the phone shows.
