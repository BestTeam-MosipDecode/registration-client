/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.LivenessAuthGate;
import io.mosip.registration.liveness.LivenessCapture;
import io.mosip.registration.liveness.LivenessConfig;
import io.mosip.registration.liveness.LivenessConfig.OnMaxAttempts;
import io.mosip.registration.liveness.LivenessService;
import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.Workflow;

/** The face an operator or a supervisor enrols at onboarding is stored only after liveness. */
public class BiometricsControllerOnboardLivenessTest {

	private final LivenessService livenessService = mock(LivenessService.class);
	private final LivenessAuthGate gate = new LivenessAuthGate(livenessService);

	@Test
	public void aFaceCapturedWithoutAnEstablishedLivenessCheckIsRefused() {
		livenessEnabled(true);
		assertFalse(BiometricsController.acceptOnboardFace(true, true, gate, "jdoe", oneFace()));
	}

	@Test
	public void aFaceCapturedAfterLivenessIsAcceptedOnceForThatUser() {
		livenessEnabled(true);
		LivenessCapture capture = mock(LivenessCapture.class);
		when(capture.checkCaptures(anyList())).thenReturn(CaptureCheck.accepted("test"));
		gate.arm(Workflow.OPERATOR, "jdoe", capture);
		assertFalse(BiometricsController.acceptOnboardFace(true, true, gate, "someone-else", oneFace()));
		assertTrue(BiometricsController.acceptOnboardFace(true, true, gate, "jdoe", oneFace()));
		assertFalse(BiometricsController.acceptOnboardFace(true, true, gate, "jdoe", oneFace()));
	}

	@Test
	public void withLivenessDisabledTheFaceIsStoredAsBefore() {
		livenessEnabled(false);
		assertTrue(BiometricsController.acceptOnboardFace(true, true, gate, "jdoe", oneFace()));
	}

	@Test
	public void otherModalitiesAndOtherScreensDoNotConsultTheGate() {
		LivenessAuthGate untouched = mock(LivenessAuthGate.class);
		assertTrue(BiometricsController.acceptOnboardFace(true, false, untouched, "jdoe", oneFace()));
		assertTrue(BiometricsController.acceptOnboardFace(false, true, untouched, "jdoe", oneFace()));
		verify(untouched, never()).accept(any(), any(), any());
	}

	private void livenessEnabled(boolean enabled) {
		when(livenessService.config(Workflow.OPERATOR)).thenReturn(
				new LivenessConfig(Workflow.OPERATOR, enabled, OnMaxAttempts.BLOCK, LivenessPolicy.defaults()));
	}

	private static List<BiometricsDto> oneFace() {
		BiometricsDto face = new BiometricsDto();
		face.setAttributeISO(new byte[] { 1 });
		return Collections.singletonList(face);
	}
}
