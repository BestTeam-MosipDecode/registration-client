/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.registration.liveness.active.ChallengeType;
import io.mosip.registration.liveness.api.DeviceEvent;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessAuditListenerTest {

	/** Records what the screen listener receives. */
	static final class Screen implements LivenessEventListener {
		final List<Object> received = new ArrayList<>();

		@Override
		public void onUpdate(LivenessUpdate update) {
			received.add(update);
		}

		@Override
		public void onEvent(LivenessEvent event) {
			received.add(event);
		}
	}

	private static final String USER = "110011";
	private static final String USER_ID = AuditReferenceIdTypes.USER_ID.getReferenceTypeId();

	private final AuditManagerService audit = mock(AuditManagerService.class);
	private final Screen screen = new Screen();
	private final LivenessAuditListener listener = new LivenessAuditListener(audit, screen, USER, USER_ID,
			Runnable::run);

	private static LivenessEvent event(LivenessEvent.Type type, FailureReason reason, ChallengeType challenge,
			DeviceEvent device) {
		return new LivenessEvent(type, Workflow.SUPERVISOR, 2, 1500, "1.1.0", reason, challenge, device);
	}

	private String auditedDetails(AuditEvent expected) {
		ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
		verify(audit).audit(eq(expected), eq(Components.LIVENESS), eq(USER), eq(USER_ID), details.capture());
		return details.getValue();
	}

	@Test
	public void everySessionEventLeavesAnAuditEntry() {
		Set<AuditEvent> audited = new HashSet<>();
		for (LivenessEvent.Type type : LivenessEvent.Type.values()) {
			AuditEvent auditEvent = LivenessAuditListener.auditEventOf(type);
			assertTrue(type + " -> " + auditEvent, auditEvent.name().equals("LIVENESS_" + type.name()));
			audited.add(auditEvent);
		}
		assertEquals(LivenessEvent.Type.values().length, audited.size());
	}

	@Test
	public void recordsTheWorkflowTheAttemptTheDurationAndTheModels() {
		listener.onEvent(event(LivenessEvent.Type.SESSION_STARTED, null, null, null));
		assertEquals("workflow=SUPERVISOR, attempt=2, durationMs=1500, models=1.1.0",
				auditedDetails(AuditEvent.LIVENESS_SESSION_STARTED));
	}

	@Test
	public void recordsTheReasonTheChallengeAndTheDevice() {
		listener.onEvent(event(LivenessEvent.Type.CHALLENGE_FAILED, FailureReason.CHALLENGE_TIMEOUT,
				ChallengeType.BLINK, null));
		assertTrue(auditedDetails(AuditEvent.LIVENESS_CHALLENGE_FAILED)
				.endsWith("reason=CHALLENGE_TIMEOUT, challenge=BLINK"));
		listener.onEvent(event(LivenessEvent.Type.DEVICE_ERROR, FailureReason.DEVICE_ERROR, null,
				DeviceEvent.STREAM_INTERRUPTED));
		assertTrue(auditedDetails(AuditEvent.LIVENESS_DEVICE_ERROR)
				.endsWith("reason=DEVICE_ERROR, device=STREAM_INTERRUPTED"));
	}

	@Test
	public void theScreenStillReceivesUpdatesAndEvents() {
		LivenessUpdate update = new LivenessUpdate(SessionState.PASSIVE_CHECK, FeedbackCode.CHECKING, null, null, 0.2,
				1, 3, null, false, false, 0.9);
		LivenessEvent started = event(LivenessEvent.Type.SESSION_STARTED, null, null, null);
		listener.onUpdate(update);
		listener.onEvent(started);
		assertEquals(2, screen.received.size());
		assertTrue(screen.received.get(0) == update && screen.received.get(1) == started);
	}

	@Test
	public void aFailingAuditDoesNotStopTheScreen() {
		doThrow(new IllegalStateException("database locked")).when(audit).audit(eq(AuditEvent.LIVENESS_FAILED),
				eq(Components.LIVENESS), anyString(), anyString(), anyString());
		listener.onEvent(event(LivenessEvent.Type.FAILED, FailureReason.PAD_ATTACK_DETECTED, null, null));
		assertEquals(1, screen.received.size());
	}

	@Test
	public void worksWithoutAScreenListener() {
		new LivenessAuditListener(audit, null, USER, USER_ID, Runnable::run)
				.onEvent(event(LivenessEvent.Type.PASSED, null, null, null));
		assertTrue(auditedDetails(AuditEvent.LIVENESS_PASSED).contains("workflow=SUPERVISOR"));
	}

	@Test
	public void livenessAuditEventsHaveTheirOwnIds() {
		Set<String> ids = new HashSet<>();
		for (AuditEvent auditEvent : EnumSet.allOf(AuditEvent.class)) {
			if (auditEvent.name().startsWith("LIVENESS_")) {
				assertTrue(auditEvent.getId(), auditEvent.getId().startsWith("REG-EVT-"));
				assertTrue("duplicate id " + auditEvent.getId(), ids.add(auditEvent.getId()));
			}
		}
		assertEquals(9, ids.size());
		for (AuditEvent auditEvent : EnumSet.allOf(AuditEvent.class)) {
			if (!auditEvent.name().startsWith("LIVENESS_")) {
				assertFalse("id reused by " + auditEvent, ids.contains(auditEvent.getId()));
			}
		}
	}

	@Test
	public void theWriteNeverHoldsTheEngineAndKeepsTheOrder() {
		java.util.Deque<Runnable> queued = new java.util.ArrayDeque<>();
		LivenessAuditListener asynchronous = new LivenessAuditListener(audit, screen, USER, USER_ID, queued::add);
		asynchronous.onEvent(event(LivenessEvent.Type.SESSION_STARTED, null, null, null));
		asynchronous.onEvent(event(LivenessEvent.Type.PASSED, null, null, null));
		org.mockito.Mockito.verifyZeroInteractions(audit);
		assertEquals("the screen does not wait for the audit", 2, screen.received.size());
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(audit);
		while (!queued.isEmpty()) {
			queued.poll().run();
		}
		order.verify(audit).audit(eq(AuditEvent.LIVENESS_SESSION_STARTED), eq(Components.LIVENESS), eq(USER),
				eq(USER_ID), anyString());
		order.verify(audit).audit(eq(AuditEvent.LIVENESS_PASSED), eq(Components.LIVENESS), eq(USER), eq(USER_ID),
				anyString());
	}

	@Test
	public void theDefaultWriterIsOneDaemonThread() throws Exception {
		LivenessAuditListener production = new LivenessAuditListener(audit, null, USER, USER_ID);
		production.onEvent(event(LivenessEvent.Type.SESSION_STARTED, null, null, null));
		org.mockito.Mockito.verify(audit, org.mockito.Mockito.timeout(2000)).audit(
				eq(AuditEvent.LIVENESS_SESSION_STARTED), eq(Components.LIVENESS), eq(USER), eq(USER_ID), anyString());
	}
}
