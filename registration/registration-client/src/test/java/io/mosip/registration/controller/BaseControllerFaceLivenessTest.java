/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.LivenessAuthGate;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.service.security.AuthenticationService;

/** The face comparison of the packet and end of day authentication runs only after the liveness gate. */
public class BaseControllerFaceLivenessTest {

	private final BaseController controller = new BaseController();
	private final BioService bioService = mock(BioService.class);
	private final LivenessAuthGate gate = mock(LivenessAuthGate.class);
	private final AuthenticationService authenticationService = mock(AuthenticationService.class);
	private final List<BiometricsDto> captured = Collections.singletonList(new BiometricsDto());

	@Before
	public void setUp() throws Exception {
		inject("bioService", bioService);
		inject("livenessAuthGate", gate);
		inject("authenticationService", authenticationService);
		ApplicationContext.setGlobalConfigValueOf(RegistrationConstants.CAPTURE_TIME_OUT, "60000");
		ApplicationContext.setGlobalConfigValueOf(RegistrationConstants.FACE_AUTHENTICATION_THRESHOLD, "30");
		when(bioService.captureModalityForAuth(any())).thenReturn(captured);
		when(authenticationService.authValidator(anyString(), anyString(), anyList())).thenReturn(true);
	}

	@Test
	public void aFaceRefusedByTheGateIsNeverCompared() throws Exception {
		when(gate.accept(Workflow.SUPERVISOR, "supervisor1", captured)).thenReturn(false);
		assertFalse(controller.captureAndValidateFace("supervisor1", false, true, Workflow.SUPERVISOR));
		verify(authenticationService, never()).authValidator(anyString(), anyString(), anyList());
		verify(gate, never()).authenticated(any(), anyString());
	}

	@Test
	public void aFaceAcceptedByTheGateIsComparedAndResetsTheAttempts() throws Exception {
		when(gate.accept(Workflow.OPERATOR, "operator1", captured)).thenReturn(true);
		assertTrue(controller.captureAndValidateFace("operator1", false, false));
		verify(authenticationService).authValidator("operator1", "Face", captured);
		verify(gate).authenticated(Workflow.OPERATOR, "operator1");
	}

	@Test
	public void theSupervisorStepUsesTheSupervisorWorkflow() throws Exception {
		when(gate.accept(any(), anyString(), anyList())).thenReturn(false);
		controller.captureAndValidateFace("supervisor1", true, true);
		verify(gate).accept(Workflow.SUPERVISOR, "supervisor1", captured);
	}

	private void inject(String name, Object value) throws Exception {
		Field field = BaseController.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(controller, value);
	}
}
