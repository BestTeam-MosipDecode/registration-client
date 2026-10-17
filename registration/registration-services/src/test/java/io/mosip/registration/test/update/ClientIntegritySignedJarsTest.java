/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.test.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.mosip.registration.update.ClientIntegrityValidator;

/** The jars whose signature the client verifies at startup. */
public class ClientIntegritySignedJarsTest {

	@Test
	public void theClientJarsAndTheLivenessEngineAreVerified() {
		assertTrue(ClientIntegrityValidator.isSignatureVerified("registration-client-1.2.0.2.jar"));
		assertTrue(ClientIntegrityValidator.isSignatureVerified("registration-services-1.2.0.2.jar"));
		assertTrue(ClientIntegrityValidator.isSignatureVerified("liveness-engine-0.1.0.jar"));
		assertTrue(ClientIntegrityValidator.isSignatureVerified("Liveness-Engine-0.1.0.jar"));
	}

	@Test
	public void otherLibrariesAreCheckedByTheirChecksumOnly() {
		assertFalse(ClientIntegrityValidator.isSignatureVerified("onnxruntime-1.19.2.jar"));
		assertFalse(ClientIntegrityValidator.isSignatureVerified("spring-core-5.0.6.RELEASE.jar"));
	}
}
