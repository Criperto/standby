package com.criperto.standby.standby

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.criperto.standby.core.MediaSnapshot
import com.criperto.standby.core.NotificationSnapshot
import com.criperto.standby.core.TimerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MediaBridge {
    private val _mediaState = MutableStateFlow(MediaSnapshot())
    val mediaState: StateFlow<MediaSnapshot> = _mediaState.asStateFlow()

    private val _notificationState = MutableStateFlow(NotificationSnapshot())
    val notificationState: StateFlow<NotificationSnapshot> = _notificationState.asStateFlow()

    private val _timerState = MutableStateFlow(TimerSnapshot())
    val timerState: StateFlow<TimerSnapshot> = _timerState.asStateFlow()

    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    @Volatile
    private var activeController: MediaController? = null

    @Volatile
    private var currentBannerKey: String? = null
    private val shownPostTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    fun dismissNotificationBanner() {
        val prev = _notificationState.value
        if (prev.lastTitle.isNotEmpty() || prev.lastText.isNotEmpty() || prev.lastPostedEpochMs != 0L) {
            _notificationState.value = prev.copy(
                lastTitle = "",
                lastText = "",
                lastPackage = "",
                lastPostedEpochMs = 0L,
            )
        }
        currentBannerKey = null
    }

    fun startTimer(
        minutes: Int,
        label: String = "Pomodoro",
        wizSunriseEnabled: Boolean = true,
        onSunriseStep: ((brightnessPct: Int) -> Unit)? = null,
    ) {
        val total = (minutes.coerceIn(1, 240) * 60)
        timerJob?.cancel()
        _timerState.value = TimerSnapshot(
            isActive = true,
            label = label,
            remainingSec = total,
            totalSec = total,
            wizSunriseEnabled = wizSunriseEnabled,
            finishedAlertEpochMs = 0L,
        )
        timerJob = timerScope.launch {
            var left = total
            while (isActive && left > 0) {
                delay(1000L)
                left -= 1
                _timerState.value = _timerState.value.copy(remainingSec = left)
                // Gradual WiZ sunrise during the final 30 seconds
                if (wizSunriseEnabled && left in 1..30 && left % 6 == 0) {
                    val progress = ((30 - left) / 30f).coerceIn(0.1f, 1f)
                    val targetBri = (15 + (progress * 85f)).toInt().coerceIn(15, 100)
                    runCatching { onSunriseStep?.invoke(targetBri) }
                }
            }
            if (left == 0) {
                if (wizSunriseEnabled) {
                    runCatching { onSunriseStep?.invoke(100) }
                }
                _timerState.value = _timerState.value.copy(
                    isActive = false,
                    remainingSec = 0,
                    finishedAlertEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerState.value = TimerSnapshot(isActive = false)
    }

    fun updateNotifications(
        activeNotifications: Array<StatusBarNotification>?,
        newlyPosted: StatusBarNotification? = null,
    ) {
        val relevant = activeNotifications?.filter { sbn ->
            !sbn.isOngoing &&
                sbn.packageName != "com.criperto.standby" &&
                sbn.packageName != "android" &&
                sbn.packageName != "com.android.systemui" &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.orEmpty()

        val activeKeys = relevant.map { it.key }.toSet()
        shownPostTimes.keys.retainAll(activeKeys)

        val now = System.currentTimeMillis()
        if (newlyPosted != null &&
            !newlyPosted.isOngoing &&
            newlyPosted.packageName != "com.criperto.standby" &&
            newlyPosted.packageName != "android" &&
            newlyPosted.packageName != "com.android.systemui" &&
            (newlyPosted.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        ) {
            val isFresh = (now - newlyPosted.postTime) in -5_000L..15_000L
            val previousShownTime = shownPostTimes.put(newlyPosted.key, newlyPosted.postTime)
            if (isFresh && previousShownTime != newlyPosted.postTime) {
                val extras = newlyPosted.notification.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (title.isNotEmpty() || text.isNotEmpty()) {
                    currentBannerKey = newlyPosted.key
                    _notificationState.value = NotificationSnapshot(
                        activeCount = relevant.size.coerceAtLeast(1),
                        lastTitle = title,
                        lastText = text,
                        lastPackage = newlyPosted.packageName.substringAfterLast('.'),
                        lastPostedEpochMs = now,
                    )
                    return
                }
            }
        }

        val prev = _notificationState.value
        val bannerRemoved = relevant.isEmpty() || (currentBannerKey != null && currentBannerKey !in activeKeys)
        if (bannerRemoved) {
            currentBannerKey = null
            if (prev.activeCount != relevant.size || prev.lastTitle.isNotEmpty() || prev.lastPostedEpochMs != 0L) {
                _notificationState.value = NotificationSnapshot(
                    activeCount = relevant.size,
                    lastTitle = "",
                    lastText = "",
                    lastPackage = "",
                    lastPostedEpochMs = 0L,
                )
            }
        } else if (prev.activeCount != relevant.size) {
            _notificationState.value = prev.copy(activeCount = relevant.size)
        }
    }

    fun updateFromControllers(controllers: List<MediaController>?) {
        val picked = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull()

        activeController = picked
        if (picked == null) {
            _mediaState.value = MediaSnapshot()
            return
        }

        val meta = picked.metadata
        val pb = picked.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val art: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val posMs = pb?.position ?: 0L
        val durSec = (durationMs / 1000.0).coerceAtLeast(0.0)
        val posSec = (posMs / 1000.0).coerceAtLeast(0.0)
        val progress = if (durSec > 0.1) (posSec / durSec).coerceIn(0.0, 1.0) else 0.0
        val playing = pb?.state == PlaybackState.STATE_PLAYING
        val actions = pb?.actions ?: 0L

        _mediaState.value = MediaSnapshot(
            hasMedia = title.isNotBlank() || artist.isNotBlank() || pb != null,
            isPlaying = playing,
            hasCover = art != null,
            title = title,
            artist = artist,
            progress = progress,
            positionSec = posSec,
            durationSec = durSec,
            canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            albumArt = art,
        )
    }

    fun dispatchCommand(command: String) {
        val tc = activeController?.transportControls ?: return
        val playing = _mediaState.value.isPlaying
        when (command) {
            "play_pause" -> if (playing) tc.pause() else tc.play()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            "play" -> tc.play()
            "pause" -> tc.pause()
        }
    }
}

class StandByNotificationListener : NotificationListenerService() {
    private var sessionManager: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        MediaBridge.updateFromControllers(list)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = mgr
            val cn = ComponentName(this, StandByNotificationListener::class.java)
            mgr.addOnActiveSessionsChangedListener(sessionsListener, cn)
            MediaBridge.updateFromControllers(mgr.getActiveSessions(cn))
            MediaBridge.updateNotifications(activeNotifications, null)
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        runCatching {
            val cn = ComponentName(this, StandByNotificationListener::class.java)
            sessionManager?.getActiveSessions(cn)?.let { MediaBridge.updateFromControllers(it) }
            MediaBridge.updateNotifications(activeNotifications, sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        runCatching {
            MediaBridge.updateNotifications(activeNotifications, null)
        }
    }
}
