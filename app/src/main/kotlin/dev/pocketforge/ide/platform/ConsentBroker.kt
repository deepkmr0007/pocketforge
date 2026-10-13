package dev.pocketforge.ide.platform

import android.os.Handler
import android.os.Looper
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentPrompter
import dev.pocketforge.engine.consent.ConsentRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the engine's blocking [ConsentPrompter] (called on a background thread) to a dialog in
 * the activity that is currently in the foreground. Fails closed: no visible screen, a main-thread
 * caller, a second concurrent request, the screen going away, or a 5-minute timeout all mean DENY.
 */
class ConsentBroker : ConsentPrompter {
    /** Implemented by the foreground activity. Must call [answer] exactly once. */
    interface Host {
        fun showConsent(request: ConsentRequest, answer: (ConsentDecision) -> Unit)
        fun dismissConsent()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var host: Host? = null
    @Volatile private var pending: CompletableFuture<ConsentDecision>? = null
    private val busy = AtomicBoolean(false)

    fun attach(h: Host) { host = h }

    /** Called when the activity pauses. Leaving the screen never leaves a request approved. */
    fun detach(h: Host) {
        if (host === h) {
            host = null
            pending?.complete(ConsentDecision.DENY)
            h.dismissConsent()
        }
    }

    override fun ask(request: ConsentRequest): ConsentDecision {
        if (Looper.myLooper() == Looper.getMainLooper()) return ConsentDecision.DENY
        if (host == null) return ConsentDecision.DENY
        if (!busy.compareAndSet(false, true)) return ConsentDecision.DENY
        val f = CompletableFuture<ConsentDecision>()
        pending = f
        return try {
            main.post {
                val h = host
                if (h == null) f.complete(ConsentDecision.DENY)
                else try { h.showConsent(request) { d -> f.complete(d) } } catch (t: Throwable) { f.complete(ConsentDecision.DENY) }
            }
            f.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
            ConsentDecision.DENY
        } finally {
            pending = null
            busy.set(false)
            main.post { host?.dismissConsent() }
        }
    }
}
