// A handful of classes and functions to support the generated data structures.
// This would be a good candidate for isolating in its own ffi-support lib.

{% if ci.iter_object_definitions().len() > 0 %}

// Interface implemented by anything that can contain an object reference.
//
// Such types expose a `destroy()` method that must be called to cleanly
// dispose of the contained objects. Failure to call this method may result
// in memory leaks.
//
// The easiest way to ensure this method is called is to use the `.use`
// helper method to execute a block and destroy the object at the end.
interface Disposable {
    fun destroy()
    companion object {
        fun destroy(vararg args: Any?) {
            args.filterIsInstance<Disposable>()
                .forEach(Disposable::destroy)
        }
    }
}

inline fun <T : Disposable?, R> T.use(block: (T) -> R) =
    try {
        block(this)
    } finally {
        try {
            // N.B. our implementation is on the nullable type `Disposable?`.
            this?.destroy()
        } catch (e: Throwable) {
            // swallow
        }
    }

// The base class for all UniFFI Object types.
//
// This class provides core operations for working with the Rust `Arc<T>` pointer to
// the live Rust struct on the other side of the FFI.
//
// There's some subtlety here, because we have to be careful not to operate on a Rust
// struct after it has been dropped, and because we must expose a public API for freeing
// the Kotlin wrapper object in lieu of reliable finalizers. The core requirements are:
//
//   * Each `FFIObject` instance holds an opaque pointer to the underlying Rust struct.
//     Method calls need to read this pointer from the object's state and pass it in to
//     the Rust FFI.
//
//   * When an `FFIObject` is no longer needed, its pointer should be passed to a
//     special destructor function provided by the Rust FFI, which will drop the
//     underlying Rust struct.
//
//   * Given an `FFIObject` instance, calling code is expected to call the special
//     `destroy` method in order to free it after use, either by calling it explicitly
//     or by using a higher-level helper like the `use` method. Failing to do so will
//     leak the underlying Rust struct.
//
//   * We can't assume that calling code will do the right thing, and must be prepared
//     to handle Kotlin method calls executing concurrently with or even after a call to
//     `destroy`, and to handle multiple (possibly concurrent!) calls to `destroy`.
//
//   * We must never allow Rust code to operate on the underlying Rust struct after
//     the destructor has been called, and must never call the destructor more than once.
//     Doing so may trigger memory unsafety.
//
// If we try to implement this with mutual exclusion on access to the pointer, there is the
// possibility of a race between a method call and a concurrent call to `destroy`:
//
//    * Thread A starts a method call, reads the value of the pointer, but is interrupted
//      before it can pass the pointer over the FFI to Rust.
//    * Thread B calls `destroy` and frees the underlying Rust struct.
//    * Thread A resumes, passing the already-read pointer value to Rust and triggering
//      a use-after-free.
//
// One possible solution would be to use a `ReadWriteLock`, with each method call taking
// a read lock (and thus allowed to run concurrently) and the special `destroy` method
// taking a write lock (and thus blocking on live method calls). However, we aim not to
// generate methods with any hidden blocking semantics, and a `destroy` method that might
// block if called incorrectly seems to meet that bar.
//
// So, we achieve our goals by giving each `FFIObject` an associated `AtomicLong` counter to track
// the number of in-flight method calls, and an `AtomicBoolean` flag to indicate whether `destroy`
// has been called. These are updated according to the following rules:
//
//    * The initial value of the counter is 1, indicating a live object with no in-flight calls.
//      The initial value for the flag is false.
//
//    * At the start of each method call, we atomically check the counter.
//      If it is 0 then the underlying Rust struct has already been destroyed and the call is aborted.
//      If it is nonzero them we atomically increment it by 1 and proceed with the method call.
//
//    * At the end of each method call, we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
//    * When `destroy` is called, we atomically flip the flag from false to true.
//      If the flag was already true we silently fail.
//      Otherwise we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
// Astute readers may observe that this all sounds very similar to the way that Rust's `Arc<T>` works,
// and indeed it is, with the addition of a flag to guard against multiple calls to `destroy`.
//
// The overall effect is that the underlying Rust struct is destroyed only when `destroy` has been
// called *and* all in-flight method calls have completed, avoiding violating any of the expectations
// of the underlying Rust code.
//
// In the future we may be able to replace some of this with automatic finalization logic, such as using
// the new "Cleaner" functionaility in Java 9. The above scheme has been designed to work even if `destroy` is
// invoked by garbage-collection machinery rather than by calling code (which by the way, it's apparently also
// possible for the JVM to finalize an object while there is an in-flight call to one of its methods [1],
// so there would still be some complexity here).
//
// Sigh...all of this for want of a robust finalization mechanism.
//
// [1] https://stackoverflow.com/questions/24376768/can-java-finalize-an-object-when-it-is-still-in-scope/24380219
//
abstract class FFIObject(
    protected val pointer: Pointer
): Disposable, AutoCloseable {

    val wasDestroyed = AtomicBoolean(false)
    val callCounter = AtomicLong(1)

    open protected fun freeRustArcPtr() {
        // To be overridden in subclasses.
    }

    override fun destroy() {
        // Only allow a single call to this method.
        // TODO: maybe we should log a warning if called more than once?
        if (this.wasDestroyed.compareAndSet(false, true)) {
            // This decrement always matches the initial count of 1 given at creation time.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        // Check and increment the call counter, to keep the object alive.
        // This needs a compare-and-set retry loop in case of concurrent updates.
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        // Now we can safely do the method call without the pointer being freed concurrently.
        try {
            return block(this.pointer)
        } finally {
            // This decrement aways matches the increment we performed above.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }
}
{% endif %}

{% if ci.iter_callback_interface_definitions().len() > 0 %}
internal typealias Handle = Long
internal class ConcurrentHandleMap<T>(
    private val leftMap: MutableMap<Handle, T> = mutableMapOf(),
    private val rightMap: MutableMap<T, Handle> = mutableMapOf()
) {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val currentHandle = AtomicLong(0L)
    private val stride = 1L

    fun insert(obj: T): Handle =
        lock.withLock {
            rightMap[obj] ?:
                currentHandle.getAndAdd(stride)
                    .also { handle ->
                        leftMap[handle] = obj
                        rightMap[obj] = handle
                    }
            }

    fun <R> callWithResult(handle: Handle, fn: (T) -> R): R =
        lock.withLock {
            leftMap[handle] ?: throw RuntimeException("Panic: handle not in handlemap")
        }.let { obj ->
            fn.invoke(obj)
        }

    fun get(handle: Handle) = lock.withLock {
        leftMap[handle]
    }

    fun delete(handle: Handle) {
        this.remove(handle)
    }

    fun remove(handle: Handle): T? =
        lock.withLock {
            leftMap.remove(handle)?.let { obj ->
                rightMap.remove(obj)
                obj
            }
        }
}

interface ForeignCallback : com.sun.jna.Callback {
    public fun invoke(handle: Long, method: Int, args: RustBuffer.ByValue): RustBuffer.ByValue
}

// Magic number for the Rust proxy to call using the same mechanism as every other method,
// to free the callback once it's dropped by Rust.
internal const val IDX_CALLBACK_FREE = 0

internal abstract class CallbackInternals<CallbackInterface>(
    protected val foreignCallback: ForeignCallback
) {
    val handleMap = ConcurrentHandleMap<CallbackInterface>()

    // Registers the foreign callback with the Rust side.
    // This method is generated for each callback interface.
    abstract fun register(lib: _UniFFILib)

    fun drop(handle: Long): RustBuffer.ByValue {
        return handleMap.remove(handle).let { RustBuffer.ByValue() }
    }

    fun lift(n: Long) = handleMap.get(n)

    fun read(buf: ByteBuffer) = lift(buf.getLong())

    fun lower(v: CallbackInterface) =
        handleMap.insert(v).also {
            assert(handleMap.get(it) === v) { "Handle map is not returning the object we just placed there. This is a bug in the HandleMap." }
        }

    fun write(v: CallbackInterface, buf: RustBufferBuilder) =
        buf.putLong(lower(v))
}
{% endif %}

// Error runtime.
@Structure.FieldOrder("code", "error_buf")
internal open class RustCallStatus : Structure() {
    @JvmField var code: Int = 0
    @JvmField var error_buf: RustBuffer.ByValue = RustBuffer.ByValue()

    fun isSuccess(): Boolean {
        return code == 0
    }

    fun isError(): Boolean {
        return code == 1
    }

    fun isPanic(): Boolean {
        return code == 2
    }
}

class InternalException(message: String) : Exception(message)

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
interface CallStatusErrorHandler<E> {
    fun lift(error_buf: RustBuffer.ByValue): E;
}

// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
private inline fun <U, E: Exception> rustCallWithError(errorHandler: CallStatusErrorHandler<E>, callback: (RustCallStatus) -> U): U {
    var status = RustCallStatus();
    val return_value = callback(status)
    if (status.isSuccess()) {
        return return_value
    } else if (status.isError()) {
        throw errorHandler.lift(status.error_buf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.error_buf.len > 0) {
            throw InternalException({{ "status.error_buf"|lift_kt(Type::String) }})
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// CallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
object NullCallStatusErrorHandler: CallStatusErrorHandler<InternalException> {
    override fun lift(error_buf: RustBuffer.ByValue): InternalException {
        RustBuffer.free(error_buf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
private inline fun <U> rustCall(callback: (RustCallStatus) -> U): U {
    return rustCallWithError(NullCallStatusErrorHandler, callback);
}