/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.image.BgrImage;
import io.mosip.registration.liveness.platform.desktop.ImageIoFrameDecoder;

/**
 * One scan gated on liveness: one session of the engine, one attempt. The attempts are counted across scans by the
 * client and the session only gets the attempts left (see {@link LivenessService}). The screen is told when liveness
 * is established, once, and only then sends the capture request (decision-flow sections 6 and 7).
 */
public class LivenessCapture implements AutoCloseable {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessCapture.class);

	/** Screen side. Both methods are called on the thread of the engine: move to the UI thread before any UI work. */
	public interface Callbacks {

		void onUpdate(LivenessUpdate update);

		/** Liveness is established: send the capture request now, off the engine thread. */
		void onPassed();
	}

	/** Turns the ISO/IEC 19794-5 record returned by RCAPTURE into pixels. */
	@FunctionalInterface
	public interface ImageConverter {
		BgrImage convert(byte[] isoRecord) throws Exception;
	}

	private final Callbacks screen;
	private final ImageConverter converter;
	private final AtomicBoolean passAnnounced = new AtomicBoolean();
	private final LivenessEventListener listener = new LivenessEventListener() {
		@Override
		public void onUpdate(LivenessUpdate update) {
			screen.onUpdate(update);
			if (update.state() == SessionState.PASSED && update.feedback() == FeedbackCode.HOLD_STILL
					&& passAnnounced.compareAndSet(false, true)) {
				screen.onPassed();
			}
		}
	};
	private volatile LivenessSession session;
	private volatile LivenessFrameSource frameSource;
	private volatile boolean captureRequested;

	public LivenessCapture(Callbacks screen, ImageConverter converter) {
		this.screen = screen;
		this.converter = converter;
	}

	/** Listener to give to the session, wrapped in the audit listener. */
	LivenessEventListener listener() {
		return listener;
	}

	void attach(LivenessSession session) {
		this.session = session;
		this.frameSource = new LivenessFrameSource(session);
	}

	/** Opens the device stream; a failure is reported to the session, then thrown as before. */
	public InputStream open(LivenessFrameSource.StreamOpener opener) throws RegBaseCheckedException {
		return frameSource.open(opener);
	}

	/** To set on the {@code Streamer} after any stop and before {@code startStream}. */
	public StreamFrameListener frameListener() {
		return frameSource;
	}

	/** To call when the capture request is sent: the end of the stream is then no longer an interruption. */
	public void captureRequested() {
		captureRequested = true;
		frameSource.captureRequested();
	}

	public boolean isCaptureRequested() {
		return captureRequested;
	}

	/**
	 * Ends the scan. An attempt still in progress (stream stopped, capture request failed, scan abandoned) ends as an
	 * interruption of the stream and counts, as section 7 of the decision flow asks; then the session is closed. Waits
	 * for the engine: call it off the UI thread.
	 *
	 * @return true when an attempt was in progress
	 */
	public boolean abort() {
		LivenessUpdate current = session.current();
		boolean inProgress = current.state() != SessionState.INIT && current.state() != SessionState.FAILED
				&& !current.finished();
		if (inProgress) {
			session.deviceEvent(DeviceEvent.STREAM_INTERRUPTED, LivenessFrameSource.monotonicMillis());
			// A check runs on the worker after the event above: it returns once the attempt has failed and its
			// listeners (screen, audit) have been called. On a failed attempt it is refused and changes nothing.
			session.checkCapture((BgrImage) null);
		}
		session.close();
		return inProgress;
	}

	/**
	 * Checks the record returned by RCAPTURE before it is stored. A record that cannot be converted is refused
	 * ({@code INVALID_FRAME}). Blocks up to 5 seconds: call it off the UI thread.
	 */
	public CaptureCheck checkCapture(byte[] isoRecord) {
		BgrImage image = null;
		if (isoRecord != null) {
			try {
				image = converter.convert(isoRecord);
			} catch (Exception exception) {
				LOGGER.error("The captured face record could not be converted", exception);
			}
		}
		return session.checkCapture(image);
	}

	/**
	 * Checks the face records returned by RCAPTURE: there must be exactly one, since every record of the response is
	 * stored and only a checked one may be. Otherwise the attempt ends as an invalid capture.
	 */
	public CaptureCheck checkCaptures(List<byte[]> isoRecords) {
		if (isoRecords == null || isoRecords.size() != 1) {
			LOGGER.error("Face capture refused: {} face records instead of one", isoRecords == null ? 0 : isoRecords.size());
			return session.checkCapture((BgrImage) null);
		}
		return checkCapture(isoRecords.get(0));
	}

	/** The captured image was accepted: a new scan needs a new capture. */
	public boolean isAccepted() {
		LivenessUpdate current = session.current();
		return current.state() == SessionState.PASSED && current.feedback() == FeedbackCode.SUCCESS;
	}

	public LivenessUpdate current() {
		return session.current();
	}

	@Override
	public void close() {
		session.close();
	}

	/** RCAPTURE record to pixels, with the MOSIP face decoder the client uses to display the captured face. */
	public static BgrImage isoToImage(byte[] isoRecord) throws Exception {
		ConvertRequestDto request = new ConvertRequestDto();
		request.setVersion("ISO19794_5_2011");
		request.setInputBytes(isoRecord);
		return ImageIoFrameDecoder.toBgr(FaceDecoder.convertFaceISOToBufferedImage(request));
	}
}
