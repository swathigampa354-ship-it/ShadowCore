package com.shadowcore.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real implementation of [ContainerEngine] backed by BlackBoxCore.
 *
 * Uses reflection to interact with the BlackBox virtual engine so that the app
 * compiles even when the BCore AAR is not present. At runtime, [isAvailable]
 * checks whether BlackBoxCore is on the classpath before any API call.
 *
 * ## BlackBox API Surface (via reflection)
 * - `BlackBoxCore.get()` → singleton accessor
 * - `installPackageAsUser(File, int)` → install APK into virtual env
 * - `uninstallPackageAsUser(String, int)` → remove from virtual env
 * - `launchApk(String, int)` → launch an installed app
 * - `getInstalledApplications(int, int)` → list installed apps
 * - `getInstalledPackages(int, int)` → list installed packages
 * - `isInstalled(String, int)` → check if package is installed
 * - `killProcess(String, int)` → kill a virtualized app process
 *
 * ## User IDs
 * BlackBox supports multiple "virtual users" (like Android's multi-user).
 * We use userId=0 for the default container. Future: map each VE profile
 * to a different userId for true isolation.
 *
 * @property context Application context for package manager lookups
 */
@Singleton
class BlackBoxContainerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ContainerEngine {

    companion object {
        private const val TAG = "BlackBoxEngine"
        private const val BLACKBOX_CORE_CLASS = "top.niunaijun.blackbox.BlackBoxCore"
        private const val DEFAULT_USER_ID = 0

        /**
         * Checks whether the BlackBox SDK is available on the classpath.
         * Call this before constructing the engine or using any API.
         */
        fun isAvailable(): Boolean {
            return try {
                Class.forName(BLACKBOX_CORE_CLASS)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }

    /** Cached reflection reference to `BlackBoxCore.get()` result. */
    private val coreInstance: Any? by lazy {
        try {
            val coreClass = Class.forName(BLACKBOX_CORE_CLASS)
            val getMethod = coreClass.getMethod("get")
            getMethod.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get BlackBoxCore instance", e)
            null
        }
    }

    /** Tracks which containers we consider "booted". */
    private val bootedContainers = mutableSetOf<String>()

    override fun boot(veId: String, imagePath: String): Flow<BootEvent> = flow {
        Log.i(TAG, "Booting container $veId (BlackBox engine)")

        if (coreInstance == null) {
            emit(BootEvent.Error("BlackBox SDK not available. Install the BCore AAR."))
            return@flow
        }

        try {
            emit(BootEvent.Initializing)
            delay(300)

            emit(BootEvent.LoadingFramework)
            // BlackBox loads frameworks lazily — the engine is already initialized
            // in Application.attachBaseContext(). This is just a progress signal.
            delay(400)

            emit(BootEvent.StartingServices)
            delay(300)

            bootedContainers.add(veId)
            emit(BootEvent.Ready)
            Log.i(TAG, "Container $veId booted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Boot failed for container $veId", e)
            emit(BootEvent.Error("Boot failed: ${e.message}", e))
        }
    }.flowOn(Dispatchers.Main)

    override suspend fun stop(veId: String) {
        Log.i(TAG, "Stopping container $veId")
        withContext(Dispatchers.IO) {
            try {
                // Kill all running processes for this container's apps
                val apps = listInstalledApps(veId)
                apps.forEach { app ->
                    invokeVoid("killProcess", app.packageName, DEFAULT_USER_ID)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error killing processes during stop", e)
            }
        }
        bootedContainers.remove(veId)
    }

    override fun isRunning(veId: String): Boolean =
        veId in bootedContainers

    override suspend fun installApp(veId: String, apkPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(apkPath)
                if (!apkFile.exists()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("APK file not found: $apkPath")
                    )
                }

                Log.i(TAG, "Installing APK: $apkPath into container $veId")

                // BlackBoxCore.get().installPackageAsUser(File, int)
                val result = invoke("installPackageAsUser",
                    arrayOf(File::class.java, Int::class.javaPrimitiveType!!),
                    apkFile, DEFAULT_USER_ID
                )

                if (result != null) {
                    // The result object has `success` boolean and `packageName` string
                    val successField = result.javaClass.getField("success")
                    val success = successField.getBoolean(result)

                    if (success) {
                        val pkgField = result.javaClass.getField("packageName")
                        val packageName = pkgField.get(result) as? String ?: "unknown"
                        Log.i(TAG, "Installed successfully: $packageName")
                        Result.success(packageName)
                    } else {
                        val msgField = result.javaClass.getField("msg")
                        val msg = msgField.get(result) as? String ?: "Unknown error"
                        Log.e(TAG, "Install failed: $msg")
                        Result.failure(Exception("Install failed: $msg"))
                    }
                } else {
                    Result.failure(Exception("BlackBoxCore returned null"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install exception", e)
                Result.failure(e)
            }
        }

    override suspend fun uninstallApp(veId: String, packageName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Uninstalling $packageName from container $veId")
                invokeVoid("uninstallPackageAsUser",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                    packageName, DEFAULT_USER_ID
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Uninstall failed", e)
                Result.failure(e)
            }
        }

    override suspend fun listInstalledApps(veId: String): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            try {
                // BlackBoxCore.get().getInstalledApplications(int flags, int userId)
                @Suppress("UNCHECKED_CAST")
                val appInfoList = invoke(
                    "getInstalledApplications",
                    arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    0, DEFAULT_USER_ID
                ) as? List<ApplicationInfo> ?: emptyList()

                appInfoList.map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        appName = try {
                            info.loadLabel(context.packageManager).toString()
                        } catch (_: Exception) {
                            info.packageName
                        },
                        icon = try {
                            info.loadIcon(context.packageManager)
                        } catch (_: Exception) {
                            null
                        },
                        versionName = "",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "listInstalledApps failed", e)
                emptyList()
            }
        }

    /**
     * Launches an installed app inside the virtual environment.
     *
     * This is called from the ContainerScreen when the user taps an app icon.
     * It invokes `BlackBoxCore.get().launchApk(packageName, userId)`.
     *
     * @param packageName The package to launch
     * @return true if launch succeeded
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            Log.i(TAG, "Launching app: $packageName")
            val result = invoke(
                "launchApk",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                packageName, DEFAULT_USER_ID
            )
            // launchApk returns an Intent or boolean depending on version
            result != null
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed for $packageName", e)
            false
        }
    }

    /**
     * Checks whether a specific package is installed in the virtual environment.
     */
    fun isInstalled(packageName: String): Boolean {
        return try {
            val result = invoke(
                "isInstalled",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                packageName, DEFAULT_USER_ID
            ) as? Boolean ?: false
            result
        } catch (e: Exception) {
            false
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────

    /**
     * Invokes a method on the BlackBoxCore instance via reflection.
     *
     * @param methodName Name of the method to call
     * @param paramTypes Parameter types for method lookup
     * @param args Actual arguments to pass
     * @return The method's return value, or null
     */
    private fun invoke(
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?,
    ): Any? {
        val core = coreInstance ?: throw IllegalStateException("BlackBoxCore not initialized")
        val method = core.javaClass.getMethod(methodName, *paramTypes)
        return method.invoke(core, *args)
    }

    /**
     * Invokes a void method on the BlackBoxCore instance.
     */
    private fun invokeVoid(
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?,
    ) {
        invoke(methodName, paramTypes, *args)
    }

    /**
     * Convenience overload for methods with (String, int) signature.
     */
    private fun invokeVoid(methodName: String, stringArg: String, intArg: Int) {
        invokeVoid(
            methodName,
            arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
            stringArg, intArg
        )
    }
}
