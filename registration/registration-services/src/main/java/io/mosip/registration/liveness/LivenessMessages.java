/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.ArrayList;
import java.util.List;

import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.GuidanceTip;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.Workflow;

/**
 * Message keys of a liveness update, as in section 6 of docs/engine-api.md of face-liveness-pad. A detected attack
 * and an engine error show the same generic message: the screen never tells that an attack was suspected. The keys
 * are in the message files of the client; {@code LIVENESS_RETRY_AVAILABLE} takes the attempt and the maximum.
 */
public final class LivenessMessages {

	private LivenessMessages() {
	}

	public static List<String> keys(LivenessUpdate update, Workflow workflow) {
		List<String> keys = new ArrayList<>();
		switch (update.feedback()) {
		case CHECKING:
			keys.add("LIVENESS_CHECKING");
			break;
		case NO_FACE:
			keys.add("LIVENESS_NO_FACE");
			break;
		case MULTIPLE_FACES:
			keys.add("LIVENESS_MULTIPLE_FACES");
			break;
		case LOW_QUALITY:
			keys.add(tipKey(update.tip().orElse(GuidanceTip.STAY_IN_FRAME)));
			break;
		case HOLD_STILL:
			keys.add("LIVENESS_HOLD_STILL");
			break;
		case CHALLENGE:
			keys.add(update.challenge().map(challenge -> "LIVENESS_CHALLENGE_" + challenge.name())
					.orElse("LIVENESS_HOLD_STILL"));
			break;
		case KEEP_GOING:
			keys.add("LIVENESS_KEEP_GOING");
			break;
		case ACTION_DETECTED:
			keys.add("LIVENESS_ACTION_DETECTED");
			break;
		case SUCCESS:
			keys.add("LIVENESS_SUCCESS");
			break;
		case DEVICE_ERROR:
			keys.add("LIVENESS_DEVICE_UNAVAILABLE");
			break;
		case MAX_ATTEMPTS_EXCEEDED:
			keys.add(workflow == Workflow.RESIDENT ? "LIVENESS_MAX_ATTEMPTS" : authMaxAttemptsKey(workflow));
			break;
		case FAILED:
			addFailure(update, keys);
			break;
		default:
			keys.add("LIVENESS_CHECKING");
		}
		return keys;
	}

	private static void addFailure(LivenessUpdate update, List<String> keys) {
		FailureReason reason = update.failureReason().orElse(FailureReason.ENGINE_ERROR);
		if (reason == FailureReason.PAD_ATTACK_DETECTED || reason == FailureReason.ENGINE_ERROR) {
			keys.add("LIVENESS_GENERIC_FAILURE");
		} else if (reason == FailureReason.DEVICE_ERROR) {
			keys.add("LIVENESS_DEVICE_UNAVAILABLE");
		} else {
			keys.add("LIVENESS_FAILED");
			update.tip().ifPresent(tip -> keys.add(tipKey(tip)));
		}
		if (update.retryAvailable()) {
			keys.add("LIVENESS_RETRY_AVAILABLE");
		}
	}

	static String tipKey(GuidanceTip tip) {
		return tip == GuidanceTip.HOLD_STILL ? "LIVENESS_HOLD_STILL" : "LIVENESS_TIP_" + tip.name();
	}

	/** Attempts exhausted in an authentication: a supervisor is not told to contact a supervisor. */
	static String authMaxAttemptsKey(Workflow workflow) {
		return workflow == Workflow.SUPERVISOR ? "LIVENESS_MAX_ATTEMPTS_SUPERVISOR" : "LIVENESS_MAX_ATTEMPTS_AUTH";
	}
}
