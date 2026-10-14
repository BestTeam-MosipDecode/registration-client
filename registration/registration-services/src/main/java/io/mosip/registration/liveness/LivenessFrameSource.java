package io.mosip.registration.liveness;

import java.io.InputStream;
import java.util.function.LongSupplier;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.LivenessSession;

/**
 * Frame source adapter between the device stream of the client and a liveness session: frames and device errors
 * become {@code submit} and {@code deviceEvent} calls. Frame times come from a monotonic clock, because the engine
 * ends an attempt when they go back.
 */
public class LivenessFrameSource implements StreamFrameListener {

	/** Opens the device stream, for example {@code () -> bioService.getStream(RegistrationConstants.FACE_FULLFACE)}. */
	@FunctionalInterface
	public interface StreamOpener {
		InputStream open() throws RegBaseCheckedException;
	}

	private final LivenessSession session;
	private final LongSupplier clock;

	public LivenessFrameSource(LivenessSession session) {
		this(session, LivenessFrameSource::monotonicMillis);
	}

	LivenessFrameSource(LivenessSession session, LongSupplier clock) {
		this.session = session;
		this.clock = clock;
	}

	/**
	 * Opens the stream. A failure is reported to the session as a device event, then thrown again so that the
	 * controller keeps its own error handling.
	 */
	public InputStream open(StreamOpener opener) throws RegBaseCheckedException {
		InputStream stream;
		try {
			stream = opener.open();
		} catch (RegBaseCheckedException exception) {
			session.deviceEvent(eventFor(exception), clock.getAsLong());
			throw exception;
		}
		if (stream == null) {
			session.deviceEvent(DeviceEvent.CONNECTION_FAILED, clock.getAsLong());
			throw new RegBaseCheckedException(RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorCode(),
					RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorMessage());
		}
		return stream;
	}

	@Override
	public void onFrame(byte[] jpeg) {
		session.submit(jpeg, clock.getAsLong());
	}

	@Override
	public void onStreamInterrupted(Throwable cause) {
		session.deviceEvent(DeviceEvent.STREAM_INTERRUPTED, clock.getAsLong());
	}

	/** No device found answers {@code UNAVAILABLE}; any other failure to open the stream is a connection failure. */
	static DeviceEvent eventFor(RegBaseCheckedException exception) {
		return RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode().equals(exception.getErrorCode())
				? DeviceEvent.UNAVAILABLE
				: DeviceEvent.CONNECTION_FAILED;
	}

	static long monotonicMillis() {
		return System.nanoTime() / 1_000_000L;
	}
}
