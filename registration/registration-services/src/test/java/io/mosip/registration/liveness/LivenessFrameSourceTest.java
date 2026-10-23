/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessFrameSourceTest {

	/** Records what the adapter sends to the session. */
	static final class RecordingSession implements LivenessSession {
		final List<String> calls = new ArrayList<>();
		final List<byte[]> frames = new ArrayList<>();

		@Override
		public void submit(byte[] encodedFrame, long timestampMs) {
			frames.add(encodedFrame);
			calls.add("frame@" + timestampMs);
		}

		@Override
		public void submit(byte[] encodedFrame, long timestampMs, double deviceScore) {
			frames.add(encodedFrame);
			calls.add("frame@" + timestampMs + " device score " + deviceScore);
		}

		@Override
		public void deviceEvent(DeviceEvent event, long timestampMs) {
			calls.add(event + "@" + timestampMs);
		}

		@Override
		public CaptureCheck checkCapture(byte[] encodedImage) {
			throw new UnsupportedOperationException();
		}

		// No @Override: this method comes with face-liveness-pad#70, the test compiles before and after it.
		public CaptureCheck checkCapture(io.mosip.registration.liveness.image.BgrImage capturedImage) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean retry() {
			return false;
		}

		@Override
		public LivenessUpdate current() {
			return null;
		}

		@Override
		public Workflow workflow() {
			return Workflow.RESIDENT;
		}

		@Override
		public void close() {
		}
	}

	private final RecordingSession session = new RecordingSession();
	private final LivenessFrameSource source = new LivenessFrameSource(session, () -> 42L);

	private static RegBaseCheckedException error(RegistrationExceptionConstants constant) {
		return new RegBaseCheckedException(constant.getErrorCode(), constant.getErrorMessage());
	}

	@Test
	public void framesGoToTheSessionWithTheirTime() {
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8 };
		source.onFrame(jpeg);
		assertSame(jpeg, session.frames.get(0));
		assertEquals("frame@42", session.calls.get(0));
	}

	@Test
	public void theScoreOfTheDeviceGoesToTheSessionWithItsFrame() {
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8 };
		source.onFrame(jpeg, 0.25);
		source.onFrame(jpeg, null);
		assertEquals("frame@42 device score 0.25", session.calls.get(0));
		assertEquals("frame@42", session.calls.get(1));
	}

	@Test
	public void anInterruptedStreamIsADeviceEvent() {
		source.onStreamInterrupted(new IOException("unplugged"));
		assertEquals("STREAM_INTERRUPTED@42", session.calls.get(0));
	}

	@Test
	public void anOpenStreamIsReturned() throws RegBaseCheckedException {
		InputStream stream = new ByteArrayInputStream(new byte[0]);
		assertSame(stream, source.open(() -> stream));
		assertTrue(session.calls.isEmpty());
	}

	@Test
	public void aMissingDeviceIsReportedThenRethrown() {
		RegBaseCheckedException missing = error(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND);
		try {
			source.open(() -> {
				throw missing;
			});
			fail("the exception must reach the controller");
		} catch (RegBaseCheckedException exception) {
			assertSame(missing, exception);
		}
		assertEquals("UNAVAILABLE@42", session.calls.get(0));
	}

	@Test
	public void anyOtherFailureIsAConnectionFailure() {
		try {
			source.open(() -> {
				throw error(RegistrationExceptionConstants.MDS_STREAM_ERROR);
			});
			fail("the exception must reach the controller");
		} catch (RegBaseCheckedException exception) {
			assertEquals(RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorCode(), exception.getErrorCode());
		}
		assertEquals("CONNECTION_FAILED@42", session.calls.get(0));
	}

	@Test
	public void aStreamWithoutContentIsAConnectionFailure() {
		try {
			source.open(() -> null);
			fail("a null stream cannot be displayed");
		} catch (RegBaseCheckedException exception) {
			assertEquals(RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorCode(), exception.getErrorCode());
		}
		assertEquals("CONNECTION_FAILED@42", session.calls.get(0));
	}

	@Test
	public void defaultClockIsMonotonic() {
		long first = LivenessFrameSource.monotonicMillis();
		long second = LivenessFrameSource.monotonicMillis();
		assertTrue(second >= first);
		assertTrue(Math.abs(first - System.nanoTime() / 1_000_000L) < 1000);
	}

	@Test
	public void theEndOfTheStreamAfterTheCaptureRequestIsNotReported() {
		source.captureRequested();
		source.onStreamInterrupted(new java.io.EOFException("the device ends its stream after RCAPTURE"));
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8 };
		source.onFrame(jpeg);
		assertEquals(1, session.calls.size());
		assertEquals("frame@42", session.calls.get(0));
	}
}
