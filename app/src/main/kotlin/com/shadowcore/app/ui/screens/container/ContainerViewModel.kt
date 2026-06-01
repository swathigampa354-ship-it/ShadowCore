package com.shadowcore.app.ui.screens.container

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shadowcore.app.engine.BlackBoxContainerEngine
import com.shadowcore.app.engine.BootEvent
import com.shadowcore.app.engine.ContainerEngine
import com.shadowcore.app.engine.ContainerManager
import com.shadowcore.app.engine.ImageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContainerUiState(
    val veId: String = "",
    val androidVersion: String = "Android 15",
    val isBooting: Boolean = false,
    val isRunning: Boolean = false,
    val bootPhase: String = "",
    val bootProgress: Float = 0f,
    val installedApps: List<VirtualApp> = emptyList(),
    val showInstallDialog: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class ContainerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val containerManager: ContainerManager,
    private val imageManager: ImageManager,
    private val engine: ContainerEngine,
) : ViewModel() {

    companion object {
        private const val TAG = "ContainerViewModel"
    }

    private val veId: String = savedStateHandle["veId"] ?: ""

    private val _state = MutableStateFlow(ContainerUiState(veId = veId))
    val state: StateFlow<ContainerUiState> = _state.asStateFlow()

    init {
        // Auto-start boot sequence when the screen opens
        viewModelScope.launch {
            kotlinx.coroutines.delay(500) // Brief delay for screen transition
            startContainer()
        }
    }

    fun startContainer() {
        if (_state.value.isBooting || _state.value.isRunning) return

        viewModelScope.launch {
            _state.update { it.copy(isBooting = true, bootProgress = 0f) }

            containerManager.startContainer(veId)
                .collect { event ->
                    when (event) {
                        is BootEvent.Initializing -> {
                            _state.update {
                                it.copy(bootPhase = "Initializing container engine...", bootProgress = 0.15f)
                            }
                        }
                        is BootEvent.LoadingFramework -> {
                            _state.update {
                                it.copy(bootPhase = "Loading Android framework...", bootProgress = 0.45f)
                            }
                        }
                        is BootEvent.StartingServices -> {
                            _state.update {
                                it.copy(bootPhase = "Starting system services...", bootProgress = 0.75f)
                            }
                        }
                        is BootEvent.Ready -> {
                            _state.update {
                                it.copy(
                                    isBooting = false,
                                    isRunning = true,
                                    bootPhase = "",
                                    bootProgress = 1f,
                                )
                            }
                            // Load installed apps from the engine
                            refreshInstalledApps()
                        }
                        is BootEvent.Error -> {
                            Log.e(TAG, "Boot error: ${event.message}", event.cause)
                            _state.update {
                                it.copy(
                                    isBooting = false,
                                    isRunning = false,
                                    errorMessage = event.message,
                                )
                            }
                        }
                    }
                }
        }
    }

    fun stopContainer() {
        viewModelScope.launch {
            try {
                containerManager.stopContainer(veId)
            } catch (e: Exception) {
                Log.e(TAG, "Stop failed", e)
            }
            _state.update { it.copy(isRunning = false, installedApps = emptyList()) }
        }
    }

    /**
     * Launches an installed app inside the virtual environment.
     *
     * If the engine is [BlackBoxContainerEngine], calls `launchApk()`.
     * Otherwise logs a placeholder message.
     */
    fun launchApp(packageName: String) {
        viewModelScope.launch {
            Log.i(TAG, "Launching app: $packageName")
            val bbEngine = engine as? BlackBoxContainerEngine
            if (bbEngine != null) {
                val success = bbEngine.launchApp(packageName)
                if (!success) {
                    _state.update { it.copy(errorMessage = "Failed to launch $packageName") }
                }
            } else {
                _state.update {
                    it.copy(errorMessage = "App launching requires BlackBox SDK")
                }
            }
        }
    }

    fun showInstallDialog() {
        _state.update { it.copy(showInstallDialog = true) }
    }

    fun dismissInstallDialog() {
        _state.update { it.copy(showInstallDialog = false) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun onVirtualBack() {
        // TODO: Route back press to the running virtual activity
        Log.d(TAG, "Virtual back pressed")
    }

    fun onVirtualHome() {
        // TODO: Route home press to virtual launcher
        Log.d(TAG, "Virtual home pressed")
    }

    fun onVirtualRecents() {
        // TODO: Route recents press to virtual recents screen
        Log.d(TAG, "Virtual recents pressed")
    }

    /**
     * Refreshes the installed apps list from the engine.
     * Falls back to default system apps if the engine returns empty.
     */
    private fun refreshInstalledApps() {
        viewModelScope.launch {
            try {
                val apps = containerManager.listInstalledApps(veId)
                val virtualApps = if (apps.isNotEmpty()) {
                    apps.map { VirtualApp(it.packageName, it.appName, it.versionName) }
                } else {
                    // Show default system apps when engine has nothing installed yet
                    getDefaultApps()
                }
                _state.update { it.copy(installedApps = virtualApps) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load installed apps", e)
                _state.update { it.copy(installedApps = getDefaultApps()) }
            }
        }
    }

    private fun getDefaultApps(): List<VirtualApp> {
        // Pre-installed system apps that come with the system image
        return listOf(
            VirtualApp("com.android.settings", "Settings"),
            VirtualApp("com.android.calculator2", "Calculator"),
            VirtualApp("com.android.contacts", "Contacts"),
            VirtualApp("com.android.deskclock", "Clock"),
        )
    }
}
