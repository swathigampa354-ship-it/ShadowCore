package com.shadowcore.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.shadowcore.app.billing.BillingManager
import com.shadowcore.app.engine.BlackBoxInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ShadowCoreApp : Application() {

    @Inject
    lateinit var billingManager: BillingManager

    /**
     * BlackBox engine MUST be initialized here, BEFORE Hilt creates its
     * component graph. This ensures native libraries are loaded before
     * any Hilt-injected class tries to use BlackBoxCore.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        BlackBoxInitializer.doAttachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // Phase 2: Complete BlackBox init after Hilt is ready
        BlackBoxInitializer.doCreate()
        billingManager.initialize()
        Log.i("ShadowCoreApp", "App initialized — BlackBox: ${BlackBoxInitializer.isInitialized}")
    }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.destroy()
    }
}

