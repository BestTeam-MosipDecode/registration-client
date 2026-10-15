package io.mosip.registration.liveness;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.ModelIntegrityException;
import io.mosip.registration.liveness.api.Workflow;

/** Starts the liveness-gated face captures of the client: resident capture, operator and supervisor authentication. */
@Component
public class LivenessService {

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

	/**
	 * A new capture on a new session of the engine, with the policy of the workflow and the audit listener.
	 *
	 * @return null when liveness is disabled for the workflow: the client keeps its original behaviour
	 * @throws ModelIntegrityException
	 *             when a model file is missing or modified: the capture must be refused
	 */
	public LivenessCapture start(Workflow workflow, String refId, String refIdType, LivenessCapture.Callbacks screen)
			throws ModelIntegrityException {
		LivenessConfig config = configProvider.forWorkflow(workflow);
		if (!config.isEnabled()) {
			return null;
		}
		LivenessCapture capture = new LivenessCapture(config, screen, LivenessCapture::isoToImage);
		LivenessSession session = engineProvider.get().newSession(workflow, config.getPolicy(),
				new LivenessAuditListener(auditManagerService, capture.listener(), refId, refIdType));
		capture.attach(session);
		return capture;
	}
}
