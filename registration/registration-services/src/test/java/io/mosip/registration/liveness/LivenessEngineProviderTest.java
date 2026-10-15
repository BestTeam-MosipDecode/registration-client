/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.ModelIntegrityException;

public class LivenessEngineProviderTest {

	private int created;

	@Test
	public void createsTheEngineOnceOnFirstUse() throws Exception {
		LivenessEngine engine = mock(LivenessEngine.class);
		LivenessEngineProvider provider = new LivenessEngineProvider(() -> {
			created++;
			return engine;
		});
		assertEquals(0, created);
		assertSame(engine, provider.get());
		assertSame(engine, provider.get());
		assertEquals(1, created);
	}

	@Test
	public void aModelProblemIsReportedAndTriedAgainLater() throws Exception {
		LivenessEngine engine = mock(LivenessEngine.class);
		LivenessEngineProvider provider = new LivenessEngineProvider(() -> {
			if (created++ == 0) {
				throw new ModelIntegrityException("model file missing: face_mesh_v2_256.onnx");
			}
			return engine;
		});
		try {
			provider.get();
			fail("a modified model must stop the engine");
		} catch (ModelIntegrityException expected) {
			// next call tries again
		}
		assertSame(engine, provider.get());
	}
}
