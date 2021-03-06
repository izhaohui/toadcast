package com.vaguehope.toadcast;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.litvak.chromecast.api.v2.ChromeCast;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEvent;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEventListener;
import su.litvak.chromecast.api.v2.Media;
import su.litvak.chromecast.api.v2.MediaStatus;
import su.litvak.chromecast.api.v2.MediaStatus.IdleReason;
import su.litvak.chromecast.api.v2.MediaStatus.PlayerState;
import su.litvak.chromecast.api.v2.Status;

import com.vaguehope.toadcast.transcode.Transcoder;
import com.vaguehope.toadcast.util.Timestamped;

public class GoalSeeker implements Runnable, ChromeCastSpontaneousEventListener {

	private static final long POLL_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);

	/**
	 * If nothing happy back from ChromeCast in the time, start everything over.
	 * TODO Is this too long?
	 */
	private static final long GIVEUP_AND_REDISCOVER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

	/**
	 * If there should be something playing but there is not, wait at least this long before trying to play it again.
	 * This allows time for session end event to arrive and be processed.
	 */
	private static final int WAIT_FOR_MEDIA_SESSION_END_TIMEOUT_SECONDS = 5;

	/**
	 * Only try and restore position if played at least this far though.
	 */
	private static final int MIN_POSITION_TO_RESTORE_SECONDS = 1;

	private static final Set<IdleReason> GOAL_REACHED_IF_IDLE_REASONS = EnumSet.of(IdleReason.CANCELLED, IdleReason.INTERRUPTED, IdleReason.FINISHED, IdleReason.ERROR, IdleReason.COMPLETED);

	private static final Logger LOG = LoggerFactory.getLogger(GoalSeeker.class);

	private final ChromeCastHolder chromecastHolder;
	private final CastFinder castFinder;
	private final Transcoder transcoder;

	// Reliability tracking.
	private volatile long lastSuccessTime;

	// Where we are.
	private volatile Timestamped<MediaStatus> currentMediaStatus;
	private volatile long ourMediaSessionId;
	private final BlockingQueue<Object> eventQueue = new LinkedBlockingQueue<>();

	// Where we want to be.
	private volatile PlayingState targetPlayingStateRequested = null; // As asked for.
	private volatile PlayingState targetPlayingStateAdapted = null; // May include transcode.
	private volatile boolean targetPaused = false;
	private volatile double seekToSeconds = -1;

	// Recovery info.
	private volatile Timestamped<Double> lastObservedPosition;
	private volatile Double lastObservedPositionToRestore;

	public GoalSeeker (final ChromeCastHolder chromecastHolder, final CastFinder castFinder, final Transcoder transcoder) {
		this.chromecastHolder = chromecastHolder;
		this.transcoder = transcoder;
		this.chromecastHolder.addEventListener(this);
		this.castFinder = castFinder;
		setCurrentMediaStatus(null);
		setLastObservedPosition(0);
	}

	@Override
	public void run () {
		try {
			while (true) {
				poll();
			}
		}
		finally {
			LOG.warn("Ended.");
		}
	}

	private void poll () {
		try {
			readEventQueue(); // Blocks / rate limits.
			connectAndReadStateAndSeekGoal();
		}
		catch (final Exception e) {
			LOG.warn("Unhandled error while reading / writing ChromeCast state.", e);
			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(10));// Rate limit errors.
			}
			catch (final InterruptedException e1) {/* Unused. */}
		}
	}

	private void readEventQueue () {
		try {
			long timeoutMillis = POLL_INTERVAL_MILLIS;
			Object obj;
			while ((obj = this.eventQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS)) != null) {
				timeoutMillis = 0L; // If something happened, stop sleeping.
				if (obj instanceof Boolean) {
					// For short-circuiting timeout after goal change.
				}
				else if (obj instanceof Long) {
					this.seekToSeconds = (Long) obj;
				}
				else if (obj instanceof MediaStatus) {
					onEventMediaStatus((MediaStatus) obj);
				}
				else {
					LOG.warn("Unexpected {} type on event queue: {}", obj.getClass(), obj);
				}
			}
		}
		catch (final InterruptedException e) {/* Unused. */ }
	}

	/**
	 * NOTE: Pushed MEDIA_STATUS objects are incomplete and not suitable for general goal seeking.
	 */
	private void onEventMediaStatus (final MediaStatus status) {
		if (status.mediaSessionId == this.ourMediaSessionId) {
			if (GOAL_REACHED_IF_IDLE_REASONS.contains(status.idleReason)) {
				LOG.info("Goal for mediaSessionId={} reached by idle reason: {}", this.ourMediaSessionId, status.idleReason);
				setTargetPlayingState(null);
				setLastObservedPosition(0);
				this.ourMediaSessionId = -2; // Session is over.
			}
			else if (status.playerState == PlayerState.PLAYING) {
				final Double lop = this.lastObservedPositionToRestore;
				if (lop != null) {
					this.seekToSeconds = lop;
					this.lastObservedPositionToRestore = null; // Safety.
					LOG.info("Requested restore position to {}s.", lop);
				}
			}
		}
		else {
			LOG.warn("Unexpected spontaneous media status: mediaSessionId={} playerState={} idleReason={}",
					status.mediaSessionId, status.playerState, status.idleReason);
		}
	}

	private void connectAndReadStateAndSeekGoal () throws IOException {
		final ChromeCast c = this.chromecastHolder.get();
		if (c == null) {
			markLastSuccess(); // Did nothing successfully.
			return;
		}

		if (!c.isConnected()) {
			try {
				c.disconnect();
				c.connect();
				LOG.info("Connected to ChromeCast {}.", c.getAddress());
				markLastSuccess();
			}
			catch (final Exception e) {
				LOG.warn("Failed to connect: {}", e.toString());
				checkNoSuccessTimeout(c);
				return;
			}
		}

		try {
			readStatusAndSeekGoal(c);
			markLastSuccess();
		}
		catch (NotConnectedExecption | NoResponseException e) {
			LOG.warn("ChromeCast connection error: {}", e.toString());
			checkNoSuccessTimeout(c);
		}
	}

	private void markLastSuccess () {
		this.lastSuccessTime = System.currentTimeMillis();
	}

	private void checkNoSuccessTimeout (final ChromeCast c) throws IOException {
		final long millisSinceLastSuccess = System.currentTimeMillis() - this.lastSuccessTime;
		if (millisSinceLastSuccess > GIVEUP_AND_REDISCOVER_TIMEOUT_MILLIS) {
			if (this.chromecastHolder.compareAndSet(c, null)) {
				LOG.warn("Abandoning non-responsive ChromeCast {} after {}s, re-discovering...",
						c.getAddress(), TimeUnit.MILLISECONDS.toSeconds(millisSinceLastSuccess));
				try {
					c.disconnect();
				}
				finally {
					this.castFinder.rediscover();
				}
			}
		}
	}

	private void readStatusAndSeekGoal (final ChromeCast c) throws IOException {
		final Status status = CastHelper.readStatus(c);

		final MediaStatus mStatus;
		if (CastHelper.isRunningDefaultApp(status)) {
			mStatus = CastHelper.readMediaStatus(c);
		}
		else {
			mStatus = null;
		}
		setCurrentMediaStatus(mStatus);

		seekGoal(c, status, mStatus);
	}

	private void seekGoal (final ChromeCast c, final Status cStatus, final MediaStatus cMStatus) throws IOException {
		// Capture target.
		final PlayingState tState = this.targetPlayingStateAdapted;
		final boolean tPaused = this.targetPaused;

		if (tState == null && !CastHelper.isRunningDefaultApp(cStatus)) {
			return; // If we do not have a target and default app is not running, do not mess with anything.
		}

		// Get things ready to compare.
		final Media cMedia = cMStatus != null ? cMStatus.media : null;
		final String cUrl = cMedia != null ? StringUtils.trimToNull(cMedia.url) : null;
		final String tUri = tState != null ? StringUtils.trimToNull(tState.getMediaUri()) : null;
		final PlayerState cState = cMStatus != null ? cMStatus.playerState : null;

		// Should stop?
		if (tState == null || tUri == null) {
			if (cState != null) {
				switch (cState) {
					case BUFFERING:
					case PLAYING:
						c.pause();
						LOG.info("Stopped.");
					default:
				}
			}
			return; // Target state reached.  Stop.
		}

		// Got right URI?
		if (!Objects.equals(cUrl, tUri)) {
			if (tPaused) return; // We would load, but will wait until not paused before doing so.

			// If mediaSessionId is still set, ChromeCast session ended event might still be en-route.
			// If age of last observed position is too young, wait a bit in case end event turns up.
			final Timestamped<Double> lop = this.lastObservedPosition;
			if (this.ourMediaSessionId > 0 && lop.age(TimeUnit.SECONDS) < WAIT_FOR_MEDIA_SESSION_END_TIMEOUT_SECONDS) return;

			try {
				CastHelper.readyChromeCast(c, cStatus);
			}
			catch (final ChromeCastInUseException e) {
				LOG.warn("Failed to ready ChromeCast, going to paused: {}", e.toString());
				this.targetPaused = true;
				return; // Made a change, so return.
			}

			final Media mediaToLoad = tState.toChromeCastMedia();
			LOG.info("media={} state={}, loading {} ...", cMedia, cState, mediaToLoad);
			final MediaStatus afterLoad = c.load(mediaToLoad);
			if (afterLoad != null) {
				this.ourMediaSessionId = afterLoad.mediaSessionId;
				setCurrentMediaStatus(afterLoad);
			}
			else {
				this.ourMediaSessionId = -2;
			}
			LOG.info("Loaded {} (mediaSessionId={}).", mediaToLoad, this.ourMediaSessionId);

			if (lop.get() > MIN_POSITION_TO_RESTORE_SECONDS) {
				this.lastObservedPositionToRestore = lop.get();
			}
			else {
				this.lastObservedPositionToRestore = null; // In case something left over.
			}

			return; // Made a change, so return.
		}

		// Should resume / pause?
		if (tPaused) {
			if (cState == PlayerState.PLAYING) {
				c.pause();
				LOG.info("Paused.");
				return; // Made a change, so return.
			}
		}
		else {
			if (cState == PlayerState.PAUSED) {
				c.play();
				LOG.info("Resumed.");
				return; // Made a change, so return.
			}
		}

		// Check and set should be safe as only our thread should be updating it.
		if (this.seekToSeconds >= 0) {
			c.seek(this.seekToSeconds);
			LOG.info("Set position to {}s.", this.seekToSeconds);
			this.seekToSeconds = -1;
		}

		setLastObservedPosition(cMStatus.currentTime);
	}

	private PlayingState transcodeIfRequired (final PlayingState tState) {
		if (tState == null) return null;

		final String uri = StringUtils.trimToNull(tState.getMediaUri());
		if (uri == null) return null;

		if (this.transcoder != null && this.transcoder.transcodeRequired(tState.getContentType())) {
			return this.transcoder.transcode(tState);
		}

		return tState;
	}

	public boolean isChromeCastFound() {
		return this.chromecastHolder.get() != null;
	}

	/**
	 * @param newStatus
	 *            May be null.
	 */
	private void setCurrentMediaStatus (final MediaStatus newStatus) {
		this.currentMediaStatus = new Timestamped<>(newStatus);
	}

	/**
	 * Will not return null.
	 */
	public Timestamped<MediaStatus> getCurrentMediaStatus () {
		return this.currentMediaStatus;
	}

	private void setTargetPlayingState (final PlayingState playingState) {
		this.targetPlayingStateRequested = playingState;
		this.targetPlayingStateAdapted = transcodeIfRequired(playingState);
	}

	/**
	 * Exactly what was asked for.
	 */
	public PlayingState getTargetPlayingStateRequested () {
		return this.targetPlayingStateRequested;
	}

	/**
	 * What is send to Chomecast, e.g. may include transcode.
	 */
	public PlayingState getTargetPlayingStateAdapted () {
		return this.targetPlayingStateAdapted;
	}

	public boolean isTargetPaused () {
		return this.targetPaused;
	}

	public void setLastObservedPosition (final double position) {
		this.lastObservedPosition = new Timestamped<Double>(position);
	}

	public void gotoPlaying (final PlayingState playingState) {
		setLastObservedPosition(0); // Set before state.
		setTargetPlayingState(playingState);
		this.targetPaused = false;
		this.eventQueue.offer(Boolean.TRUE);
	}

	public void gotoPaused () {
		this.targetPaused = true;
		this.eventQueue.offer(Boolean.TRUE);
	}

	public void gotoResumed () {
		this.targetPaused = false;
		this.eventQueue.offer(Boolean.TRUE);
	}

	public void gotoStopped () {
		setTargetPlayingState(null);
		setLastObservedPosition(0); // Set after state.
		this.eventQueue.offer(Boolean.TRUE);
	}

	public void seek (final long targetSeconds) {
		this.eventQueue.offer(Long.valueOf(targetSeconds));
	}

	@Override
	public void spontaneousEventReceived (final ChromeCastSpontaneousEvent event) {
		switch (event.getType()) {
			case MEDIA_STATUS:
				onSpontaneousMediaStatus(event.getData(MediaStatus.class));
				break;
			case STATUS:
				onSpontaneousStatus(event.getData(Status.class));
				break;
			default:
				onUnidentifiedSpontaneousEvent(event.getData());
		}
	}

	private void onSpontaneousMediaStatus (final MediaStatus mediaStatus) {
		LOG.info("Spontaneous media status: mediaSessionId={} playerState={} idleReason={}",
				mediaStatus.mediaSessionId, mediaStatus.playerState, mediaStatus.idleReason);
		try {
			this.eventQueue.put(mediaStatus);
		}
		catch (final InterruptedException e) {
			LOG.warn("Interupted while trying to enqueue spontaneous media status: {}", mediaStatus);
		}
	}

	private static void onSpontaneousStatus (final Status status) {
		LOG.debug("Spontaneous status: {}", status);
	}

	private static void onUnidentifiedSpontaneousEvent (final Object event) {
		LOG.debug("Spontaneous event: {}", event);
	}

}
