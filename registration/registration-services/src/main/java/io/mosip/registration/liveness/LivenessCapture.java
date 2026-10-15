package io.mosip.registration.liveness;

import java.io.InputStream;
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
 * One face capture or authentication gated on liveness. Its session keeps the attempt count between two scans; each
 * scan gets its own frame source. The screen is told when liveness is established, once per attempt, and only then
 * sends the capture request (decision-flow sections 6 and 7).
 */
public class LivenessCapture implements AutoCloseable {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessCapture.class);

	/** What the next scan does. */
	public enum ScanDecision {
		/** Run the liveness check, a new attempt after a failed one. */
		LIVENESS,
		/** Attempts exhausted, resident: capture once more without a liveness decision (exception process). */
		EXCEPTION,
		/** Attempts exhausted: no face is accepted. */
		BLOCKED
	}

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

	private final LivenessConfig config;
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

	public LivenessCapture(LivenessConfig config, Callbacks screen, ImageConverter converter) {
		this.config = config;
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

	public LivenessConfig getConfig() {
		return config;
	}

	/** To call on each scan, before the stream is opened. */
	public ScanDecision nextScan() {
		LivenessUpdate current = session.current();
		if (current.feedback() == FeedbackCode.MAX_ATTEMPTS_EXCEEDED) {
			return config.getOnMaxAttempts() == LivenessConfig.OnMaxAttempts.EXCEPTION ? ScanDecision.EXCEPTION
					: ScanDecision.BLOCKED;
		}
		if (current.state() == SessionState.FAILED && current.retryAvailable()) {
			session.retry();
		}
		passAnnounced.set(false);
		frameSource = new LivenessFrameSource(session);
		return ScanDecision.LIVENESS;
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
		frameSource.captureRequested();
	}

	/** The capture request failed: the attempt ends with a device error. */
	public void captureFailed() {
		session.deviceEvent(DeviceEvent.CONNECTION_FAILED, LivenessFrameSource.monotonicMillis());
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
