package io.mosip.registration.liveness;

import org.springframework.stereotype.Component;

import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.ModelIntegrityException;
import io.mosip.registration.liveness.platform.desktop.ImageIoFrameDecoder;

/**
 * The liveness engine of the client, created on first use: its models are checked and loaded once. A missing or
 * modified model file is reported to the caller, which refuses the capture, and is tried again on the next use.
 */
@Component
public class LivenessEngineProvider {

	/** Creates the engine, {@code LivenessEngine.create} in production. */
	@FunctionalInterface
	public interface EngineFactory {
		LivenessEngine create() throws ModelIntegrityException;
	}

	private final EngineFactory factory;
	private LivenessEngine engine;

	public LivenessEngineProvider() {
		this(() -> LivenessEngine.create(new ImageIoFrameDecoder()));
	}

	LivenessEngineProvider(EngineFactory factory) {
		this.factory = factory;
	}

	public synchronized LivenessEngine get() throws ModelIntegrityException {
		if (engine == null) {
			engine = factory.create();
		}
		return engine;
	}
}
