package com.shadowcore.app.engine

import android.content.Context
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the initialization lifecycle of the BlackBox virtual engine.
 *
 * BlackBox requires a very specific initialization order:
 * 1. `doAttachBaseContext()` in `Application.attachBaseContext()` — BEFORE Hilt
 * 2. `doCreate()` in `Application.onCreate()` — AFTER Hilt
 *
 * This class wraps those calls with reflection so the app compiles and runs
 * cleanly whether or not the BlackBox AAR is included in the build.
 *
 * ## Usage
 * Called from [com.shadowcore.app.ShadowCoreApp]:
 * ```kotlin
 * override fun attachBaseContext(base: Context) {
 *     super.attachBaseContext(base)
 *     BlackBoxInitializer.doAttachBaseContext(base)
 * }
 * override fun onCreate() {
 *     super.onCreate()
 *     BlackBoxInitializer.doCreate()
 * }
 * ```
 */
object BlackBoxInitializer {

    private const val TAG = "BlackBoxInit"
    private const val CORE_CLASS = "top.niunaijun.blackbox.BlackBoxCore"
    private const val CLIENT_CONFIG_CLASS = "top.niunaijun.blackbox.BlackBoxCore\$ClientConfiguration"

    /** Whether BlackBox was successfully initialized. */
    var isInitialized: Boolean = false
        private set

    /**
     * Phase 1: Call in `Application.attachBaseContext()`.
     *
     * Initializes the BlackBox engine's base context. This MUST happen
     * before Hilt's component creation to avoid crashes from uninitialized
     * native libraries.
     *
     * @param base The base context from Application.attachBaseContext()
     */
    fun doAttachBaseContext(base: Context) {
        if (!BlackBoxContainerEngine.isAvailable()) {
            Log.i(TAG, "BlackBox SDK not found on classpath — running in stub mode")
            return
        }

        try {
            val coreClass = Class.forName(CORE_CLASS)
            val getInstance = coreClass.getMethod("get")
            val coreInstance = getInstance.invoke(null)

            // Create a ClientConfiguration subclass via dynamic proxy or anonymous class.
            // BlackBoxCore.get().doAttachBaseContext(Context, ClientConfiguration)
            // ClientConfiguration is abstract with one method: getHostPackageName()
            val configClass = Class.forName(CLIENT_CONFIG_CLASS)
            val configProxy = java.lang.reflect.Proxy.newProxyInstance(
                configClass.classLoader,
                arrayOf(configClass),
            ) { _, method, _ ->
                when (method.name) {
                    "getHostPackageName" -> base.packageName
                    else -> null
                }
            }

            // Try direct approach — create anonymous inner class
            // Some versions use an abstract class, not an interface, so Proxy won't work.
            // Fall back to calling with just Context if the 2-arg version fails.
            try {
                val attachMethod = coreInstance.javaClass.getMethod(
                    "doAttachBaseContext",
                    Context::class.java,
                    configClass,
                )
                attachMethod.invoke(coreInstance, base, configProxy)
            } catch (_: NoSuchMethodException) {
                // Try single-arg version
                val attachMethod = coreInstance.javaClass.getMethod(
                    "doAttachBaseContext",
                    Context::class.java,
                )
                attachMethod.invoke(coreInstance, base)
            }

            Log.i(TAG, "BlackBox attachBaseContext succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "BlackBox attachBaseContext failed — falling back to stub mode", e)
        }
    }

    /**
     * Phase 2: Call in `Application.onCreate()`.
     *
     * Completes BlackBox initialization after Hilt components are ready.
     * Must be called after [doAttachBaseContext].
     */
    fun doCreate() {
        if (!BlackBoxContainerEngine.isAvailable()) return

        try {
            val coreClass = Class.forName(CORE_CLASS)
            val getInstance = coreClass.getMethod("get")
            val coreInstance = getInstance.invoke(null)

            val createMethod = coreInstance.javaClass.getMethod("doCreate")
            createMethod.invoke(coreInstance)

            isInitialized = true
            Log.i(TAG, "BlackBox doCreate succeeded — engine fully initialized")
        } catch (e: Exception) {
            Log.e(TAG, "BlackBox doCreate failed", e)
            isInitialized = false
        }
    }
}
