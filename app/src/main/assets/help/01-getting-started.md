# Getting started
PocketForge is an offline-first code editor and project manager for your phone. It is **not** Google's Android Studio and does not replace it; it gives you an honest, safe subset that works on a phone.

## Create or import
- **New project** offers templates: *Android app — lightweight* (Java, buildable on the phone through Termux), *Android app (Compose)* (builds on a computer or Build Host), *Kotlin console*, *Web page*, *Python*, *Empty*.
- **Import** copies a ZIP or a folder into PocketForge's private storage. Archives are checked for path tricks (`../`), absolute paths, symbolic links, too many files and compression bombs before anything is written.
- Imported projects start as **untrusted**: the terminal asks before every command. Mark a project trusted from its *More…* menu on the home screen.

## Your data
Projects live in app-private storage. Nothing is uploaded unless you start a network feature and approve that specific request. Android cloud backup is disabled for this app — use **Export ZIP** for backups.

## Where things are
- **☰** shows the file explorer; long-press files for rename, new file, trash.
- **⋮** in the editor: search in project, snapshots, export, build, tests, terminal, AI.
- **Help → What works on this phone** lists every feature with its real status on your device.
