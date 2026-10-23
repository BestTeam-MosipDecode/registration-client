/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;

/**
 * What the liveness status pane shows for a session update, following the screens of docs/ui of face-liveness-pad:
 * the tone and the text of the band, the lines under it, the liveness status of the capture details, the position of
 * the challenge, the progress and the buttons. The text keys come from {@link LivenessMessages}, so a detected attack
 * shows the generic message. No JavaFX here: the pane only resolves the keys and applies the model.
 */
public final class LivenessStatusModel {

	/** Colour of the band. The text always says what the colour says. */
	public enum Tone {
		CHECKING, INSTRUCTION, GUIDANCE, SUCCESS, FAILURE
	}

	/** A message or label key, with the values of its placeholders. */
	public static final class Line {
		private final String key;
		private final Object[] args;

		Line(String key, Object... args) {
			this.key = key;
			this.args = args;
		}

		public String key() {
			return key;
		}

		public Object[] args() {
			return args.clone();
		}
	}

	private static final double HIDDEN = -1;

	private final Tone tone;
	private final Line band;
	private final List<Line> details;
	private final String statusLabel;
	private final Line challengeCount;
	private final double progress;
	private final boolean retry;
	private final boolean continueUnderException;
	private final boolean otherSignIn;
	private final Double diagnosticScore;

	private LivenessStatusModel(Builder b) {
		this.tone = b.tone;
		this.band = b.band;
		this.details = Collections.unmodifiableList(new ArrayList<>(b.details));
		this.statusLabel = b.statusLabel;
		this.challengeCount = b.challengeCount;
		this.progress = b.progress;
		this.retry = b.retry;
		this.continueUnderException = b.continueUnderException;
		this.otherSignIn = b.otherSignIn;
		this.diagnosticScore = b.diagnosticScore;
	}

	/** Before the first analysed frame: no band, liveness not started. */
	public static LivenessStatusModel idle() {
		return new Builder().status("livenessNotStarted").build();
	}

	/**
	 * Attempts exhausted. A resident goes to the exception process when the configuration allows it, otherwise to the
	 * supervisor; an operator or a supervisor signs in another way. The exception process never applies to
	 * authentication (section 7.1 of docs/decision-flow.md).
	 */
	public static LivenessStatusModel maxAttempts(Workflow workflow, boolean exceptionAllowed) {
		Builder b = new Builder().tone(Tone.FAILURE).status("livenessNotVerified");
		if (workflow != Workflow.RESIDENT) {
			b.band(LivenessMessages.authMaxAttemptsKey(workflow));
			b.otherSignIn = true;
		} else if (exceptionAllowed) {
			b.band("LIVENESS_MAX_ATTEMPTS").detail("LIVENESS_EXCEPTION_INFO");
			b.continueUnderException = true;
		} else {
			return blocked();
		}
		return b.build();
	}

	/**
	 * Attempts exhausted on a screen that offers no other way to go on, such as the onboarding of an operator: the
	 * user is sent to the supervisor.
	 */
	public static LivenessStatusModel blocked() {
		return new Builder().tone(Tone.FAILURE).status("livenessNotVerified").band("LIVENESS_MAX_ATTEMPTS_BLOCKED")
				.build();
	}

	/**
	 * The face of a resident was captured under the exception process, without a liveness decision: a supervisor
	 * authenticates at submission (section 7.1 of docs/decision-flow.md).
	 */
	public static LivenessStatusModel exceptionCaptured() {
		return new Builder().tone(Tone.GUIDANCE).band("LIVENESS_EXCEPTION_CAPTURED")
				.detail("LIVENESS_EXCEPTION_SUPERVISOR").status("livenessNotVerified").build();
	}

	/** The model of an update of the engine. */
	public static LivenessStatusModel of(LivenessUpdate update, Workflow workflow, boolean exceptionAllowed) {
		if (update.feedback() == FeedbackCode.MAX_ATTEMPTS_EXCEEDED) {
			return maxAttempts(workflow, exceptionAllowed);
		}
		List<String> keys = LivenessMessages.keys(update, workflow);
		boolean active = update.state() == SessionState.ACTIVE_CHALLENGE;
		Builder b = new Builder().status(active ? "livenessWaitingAction" : "livenessChecking")
				.progress(update.progress());
		update.diagnosticScore().ifPresent(score -> b.diagnosticScore = score);
		if (update.challengeCount() > 0) {
			b.challengeCount = new Line("livenessChallengeCount", update.challengeIndex(), update.challengeCount());
		}
		switch (update.feedback()) {
		case CHECKING:
			b.tone(Tone.CHECKING).band(keys.get(0))
					.detail(workflow == Workflow.RESIDENT ? "LIVENESS_PASSIVE_HINT" : "LIVENESS_AUTH_HINT");
			break;
		case NO_FACE:
		case MULTIPLE_FACES:
		case LOW_QUALITY:
			b.tone(Tone.GUIDANCE).band(keys.get(0));
			break;
		case HOLD_STILL:
			b.tone(Tone.INSTRUCTION).band(keys.get(0));
			if (active) {
				b.detail("LIVENESS_INSTRUCTION_SOON");
			}
			break;
		case CHALLENGE:
			b.tone(Tone.INSTRUCTION).band(keys.get(0));
			break;
		case KEEP_GOING:
			if (update.challenge().isPresent()) {
				b.tone(Tone.INSTRUCTION).band("LIVENESS_CHALLENGE_" + update.challenge().get().name())
						.detail("LIVENESS_KEEP_GOING");
			} else {
				b.tone(Tone.INSTRUCTION).band(keys.get(0));
			}
			break;
		case ACTION_DETECTED:
			b.tone(Tone.SUCCESS).band(keys.get(0));
			break;
		case SUCCESS:
			b.tone(Tone.SUCCESS).band(keys.get(0)).status("livenessVerified").progress(1);
			if (workflow == Workflow.RESIDENT) {
				b.detail("LIVENESS_SUCCESS_HINT");
			}
			break;
		case DEVICE_ERROR:
			b.tone(Tone.FAILURE).band(keys.get(0)).detail("LIVENESS_TIP_DEVICE").status("livenessNotVerified")
					.progress(HIDDEN);
			b.retry = update.retryAvailable();
			break;
		case FAILED:
		default:
			failure(b, keys, update);
		}
		return b.build();
	}

	private static void failure(Builder b, List<String> keys, LivenessUpdate update) {
		b.tone(Tone.FAILURE).band(keys.get(0)).status("livenessNotVerified").progress(HIDDEN);
		b.challengeCount = null;
		if ("LIVENESS_DEVICE_UNAVAILABLE".equals(keys.get(0))) {
			b.detail("LIVENESS_TIP_DEVICE");
		}
		for (String key : keys.subList(1, keys.size())) {
			if ("LIVENESS_RETRY_AVAILABLE".equals(key)) {
				b.details.add(new Line(key, update.attempt(), update.maxAttempts()));
			} else {
				b.detail(key);
			}
		}
		b.retry = update.retryAvailable();
	}

	public Tone tone() {
		return tone;
	}

	/** Main text, or {@code null} when nothing has started. */
	public Line band() {
		return band;
	}

	/** Lines under the band: hints, tips, attempts left. */
	public List<Line> details() {
		return details;
	}

	/** Label key of the liveness status in the capture details. */
	public String statusLabel() {
		return statusLabel;
	}

	public boolean showsChallengeCount() {
		return challengeCount != null;
	}

	/** "Challenge {0} of {1}", or {@code null} outside the active check. */
	public Line challengeCount() {
		return challengeCount;
	}

	/** Between 0 and 1, negative when the progress bar is hidden. */
	public double progress() {
		return progress;
	}

	public boolean retry() {
		return retry;
	}

	public boolean continueUnderException() {
		return continueUnderException;
	}

	public boolean otherSignIn() {
		return otherSignIn;
	}

	/** Score of the passive check, present only in diagnostic mode. */
	public Double diagnosticScore() {
		return diagnosticScore;
	}

	private static final class Builder {
		private Tone tone = Tone.CHECKING;
		private Line band;
		private final List<Line> details = new ArrayList<>();
		private String statusLabel;
		private Line challengeCount;
		private double progress = HIDDEN;
		private boolean retry;
		private boolean continueUnderException;
		private boolean otherSignIn;
		private Double diagnosticScore;

		Builder tone(Tone value) {
			tone = value;
			return this;
		}

		Builder band(String key) {
			band = new Line(key);
			return this;
		}

		Builder detail(String key) {
			details.add(new Line(key));
			return this;
		}

		Builder status(String key) {
			statusLabel = key;
			return this;
		}

		Builder progress(double value) {
			progress = value;
			return this;
		}

		LivenessStatusModel build() {
			return new LivenessStatusModel(this);
		}
	}
}
