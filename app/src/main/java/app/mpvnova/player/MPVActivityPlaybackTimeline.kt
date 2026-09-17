package app.mpvnova.player

import kotlin.math.roundToInt

import android.os.SystemClock
import android.view.KeyEvent

internal fun MPVActivity.seekbarProgressFromMillis(positionMs: Long): Int {
    val scaled = positionMs.coerceAtLeast(0L) * SEEK_BAR_PRECISION / MILLIS_PER_SECOND_LONG
    return scaled.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun MPVActivity.millisFromSeekbarProgress(progress: Int): Long {
    return progress.toLong() * MILLIS_PER_SECOND_LONG / SEEK_BAR_PRECISION
}

internal fun MPVActivity.seekPlaybackFromDpad(deltaMs: Long, baseOnVisibleSeekbar: Boolean = false) {
    val durationMs = psc.duration.coerceAtLeast(0L)
    if (durationMs <= 0L)
        return
    val isNewDpadSeek = pendingDpadSeekPreviewMs == null
    val displayedPositionMs = if (baseOnVisibleSeekbar && binding.playbackSeekbar.max > 0) {
        millisFromSeekbarProgress(binding.playbackSeekbar.progress)
    } else {
        psc.position
    }
    val currentPositionMs = (
        pendingDpadSeekPreviewMs
            ?: pendingSeekbarSeekMs
            ?: displayedPositionMs
    ).coerceAtLeast(0L)
    val newPositionMs = (currentPositionMs + deltaMs).coerceIn(0L, durationMs)
    if (isNewDpadSeek) {
        lastDpadSeekApplyMs = 0L
        lastAppliedSeekMs = Long.MIN_VALUE
    }
    pendingDpadSeekPreviewMs = newPositionMs
    pendingSeekbarSeekMs = newPositionMs
    eventUiHandler.removeCallbacks(commitSeekbarSeekRunnable)
    eventUiHandler.postDelayed(commitSeekbarSeekRunnable, DPAD_SEEK_DEBOUNCE_MS)
    setPlaybackSeekbarProgress(seekbarProgressFromMillis(newPositionMs))
    updatePlaybackTimeline(newPositionMs, forceTextUpdate = true)

    val now = SystemClock.uptimeMillis()
    val playbackHasSettled = firstPlaybackRestartMs > 0L &&
        now - firstPlaybackRestartMs >= PLAYBACK_START_SEEK_SETTLE_MS
    if (playbackHasSettled && now - lastDpadSeekApplyMs >= DPAD_SEEK_APPLY_INTERVAL_MS) {
        lastDpadSeekApplyMs = now
        if (lastAppliedSeekMs != newPositionMs) {
            lastAppliedSeekMs = newPositionMs
            applyPlaybackSeek(newPositionMs)
        }
    }
}

internal fun MPVActivity.scheduleSeekbarSeek(positionMs: Long) {
    pendingSeekbarSeekMs = positionMs
    eventUiHandler.removeCallbacks(commitSeekbarSeekRunnable)
    if (userIsOperatingSeekbar) {
        eventUiHandler.postDelayed(commitSeekbarSeekRunnable, SEEKBAR_SEEK_DEBOUNCE_MS)
    } else {
        commitPendingSeekbarSeek()
    }
}

internal fun MPVActivity.commitPendingSeekbarSeek() {
    val positionMs = pendingSeekbarSeekMs ?: return
    pendingSeekbarSeekMs = null
    pendingDpadSeekPreviewMs = null
    eventUiHandler.removeCallbacks(commitSeekbarSeekRunnable)
    lastDpadSeekApplyMs = 0L
    if (lastAppliedSeekMs != positionMs) {
        lastAppliedSeekMs = positionMs
        applyPlaybackSeek(positionMs)
    }
}

internal fun MPVActivity.seekDeltaFromDpadEvent(ev: KeyEvent): Long {
    val direction = if (ev.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1L else -1L
    // Single press = the user-configurable seek step; holding ramps to the fast-scrub tiers.
    val magnitudeMs = when {
        ev.repeatCount >= SEEK_FAST_REPEAT_THRESHOLD -> SEEK_FAST_STEP_MS
        ev.repeatCount >= SEEK_MEDIUM_REPEAT_THRESHOLD -> SEEK_MEDIUM_STEP_MS
        ev.repeatCount >= SEEK_SLOW_REPEAT_THRESHOLD -> SEEK_SLOW_STEP_MS
        else -> seekStepMs
    }
    return direction * magnitudeMs
}

internal fun MPVActivity.setPlaybackSeekbarProgress(progress: Int) {
    if (binding.playbackSeekbar.progress != progress)
        binding.playbackSeekbar.progress = progress
    lastSeekbarProgress = progress
    lastSeekbarUiUpdateMs = SystemClock.uptimeMillis()
}

/**
 * Updates the seekbar secondary-progress layer from mpv's demuxer cache.
 *
 * mpv reports demuxer-cache-duration as the amount of media currently buffered
 * around the playback point. For the forward-cache visualization we project
 * that duration from the current playback position onto the seekbar timeline.
 * The value is clamped to the media duration so it can never extend beyond
 * the end of a finite file.
 */
internal fun MPVActivity.updatePlaybackBuffer(positionMs: Long = psc.position) {
    if (userIsOperatingSeekbar) return

    val seekbar = binding.playbackSeekbar
    val max = seekbar.max
    val durationMs = psc.duration.coerceAtLeast(0L)

    if (max <= 0 || durationMs <= 0L) {
        if (seekbar.secondaryProgress != 0)
            seekbar.secondaryProgress = 0
        return
    }

    val cacheSeconds = mpvGetPropertyDouble("demuxer-cache-duration")
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0
    val currentPositionMs = positionMs.coerceIn(0L, durationMs)
    val bufferedUntilMs = (currentPositionMs + cacheSeconds * MPV_MILLIS_PER_SECOND_DOUBLE)
        .coerceIn(currentPositionMs.toDouble(), durationMs.toDouble())

    val secondaryProgress = (bufferedUntilMs / durationMs * max.toDouble())
        .roundToInt()
        .coerceIn(0, max)

    if (seekbar.secondaryProgress != secondaryProgress)
        seekbar.secondaryProgress = secondaryProgress
}

internal fun MPVActivity.updatePlaybackTimeline(positionMs: Long, forceTextUpdate: Boolean = false) {
    if (!userIsOperatingSeekbar) updatePlaybackBuffer(positionMs)
    if (!userIsOperatingSeekbar) {
        val progress = seekbarProgressFromMillis(positionMs)
        val now = SystemClock.uptimeMillis()
        val shouldUpdateSeekbar = forceTextUpdate ||
                progress == 0 ||
                progress == binding.playbackSeekbar.max ||
                now - lastSeekbarUiUpdateMs >= PLAYER_SEEKBAR_UI_INTERVAL_MS
        if (shouldUpdateSeekbar && progress != lastSeekbarProgress)
            setPlaybackSeekbarProgress(progress)
    }
    updatePlaybackText((positionMs / MILLIS_PER_SECOND_LONG).toInt().coerceAtLeast(0), force = forceTextUpdate)
}

internal fun MPVActivity.updatePlaybackText(position: Int, force: Boolean = false) {
    if (!force && lastDisplayedPlaybackSecond == position)
        return
    lastDisplayedPlaybackSecond = position
    renderPlayerBarTime(position, psc.durationSec)

    // Skip secondary UI work while scrubbing — decoder is busy with the seek.
    // Clock + "Ends at" panel has its own 30s heartbeat.
    if (!userIsOperatingSeekbar && pendingDpadSeekPreviewMs == null)
        updateStats()
}
