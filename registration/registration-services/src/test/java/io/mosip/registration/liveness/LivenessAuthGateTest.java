/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.LivenessCaptureTest.FakeSession;
import io.mosip.registration.liveness.LivenessCaptureTest.Screen;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.image.BgrImage;

public class LivenessAuthGateTest {

	private static final String P = RegistrationConstants.LIVENESS_PREFIX;
	private static final BgrImage IMAGE = new BgrImage(2, 2, new byte[12]);

	private final Map<String, String> settings = new HashMap<>();
	private final LivenessService service = new LivenessService(new LivenessConfigProvider(settings::get),
			new LivenessEngineProvider(() -> mock(LivenessEngine.class)), mock(AuditManagerService.class));
	private final LivenessAuthGate gate = new LivenessAuthGate(service);
	private final FakeSession session = new FakeSession();

	private LivenessCapture passedCapture() {
		LivenessCapture capture = new LivenessCapture(new Screen(), iso -> IMAGE);
		capture.attach(session);
		return capture;
	}

	private static List<BiometricsDto> oneFace() {
		BiometricsDto face = new BiometricsDto();
		face.setAttributeISO(new byte[] { 1 });
		return Collections.singletonList(face);
	}

	@Test
	public void withoutAPassedSessionTheFaceIsRefused() {
		assertFalse(gate.accept(Workflow.OPERATOR, "officer", oneFace()));
	}

	@Test
	public void aPassedSessionLetsOneCapturedFaceThroughAfterItsCheck() {
		gate.arm(Workflow.OPERATOR, "officer", passedCapture());
		assertTrue(gate.accept(Workflow.OPERATOR, "OFFICER", oneFace()));
		assertTrue(session.calls.contains("check"));
		assertFalse("one session, one comparison", gate.accept(Workflow.OPERATOR, "officer", oneFace()));
	}

	@Test
	public void theSessionOfAnotherUserOrWorkflowDoesNotCount() {
		gate.arm(Workflow.OPERATOR, "officer", passedCapture());
		assertFalse(gate.accept(Workflow.OPERATOR, "supervisor", oneFace()));
		gate.arm(Workflow.OPERATOR, "officer", passedCapture());
		assertFalse(gate.accept(Workflow.SUPERVISOR, "officer", oneFace()));
	}

	@Test
	public void aResponseWithTwoFacesIsRefused() {
		gate.arm(Workflow.SUPERVISOR, "supervisor", passedCapture());
		List<BiometricsDto> two = Arrays.asList(oneFace().get(0), oneFace().get(0));
		assertFalse(gate.accept(Workflow.SUPERVISOR, "supervisor", two));
	}

	@Test
	public void withLivenessDisabledTheOriginalComparisonRuns() {
		settings.put(P + "operator.enabled", "N");
		assertTrue(gate.accept(Workflow.OPERATOR, "officer", oneFace()));
	}

	@Test
	public void attemptsAreCountedPerWorkflowAndUserUntilTheUserIsAuthenticated() {
		gate.attempts(Workflow.OPERATOR, "officer").incrementAndGet();
		gate.attempts(Workflow.OPERATOR, "Officer").incrementAndGet();
		assertEquals(2, gate.attempts(Workflow.OPERATOR, "OFFICER").get());
		assertEquals(0, gate.attempts(Workflow.SUPERVISOR, "officer").get());
		gate.authenticated(Workflow.OPERATOR, "officer");
		assertEquals(0, gate.attempts(Workflow.OPERATOR, "officer").get());
	}

	@Test
	public void disarmingForgetsOnlyItsOwnSession() {
		LivenessCapture first = passedCapture();
		gate.arm(Workflow.OPERATOR, "officer", first);
		gate.disarm(passedCapture());
		assertTrue(gate.accept(Workflow.OPERATOR, "officer", oneFace()));
		gate.arm(Workflow.OPERATOR, "officer", first);
		gate.disarm(first);
		assertFalse(gate.accept(Workflow.OPERATOR, "officer", oneFace()));
	}

	@Test
	public void twoScreensKeepTheirOwnSession() {
		gate.arm(Workflow.OPERATOR, "officer", passedCapture());
		gate.arm(Workflow.SUPERVISOR, "supervisor", passedCapture());
		assertFalse(gate.accept(Workflow.OPERATOR, "supervisor", oneFace()));
		assertTrue(gate.accept(Workflow.SUPERVISOR, "supervisor", oneFace()));
		assertTrue(gate.accept(Workflow.OPERATOR, "officer", oneFace()));
	}
}
