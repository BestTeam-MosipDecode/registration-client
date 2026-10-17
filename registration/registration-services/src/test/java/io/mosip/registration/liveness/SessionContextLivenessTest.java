/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.dto.AuthenticationValidatorDTO;
import io.mosip.registration.dto.UserDTO;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.service.bio.BioService;
import io.mosip.registration.service.login.LoginService;
import io.mosip.registration.service.security.AuthenticationService;

/** The face login compares a face only after the liveness gate accepted it. */
public class SessionContextLivenessTest {

	@Before
	public void setUpTheSettings() {
		Map<String, Object> settings = new HashMap<>();
		settings.put(RegistrationConstants.CAPTURE_TIME_OUT, "10000");
		settings.put(RegistrationConstants.FACE_AUTHENTICATION_THRESHOLD, "30");
		io.mosip.registration.context.ApplicationContext.getInstance().setApplicationMap(settings);
	}

	@After
	public void forgetTheBeans() {
		SessionContext.setApplicationContext(null);
		io.mosip.registration.context.ApplicationContext.getInstance().setApplicationMap(new HashMap<>());
	}

	@Test
	public void aFaceRefusedByTheGateIsNeverCompared() throws Exception {
		UserDTO user = new UserDTO();
		user.setId("officer");
		user.setUserRole(new HashSet<>());
		LoginService loginService = mock(LoginService.class);
		when(loginService.getUserDetail("officer")).thenReturn(user);
		when(loginService.getModesOfLogin(anyString(), any())).thenReturn(Collections.singletonList("FACE"));
		List<BiometricsDto> captured = Collections.singletonList(new BiometricsDto());
		BioService bioService = mock(BioService.class);
		when(bioService.captureModalityForAuth(any(MDMRequestDto.class))).thenReturn(captured);
		AuthenticationService authenticationService = mock(AuthenticationService.class);
		when(authenticationService.authValidator(anyString(), anyString(), anyListOf(BiometricsDto.class)))
				.thenReturn(true);
		LivenessAuthGate gate = mock(LivenessAuthGate.class);
		when(gate.accept(Workflow.OPERATOR, "officer", captured)).thenReturn(false);
		ApplicationContext beans = mock(ApplicationContext.class);
		when(beans.getBean(LoginService.class)).thenReturn(loginService);
		when(beans.getBean(BioService.class)).thenReturn(bioService);
		when(beans.getBean(AuthenticationService.class)).thenReturn(authenticationService);
		when(beans.getBean(LivenessAuthGate.class)).thenReturn(gate);
		SessionContext.setApplicationContext(beans);

		AuthenticationValidatorDTO validator = new AuthenticationValidatorDTO();
		validator.setUserId("officer");
		assertFalse(SessionContext.create(user, RegistrationConstants.FACE, false, false, validator));
		verify(gate).accept(eq(Workflow.OPERATOR), eq("officer"), eq(captured));
		verify(authenticationService, never()).authValidator(anyString(), anyString(),
				anyListOf(BiometricsDto.class));
	}
}
