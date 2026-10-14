package io.mosip.registration.liveness;

/**
 * Receives the frames of a device stream as they are read, for face liveness. Called on the streaming thread; an
 * implementation must return quickly and never throw.
 */
public interface StreamFrameListener {

	/** JPEG bytes of one frame, as received from the device. */
	void onFrame(byte[] jpeg);

	/** The stream stopped without being closed by the client: device unplugged or connection lost. */
	void onStreamInterrupted(Throwable cause);
}
