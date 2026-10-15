package io.mosip.registration.liveness;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessServiceTest {

	private final Map<String, String> settings = new HashMap<>();
	private final LivenessEngine engine = mock(LivenessEngine.class);
	private int enginesCreated;
	private final LivenessService service = new LivenessService(new LivenessConfigProvider(settings::get),
			new LivenessEngineProvider(() -> {
				enginesCreated++;
				return engine;
			}), mock(AuditManagerService.class));
	private final LivenessCapture.Callbacks screen = mock(LivenessCapture.Callbacks.class);

	@Test
	public void noCaptureWhenLivenessIsDisabledForTheWorkflow() throws Exception {
		settings.put(RegistrationConstants.LIVENESS_PREFIX + "resident.enabled", "N");
		assertNull(service.start(Workflow.RESIDENT, "10001", "REGISTRATION_ID", screen));
		assertTrue("the engine is not even loaded", enginesCreated == 0);
	}

	@Test
	public void aCaptureRunsOnASessionOfTheEngineWithTheAuditListener() throws Exception {
		LivenessSession session = mock(LivenessSession.class);
		when(engine.newSession(eq(Workflow.RESIDENT), any(LivenessPolicy.class), any(LivenessEventListener.class)))
				.thenReturn(session);
		LivenessCapture capture = service.start(Workflow.RESIDENT, "10001", "REGISTRATION_ID", screen);
		assertNotNull(capture);
		ArgumentCaptor<LivenessEventListener> listener = ArgumentCaptor.forClass(LivenessEventListener.class);
		verify(engine).newSession(eq(Workflow.RESIDENT), any(LivenessPolicy.class), listener.capture());
		assertTrue(listener.getValue() instanceof LivenessAuditListener);
		capture.close();
		verify(session).close();
		verify(engine).newSession(same(Workflow.RESIDENT), any(LivenessPolicy.class), any(LivenessEventListener.class));
	}
}
