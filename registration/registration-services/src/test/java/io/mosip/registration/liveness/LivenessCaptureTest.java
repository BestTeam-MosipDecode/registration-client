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

import io.mosip.registration.liveness.LivenessCapture.ScanDecision;
import io.mosip.registration.liveness.LivenessConfig.OnMaxAttempts;
import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
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

	private LivenessCapture capture(OnMaxAttempts onMaxAttempts) {
		LivenessConfig config = new LivenessConfig(Workflow.RESIDENT, true, onMaxAttempts, LivenessPolicy.defaults());
		LivenessCapture capture = new LivenessCapture(config, screen, iso -> {
			if (iso.length == 0) {
				throw new IllegalArgumentException("not an ISO 19794-5 record");
			}
			return IMAGE;
		});
		capture.attach(session);
		return capture;
	}

	@Test
	public void theFirstScanStartsTheFirstAttempt() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		assertEquals(ScanDecision.LIVENESS, capture.nextScan());
		assertFalse(session.calls.contains("retry"));
	}

	@Test
	public void aScanAfterAFailedAttemptStartsANewOne() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		capture.nextScan();
		session.current = update(SessionState.FAILED, FeedbackCode.FAILED, FailureReason.CHALLENGE_TIMEOUT, true,
				false);
		assertEquals(ScanDecision.LIVENESS, capture.nextScan());
		assertTrue(session.calls.contains("retry"));
	}

	@Test
	public void exhaustedAttemptsFollowThePolicy() {
		LivenessUpdate exhausted = update(SessionState.FAILED, FeedbackCode.MAX_ATTEMPTS_EXCEEDED,
				FailureReason.PAD_ATTACK_DETECTED, false, true);
		LivenessCapture resident = capture(OnMaxAttempts.EXCEPTION);
		session.current = exhausted;
		assertEquals(ScanDecision.EXCEPTION, resident.nextScan());
		LivenessCapture blocked = capture(OnMaxAttempts.BLOCK);
		assertEquals(ScanDecision.BLOCKED, blocked.nextScan());
	}

	@Test
	public void eachScanHasItsOwnFrameSource() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		capture.nextScan();
		capture.captureRequested();
		capture.frameListener().onStreamInterrupted(new EOFException());
		assertFalse("the end of the stream after RCAPTURE is not reported",
				session.calls.contains("STREAM_INTERRUPTED"));
		session.current = update(SessionState.FAILED, FeedbackCode.FAILED, FailureReason.CHALLENGE_FAILED, true,
				false);
		capture.nextScan();
		capture.frameListener().onStreamInterrupted(new EOFException());
		assertTrue(session.calls.contains("STREAM_INTERRUPTED"));
		capture.frameListener().onFrame(new byte[] { 1 });
		assertTrue(session.calls.contains("frame"));
	}

	@Test
	public void thePassIsAnnouncedOncePerAttempt() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		capture.nextScan();
		LivenessUpdate passed = update(SessionState.PASSED, FeedbackCode.HOLD_STILL, null, false, false);
		capture.listener().onUpdate(passed);
		capture.listener().onUpdate(passed);
		assertEquals(1, screen.passed);
		assertEquals(2, screen.updates.size());
		session.current = update(SessionState.FAILED, FeedbackCode.FAILED, FailureReason.FACE_CONTINUITY_LOST, true,
				false);
		capture.nextScan();
		capture.listener().onUpdate(passed);
		assertEquals(2, screen.passed);
	}

	@Test
	public void theCapturedRecordIsConvertedThenChecked() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		assertTrue(capture.checkCapture(new byte[] { 1, 2, 3 }).accepted());
		assertSame(IMAGE, session.checked);
	}

	@Test
	public void aRecordThatCannotBeConvertedIsRefused() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		CaptureCheck check = capture.checkCapture(new byte[0]);
		assertFalse(check.accepted());
		assertNull(session.checked);
		assertFalse(capture.checkCapture(null).accepted());
	}

	@Test
	public void aFailedCaptureRequestEndsTheAttempt() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		capture.nextScan();
		capture.captureFailed();
		assertTrue(session.calls.contains("CONNECTION_FAILED"));
	}

	@Test
	public void anAcceptedCaptureIsReported() {
		LivenessCapture capture = capture(OnMaxAttempts.EXCEPTION);
		assertFalse(capture.isAccepted());
		session.current = new LivenessUpdate(SessionState.PASSED, FeedbackCode.SUCCESS, null, null, 1, 1, 3, null,
				false, true, null);
		assertTrue(capture.isAccepted());
		capture.close();
		assertTrue(session.calls.contains("close"));
	}
}
