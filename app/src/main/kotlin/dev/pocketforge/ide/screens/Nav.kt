package dev.pocketforge.ide.screens

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Typed navigation between screens (explicit, non-exported activities only). */
object Nav {
    const val PROJECT = "pf.project"
    const val PATH = "pf.path"
    const val LINE = "pf.line"
    const val COMMAND = "pf.command"
    const val SEL_FROM = "pf.sel_from"
    const val SEL_TO = "pf.sel_to"
    const val ACTION = "pf.action"

    private inline fun <reified T : Activity> go(ctx: Context, vararg extras: Pair<String, Any?>) {
        val i = Intent(ctx, T::class.java)
        for ((k, v) in extras) when (v) {
            is String -> i.putExtra(k, v)
            is Int -> i.putExtra(k, v)
            null -> {}
            else -> i.putExtra(k, v.toString())
        }
        ctx.startActivity(i)
    }

    fun home(ctx: Context) = ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    fun newProject(ctx: Context) = go<NewProjectActivity>(ctx)
    /** Reuses an existing editor screen (CLEAR_TOP|SINGLE_TOP) so one project never has two live editors. */
    fun project(ctx: Context, pid: String, path: String? = null, line: Int = 0) {
        val i = Intent(ctx, ProjectActivity::class.java).putExtra(PROJECT, pid).putExtra(LINE, line)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (path != null) i.putExtra(PATH, path)
        ctx.startActivity(i)
    }
    fun search(ctx: Context, pid: String) = go<SearchActivity>(ctx, PROJECT to pid)
    fun snapshots(ctx: Context, pid: String) = go<SnapshotsActivity>(ctx, PROJECT to pid)
    fun export(ctx: Context, pid: String) = go<ExportActivity>(ctx, PROJECT to pid)
    fun build(ctx: Context, pid: String?) = go<BuildActivity>(ctx, PROJECT to pid)
    fun terminal(ctx: Context, pid: String, command: String? = null) = go<TerminalActivity>(ctx, PROJECT to pid, COMMAND to command)
    fun tests(ctx: Context, pid: String) = go<TestActivity>(ctx, PROJECT to pid)
    fun ai(ctx: Context, pid: String?, path: String? = null, from: Int = 0, to: Int = 0, action: String? = null) =
        go<AiActivity>(ctx, PROJECT to pid, PATH to path, SEL_FROM to from, SEL_TO to to, ACTION to action)
    fun privacy(ctx: Context) = go<PrivacyActivity>(ctx)
    fun settings(ctx: Context) = go<SettingsActivity>(ctx)
    fun help(ctx: Context, page: String? = null) = go<HelpActivity>(ctx, PATH to page)
    fun preview(ctx: Context, pid: String, path: String) = go<PreviewActivity>(ctx, PROJECT to pid, PATH to path)
}
