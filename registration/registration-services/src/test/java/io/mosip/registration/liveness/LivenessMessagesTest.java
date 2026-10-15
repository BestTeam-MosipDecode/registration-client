package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.liveness.active.ChallengeType;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.GuidanceTip;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessMessagesTest {

	private static LivenessUpdate update(SessionState state, FeedbackCode feedback, GuidanceTip tip,
			ChallengeType challenge, FailureReason reason, boolean retry) {
		return new LivenessUpdate(state, feedback, tip, challenge, 0.5, 1, 3, reason, retry, false, null);
	}

	private static List<String> keys(LivenessUpdate update) {
		return LivenessMessages.keys(update, Workflow.RESIDENT);
	}

	@Test
	public void guidanceDuringTheChecks() {
		assertEquals(Collections.singletonList("LIVENESS_CHECKING"),
				keys(update(SessionState.PASSIVE_CHECK, FeedbackCode.CHECKING, null, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_NO_FACE"),
				keys(update(SessionState.PASSIVE_CHECK, FeedbackCode.NO_FACE, null, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_MULTIPLE_FACES"),
				keys(update(SessionState.PASSIVE_CHECK, FeedbackCode.MULTIPLE_FACES, null, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_TIP_LIGHTING"), keys(
				update(SessionState.PASSIVE_CHECK, FeedbackCode.LOW_QUALITY, GuidanceTip.LIGHTING, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_HOLD_STILL"), keys(update(SessionState.PASSIVE_CHECK,
				FeedbackCode.LOW_QUALITY, GuidanceTip.HOLD_STILL, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_HOLD_STILL"),
				keys(update(SessionState.ACTIVE_CHALLENGE, FeedbackCode.HOLD_STILL, null, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_CHALLENGE_TURN_LEFT"), keys(update(
				SessionState.ACTIVE_CHALLENGE, FeedbackCode.CHALLENGE, null, ChallengeType.TURN_LEFT, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_KEEP_GOING"), keys(update(SessionState.ACTIVE_CHALLENGE,
				FeedbackCode.KEEP_GOING, null, ChallengeType.BLINK, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_ACTION_DETECTED"), keys(update(
				SessionState.ACTIVE_CHALLENGE, FeedbackCode.ACTION_DETECTED, null, ChallengeType.BLINK, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_SUCCESS"),
				keys(update(SessionState.PASSED, FeedbackCode.SUCCESS, null, null, null, false)));
		assertEquals(Collections.singletonList("LIVENESS_DEVICE_UNAVAILABLE"),
				keys(update(SessionState.INIT, FeedbackCode.DEVICE_ERROR, null, null, null, false)));
	}

	@Test
	public void anAttackShowsOnlyTheGenericMessage() {
		assertEquals(Arrays.asList("LIVENESS_GENERIC_FAILURE", "LIVENESS_RETRY_AVAILABLE"), keys(update(
				SessionState.FAILED, FeedbackCode.FAILED, null, null, FailureReason.PAD_ATTACK_DETECTED, true)));
		assertEquals(Collections.singletonList("LIVENESS_GENERIC_FAILURE"), keys(update(SessionState.FAILED,
				FeedbackCode.FAILED, GuidanceTip.STAY_IN_FRAME, null, FailureReason.ENGINE_ERROR, false)));
	}

	@Test
	public void otherFailuresGiveTheirTip() {
		assertEquals(Arrays.asList("LIVENESS_FAILED", "LIVENESS_TIP_FOLLOW_ACTION", "LIVENESS_RETRY_AVAILABLE"),
				keys(update(SessionState.FAILED, FeedbackCode.FAILED, GuidanceTip.FOLLOW_ACTION, ChallengeType.BLINK,
						FailureReason.CHALLENGE_TIMEOUT, true)));
		assertEquals(Arrays.asList("LIVENESS_FAILED", "LIVENESS_TIP_LOOK_CAMERA"), keys(update(SessionState.FAILED,
				FeedbackCode.FAILED, GuidanceTip.LOOK_CAMERA, null, FailureReason.LOW_QUALITY, false)));
		assertEquals(Arrays.asList("LIVENESS_DEVICE_UNAVAILABLE", "LIVENESS_RETRY_AVAILABLE"), keys(update(
				SessionState.FAILED, FeedbackCode.FAILED, null, null, FailureReason.DEVICE_ERROR, true)));
	}

	@Test
	public void theEndOfTheAttemptsDependsOnTheWorkflow() {
		LivenessUpdate exhausted = update(SessionState.FAILED, FeedbackCode.MAX_ATTEMPTS_EXCEEDED, null, null,
				FailureReason.PAD_ATTACK_DETECTED, false);
		assertEquals(Collections.singletonList("LIVENESS_MAX_ATTEMPTS"),
				LivenessMessages.keys(exhausted, Workflow.RESIDENT));
		assertEquals(Collections.singletonList("LIVENESS_MAX_ATTEMPTS_AUTH"),
				LivenessMessages.keys(exhausted, Workflow.OPERATOR));
		assertEquals(Collections.singletonList("LIVENESS_MAX_ATTEMPTS_AUTH"),
				LivenessMessages.keys(exhausted, Workflow.SUPERVISOR));
	}
}
