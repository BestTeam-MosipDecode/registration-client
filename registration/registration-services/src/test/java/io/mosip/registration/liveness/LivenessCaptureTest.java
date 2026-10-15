package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.image.BgrImage;

public class LivenessCaptureTest {

	/** A session whose state the test sets, recording the calls of the capture. */
	static final class FakeSession implements LivenessSession {
		LivenessUpdate current = update(SessionState.INIT, FeedbackCode.CHECKING, null, false, false);
		final List<String> calls = new ArrayList<>();
		BgrImage checked;

		@Override
		public void submit(byte[] encodedFrame, long timestampMs) {
			calls.add("frame");
		}

		@Override
		public void deviceEvent(DeviceEvent event, long timestampMs) {
			calls.add(event.name());
		}

		@Override
		public CaptureCheck checkCapture(byte[] encodedImage) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CaptureCheck checkCapture(BgrImage capturedImage) {
			checked = capturedImage;
			calls.add("check");
			return capturedImage == null ? CaptureCheck.rejected(FailureReason.INVALID_FRAME, "1.1.0")
					: CaptureCheck.accepted("1.1.0");
		}

		@Override
		public boolean retry() {
			calls.add("retry");
			current = update(SessionState.INIT, FeedbackCode.CHECKING, null, false, false);
			return true;
		}

		@Override
		public LivenessUpdate current() {
			return current;
		}

		@Override
		public Workflow workflow() {
			return Workflow.RESIDENT;
		}

		@Override
		public void close() {
			calls.add("close");
		}
	}

	/** Records what the screen receives. */
	static final class Screen implements LivenessCapture.Callbacks {
		final List<LivenessUpdate> updates = new ArrayList<>();
		int passed;

		@Override
		public void onUpdate(LivenessUpdate update) {
			updates.add(update);
		}

		@Override
		public void onPassed() {
			passed++;
		}
	}

	static LivenessUpdate update(SessionState state, FeedbackCode feedback, FailureReason reason, boolean retry,
			boolean finished) {
		return new LivenessUpdate(state, feedback, null, null, 0, 1, 3, reason, retry, finished, null);
	}

	private static final BgrImage IMAGE = new BgrImage(2, 2, new byte[12]);

	private final FakeSession session = new FakeSession();
	private final Screen screen = new Screen();

	private LivenessCapture capture() {
		LivenessCapture capture = new LivenessCapture(screen, iso -> {
			if (iso.length == 0) {
				throw new IllegalArgumentException("not an ISO 19794-5 record");
			}
			return IMAGE;
		});
		capture.attach(session);
		return capture;
	}

	@Test
	public void framesAndInterruptionsGoToTheSession() {
		LivenessCapture capture = capture();
		capture.frameListener().onFrame(new byte[] { 1 });
		capture.frameListener().onStreamInterrupted(new EOFException());
		assertTrue(session.calls.contains("frame"));
		assertTrue(session.calls.contains("STREAM_INTERRUPTED"));
	}

	@Test
	public void theEndOfTheStreamAfterTheCaptureRequestIsNotReported() {
		LivenessCapture capture = capture();
		assertFalse(capture.isCaptureRequested());
		capture.captureRequested();
		assertTrue(capture.isCaptureRequested());
		capture.frameListener().onStreamInterrupted(new EOFException());
		assertFalse(session.calls.contains("STREAM_INTERRUPTED"));
	}

	@Test
	public void thePassIsAnnouncedOnce() {
		LivenessCapture capture = capture();
		LivenessUpdate passed = update(SessionState.PASSED, FeedbackCode.HOLD_STILL, null, false, false);
		capture.listener().onUpdate(passed);
		capture.listener().onUpdate(passed);
		assertEquals(1, screen.passed);
		assertEquals(2, screen.updates.size());
	}

	@Test
	public void theCapturedRecordIsConvertedThenChecked() {
		LivenessCapture capture = capture();
		assertTrue(capture.checkCapture(new byte[] { 1, 2, 3 }).accepted());
		assertSame(IMAGE, session.checked);
	}

	@Test
	public void aRecordThatCannotBeConvertedIsRefused() {
		LivenessCapture capture = capture();
		CaptureCheck check = capture.checkCapture(new byte[0]);
		assertFalse(check.accepted());
		assertNull(session.checked);
		assertFalse(capture.checkCapture(null).accepted());
	}

	@Test
	public void abortingEndsAnAttemptInProgressBeforeClosing() {
		LivenessCapture capture = capture();
		session.current = update(SessionState.ACTIVE_CHALLENGE, FeedbackCode.CHALLENGE, null, false, false);
		assertTrue(capture.abort());
		assertEquals(java.util.Arrays.asList("STREAM_INTERRUPTED", "retry", "close"), session.calls);
	}

	@Test
	public void abortingBeforeAnyFrameUsesNoAttempt() {
		LivenessCapture capture = capture();
		assertFalse(capture.abort());
		assertEquals(java.util.Collections.singletonList("close"), session.calls);
	}

	@Test
	public void abortingAfterTheEndOfTheAttemptOnlyCloses() {
		LivenessCapture capture = capture();
		session.current = update(SessionState.FAILED, FeedbackCode.FAILED, FailureReason.CHALLENGE_TIMEOUT, true,
				false);
		assertFalse(capture.abort());
		assertEquals(java.util.Collections.singletonList("close"), session.calls);
	}

	@Test
	public void anAcceptedCaptureIsReported() {
		LivenessCapture capture = capture();
		assertFalse(capture.isAccepted());
		session.current = new LivenessUpdate(SessionState.PASSED, FeedbackCode.SUCCESS, null, null, 1, 1, 3, null,
				false, true, null);
		assertTrue(capture.isAccepted());
		capture.close();
		assertTrue(session.calls.contains("close"));
	}
}
