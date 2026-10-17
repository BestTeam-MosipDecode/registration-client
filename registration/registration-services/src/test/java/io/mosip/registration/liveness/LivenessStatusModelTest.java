/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import io.mosip.registration.liveness.LivenessStatusModel.Line;
import io.mosip.registration.liveness.LivenessStatusModel.Tone;
import io.mosip.registration.liveness.active.ChallengeType;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.GuidanceTip;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessStatusModelTest {

	private static LivenessUpdate update(SessionState state, FeedbackCode feedback, GuidanceTip tip,
			ChallengeType challenge, double progress, FailureReason reason, boolean retry, Double score, int index,
			int count) {
		return new LivenessUpdate(state, feedback, tip, challenge, progress, 1, 3, reason, retry, false, score, index,
				count);
	}

	private static LivenessUpdate passive(FeedbackCode feedback, GuidanceTip tip) {
		return update(SessionState.PASSIVE_CHECK, feedback, tip, null, 0.4, null, false, null, 0, 0);
	}

	private static LivenessUpdate active(FeedbackCode feedback, ChallengeType challenge, int index, int count) {
		return update(SessionState.ACTIVE_CHALLENGE, feedback, null, challenge, 0.5, null, false, null, index, count);
	}

	private static LivenessUpdate failed(FailureReason reason, GuidanceTip tip, boolean retry) {
		return update(SessionState.FAILED, FeedbackCode.FAILED, tip, null, 0, reason, retry, null, 0, 0);
	}

	private static List<String> keys(List<Line> lines) {
		return lines.stream().map(Line::key).collect(Collectors.toList());
	}

	@Test
	public void thePassiveCheckOfAResidentAsksForNoAction() {
		LivenessStatusModel model = LivenessStatusModel.of(passive(FeedbackCode.CHECKING, null), Workflow.RESIDENT, true);
		assertEquals(Tone.CHECKING, model.tone());
		assertEquals("LIVENESS_CHECKING", model.band().key());
		assertEquals(List.of("LIVENESS_PASSIVE_HINT"), keys(model.details()));
		assertEquals("livenessChecking", model.statusLabel());
		assertEquals(0.4, model.progress(), 0.0);
		assertFalse(model.showsChallengeCount());
		assertFalse(model.retry() || model.continueUnderException() || model.otherSignIn());
	}

	@Test
	public void thePassiveCheckOfAnOperatorSaysThatSignInContinuesAlone() {
		LivenessStatusModel model = LivenessStatusModel.of(passive(FeedbackCode.CHECKING, null), Workflow.OPERATOR, false);
		assertEquals(List.of("LIVENESS_AUTH_HINT"), keys(model.details()));
	}

	@Test
	public void aFrameThatCannotBeUsedGivesItsTipOnAGuidanceBand() {
		LivenessStatusModel model = LivenessStatusModel.of(passive(FeedbackCode.LOW_QUALITY, GuidanceTip.LIGHTING),
				Workflow.RESIDENT, true);
		assertEquals(Tone.GUIDANCE, model.tone());
		assertEquals("LIVENESS_TIP_LIGHTING", model.band().key());
		assertEquals("livenessChecking", model.statusLabel());
		assertEquals(Tone.GUIDANCE, LivenessStatusModel.of(passive(FeedbackCode.NO_FACE, null), Workflow.RESIDENT, true).tone());
		assertEquals(Tone.GUIDANCE,
				LivenessStatusModel.of(passive(FeedbackCode.MULTIPLE_FACES, null), Workflow.RESIDENT, true).tone());
	}

	@Test
	public void theHoldStillPhaseAnnouncesTheInstruction() {
		LivenessStatusModel model = LivenessStatusModel.of(active(FeedbackCode.HOLD_STILL, ChallengeType.BLINK, 1, 2),
				Workflow.RESIDENT, true);
		assertEquals(Tone.INSTRUCTION, model.tone());
		assertEquals("LIVENESS_HOLD_STILL", model.band().key());
		assertEquals(List.of("LIVENESS_INSTRUCTION_SOON"), keys(model.details()));
		assertEquals("livenessWaitingAction", model.statusLabel());
	}

	@Test
	public void aChallengeShowsItsInstructionAndItsPosition() {
		LivenessStatusModel model = LivenessStatusModel.of(active(FeedbackCode.CHALLENGE, ChallengeType.BLINK, 1, 2),
				Workflow.RESIDENT, true);
		assertEquals(Tone.INSTRUCTION, model.tone());
		assertEquals("LIVENESS_CHALLENGE_BLINK", model.band().key());
		assertTrue(model.showsChallengeCount());
		assertEquals("livenessChallengeCount", model.challengeCount().key());
		assertArrayEquals(new Object[] { 1, 2 }, model.challengeCount().args());
		assertEquals("livenessWaitingAction", model.statusLabel());
	}

	@Test
	public void keepGoingKeepsTheInstructionOnTheBand() {
		LivenessStatusModel model = LivenessStatusModel.of(
				active(FeedbackCode.KEEP_GOING, ChallengeType.TURN_LEFT, 2, 2), Workflow.RESIDENT, true);
		assertEquals("LIVENESS_CHALLENGE_TURN_LEFT", model.band().key());
		assertEquals(List.of("LIVENESS_KEEP_GOING"), keys(model.details()));
	}

	@Test
	public void aDetectedActionIsShownInGreen() {
		LivenessStatusModel model = LivenessStatusModel.of(
				active(FeedbackCode.ACTION_DETECTED, ChallengeType.BLINK, 1, 2), Workflow.RESIDENT, true);
		assertEquals(Tone.SUCCESS, model.tone());
		assertEquals("LIVENESS_ACTION_DETECTED", model.band().key());
		assertTrue(model.showsChallengeCount());
	}

	@Test
	public void anAcceptedCaptureIsVerified() {
		LivenessUpdate success = update(SessionState.PASSED, FeedbackCode.SUCCESS, null, null, 1, null, false, null,
				0, 0);
		LivenessStatusModel resident = LivenessStatusModel.of(success, Workflow.RESIDENT, true);
		assertEquals(Tone.SUCCESS, resident.tone());
		assertEquals("LIVENESS_SUCCESS", resident.band().key());
		assertEquals(List.of("LIVENESS_SUCCESS_HINT"), keys(resident.details()));
		assertEquals("livenessVerified", resident.statusLabel());
		assertTrue(LivenessStatusModel.of(success, Workflow.SUPERVISOR, false).details().isEmpty());
	}

	@Test
	public void aDetectedAttackShowsTheGenericMessageAndNoTip() {
		LivenessStatusModel model = LivenessStatusModel.of(
				failed(FailureReason.PAD_ATTACK_DETECTED, GuidanceTip.LIGHTING, true), Workflow.RESIDENT, true);
		assertEquals(Tone.FAILURE, model.tone());
		assertEquals("LIVENESS_GENERIC_FAILURE", model.band().key());
		assertEquals(List.of("LIVENESS_RETRY_AVAILABLE"), keys(model.details()));
		assertArrayEquals(new Object[] { 1, 3 }, model.details().get(0).args());
		assertEquals("livenessNotVerified", model.statusLabel());
		assertTrue(model.retry());
		assertTrue(model.progress() < 0);
	}

	@Test
	public void aFailedChallengeGivesItsTipAndTheAttemptsLeft() {
		LivenessStatusModel model = LivenessStatusModel.of(
				failed(FailureReason.CHALLENGE_TIMEOUT, GuidanceTip.FOLLOW_ACTION, true), Workflow.RESIDENT, true);
		assertEquals("LIVENESS_FAILED", model.band().key());
		assertEquals(List.of("LIVENESS_TIP_FOLLOW_ACTION", "LIVENESS_RETRY_AVAILABLE"), keys(model.details()));
		assertTrue(model.retry());
	}

	@Test
	public void noRetryButtonWithoutAnAttemptLeft() {
		LivenessStatusModel model = LivenessStatusModel.of(failed(FailureReason.LIVENESS_BELOW_THRESHOLD, null, false),
				Workflow.RESIDENT, true);
		assertFalse(model.retry());
		assertEquals(List.of(), keys(model.details()));
	}

	@Test
	public void aDeviceErrorTellsWhatToCheck() {
		LivenessStatusModel failure = LivenessStatusModel.of(failed(FailureReason.DEVICE_ERROR, null, true),
				Workflow.RESIDENT, true);
		assertEquals("LIVENESS_DEVICE_UNAVAILABLE", failure.band().key());
		assertEquals(List.of("LIVENESS_TIP_DEVICE", "LIVENESS_RETRY_AVAILABLE"), keys(failure.details()));
		LivenessStatusModel feedback = LivenessStatusModel.of(
				update(SessionState.INIT, FeedbackCode.DEVICE_ERROR, null, null, 0, null, false, null, 0, 0),
				Workflow.OPERATOR, false);
		assertEquals(Tone.FAILURE, feedback.tone());
		assertEquals("LIVENESS_DEVICE_UNAVAILABLE", feedback.band().key());
		assertEquals(List.of("LIVENESS_TIP_DEVICE"), keys(feedback.details()));
	}

	@Test
	public void theLastAttemptOfAResidentLeadsToTheExceptionProcess() {
		LivenessStatusModel model = LivenessStatusModel.maxAttempts(Workflow.RESIDENT, true);
		assertEquals(Tone.FAILURE, model.tone());
		assertEquals("LIVENESS_MAX_ATTEMPTS", model.band().key());
		assertEquals(List.of("LIVENESS_EXCEPTION_INFO"), keys(model.details()));
		assertTrue(model.continueUnderException());
		assertFalse(model.retry() || model.otherSignIn());
		assertEquals("livenessNotVerified", model.statusLabel());
	}

	@Test
	public void aCaptureUnderExceptionSaysThatASupervisorMustAuthenticate() {
		LivenessStatusModel model = LivenessStatusModel.exceptionCaptured();
		assertEquals(Tone.GUIDANCE, model.tone());
		assertEquals("LIVENESS_EXCEPTION_CAPTURED", model.band().key());
		assertEquals(List.of("LIVENESS_EXCEPTION_SUPERVISOR"), keys(model.details()));
		assertEquals("livenessNotVerified", model.statusLabel());
		assertFalse(model.retry() || model.continueUnderException() || model.otherSignIn());
		assertTrue(model.progress() < 0);
	}

	@Test
	public void withoutTheExceptionProcessTheResidentIsSentToTheSupervisor() {
		LivenessStatusModel model = LivenessStatusModel.maxAttempts(Workflow.RESIDENT, false);
		assertEquals("LIVENESS_MAX_ATTEMPTS_BLOCKED", model.band().key());
		assertFalse(model.continueUnderException() || model.retry() || model.otherSignIn());
	}

	@Test
	public void authenticationNeverOffersTheExceptionProcess() {
		for (Workflow workflow : new Workflow[] { Workflow.OPERATOR, Workflow.SUPERVISOR }) {
			LivenessStatusModel model = LivenessStatusModel.maxAttempts(workflow, true);
			assertEquals("LIVENESS_MAX_ATTEMPTS_AUTH", model.band().key());
			assertTrue(model.otherSignIn());
			assertFalse(model.continueUnderException());
		}
	}

	@Test
	public void anUpdateWithAttemptsExhaustedFollowsTheSameRule() {
		LivenessUpdate exhausted = update(SessionState.FAILED, FeedbackCode.MAX_ATTEMPTS_EXCEEDED, null, null, 0,
				FailureReason.LIVENESS_BELOW_THRESHOLD, false, null, 0, 0);
		assertTrue(LivenessStatusModel.of(exhausted, Workflow.RESIDENT, true).continueUnderException());
		assertEquals("LIVENESS_MAX_ATTEMPTS_BLOCKED",
				LivenessStatusModel.of(exhausted, Workflow.RESIDENT, false).band().key());
		assertTrue(LivenessStatusModel.of(exhausted, Workflow.OPERATOR, true).otherSignIn());
	}

	@Test
	public void theScoreAppearsOnlyInDiagnosticMode() {
		LivenessUpdate withScore = update(SessionState.PASSIVE_CHECK, FeedbackCode.CHECKING, null, null, 0.2, null,
				false, 0.9731, 0, 0);
		assertEquals(0.9731, LivenessStatusModel.of(withScore, Workflow.RESIDENT, true).diagnosticScore(), 0.0);
		assertNull(LivenessStatusModel.of(passive(FeedbackCode.CHECKING, null), Workflow.RESIDENT, true)
				.diagnosticScore());
	}

	@Test
	public void beforeTheFirstFrameNothingHasStarted() {
		LivenessStatusModel model = LivenessStatusModel.idle();
		assertEquals("livenessNotStarted", model.statusLabel());
		assertNull(model.band());
		assertTrue(model.details().isEmpty());
		assertTrue(model.progress() < 0);
	}
}
