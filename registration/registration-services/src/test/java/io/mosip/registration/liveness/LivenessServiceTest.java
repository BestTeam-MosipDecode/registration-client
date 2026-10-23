/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.RegistrationDTO;
import io.mosip.registration.dto.UserDTO;
import io.mosip.registration.dto.UserRoleDTO;
import io.mosip.registration.liveness.LivenessService.ScanDecision;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessServiceTest {

	private static final String P = RegistrationConstants.LIVENESS_PREFIX;

	private final Map<String, String> settings = new HashMap<>();
	private final LivenessEngine engine = mock(LivenessEngine.class);
	private final AuditManagerService audit = mock(AuditManagerService.class);
	private final LivenessService service = new LivenessService(new LivenessConfigProvider(settings::get),
			new LivenessEngineProvider(() -> engine), audit);
	private final LivenessCapture.Callbacks screen = mock(LivenessCapture.Callbacks.class);

	@Test
	public void decidesFromTheAttemptsAlreadyUsed() {
		LivenessConfig resident = service.config(Workflow.RESIDENT);
		assertEquals(ScanDecision.LIVENESS, LivenessService.decide(resident, 0));
		assertEquals(ScanDecision.LIVENESS, LivenessService.decide(resident, 2));
		assertEquals(ScanDecision.EXCEPTION, LivenessService.decide(resident, 3));
		assertEquals(ScanDecision.BLOCKED, LivenessService.decide(service.config(Workflow.OPERATOR), 3));
		settings.put(P + "resident.enabled", "N");
		assertEquals(ScanDecision.DISABLED, LivenessService.decide(service.config(Workflow.RESIDENT), 5));
	}

	@Test
	public void aScanRunsOnANewSessionWithTheAttemptsLeftAndTheAuditListener() throws Exception {
		LivenessSession session = mock(LivenessSession.class);
		when(engine.newSession(eq(Workflow.RESIDENT), any(LivenessPolicy.class), any(LivenessEventListener.class)))
				.thenReturn(session);
		LivenessCapture capture = service.startScan(service.config(Workflow.RESIDENT), 1, "10001", "REGISTRATION_ID",
				screen);
		assertNotNull(capture);
		ArgumentCaptor<LivenessPolicy> policy = ArgumentCaptor.forClass(LivenessPolicy.class);
		ArgumentCaptor<LivenessEventListener> listener = ArgumentCaptor.forClass(LivenessEventListener.class);
		verify(engine).newSession(eq(Workflow.RESIDENT), policy.capture(), listener.capture());
		assertEquals(2, policy.getValue().maxAttempts());
		assertEquals(LivenessPolicy.defaults().passThreshold(), policy.getValue().passThreshold(), 0);
		assertTrue(listener.getValue() instanceof AttemptOffsetListener);
		capture.close();
		verify(session).close();
	}

	@Test
	public void keepsEveryOtherValueOfThePolicy() {
		LivenessPolicy original = LivenessPolicy.builder().passThreshold(0.9).attackThreshold(0.2).minFrames(7)
				.windowMs(4000).samplingMs(50).minFacePx(100).maxYawDeg(15).activeEnabled(false).minChallenges(3)
				.challengeTimeoutMs(9000).baselineMs(700).maxAttempts(5).passiveTimeoutMs(15000).diagnostic(true)
				.build();
		LivenessPolicy copy = LivenessService.withMaxAttempts(original, 2);
		assertEquals(2, copy.maxAttempts());
		assertEquals(0.9, copy.passThreshold(), 0);
		assertEquals(0.2, copy.attackThreshold(), 0);
		assertEquals(7, copy.minFrames());
		assertEquals(4000, copy.windowMs());
		assertEquals(50, copy.samplingMs());
		assertEquals(100, copy.minFacePx());
		assertEquals(15, copy.maxYawDeg(), 0);
		assertEquals(false, copy.activeEnabled());
		assertEquals(3, copy.minChallenges());
		assertEquals(original.challengeTypes(), copy.challengeTypes());
		assertEquals(9000, copy.challengeTimeoutMs());
		assertEquals(700, copy.baselineMs());
		assertEquals(15000, copy.passiveTimeoutMs());
		assertEquals(true, copy.diagnostic());
	}

	@Test
	public void aScanCannotRunAfterTheLastAttempt() throws Exception {
		try {
			service.startScan(service.config(Workflow.RESIDENT), 3, "10001", "REGISTRATION_ID", screen);
			org.junit.Assert.fail("no attempt left");
		} catch (IllegalStateException expected) {
			// decide() said EXCEPTION
		}
	}

	@Test
	public void aCaptureUnderExceptionMarksTheRegistrationAndIsAudited() {
		RegistrationDTO registration = new RegistrationDTO();
		registration.setRegistrationId("10001");
		assertFalse(LivenessService.requiresSupervisor(registration));
		service.recordExceptionCapture(registration, "individualBiometrics", 3);
		assertTrue(LivenessService.requiresSupervisor(registration));
		assertTrue(registration.getFaceLivenessExceptions().contains("individualBiometrics"));
		verify(audit).audit(AuditEvent.LIVENESS_EXCEPTION_CAPTURE, Components.LIVENESS, "10001", "REGISTRATION_ID",
				"workflow=RESIDENT, attempts=3, field=individualBiometrics");
	}

	@Test
	public void aFailingAuditStillMarksTheRegistration() {
		doThrow(new RuntimeException("database")).when(audit).audit(any(AuditEvent.class), any(Components.class),
				any(String.class), any(String.class), any(String.class));
		RegistrationDTO registration = new RegistrationDTO();
		service.recordExceptionCapture(registration, "individualBiometrics", 3);
		assertTrue(LivenessService.requiresSupervisor(registration));
	}

	@Test
	public void thePacketCarriesTheMarkOnlyWhenTheRegistrationIsMarked() {
		RegistrationDTO registration = new RegistrationDTO();
		Map<String, String> metaData = new LinkedHashMap<>();
		LivenessService.addPacketMark(registration, metaData);
		assertTrue(metaData.isEmpty());
		registration.getFaceLivenessExceptions().add("individualBiometrics");
		registration.getFaceLivenessExceptions().add("guardianBiometrics");
		LivenessService.addPacketMark(registration, metaData);
		assertEquals("individualBiometrics,guardianBiometrics", metaData.get("faceLivenessException"));
	}

	@Test
	public void thePacketCarriesTheCheckOfEachFace() throws Exception {
		RegistrationDTO registration = new RegistrationDTO();
		Map<String, Object> challenge = new LinkedHashMap<>();
		challenge.put("type", "BLINK");
		challenge.put("result", "PASSED");
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("decision", "PASSED");
		details.put("challenges", Collections.singletonList(challenge));
		LivenessService.recordCheck(registration, "individualBiometrics", details);
		Map<String, String> metaData = new LinkedHashMap<>();
		LivenessService.addPacketMark(registration, metaData);
		assertFalse("not marked", metaData.containsKey("faceLivenessException"));
		JsonNode carried = new ObjectMapper().readTree(metaData.get("faceLiveness")).path("individualBiometrics");
		assertEquals("PASSED", carried.path("decision").asText());
		assertEquals("BLINK", carried.path("challenges").path(0).path("type").asText());
	}

	@Test
	public void aCaptureUnderExceptionIsCarriedAsSuchInTheCheck() throws Exception {
		RegistrationDTO registration = new RegistrationDTO();
		service.recordExceptionCapture(registration, "individualBiometrics", 3);
		Map<String, String> metaData = new LinkedHashMap<>();
		LivenessService.addPacketMark(registration, metaData);
		JsonNode carried = new ObjectMapper().readTree(metaData.get("faceLiveness")).path("individualBiometrics");
		assertEquals("EXCEPTION", carried.path("decision").asText());
		assertEquals(3, carried.path("attempts").asInt());
		assertEquals("individualBiometrics", metaData.get("faceLivenessException"));
	}

	@Test
	public void theRegistrationIsMarkedBeforeTheCaptureWithoutAnAudit() {
		RegistrationDTO registration = new RegistrationDTO();
		LivenessService.markException(registration, "individualBiometrics");
		assertTrue(LivenessService.requiresSupervisor(registration));
		org.mockito.Mockito.verifyZeroInteractions(audit);
	}

	@Test
	public void theOperatorCannotReviewTheirOwnRegistrationWhateverTheCase() {
		RegistrationDTO registration = new RegistrationDTO();
		assertFalse(LivenessService.isValidReviewer(user("jdoe", "REGISTRATION_SUPERVISOR"), "jdoe", registration));
		assertFalse(LivenessService.isValidReviewer(user("jdoe", "REGISTRATION_SUPERVISOR"), "JDOE", registration));
		assertFalse(LivenessService.isValidReviewer(null, "jdoe", registration));
		// Not marked: another user, as in 1.2.0.2.
		assertTrue(LivenessService.isValidReviewer(user("asmith", "REGISTRATION_OFFICER"), "jdoe", registration));
	}

	@Test
	public void aMarkedRegistrationNeedsTheSupervisorRole() {
		RegistrationDTO registration = new RegistrationDTO();
		LivenessService.markException(registration, "individualBiometrics");
		assertFalse(LivenessService.isValidReviewer(user("asmith", "REGISTRATION_OFFICER"), "jdoe", registration));
		assertFalse(LivenessService.isValidReviewer(user("asmith"), "jdoe", registration));
		assertTrue(LivenessService.isValidReviewer(user("asmith", "REGISTRATION_OFFICER", "REGISTRATION_SUPERVISOR"),
				"jdoe", registration));
	}

	@Test
	public void theFaceIsNotOfferedToTheSupervisorOfAMarkedRegistrationWithoutLiveness() {
		RegistrationDTO registration = new RegistrationDTO();
		List<String> modes = Arrays.asList("PWD", "FACE");
		assertEquals(modes, LivenessService.supervisorModes(modes, registration, false));
		LivenessService.markException(registration, "individualBiometrics");
		assertEquals(Collections.singletonList("PWD"), LivenessService.supervisorModes(modes, registration, false));
		assertEquals(Collections.singletonList("PWD"),
				LivenessService.supervisorModes(Arrays.asList("PWD", "face"), registration, false));
		assertEquals(modes, LivenessService.supervisorModes(modes, registration, true));
		assertEquals(Collections.emptyList(),
				LivenessService.supervisorModes(Collections.singletonList("FACE"), registration, false));
	}

	@Test
	public void aMarkedFieldIsCapturedAgainUnderTheException() {
		RegistrationDTO registration = new RegistrationDTO();
		LivenessConfig resident = service.config(Workflow.RESIDENT);
		assertFalse(LivenessService.capturesUnderException(registration, "individualBiometrics", resident, 3));
		LivenessService.markException(registration, "individualBiometrics");
		assertTrue(LivenessService.capturesUnderException(registration, "individualBiometrics", resident, 3));
		assertFalse(LivenessService.capturesUnderException(registration, "guardianBiometrics", resident, 3));
		assertFalse(LivenessService.capturesUnderException(registration, "individualBiometrics", resident, 2));
		settings.put(P + "resident.on_max_attempts", "BLOCK");
		assertFalse(LivenessService.capturesUnderException(registration, "individualBiometrics",
				service.config(Workflow.RESIDENT), 3));
	}

	@Test
	public void theMarkGoesWithTheFaceOfItsField() {
		RegistrationDTO registration = new RegistrationDTO();
		LivenessService.markException(registration, "individualBiometrics");
		LivenessService.markException(registration, "guardianBiometrics");
		registration.clearBIOCache("individualBiometrics", "leftEye");
		assertTrue(registration.getFaceLivenessExceptions().contains("individualBiometrics"));
		registration.clearBIOCache("individualBiometrics", "face");
		assertEquals(Collections.singleton("guardianBiometrics"), registration.getFaceLivenessExceptions());
		registration.clearRegistrationDto();
		assertFalse(LivenessService.requiresSupervisor(registration));
	}

	private static UserDTO user(String id, String... roles) {
		UserDTO user = new UserDTO();
		user.setId(id);
		java.util.Set<UserRoleDTO> userRoles = new java.util.HashSet<>();
		for (String role : roles) {
			UserRoleDTO userRole = new UserRoleDTO();
			userRole.setUsrId(id);
			userRole.setRoleCode(role);
			userRoles.add(userRole);
		}
		user.setUserRole(userRoles);
		return user;
	}
}
