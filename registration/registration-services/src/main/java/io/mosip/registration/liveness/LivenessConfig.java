/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.Locale;

import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.Workflow;

/**
 * Effective face liveness settings of one workflow, built by {@link LivenessConfigProvider}. The client applies
 * {@code enabled} and {@code onMaxAttempts}; the policy goes to the liveness engine.
 */
public class LivenessConfig {

	/** What follows the last failed attempt (section 7.1 of docs/decision-flow.md). */
	public enum OnMaxAttempts {
		/** No face is accepted. */
		BLOCK,
		/** The resident is directed to the exception process. Residents only. */
		EXCEPTION
	}

	private final Workflow workflow;
	private final boolean enabled;
	private final OnMaxAttempts onMaxAttempts;
	private final LivenessPolicy policy;

	public LivenessConfig(Workflow workflow, boolean enabled, OnMaxAttempts onMaxAttempts, LivenessPolicy policy) {
		this.workflow = workflow;
		this.enabled = enabled;
		this.onMaxAttempts = onMaxAttempts;
		this.policy = policy;
	}

	public Workflow getWorkflow() {
		return workflow;
	}

	/** False when liveness is disabled for this workflow: the client keeps its original behaviour. */
	public boolean isEnabled() {
		return enabled;
	}

	public OnMaxAttempts getOnMaxAttempts() {
		return onMaxAttempts;
	}

	public LivenessPolicy getPolicy() {
		return policy;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "%s: enabled=%s, onMaxAttempts=%s, pass=%.2f, attack=%.2f, minFrames=%d, "
				+ "windowMs=%d, passiveTimeoutMs=%d, samplingMs=%d, minFacePx=%d, maxYawDeg=%.0f, activeEnabled=%s, "
				+ "minChallenges=%d, challengeTypes=%s, challengeTimeoutMs=%d, baselineMs=%d, maxAttempts=%d, "
				+ "diagnostic=%s", workflow, enabled, onMaxAttempts, policy.passThreshold(), policy.attackThreshold(),
				policy.minFrames(), policy.windowMs(), policy.passiveTimeoutMs(), policy.samplingMs(),
				policy.minFacePx(), policy.maxYawDeg(), policy.activeEnabled(), policy.minChallenges(),
				policy.challengeTypes(), policy.challengeTimeoutMs(), policy.baselineMs(), policy.maxAttempts(),
				policy.diagnostic());
	}
}
