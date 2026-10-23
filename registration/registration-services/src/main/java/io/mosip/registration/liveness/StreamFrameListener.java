/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

/**
 * Receives the frames of a device stream as they are read, for face liveness. Called on the streaming thread; an
 * implementation must return quickly and never throw.
 */
public interface StreamFrameListener {

	/** JPEG bytes of one frame, as received from the device. */
	void onFrame(byte[] jpeg);

	/**
	 * One frame with the passive liveness score that the device computed for it and sent in the
	 * {@code X-Liveness-Score} header of the stream, or null when the device sent none.
	 */
	default void onFrame(byte[] jpeg, Double livenessScore) {
		onFrame(jpeg);
	}

	/** The stream stopped without being closed by the client: device unplugged or connection lost. */
	void onStreamInterrupted(Throwable cause);
}
