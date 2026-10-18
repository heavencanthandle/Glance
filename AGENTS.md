Read [README.md](README.md) first.

Do not:

- Uninstall this fork, or create a keystore, when an update will not replace the installed app. Uninstall-once is only for upstream Glance. Raise `versionCode` for a same-certificate replacement. An unsigned release is not that replacement.
- Replace or remove the keystore in `signing/`. The phone's install is signed by it, and it signs debug builds too. Without it, no build replaces the installed app.
- Renumber `versionName` to continue the `0.1.x` subjects in git history. Those subjects and `versionName` are not one sequence.
- Add an app to the accessibility service `packageNames` so its window can be read. Windows are already retrieved. That attribute is which apps' events are delivered.
- Exempt a music player from the check that hides the panel for an application window, or treat an accessibility overlay as that window. Overlays are measured and sat under. That check is the call screen.
- Place the moon with `placeGlyph`. That helper is not where the lock screen draws it. The moon is only the utilities icon.
- Clear the lamp, moon, or hotspot switches when the utilities row is turned off, or stop the hotspot then. Only the lamp is put out.
- Add a fixed inset at the foot for a music strip. The overlay is already measured. A second inset leaves no room and hides the panel.
