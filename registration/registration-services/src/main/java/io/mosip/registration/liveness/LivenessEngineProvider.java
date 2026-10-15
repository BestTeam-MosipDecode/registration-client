/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
