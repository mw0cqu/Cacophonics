# Making a GitHub release of Cacophonics

One-time setup
1. Create a free GitHub account, then a new **public** repository (for example `cacophonics`).
   Tick "Add a README" OFF, since this project already has one.
2. Upload the project: GitHub Desktop (or Android Studio: VCS > Share Project on GitHub) is the
   easiest. `.gitignore` already keeps out `local.properties`, build folders and keys.
3. In Android Studio: **Build > Generate Signed App Bundle or APK > APK > Create new...** and make
   a release keystore. **Back up the .jks file and its passwords** somewhere safe, outside the
   project folder. Never upload it. If it is lost, nobody can update the app without uninstalling.

Each release
1. In `app/build.gradle.kts` raise `versionCode` by 1 and set `versionName` (for example "1.0.1").
2. Build > Generate Signed App Bundle or APK > **APK** > pick your keystore > **release**.
   The file ends up in `app/release/app-release.apk`.
3. On GitHub: Releases > **Draft a new release** > new tag (for example `v1.0`) > title and notes
   (see RELEASE_NOTES.md) > attach the APK > Publish.
4. Put the repository link into the About screen and README when it exists.

Installing (for your README / release notes)
Download the APK on the phone, allow "Install unknown apps" for the browser or file manager when
asked, then open the file. Android may warn because the app is not from the Play Store.
