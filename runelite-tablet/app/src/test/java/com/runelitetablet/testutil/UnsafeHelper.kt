package com.runelitetablet.testutil

object UnsafeHelper {
    private val unsafe: Any = run {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null)
    }

    private val allocateMethod = Class.forName("sun.misc.Unsafe")
        .getMethod("allocateInstance", Class::class.java)

    @Suppress("UNCHECKED_CAST")
    fun <T> allocate(clazz: Class<T>): T = allocateMethod.invoke(unsafe, clazz) as T
}
