/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.liveness.LivenessConfig.OnMaxAttempts;
import io.mosip.registration.liveness.active.ChallengeType;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.Workflow;

public class LivenessConfigProviderTest {

	private static final String P = RegistrationConstants.LIVENESS_PREFIX;

	private final Map<String, String> values = new HashMap<>();
	private final LivenessConfigProvider provider = new LivenessConfigProvider(values::get);

	private LivenessPolicy policy(Workflow workflow) {
		return provider.forWorkflow(workflow).getPolicy();
	}

	@Test
	public void appliesTheDefaultsWhenNothingIsConfigured() {
		LivenessConfig resident = provider.forWorkflow(Workflow.RESIDENT);
		LivenessPolicy defaults = LivenessPolicy.defaults();
		assertTrue(resident.isEnabled());
		assertEquals(OnMaxAttempts.EXCEPTION, resident.getOnMaxAttempts());
		assertEquals(defaults.passThreshold(), resident.getPolicy().passThreshold(), 0);
		assertEquals(defaults.attackThreshold(), resident.getPolicy().attackThreshold(), 0);
		assertEquals(defaults.windowMs(), resident.getPolicy().windowMs());
		assertEquals(defaults.challengeTypes(), resident.getPolicy().challengeTypes());
		assertEquals(defaults.maxAttempts(), resident.getPolicy().maxAttempts());
		assertFalse(resident.getPolicy().diagnostic());
		assertEquals(OnMaxAttempts.BLOCK, provider.forWorkflow(Workflow.OPERATOR).getOnMaxAttempts());
		assertEquals(OnMaxAttempts.BLOCK, provider.forWorkflow(Workflow.SUPERVISOR).getOnMaxAttempts());
	}

	@Test
	public void readsEveryKeyInRange() {
		values.put(P + "passive.threshold.pass", "0.9");
		values.put(P + "passive.threshold.attack", "0.2");
		values.put(P + "passive.min_frames", "7");
		values.put(P + "passive.window_ms", "4000");
		values.put(P + "passive.timeout_ms", "15000");
		values.put(P + "frame.sampling_ms", "50");
		values.put(P + "quality.min_face_px", "100");
		values.put(P + "quality.max_yaw_deg", "30");
		values.put(P + "active.enabled", "N");
		values.put(P + "active.min_challenges", "3");
		values.put(P + "active.challenge_types", "BLINK,TURN_LEFT");
		values.put(P + "active.challenge_timeout_ms", "9000");
		values.put(P + "active.baseline_ms", "700");
		values.put(P + "max_attempts", "5");
		values.put(P + "diagnostic.enabled", "Y");
		LivenessPolicy p = policy(Workflow.OPERATOR);
		assertEquals(0.9, p.passThreshold(), 0);
		assertEquals(0.2, p.attackThreshold(), 0);
		assertEquals(7, p.minFrames());
		assertEquals(4000, p.windowMs());
		assertEquals(15000, p.passiveTimeoutMs());
		assertEquals(50, p.samplingMs());
		assertEquals(100, p.minFacePx());
		assertEquals(30, p.maxYawDeg(), 0);
		assertFalse(p.activeEnabled());
		assertEquals(3, p.minChallenges());
		assertEquals(EnumSet.of(ChallengeType.BLINK, ChallengeType.TURN_LEFT), p.challengeTypes());
		assertEquals(9000, p.challengeTimeoutMs());
		assertEquals(700, p.baselineMs());
		assertEquals(5, p.maxAttempts());
		assertTrue(p.diagnostic());
	}

	@Test
	public void theWorkflowKeyWinsOverTheGeneralKey() {
		values.put(P + "active.min_challenges", "3");
		values.put(P + "supervisor.active.min_challenges", "4");
		assertEquals(4, policy(Workflow.SUPERVISOR).minChallenges());
		assertEquals(3, policy(Workflow.OPERATOR).minChallenges());
		assertEquals(3, policy(Workflow.RESIDENT).minChallenges());
	}

	@Test
	public void oneWorkflowCanBeDisabled() {
		values.put(P + "operator.enabled", "N");
		assertFalse(provider.forWorkflow(Workflow.OPERATOR).isEnabled());
		assertTrue(provider.forWorkflow(Workflow.RESIDENT).isEnabled());
	}

	@Test
	public void readsBooleansAndListsLoosely() {
		values.put(P + "diagnostic.enabled", " y ");
		values.put(P + "active.enabled", "False");
		values.put(P + "active.challenge_types", " blink , Smile ");
		LivenessPolicy p = policy(Workflow.RESIDENT);
		assertTrue(p.diagnostic());
		assertFalse(p.activeEnabled());
		assertEquals(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE), p.challengeTypes());
	}

	@Test
	public void anInvalidValueFallsBackToItsDefault() {
		LivenessPolicy defaults = LivenessPolicy.defaults();
		values.put(P + "enabled", "maybe");
		values.put(P + "max_attempts", "11");
		values.put(P + "passive.min_frames", "0");
		values.put(P + "passive.window_ms", "abc");
		values.put(P + "passive.threshold.pass", "1.5");
		values.put(P + "passive.threshold.attack", "0,2");
		values.put(P + "active.challenge_types", "BLINK,LOOK_LEFT");
		values.put(P + "on_max_attempts", "LATER");
		LivenessConfig config = provider.forWorkflow(Workflow.RESIDENT);
		assertTrue(config.isEnabled());
		// An unreadable on_max_attempts never opens the exception process: BLOCK, not the default EXCEPTION.
		assertEquals(OnMaxAttempts.BLOCK, config.getOnMaxAttempts());
		LivenessPolicy p = config.getPolicy();
		assertEquals(defaults.maxAttempts(), p.maxAttempts());
		assertEquals(defaults.minFrames(), p.minFrames());
		assertEquals(defaults.windowMs(), p.windowMs());
		assertEquals(defaults.passThreshold(), p.passThreshold(), 0);
		assertEquals(defaults.attackThreshold(), p.attackThreshold(), 0);
		assertEquals(defaults.challengeTypes(), p.challengeTypes());
	}

	@Test
	public void anEmptyItemMakesTheWholeListInvalid() {
		values.put(P + "active.challenge_types", "BLINK,,SMILE");
		assertEquals(LivenessPolicy.defaults().challengeTypes(), policy(Workflow.RESIDENT).challengeTypes());
	}

	@Test
	public void aBlankWorkflowKeyFallsBackToTheGeneralKey() {
		values.put(P + "resident.max_attempts", "  ");
		values.put(P + "max_attempts", "4");
		assertEquals(4, policy(Workflow.RESIDENT).maxAttempts());
	}

	@Test
	public void thresholdsInTheWrongOrderBothFallBack() {
		values.put(P + "passive.threshold.pass", "0.5");
		values.put(P + "passive.threshold.attack", "0.6");
		LivenessPolicy p = policy(Workflow.RESIDENT);
		assertEquals(LivenessPolicy.defaults().passThreshold(), p.passThreshold(), 0);
		assertEquals(LivenessPolicy.defaults().attackThreshold(), p.attackThreshold(), 0);
	}

	@Test
	public void aWindowTooShortForItsFramesFallsBack() {
		values.put(P + "passive.window_ms", "500");
		values.put(P + "passive.min_frames", "10");
		values.put(P + "frame.sampling_ms", "100");
		LivenessPolicy p = policy(Workflow.RESIDENT);
		assertEquals(3000, p.windowMs());
		assertEquals(5, p.minFrames());
		assertEquals(100, p.samplingMs());
	}

	@Test
	public void aBaselineAsLongAsTheTimeoutFallsBack() {
		values.put(P + "active.baseline_ms", "3000");
		values.put(P + "active.challenge_timeout_ms", "3000");
		LivenessPolicy p = policy(Workflow.RESIDENT);
		assertEquals(600, p.baselineMs());
		assertEquals(LivenessPolicy.defaults().challengeTimeoutMs(), p.challengeTimeoutMs());
	}

	@Test
	public void theExceptionProcessIsForResidentsOnly() {
		values.put(P + "on_max_attempts", "EXCEPTION");
		values.put(P + "supervisor.on_max_attempts", "exception");
		assertEquals(OnMaxAttempts.EXCEPTION, provider.forWorkflow(Workflow.RESIDENT).getOnMaxAttempts());
		assertEquals(OnMaxAttempts.BLOCK, provider.forWorkflow(Workflow.OPERATOR).getOnMaxAttempts());
		assertEquals(OnMaxAttempts.BLOCK, provider.forWorkflow(Workflow.SUPERVISOR).getOnMaxAttempts());
		values.put(P + "resident.on_max_attempts", "BLOCK");
		assertEquals(OnMaxAttempts.BLOCK, provider.forWorkflow(Workflow.RESIDENT).getOnMaxAttempts());
	}

	@Test
	public void readsTheApplicationContextByDefault() {
		String key = P + "max_attempts";
		ApplicationContext.getInstance();
		ApplicationContext.map().put(key, "6");
		try {
			assertEquals(6, new LivenessConfigProvider().forWorkflow(Workflow.OPERATOR).getPolicy().maxAttempts());
		} finally {
			ApplicationContext.map().remove(key);
		}
	}

	@Test
	public void everyLivenessKeyUsesThePrefix() throws IllegalAccessException {
		Set<String> keys = new HashSet<>();
		for (Field field : RegistrationConstants.class.getFields()) {
			if (field.getName().startsWith("LIVENESS_") && Modifier.isStatic(field.getModifiers())
					&& !field.getName().equals("LIVENESS_PREFIX")) {
				String key = (String) field.get(null);
				assertTrue(key, key.startsWith(P) && key.length() > P.length());
				keys.add(key);
			}
		}
		assertEquals(17, keys.size());
	}

	@Test
	public void describesTheEffectiveSettings() {
		String text = provider.forWorkflow(Workflow.SUPERVISOR).toString();
		assertTrue(text, text.startsWith("SUPERVISOR"));
		assertTrue(text, text.contains("enabled=true"));
		assertTrue(text, text.contains(String.format(Locale.ROOT, "pass=%.2f", LivenessPolicy.defaults().passThreshold())));
	}
}
