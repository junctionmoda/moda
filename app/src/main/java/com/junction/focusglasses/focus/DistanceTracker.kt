package com.junction.focusglasses.focus

/**
 * Viewing distance to one kind of work object (screen or book), from how big it looks to the
 * glasses camera. Apparent size ∝ 1 / distance, so size / baseline = how many times closer the
 * wearer is than when the session started.
 *
 * Measured on Ray-Ban Meta Gen 2 with a 14" laptop: normal sitting distance gives a size of
 * 0.52–0.68, leaning in gives 0.78–1.0, but up close the detector often loses the screen or
 * boxes only part of it. So each sighting is a vote (close / not close), and the state comes from
 * the recent votes rather than one smoothed number. Frames without a sighting do not vote.
 *
 * @param absCloseSize an apparent size that counts as too close regardless of the baseline (the
 *   glasses FOV is fixed, so for a laptop this is meaningful on its own). Sightings this big are
 *   also kept out of the baseline, so starting a session while leaning in does not poison it.
 */
class DistanceTracker(private val absCloseSize: Float? = null) {
  companion object {
    const val CALIBRATION_SAMPLES = 6
    private const val WINDOW_MS = 6_000L
    private const val MIN_VOTES = 3
    private const val CLOSE_SHARE = 0.6f
    /** No sighting for this long: forget the close state instead of carrying it over. */
    private const val STALE_MS = 10_000L
  }

  private val calibration = mutableListOf<Float>()
  var baseline: Float? = null
    private set
  private var ema: Float? = null
  private val votes = ArrayDeque<Pair<Long, Boolean>>()
  private var lastSeenAt = 0L
  /** elapsedRealtime since when the wearer has been too close, 0 if not. */
  var closeSince = 0L
  var lastAlertAt = 0L
  /** Current too-close state. */
  var close = false
    private set

  val calibrating: Boolean get() = baseline == null

  fun reset() {
    calibration.clear()
    baseline = null
    ema = null
    votes.clear()
    lastSeenAt = 0
    closeSince = 0
    close = false
  }

  /** Feeds one sighting; returns the smoothed ratio to the baseline once calibrated. */
  fun add(size: Float, now: Long, closeRatio: Float): Float? {
    lastSeenAt = now
    val smoothed = ema?.let { it * 0.5f + size * 0.5f } ?: size
    ema = smoothed
    val absClose = absCloseSize != null && size >= absCloseSize
    if (baseline == null && !absClose) {
      calibration += size
      if (calibration.size >= CALIBRATION_SAMPLES) baseline = calibration.sorted()[calibration.size / 3]
    }
    val base = baseline
    val vote = absClose || (base != null && size / base >= closeRatio)
    votes.addLast(now to vote)
    return base?.let { smoothed / it }
  }

  /** Re-evaluates [close] / [closeSince] from the recent votes; call every frame. */
  fun update(now: Long) {
    while (votes.isNotEmpty() && now - votes.first().first > WINDOW_MS) votes.removeFirst()
    if (votes.size >= MIN_VOTES) {
      close = votes.count { it.second } >= votes.size * CLOSE_SHARE
    } else if (now - lastSeenAt > STALE_MS) {
      close = false
    } // else: too few sightings to tell (often because we are so close the detector lost it): keep state
    if (close) {
      if (closeSince == 0L) closeSince = now
    } else {
      closeSince = 0
    }
  }
}
