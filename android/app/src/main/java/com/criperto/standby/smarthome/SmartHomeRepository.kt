package com.criperto.standby.smarthome

import android.content.Context
import com.criperto.standby.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class WizDeviceConfig(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 38899,
    val mac: String = "",
    val moduleName: String = "",
    val room: String = "Escritorio",
    val supportsRgb: Boolean = true,
    val supportsTemp: Boolean = true,
    val isFavorite: Boolean = true,
    // Multi-brand & Multi-category extensions (v0.3)
    val driver: String = "wiz", // wiz | wled | shelly | tasmota | yeelight | hue | home_assistant
    val category: String = "light", // light | ac | tv | switch | fan
    val entityId: String = "", // Home Assistant entity_id (e.g. light.velador, climate.aire, media_player.tv)
    val authToken: String = "",
)

@Serializable
data class HomeAssistantConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val accessToken: String = "",
)

@Serializable
data class SmartSceneFavorite(
    val id: String,
    val name: String,
    val deviceId: String = "", // empty => primary/all
    val actionType: String = "scene", // off | on | rgb | temp | scene
    val r: Int = 255,
    val g: Int = 200,
    val b: Int = 140,
    val kelvin: Int = 3000,
    val brightness: Int = 80,
    val sceneId: Int = 11,
    val speed: Int = 100,
)

@Serializable
data class SmartHomeStore(
    val devices: List<WizDeviceConfig> = emptyList(),
    val favorites: List<SmartSceneFavorite> = defaultFavorites(),
    val rooms: List<String> = listOf("Escritorio", "Dormitorio", "Living"),
    val homeAssistant: HomeAssistantConfig = HomeAssistantConfig(),
) {
    companion object {
        fun defaultFavorites(): List<SmartSceneFavorite> = listOf(
            SmartSceneFavorite(
                id = "fav_daylight",
                name = "Luz diurna",
                actionType = "scene",
                sceneId = 12,
                brightness = 100,
                speed = 100,
            ),
            SmartSceneFavorite(
                id = "fav_warm",
                name = "Lectura cálida",
                actionType = "temp",
                kelvin = 2900,
                brightness = 75,
            ),
            SmartSceneFavorite(
                id = "fav_sunset",
                name = "Atardecer",
                actionType = "scene",
                sceneId = 3,
                brightness = 80,
                speed = 80,
            ),
            SmartSceneFavorite(
                id = "fav_night",
                name = "Luz nocturna",
                actionType = "scene",
                sceneId = 14,
                brightness = 20,
                speed = 50,
            ),
        )
    }
}

class SmartHomeRepository(private val context: Context) {
    private val storeFile = File(context.filesDir, "smarthome.json")
    private val ioLock = Any()

    private val _store = MutableStateFlow(loadFromDisk())
    val storeFlow: StateFlow<SmartHomeStore> = _store.asStateFlow()

    private fun loadFromDisk(): SmartHomeStore {
        if (!storeFile.exists()) return SmartHomeStore()
        return runCatching {
            AppJson.decodeFromString<SmartHomeStore>(storeFile.readText())
        }.getOrDefault(SmartHomeStore())
    }

    private fun saveToDisk(store: SmartHomeStore) {
        synchronized(ioLock) {
            val tmp = File(context.filesDir, "smarthome.json.tmp")
            tmp.writeText(AppJson.encodeToString(store))
            tmp.renameTo(storeFile)
        }
    }

    suspend fun update(transform: (SmartHomeStore) -> SmartHomeStore): SmartHomeStore {
        val next = withContext(Dispatchers.IO) {
            val updated = transform(_store.value)
            saveToDisk(updated)
            updated
        }
        _store.value = next
        return next
    }

    suspend fun upsertDevice(device: WizDeviceConfig) {
        update { store ->
            val idx = store.devices.indexOfFirst {
                it.id == device.id ||
                    (device.entityId.isNotBlank() && it.entityId == device.entityId) ||
                    (device.mac.isNotBlank() && it.mac == device.mac) ||
                    (device.driver != "home_assistant" && it.ip == device.ip && it.driver == device.driver)
            }
            val nextList = if (idx >= 0) {
                store.devices.toMutableList().apply { set(idx, device) }
            } else {
                store.devices + device
            }
            val nextRooms = if (device.room.isNotBlank() && device.room !in store.rooms) {
                store.rooms + device.room
            } else {
                store.rooms
            }
            store.copy(devices = nextList, rooms = nextRooms)
        }
    }

    suspend fun updateHomeAssistant(config: HomeAssistantConfig) {
        update { store -> store.copy(homeAssistant = config) }
    }

    suspend fun removeDevice(deviceId: String) {
        update { store ->
            store.copy(devices = store.devices.filterNot { it.id == deviceId })
        }
    }

    suspend fun addFavorite(fav: SmartSceneFavorite) {
        val withId = if (fav.id.isBlank()) fav.copy(id = UUID.randomUUID().toString()) else fav
        update { store ->
            store.copy(favorites = store.favorites + withId)
        }
    }

    suspend fun removeFavorite(favId: String) {
        update { store ->
            store.copy(favorites = store.favorites.filterNot { it.id == favId })
        }
    }
}
