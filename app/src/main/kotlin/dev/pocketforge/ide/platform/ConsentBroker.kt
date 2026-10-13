package dev.pocketforge.ide.platform

import android.os.Looper
import dev.pocketforge.engine.consent.ConsentDecision
import dev.pocketforge.engine.consent.ConsentPrompter
import dev.pocketforge.engine.consent.ConsentRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges the engine's blocking [ConsentPrompter] to the Compose consent dialog.
 * Fails closed: no visible UI, main-thread caller, a second concurrent request, or a 5-minute
 * timeout all result in DENY.
 */
class ConsentBroker : ConsentPrompter {
    class Pending(val request: ConsentRequest, private val future: CompletableFuture<ConsentDecision>) {
        fun answer(d: ConsentDecision) { future.complete(d) }
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private val visibleScreens = AtomicInteger(0)

    fun onUiVisible() { visibleScreens.incrementAndGet() }
    fun onUiHidden() {
        if (visibleScreens.decrementAndGet() <= 0) {
            visibleScreens.set(0)
            _pending.value?.answer(ConsentDecision.DENY) // leaving the app never leaves a request approved
        }
    }

    override fun ask(request: ConsentRequest): ConsentDecision {
        if (Looper.myLooper() == Looper.getMainLooper()) return ConsentDecision.DENY
        if (visibleScreens.get() <= 0) return ConsentDecision.DENY
        val future = CompletableFuture<ConsentDecision>()
        val p = Pending(request, future)
        synchronized(this) {
            if (_pending.value != null) return ConsentDecision.DENY
            _pending.value = p
        }
        return try {
            future.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
            ConsentDecision.DENY
        } finally {
            synchronized(this) { if (_pending.value === p) _pending.value = null }
        }
    }
}
