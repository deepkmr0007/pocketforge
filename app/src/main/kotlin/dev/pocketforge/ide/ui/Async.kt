package dev.pocketforge.ide.ui

import dev.pocketforge.engine.common.PfException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Runs blocking engine work off the main thread and turns failures into actionable messages. */
suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (e: PfException) {
        Result.failure(UserFacingError(e.message ?: e.code))
    } catch (e: SecurityException) {
        Result.failure(UserFacingError("Android denied access: ${e.message}. Re-select the file or folder."))
    } catch (e: IOException) {
        Result.failure(UserFacingError("Storage error: ${e.message}. Check free space and try again."))
    } catch (e: OutOfMemoryError) {
        Result.failure(UserFacingError("Not enough memory for this operation. Close other tabs or apps and retry."))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Result.failure(UserFacingError("Unexpected error (${e.javaClass.simpleName}): ${e.message}. Details are in Help › Logs."))
    }
}

class UserFacingError(message: String) : Exception(message)
