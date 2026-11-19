package com.criperto.standby.standby

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaBridge {
    private val _mediaState = MutableStateFlow(MediaSnapshot())
    val mediaState: StateFlow<MediaSnapshot> = _mediaState.asStateFlow()

    @Volatile
    private var activeController: MediaController? = null

    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
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
        }
    }
}
