package dev.pocketforge.ide.screens

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketforge.engine.ai.AiAction
import dev.pocketforge.engine.ai.AiReview
import dev.pocketforge.engine.ai.AiTask
import dev.pocketforge.engine.ai.ContextBuilder
import dev.pocketforge.engine.ai.ContextFile
import dev.pocketforge.engine.ai.ErrorExplainer
import dev.pocketforge.engine.ai.FileProposal
import dev.pocketforge.engine.ai.ResponseParser
import dev.pocketforge.engine.ai.consentFor
import dev.pocketforge.engine.common.Hashing
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.common.humanBytes
import dev.pocketforge.engine.projects.Workspace
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.engine.storage.AtomicFileWriter
import dev.pocketforge.engine.storage.TextCodec
import dev.pocketforge.engine.toolchain.CommandRisk
import dev.pocketforge.ide.platform.AiProviderKind
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Btn
import dev.pocketforge.ide.ui.ConsentDialog
import dev.pocketforge.ide.ui.DiffView
import dev.pocketforge.ide.ui.Dialogs
import dev.pocketforge.ide.ui.Markdown
import dev.pocketforge.ide.ui.value

/**
 * Optional AI assistant. The IDE works fully without it.
 *  - You choose the context (files / line ranges); likely secrets are masked; prompt-injection text is flagged.
 *  - Every request goes through the consent screen showing provider, files and the exact body.
 *  - Answers are untrusted: file changes become diffs you accept per change (a snapshot is taken first);
 *    commands are only shown, never run.
 */
class AiActivity : BaseActivity() {
    private var pid: String? = null
    private var action = AiAction.EXPLAIN
    private val shared = ArrayList<ContextFile>()
    private lateinit var prompt: EditText
    private lateinit var ctxList: LinearLayout
    private lateinit var result: LinearLayout
    private lateinit var sendBtn: TextView
    private lateinit var providerLabel: TextView
    private var waiting = false
    private var requestSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra(Nav.PROJECT)
        intent.getStringExtra(Nav.ACTION)?.let { a -> runCatching { action = AiAction.valueOf(a) } }
        val (page, col) = ui.page(ui.topBar("AI assistant", "Optional · asks before every request", { finish() }, listOf(
            ui.iconButton("⚙", "AI settings") { Nav.settings(this) },
        )))
        providerLabel = ui.caption("")
        col.addView(ui.card { addView(providerLabel) })

        col.addView(ui.labeled("What do you want?", ui.spinner(AiAction.values().map { it.title }, AiAction.values().indexOf(action)) {
            action = AiAction.values()[it]; prefill()
        }))
        prompt = ui.input("Your request", singleLine = false).apply { minLines = 3; gravity = android.view.Gravity.TOP }
        col.addView(ui.labeled("Request (editable — sent exactly as written)", prompt))

        col.addView(ui.section("Context you choose to share"))
        ctxList = ui.vertical()
        col.addView(ctxList)
        if (pid != null) col.addView(ui.horizontal {
            addView(ui.button("Add files…", Btn.TEXT) { pickFiles() })
            addView(ui.button("Clear", Btn.TEXT) { shared.clear(); renderContext() })
        })

        col.addView(ui.space(10))
        sendBtn = ui.button("Review & send…", Btn.PRIMARY) { send() }
        col.addView(ui.horizontal {
            addView(ui.button("Preview exact request", Btn.SECONDARY) { preview() }, ui.lp(0, weight = 1f))
            addView(ui.space(0), ui.lp(ui.dp(8), 1))
            addView(sendBtn, ui.lp(0, weight = 1f))
        })
        result = ui.vertical()
        col.addView(result)
        col.addView(ui.space(24))
        setScreen(page)

        loadInitialContext()
        prefill()
    }

    override fun onResume() {
        super.onResume()
        val prov = c.aiProvider()
        val s = c.settings.value
        providerLabel.text = when {
            s.aiProvider == AiProviderKind.NONE -> "AI is off. Turn it on in Settings → AI assistant (Anthropic, any OpenAI-compatible service, or a model server you run on this phone at 127.0.0.1). The offline error explainer works without AI."
            prov == null -> "AI provider is selected but not fully set up (missing API key or base URL). Open Settings → AI assistant."
            prov.isLocal -> "Provider: ${prov.displayName}\nRuns on THIS phone (127.0.0.1) — data does not leave the device, but you still approve each request."
            else -> "Provider: ${prov.displayName}\nRemote service: the code you select leaves this phone after you approve each request. No request is ever sent automatically."
        }
        if (s.networkBlocked) providerLabel.append("\n\nNetwork is blocked in Settings, so requests will be refused.")
    }

    private fun prefill() {
        if (prompt.value().isNotBlank() && prompt.tag != "auto") return
        prompt.setText(when (action) {
            AiAction.EXPLAIN -> "Explain what this code does."
            AiAction.FIX -> "Find bugs in this code and suggest fixes."
            AiAction.TESTS -> "Write unit tests for this code."
            AiAction.DOCUMENT -> "Add documentation comments to the public functions and classes."
            AiAction.REFACTOR -> "Refactor this code for readability without changing behaviour."
            AiAction.EXPLAIN_ERROR -> "Explain this build error and propose the smallest fix."
            AiAction.PLAN -> "Create a step-by-step plan with acceptance criteria for: "
            AiAction.CUSTOM -> ""
        })
        prompt.tag = "auto"
        prompt.setOnFocusChangeListener { _, has -> if (has) prompt.tag = null }
    }

    private fun loadInitialContext() {
        val project = pid
        c.aiHandoff?.let { log ->
            c.aiHandoff = null
            shared += ContextFile("build.log", log)
            val hints = ErrorExplainer.explain(log)
            if (hints.isNotEmpty()) result.addView(ui.card {
                addView(ui.text("Offline hints (rule-based, not AI — nothing was sent)", 14f, p.info, bold = true))
                hints.forEach { addView(ui.caption("• ${it.title}: ${it.detail}\n  Fix: ${it.fix}", p.text)) }
            })
        }
        val path = intent.getStringExtra(Nav.PATH)
        if (project != null && path != null) {
            val from = intent.getIntExtra(Nav.SEL_FROM, 0); val to = intent.getIntExtra(Nav.SEL_TO, 0)
            val ctl = c.editor(project)
            val open = ctl.tabs.firstOrNull { it.path == path }
            val text = open?.let { ctl.textOf(it) }
            if (text != null) { shared += ContextFile(path, text, if (from in 1..to) from..to else null); renderContext() }
            else bg({ TextCodec.decode(c.workspace(project).guard.resolve(path).readBytes()).text }) { r ->
                r.onSuccess { shared += ContextFile(path, it, if (from in 1..to) from..to else null); renderContext() }
            }
        }
        renderContext()
    }

    private fun renderContext() {
        ctxList.removeAllViews()
        if (shared.isEmpty()) ctxList.addView(ui.caption("Nothing selected. The request will contain only your text."))
        shared.toList().forEach { f ->
            ctxList.addView(ui.horizontal {
                addView(ui.vertical {
                    addView(ui.text(f.path + (f.range?.let { " · lines ${it.first}–${it.last}" } ?: ""), 14f, maxLines = 1))
                    addView(ui.caption(humanBytes(f.content.length.toLong()) + " before masking"))
                }, ui.lp(0, weight = 1f))
                addView(ui.iconButton("✕", "Remove ${f.path}") { shared.remove(f); renderContext() })
            })
        }
    }

    private fun pickFiles() {
        val project = pid ?: return
        bg({
            val g = PathGuard(c.projects.dirOf(project))
            g.walkFiles().map { g.relativize(it) to it.length() }.filter { (p, size) -> !p.startsWith("build/") && size < 512 * 1024 }.take(400).toList()
        }) { r ->
            r.onSuccess { files ->
                val labels = files.map { "${it.first}  (${humanBytes(it.second)})" }
                val checked = BooleanArray(files.size) { i -> shared.any { it.path == files[i].first } }
                Dialogs.multiChoose(this, "Share these files with the AI", labels, checked) { sel ->
                    val wanted = files.filterIndexed { i, _ -> sel[i] }.map { it.first }
                    shared.removeAll { it.path != "build.log" && it.path !in wanted }
                    val missing = wanted.filter { w -> shared.none { it.path == w } }
                    bg({ missing.map { p -> ContextFile(p, TextCodec.decode(c.workspace(project).guard.resolve(p).readBytes()).text) } }) { rr ->
                        rr.onSuccess { shared.addAll(it); renderContext() }.onFailure { showError(it) }
                    }
                }
            }.onFailure { showError(it) }
        }
    }

    private fun task(): AiTask = AiTask(action, prompt.value(), ContextBuilder.build(shared.toList(), c.settings.value.aiMaxContextKb * 1024))

    private fun preview() {
        val provider = c.aiProvider() ?: run { Nav.settings(this); return }
        bg({ consentFor(provider, task()) }) { r ->
            r.onSuccess { req ->
                val headers = req.request.headers.keys.joinToString("\n") { "$it: (value hidden)" }
                val warn = if (req.redactionsApplied > 0) "${req.redactionsApplied} likely secret(s) were masked.\n" else ""
                Dialogs.text(this, "Exact request (nothing sent)", "POST ${req.request.url}\n$headers\n\n$warn${ConsentDialog.payloadText(req)}")
            }.onFailure { showError(it) }
        }
    }

    private fun send() {
        if (waiting) { waiting = false; requestSeq++; sendBtn.text = "Review & send…"; toast("Stopped waiting. A late answer will be ignored."); return }
        val provider = c.aiProvider() ?: run { toastLong("Set up an AI provider in Settings first."); Nav.settings(this); return }
        val project = pid
        val t = task()
        val seq = ++requestSeq
        waiting = true
        sendBtn.text = "Stop waiting"
        bg({
            val consent = consentFor(provider, t)
            val ticket = c.consentGateway.request(consent) // shows the consent screen; throws if not approved
            val resp = c.gatedTransport.send(ticket, consent.request)
            val answer = provider.parseResponse(resp)
            val review = if (project != null) {
                val guard = PathGuard(c.projects.dirOf(project))
                ResponseParser.parse(answer, guard) { path -> runCatching { guard.resolve(path).takeIf { it.isFile }?.let { TextCodec.decode(it.readBytes()).text } }.getOrNull() }
            } else AiReview(answer, emptyList(), ResponseParser.parse(answer, PathGuard(cacheDir)) { null }.commands, emptyList())
            c.logAiSession(action.name, provider.displayName, provider.isLocal, Hashing.sha256(consent.request.body), consent.request.body.size, answer.length, review.proposals.size)
            review
        }) { r ->
            if (seq != requestSeq) return@bg
            waiting = false
            sendBtn.text = "Review & send…"
            r.onSuccess(::showReview).onFailure { e -> if (e is PfException && e.code == "consent.denied") toast(e.message ?: "Not sent") else showError(e, "AI request failed") }
        }
    }

    private fun showReview(rv: AiReview) {
        result.removeAllViews()
        result.addView(ui.section("Answer (AI output can be wrong — review before using)"))
        result.addView(ui.card { addView(ui.text(Markdown.render(rv.explanation, p), 15f).apply { setTextIsSelectable(true) }) })
        if (rv.rejected.isNotEmpty()) result.addView(ui.card {
            addView(ui.text("Ignored unsafe file suggestions", 14f, p.danger, bold = true))
            rv.rejected.forEach { addView(ui.caption("• $it", p.text)) }
        })
        if (rv.proposals.isNotEmpty()) {
            result.addView(ui.section("Proposed file changes (${rv.proposals.size}) — nothing applied yet"))
            rv.proposals.forEach { fp -> result.addView(proposalCard(fp)) }
        }
        if (rv.commands.isNotEmpty()) {
            result.addView(ui.section("Suggested commands — never run automatically"))
            rv.commands.forEach { sc ->
                result.addView(ui.card(10) {
                    addView(ui.text(sc.command, 13f, monoFont = true).apply { setTextIsSelectable(true) })
                    addView(ui.caption(sc.risks.joinToString { it.label }, if (sc.risks.any { it != CommandRisk.READ_ONLY }) p.warn else p.muted))
                    addView(ui.horizontal {
                        addView(ui.button("Copy", Btn.TEXT) { Dialogs.copy(this@AiActivity, "command", sc.command) })
                        pid?.let { project -> addView(ui.button("Paste into terminal", Btn.TEXT) { Nav.terminal(this@AiActivity, project, sc.command) }) }
                    })
                })
            }
        }
    }

    private fun proposalCard(fp: FileProposal): View = ui.card {
        addView(ui.text(fp.path, 15f, bold = true))
        val m = DiffView.model(fp.oldText ?: "", fp.newText)
        addView(ui.caption(if (fp.oldText == null) "New file" else DiffView.summary(m)))
        addView(ui.button("Review diff & apply…", Btn.SECONDARY) { reviewProposal(fp) })
    }

    private fun reviewProposal(fp: FileProposal) {
        val project = pid ?: return
        val m = DiffView.model(fp.oldText ?: "", fp.newText)
        val view = ui.scroll(ui.vertical(12) {
            addView(ui.caption("Untick changes you do not want. A snapshot of the project is taken before anything is written, so you can roll back."))
            addView(DiffView.build(ui, m, selectable = true))
        })
        val d = Dialogs.custom(this, fp.path, view, "Apply selected")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (m.selected.none { it }) { toast("Nothing selected"); return@setOnClickListener }
            d.dismiss()
            apply(project, fp, m)
        }
    }

    private fun apply(project: String, fp: FileProposal, m: DiffView.Model) {
        val ctl = c.editor(project)
        ctl.tabs.firstOrNull { it.path == fp.path }?.let { t ->
            if (ctl.refreshDirty(t)) { Dialogs.info(this, "Unsaved edits in ${t.name}", "Save or discard your edits in the editor first, so the AI change does not overwrite them."); return }
        }
        bg({
            val root = c.projects.dirOf(project)
            c.snapshots(project).create(root, "Before AI change to ${fp.path}")
            val ws = Workspace(root, c.largeFilePolicy())
            val f = ws.guard.resolve(fp.path)
            val oldText = fp.oldText
            if (oldText == null) {
                if (f.exists()) throw PfException("ai.exists", "${fp.path} was created meanwhile; review again.")
                AtomicFileWriter.write(f, fp.newText.toByteArray(Charsets.UTF_8))
            } else {
                val doc = ws.open(fp.path)
                if (doc.decoded.text != oldText) throw PfException("ai.stale", "${fp.path} changed since the suggestion was made. Ask again.")
                when (val res = ws.save(doc, DiffView.apply(oldText, m))) {
                    is Workspace.SaveResult.Conflict -> throw PfException("ai.conflict", "${fp.path} changed while applying; nothing was written.")
                    is Workspace.SaveResult.Saved -> {}
                }
            }
        }) { r ->
            r.onSuccess {
                ctl.reloadIfClean(listOf(fp.path))
                toastLong("Applied to ${fp.path}. A snapshot was taken first.")
            }.onFailure { showError(it, "Not applied") }
        }
    }
}
