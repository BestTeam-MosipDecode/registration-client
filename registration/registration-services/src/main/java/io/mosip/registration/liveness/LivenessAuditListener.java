/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessUpdate;

/**
 * Records the events of a face liveness session in the audit trail of the client (section 9 of
 * docs/decision-flow.md of face-liveness-pad): workflow, attempt, duration, model set version, and the failure
 * reason, challenge or device event when there is one. The engine never puts a score or an image in its events.
 * Updates and events are then passed to the listener of the screen. The writes go to one background thread, in
 * order: the listener runs on the thread of the engine, which must never wait for the database.
 */
public class LivenessAuditListener implements LivenessEventListener {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessAuditListener.class);

	private static final Map<LivenessEvent.Type, AuditEvent> AUDIT_EVENTS = new EnumMap<>(LivenessEvent.Type.class);

	static {
		AUDIT_EVENTS.put(LivenessEvent.Type.SESSION_STARTED, AuditEvent.LIVENESS_SESSION_STARTED);
		AUDIT_EVENTS.put(LivenessEvent.Type.ACTIVE_TRIGGERED, AuditEvent.LIVENESS_ACTIVE_TRIGGERED);
		AUDIT_EVENTS.put(LivenessEvent.Type.CHALLENGE_PASSED, AuditEvent.LIVENESS_CHALLENGE_PASSED);
		AUDIT_EVENTS.put(LivenessEvent.Type.CHALLENGE_FAILED, AuditEvent.LIVENESS_CHALLENGE_FAILED);
		AUDIT_EVENTS.put(LivenessEvent.Type.PASSED, AuditEvent.LIVENESS_PASSED);
		AUDIT_EVENTS.put(LivenessEvent.Type.FAILED, AuditEvent.LIVENESS_FAILED);
		AUDIT_EVENTS.put(LivenessEvent.Type.MAX_ATTEMPTS, AuditEvent.LIVENESS_MAX_ATTEMPTS);
		AUDIT_EVENTS.put(LivenessEvent.Type.DEVICE_ERROR, AuditEvent.LIVENESS_DEVICE_ERROR);
	}

	/** One writer for all the sessions of the client: the order of the entries is kept. */
	private static final Executor WRITER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "liveness-audit");
		thread.setDaemon(true);
		return thread;
	});

	private final AuditManagerService auditManagerService;
	private final Executor writer;
	private final LivenessEventListener screen;
	private final String refId;
	private final String refIdType;

	/**
	 * @param screen
	 *            listener of the capture or authentication screen, or null
	 * @param refId
	 *            reference of the audit entries, for example the user id being authenticated; the audit service
	 *            replaces it with the registration id or the user id of the session when there is one
	 */
	public LivenessAuditListener(AuditManagerService auditManagerService, LivenessEventListener screen, String refId,
			String refIdType) {
		this(auditManagerService, screen, refId, refIdType, WRITER);
	}

	LivenessAuditListener(AuditManagerService auditManagerService, LivenessEventListener screen, String refId,
			String refIdType, Executor writer) {
		this.auditManagerService = auditManagerService;
		this.writer = writer;
		this.screen = screen;
		this.refId = refId;
		this.refIdType = refIdType;
	}

	@Override
	public void onUpdate(LivenessUpdate update) {
		if (screen != null) {
			screen.onUpdate(update);
		}
	}

	@Override
	public void onEvent(LivenessEvent event) {
		AuditEvent auditEvent = auditEventOf(event.type());
		String details = details(event);
		writer.execute(() -> {
			try {
				auditManagerService.audit(auditEvent, Components.LIVENESS, refId, refIdType, details);
			} catch (RuntimeException exception) {
				// The audit trail must not stop the capture or the authentication.
				LOGGER.error("Face liveness audit failed for {}", auditEvent, exception);
			}
		});
		if (screen != null) {
			screen.onEvent(event);
		}
	}

	static AuditEvent auditEventOf(LivenessEvent.Type type) {
		return AUDIT_EVENTS.get(type);
	}

	static String details(LivenessEvent event) {
		StringBuilder details = new StringBuilder(String.format(Locale.ROOT,
				"workflow=%s, attempt=%d, durationMs=%d, models=%s", event.workflow(), event.attempt(),
				event.durationMs(), event.modelSetVersion()));
		event.failureReason().ifPresent(reason -> details.append(", reason=").append(reason));
		event.challenge().ifPresent(challenge -> details.append(", challenge=").append(challenge));
		event.deviceEvent().ifPresent(device -> details.append(", device=").append(device));
		return details.toString();
	}
}
