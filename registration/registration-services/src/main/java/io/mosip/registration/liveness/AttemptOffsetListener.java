package io.mosip.registration.liveness;

import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessUpdate;

/**
 * Numbers the attempt of a scan among all the scans of a capture: each scan runs on a new session whose first attempt
 * is number 1, after the attempts already used. Screens and audit entries then show "attempt 2 of 3".
 */
class AttemptOffsetListener implements LivenessEventListener {

	private final int attemptsUsed;
	private final int maxAttempts;
	private final LivenessEventListener next;

	AttemptOffsetListener(int attemptsUsed, int maxAttempts, LivenessEventListener next) {
		this.attemptsUsed = attemptsUsed;
		this.maxAttempts = maxAttempts;
		this.next = next;
	}

	private int attempt(int attemptOfSession) {
		return attemptOfSession == 0 ? 0 : attemptOfSession + attemptsUsed;
	}

	@Override
	public void onUpdate(LivenessUpdate update) {
		next.onUpdate(new LivenessUpdate(update.state(), update.feedback(), update.tip().orElse(null),
				update.challenge().orElse(null), update.progress(), attempt(update.attempt()), maxAttempts,
				update.failureReason().orElse(null), update.retryAvailable(), update.finished(),
				update.diagnosticScore().isPresent() ? update.diagnosticScore().getAsDouble() : null));
	}

	@Override
	public void onEvent(LivenessEvent event) {
		next.onEvent(new LivenessEvent(event.type(), event.workflow(), attempt(event.attempt()), event.durationMs(),
				event.modelSetVersion(), event.failureReason().orElse(null), event.challenge().orElse(null),
				event.deviceEvent().orElse(null)));
	}
}
