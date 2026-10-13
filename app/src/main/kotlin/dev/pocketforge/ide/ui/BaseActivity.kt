package dev.pocketforge.ide.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import dev.pocketforge.engine.common.PfException
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentRequest
import dev.pocketforge.ide.AppContainer
import dev.pocketforge.ide.PocketForgeApp
import dev.pocketforge.ide.R
import dev.pocketforge.ide.platform.Compat
import dev.pocketforge.ide.platform.ConsentBroker

/**
 * Shared behaviour for every screen: palette + theme, edge-to-edge insets, optional FLAG_SECURE,
 * hosting the consent dialog while visible, and lifecycle-safe background work.
 */
abstract class BaseActivity : Activity(), ConsentBroker.Host {
    lateinit var c: AppContainer
    lateinit var p: Palette
    lateinit var ui: Ui
    private var consentDialog: Dialog? = null
    private var appliedThemeKey = ""

    private fun themeKey() = c.settings.value.let { "${it.theme}/${it.secureScreen}/${Themes.resolve(this, it.theme).dark}" }

    override fun onCreate(savedInstanceState: Bundle?) {
        c = PocketForgeApp.of(this)
        p = Themes.resolve(this, c.settings.value.theme)
        setTheme(if (p.dark) R.style.Theme_PocketForge_Dark else R.style.Theme_PocketForge_Light)
        super.onCreate(savedInstanceState)
        ui = Ui(this, p)
        Compat.edgeToEdge(window, !p.dark)
        window.decorView.setBackgroundColor(p.bg)
        if (c.settings.value.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        appliedThemeKey = themeKey()
    }

    /** Sets the screen content inside a container that pads for status/navigation bars, cutouts and the keyboard. */
    fun setScreen(content: View) {
        val root = FrameLayout(this)
        root.setBackgroundColor(p.bg)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = Compat.safeInsets(insets)
            v.setPadding(i[0], i[1], i[2], i[3])
            Compat.consumed(insets)
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        if (themeKey() != appliedThemeKey) { recreate(); return }
        c.consentBroker.attach(this)
    }

    override fun onPause() {
        c.consentBroker.detach(this)
        super.onPause()
    }

    override fun showConsent(request: ConsentRequest, answer: (ConsentDecision) -> Unit) {
        if (isFinishing || isDestroyed) { answer(ConsentDecision.DENY); return }
        consentDialog?.dismiss()
        consentDialog = ConsentDialog.show(this, request) { d -> consentDialog = null; answer(d) }
    }

    override fun dismissConsent() {
        consentDialog?.let { if (it.isShowing) it.dismiss() }
        consentDialog = null
    }

    /** Runs [work] on the IO pool; delivers the result on the main thread only if this screen still exists. */
    fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        c.io.execute {
            val r = try { Result.success(work()) } catch (t: Throwable) { Result.failure(t) }
            r.exceptionOrNull()?.let { if (it !is PfException) c.log.error(javaClass.simpleName, "background failure", it) }
            runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    fun toastLong(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Actionable error: PfException messages are written for users; anything else names the failure honestly. */
    fun showError(t: Throwable, title: String = "Something needs attention") {
        val msg = when (t) {
            is PfException -> t.message ?: t.code
            is OutOfMemoryError -> "Not enough memory for this operation. Close other tabs or apps and try again."
            is SecurityException -> "Android denied access: ${t.message}. The permission may have been revoked."
            else -> "${t.javaClass.simpleName}: ${t.message ?: "no details"}. Details were written to the local log (Help → Logs)."
        }
        Dialogs.info(this, title, msg)
    }

    override fun onDestroy() {
        consentDialog?.dismiss()
        super.onDestroy()
    }
}
