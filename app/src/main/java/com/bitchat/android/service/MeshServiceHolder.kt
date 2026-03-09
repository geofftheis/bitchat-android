package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    // Track the UUID of the current instance so we can recreate if UUID changes
    @Volatile
    private var currentServiceUuid: java.util.UUID? = null

    @Synchronized
    fun getOrCreate(
        context: Context,
        serviceUuid: java.util.UUID = com.bitchat.android.util.AppConstants.Mesh.Gatt.SERVICE_UUID
    ): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the UUID changed, tear down old instance and create new one
            if (currentServiceUuid != null && currentServiceUuid != serviceUuid) {
                android.util.Log.i(TAG, "Service UUID changed ($currentServiceUuid -> $serviceUuid); replacing instance")
                try { existing.stopServices() } catch (e: Exception) {
                    android.util.Log.w(TAG, "Error while stopping old-UUID instance: ${e.message}")
                }
                val created = BluetoothMeshService(context.applicationContext, serviceUuid)
                android.util.Log.i(TAG, "Created new BluetoothMeshService (UUID change)")
                meshService = created
                currentServiceUuid = serviceUuid
                return created
            }
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) {
                    android.util.Log.d(TAG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    android.util.Log.w(TAG, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (e: Exception) {
                        android.util.Log.w(TAG, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = BluetoothMeshService(context.applicationContext, serviceUuid)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    currentServiceUuid = serviceUuid
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = BluetoothMeshService(context.applicationContext, serviceUuid)
                meshService = created
                currentServiceUuid = serviceUuid
                created
            }
        }
        val created = BluetoothMeshService(context.applicationContext, serviceUuid)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        currentServiceUuid = serviceUuid
        return created
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        android.util.Log.d(TAG, "Attaching BluetoothMeshService to holder")
        meshService = service
    }

    @Synchronized
    fun clear() {
        android.util.Log.d(TAG, "Clearing BluetoothMeshService from holder")
        meshService = null
        currentServiceUuid = null
    }
}
