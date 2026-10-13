package dev.pocketforge.ide.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.consent.ConsentLogEntry
import dev.pocketforge.engine.projects.TrashEntry
import dev.pocketforge.ide.platform.SecretVault
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.Dialogs
import java.text.DateFormat
import java.util.Date

/** Privacy & permissions: network kill switch, decision history, stored secrets, folder grants, trash, delete everything. */
class PrivacyActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, column) = ui.page(ui.topBar("Privacy & permissions", null, { finish() }))
        col = column
        setScreen(page)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        bg({ Triple(c.consentLog.entries(), c.projects.trash(), c.aiSessionCount()) }) { r ->
            r.onSuccess { (log, trash, ai) -> render(log, trash, ai) }.onFailure { showError(it) }
        }
    }

    private fun render(log: List<ConsentLogEntry>, trash: List<TrashEntry>, aiSessions: Int) {
        col.removeAllViews()
        val s = c.settings.value
        col.addView(ui.card {
            addView(ui.text("What PocketForge does with your data", 16f, bold = true))
            addView(ui.caption(
                "• Projects, snapshots, drafts, logs and history live in app-private storage on this phone.\n" +
                    "• No analytics, ads, crash uploads, update checks or accounts. The app makes no network request on its own.\n" +
                    "• Network is used only for features you start (AI provider, your Build Host), and every single request shows a consent screen first.\n" +
                    "• Android cloud backup and device-to-device transfer are disabled for this app. Use Export for deliberate backups.\n" +
                    "• API keys and pairing tokens are encrypted with a non-exportable Android Keystore key.",
                p.text,
            ))
        })

        col.addView(ui.section("Network"))
        col.addView(ui.card {
            addView(ui.switchRow("Block all network", "Kill switch: refuses every request, even ones already approved. The IDE keeps working offline.", s.networkBlocked) { v ->
                c.settings.update { it.copy(networkBlocked = v) }
            })
            addView(ui.switchRow("Allow network commands in the terminal", "Off: commands like curl/wget/git clone are refused. On: they still need confirmation each time.", s.terminalAllowNetwork) { v ->
                c.settings.update { it.copy(terminalAllowNetwork = v) }
            })
            addView(ui.caption("Android itself may still do system-level networking (e.g. time sync) that is outside any app's control."))
        })

        col.addView(ui.section("Decision history (${log.size})"))
        col.addView(ui.card {
            addView(ui.caption("Every request you approved or denied, and every request that was blocked. Only time, destination, size and a SHA-256 of the body are kept — never the content."))
            if (log.isEmpty()) addView(ui.caption("No network requests yet.", p.text))
            log.take(30).forEach { e ->
                val color = if (e.decision == "APPROVE_ONCE") p.warn else p.ok
                addView(ui.text("${e.decision} · ${e.feature}", 14f, color, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
                addView(ui.caption("${DateFormat.getDateTimeInstance().format(Date(e.timeMillis))} · ${e.destination} · ${humanBytes(e.payloadBytes)}" +
                    (if (e.sendsSourceCode) " · source code" else "") + (if (e.redactions > 0) " · ${e.redactions} masked" else "") + " · sha256 ${e.payloadSha256.take(12)}…"))
            }
            if (log.isNotEmpty()) addView(ui.button("Clear decision history", Btn.TEXT) {
                Dialogs.confirm(this@PrivacyActivity, "Clear decision history?", "Removes the local record of approvals and denials.", "Clear", true) { c.consentLog.clear(); refresh() }
            })
            addView(ui.caption("AI sessions recorded (metadata only): $aiSessions"))
        })

        col.addView(ui.section("Stored secrets"))
        col.addView(ui.card {
            for ((key, label) in listOf(SecretVault.AI_KEY to "AI provider API key", SecretVault.HOST_TOKEN to "Build Host pairing token")) {
                addView(ui.horizontal {
                    addView(ui.body("$label: ${if (c.vault.has(key)) "stored (encrypted)" else "none"}"), ui.lp(0, weight = 1f))
                    if (c.vault.has(key)) addView(ui.button("Delete", Btn.TEXT) { c.vault.remove(key); refresh() })
                })
            }
        })

        col.addView(ui.section("Folder access you granted"))
        col.addView(ui.card {
            val grants = contentResolver.persistedUriPermissions
            if (grants.isEmpty()) addView(ui.caption("None. Imports copy files once and do not keep access."))
            grants.forEach { g ->
                addView(ui.horizontal {
                    addView(ui.caption(g.uri.toString(), p.text).apply { maxLines = 2 }, ui.lp(0, weight = 1f))
                    addView(ui.button("Revoke", Btn.TEXT) {
                        runCatching { contentResolver.releasePersistableUriPermission(g.uri, (if (g.isReadPermission) 1 else 0) or (if (g.isWritePermission) 2 else 0)) }
                        if (c.settings.value.termuxTreeUri == g.uri.toString()) c.settings.update { it.copy(termuxTreeUri = "") }
                        refresh()
                    })
                })
            }
        })

        col.addView(ui.section("Trash (${trash.size})"))
        col.addView(ui.card {
            if (trash.isEmpty()) addView(ui.caption("Empty."))
            trash.take(40).forEach { t ->
                addView(ui.horizontal {
                    addView(ui.vertical {
                        addView(ui.body(t.label).apply { maxLines = 2 })
                        addView(ui.caption(DateFormat.getDateTimeInstance().format(Date(t.deletedAt))))
                    }, ui.lp(0, weight = 1f))
                    addView(ui.button("Restore", Btn.TEXT) { bg({ c.projects.restore(t.id) }) { r -> r.onSuccess { toast("Restored") }.onFailure { showError(it) }; refresh() } })
                    addView(ui.button("Delete", Btn.TEXT) {
                        Dialogs.confirm(this@PrivacyActivity, "Delete permanently?", "${t.label} cannot be recovered afterwards.", "Delete", true) {
                            bg({ c.projects.purge(t.id) }) { refresh() }
                        }
                    })
                })
            }
        })

        col.addView(ui.section("Delete local app data"))
        col.addView(ui.card {
            addView(ui.caption("Permanently deletes ALL projects, snapshots, trash, drafts, build history and APKs, logs, decision history, settings, stored keys and the Keystore key, then closes the app. Export anything you want to keep first.", p.text))
            addView(ui.button("Delete everything…", Btn.DANGER) {
                Dialogs.typedConfirm(this@PrivacyActivity, "Delete all PocketForge data?", "This cannot be undone.", "DELETE", "Delete everything") {
                    bg({ c.deleteAllLocalData() }) {
                        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                    }
                }
            })
        })
        col.addView(ui.space(24))
    }
}
