/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.registration.dto.RegistrationDTO;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.ModelIntegrityException;
import io.mosip.registration.liveness.api.Workflow;

/**
 * Starts the liveness-gated scans of the client: resident capture, operator and supervisor authentication. Each scan
 * runs on a new session of the engine, which runs one session at a time; the client counts the attempts of a capture
 * across scans and gives each session only the attempts left.
 */
@Component
public class LivenessService {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessService.class);

	/**
	 * Entry of the packet meta data that lists the face fields captured under the liveness exception process.
	 * Provisional: how the mark travels in the packet is to be confirmed with the mentors (Q15).
	 */
	static final String PACKET_MARK = "faceLivenessException";

	/** What the next scan of a capture does. */
	public enum ScanDecision {
		/** Liveness is disabled for the workflow: original behaviour of the client. */
		DISABLED,
		/** Run the liveness check: one attempt. */
		LIVENESS,
		/** Attempts exhausted with {@code on_max_attempts=EXCEPTION} (residents only). */
		EXCEPTION,
		/** Attempts exhausted with {@code on_max_attempts=BLOCK}: no face is accepted. */
		BLOCKED
	}

	private final LivenessConfigProvider configProvider;
	private final LivenessEngineProvider engineProvider;
	private final AuditManagerService auditManagerService;

	@Autowired
	public LivenessService(LivenessConfigProvider configProvider, LivenessEngineProvider engineProvider,
			AuditManagerService auditManagerService) {
		this.configProvider = configProvider;
		this.engineProvider = engineProvider;
		this.auditManagerService = auditManagerService;
	}

	/** Settings of the workflow, read from the synchronised configuration. Cheap: call it on each scan. */
	public LivenessConfig config(Workflow workflow) {
		return configProvider.forWorkflow(workflow);
	}

	public static ScanDecision decide(LivenessConfig config, int attemptsUsed) {
		if (!config.isEnabled()) {
			return ScanDecision.DISABLED;
		}
		if (attemptsUsed < config.getPolicy().maxAttempts()) {
			return ScanDecision.LIVENESS;
		}
		return config.getOnMaxAttempts() == LivenessConfig.OnMaxAttempts.EXCEPTION ? ScanDecision.EXCEPTION
				: ScanDecision.BLOCKED;
	}

	/**
	 * A scan on a new session with the attempts left. Loads the engine on first use: call it off the UI thread.
	 *
	 * @throws ModelIntegrityException
	 *             when a model file is missing or modified: the scan must be refused
	 * @throws IllegalStateException
	 *             when no attempt is left
	 */
	public LivenessCapture startScan(LivenessConfig config, int attemptsUsed, String refId, String refIdType,
			LivenessCapture.Callbacks screen) throws ModelIntegrityException {
		int maxAttempts = config.getPolicy().maxAttempts();
		if (attemptsUsed >= maxAttempts) {
			throw new IllegalStateException("no liveness attempt left");
		}
		LivenessCapture capture = new LivenessCapture(screen, LivenessCapture::isoToImage);
		LivenessSession session = engineProvider.get().newSession(config.getWorkflow(),
				withMaxAttempts(config.getPolicy(), maxAttempts - attemptsUsed),
				new AttemptOffsetListener(attemptsUsed, maxAttempts,
						new LivenessAuditListener(auditManagerService, capture.listener(), refId, refIdType)));
		capture.attach(session);
		return capture;
	}

	/**
	 * The face of the field was captured without a liveness decision, after the last attempt, under the exception
	 * process (section 7.1 of docs/decision-flow.md): the registration is marked and the capture audited. A failing
	 * audit write does not undo the mark.
	 */
	public void recordExceptionCapture(RegistrationDTO registration, String fieldId, int attemptsUsed) {
		registration.getFaceLivenessExceptions().add(fieldId);
		try {
			auditManagerService.audit(AuditEvent.LIVENESS_EXCEPTION_CAPTURE, Components.LIVENESS,
					registration.getRegistrationId(), AuditReferenceIdTypes.REGISTRATION_ID.getReferenceTypeId(),
					String.format(Locale.ROOT, "workflow=%s, attempts=%d, field=%s", Workflow.RESIDENT, attemptsUsed,
							fieldId));
		} catch (RuntimeException exception) {
			LOGGER.error("Face liveness exception audit failed", exception);
		}
	}

	/** A face of the registration was captured under the exception process: a supervisor must authenticate. */
	public static boolean requiresSupervisor(RegistrationDTO registration) {
		return !registration.getFaceLivenessExceptions().isEmpty();
	}

	/** Adds the exception mark to the meta data of the packet, only for a marked registration. */
	public static void addPacketMark(RegistrationDTO registration, Map<String, String> metaData) {
		if (requiresSupervisor(registration)) {
			metaData.put(PACKET_MARK, String.join(",", registration.getFaceLivenessExceptions()));
		}
	}

	static LivenessPolicy withMaxAttempts(LivenessPolicy policy, int maxAttempts) {
		return LivenessPolicy.builder().passThreshold(policy.passThreshold()).attackThreshold(policy.attackThreshold())
				.minFrames(policy.minFrames()).windowMs(policy.windowMs()).passiveTimeoutMs(policy.passiveTimeoutMs())
				.samplingMs(policy.samplingMs()).minFacePx(policy.minFacePx()).maxYawDeg(policy.maxYawDeg())
				.activeEnabled(policy.activeEnabled()).minChallenges(policy.minChallenges())
				.challengeTypes(policy.challengeTypes()).challengeTimeoutMs(policy.challengeTimeoutMs())
				.baselineMs(policy.baselineMs()).maxAttempts(maxAttempts).diagnostic(policy.diagnostic()).build();
	}
}
