package io.mosip.registration.liveness;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.liveness.LivenessConfig.OnMaxAttempts;
import io.mosip.registration.liveness.active.ChallengeType;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.Workflow;

/**
 * Reads the face liveness keys of a workflow (docs/configuration.md of face-liveness-pad): the key of the workflow
 * first, then the general key, then the default of the engine. An invalid value or a broken rule falls back to the
 * default and is logged, so that a wrong setting never weakens liveness.
 */
@Component
public class LivenessConfigProvider {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessConfigProvider.class);

	private final Function<String, String> source;

	public LivenessConfigProvider() {
		this(ApplicationContext::getStringValueFromApplicationMap);
	}

	LivenessConfigProvider(Function<String, String> source) {
		this.source = source;
	}

	/** Effective settings of the workflow, from the configuration synchronised with the server. */
	public LivenessConfig forWorkflow(Workflow workflow) {
		LivenessPolicy defaults = LivenessPolicy.defaults();
		Settings settings = new Settings(workflow);
		boolean enabled = settings.bool(RegistrationConstants.LIVENESS_ENABLED, true);
		double pass = settings.decimal(RegistrationConstants.LIVENESS_PASS_THRESHOLD, defaults.passThreshold());
		double attack = settings.decimal(RegistrationConstants.LIVENESS_ATTACK_THRESHOLD, defaults.attackThreshold());
		long minFrames = settings.integer(RegistrationConstants.LIVENESS_MIN_FRAMES, defaults.minFrames(), 1, 30);
		long windowMs = settings.integer(RegistrationConstants.LIVENESS_WINDOW_MS, defaults.windowMs(), 500, 10000);
		long passiveTimeoutMs = settings.integer(RegistrationConstants.LIVENESS_PASSIVE_TIMEOUT_MS,
				defaults.passiveTimeoutMs(), 5000, 120000);
		long samplingMs = settings.integer(RegistrationConstants.LIVENESS_SAMPLING_MS, defaults.samplingMs(), 33, 500);
		long minFacePx = settings.integer(RegistrationConstants.LIVENESS_MIN_FACE_PX, defaults.minFacePx(), 40, 1000);
		long maxYawDeg = settings.integer(RegistrationConstants.LIVENESS_MAX_YAW_DEG, (long) defaults.maxYawDeg(), 5,
				45);
		boolean activeEnabled = settings.bool(RegistrationConstants.LIVENESS_ACTIVE_ENABLED, defaults.activeEnabled());
		long minChallenges = settings.integer(RegistrationConstants.LIVENESS_MIN_CHALLENGES, defaults.minChallenges(),
				1, 4);
		Set<ChallengeType> challengeTypes = settings.challengeTypes(RegistrationConstants.LIVENESS_CHALLENGE_TYPES,
				defaults.challengeTypes());
		long challengeTimeoutMs = settings.integer(RegistrationConstants.LIVENESS_CHALLENGE_TIMEOUT_MS,
				defaults.challengeTimeoutMs(), 3000, 30000);
		long baselineMs = settings.integer(RegistrationConstants.LIVENESS_BASELINE_MS, defaults.baselineMs(), 300,
				3000);
		long maxAttempts = settings.integer(RegistrationConstants.LIVENESS_MAX_ATTEMPTS, defaults.maxAttempts(), 1, 10);
		OnMaxAttempts onMaxAttempts = settings.onMaxAttempts(RegistrationConstants.LIVENESS_ON_MAX_ATTEMPTS,
				workflow == Workflow.RESIDENT ? OnMaxAttempts.EXCEPTION : OnMaxAttempts.BLOCK);
		boolean diagnostic = settings.bool(RegistrationConstants.LIVENESS_DIAGNOSTIC_ENABLED, defaults.diagnostic());

		// Section 3 of docs/configuration.md: a broken rule puts every key involved back to its default.
		if (attack >= pass) {
			ruleBroken(workflow, "the attack threshold must be below the pass threshold");
			pass = defaults.passThreshold();
			attack = defaults.attackThreshold();
		}
		if (windowMs < minFrames * samplingMs) {
			ruleBroken(workflow, "the window must hold min_frames frames at the sampling interval");
			windowMs = defaults.windowMs();
			minFrames = defaults.minFrames();
			samplingMs = defaults.samplingMs();
		}
		if (baselineMs >= challengeTimeoutMs) {
			ruleBroken(workflow, "the baseline must be shorter than the challenge timeout");
			baselineMs = defaults.baselineMs();
			challengeTimeoutMs = defaults.challengeTimeoutMs();
		}
		if (onMaxAttempts == OnMaxAttempts.EXCEPTION && workflow != Workflow.RESIDENT) {
			ruleBroken(workflow, "the exception process is for residents only, BLOCK applied");
			onMaxAttempts = OnMaxAttempts.BLOCK;
		}

		LivenessPolicy policy = LivenessPolicy.builder().passThreshold(pass).attackThreshold(attack)
				.minFrames((int) minFrames).windowMs(windowMs).passiveTimeoutMs(passiveTimeoutMs)
				.samplingMs(samplingMs).minFacePx((int) minFacePx).maxYawDeg(maxYawDeg).activeEnabled(activeEnabled)
				.minChallenges((int) minChallenges).challengeTypes(challengeTypes)
				.challengeTimeoutMs(challengeTimeoutMs).baselineMs(baselineMs).maxAttempts((int) maxAttempts)
				.diagnostic(diagnostic).build();
		LivenessConfig config = new LivenessConfig(workflow, enabled, onMaxAttempts, policy);
		LOGGER.info("Effective face liveness settings, {}", config);
		return config;
	}

	private static void ruleBroken(Workflow workflow, String rule) {
		LOGGER.warn("Face liveness settings of {}: {}, defaults applied", workflow, rule);
	}

	/** Typed reading of the keys of one workflow. */
	private final class Settings {

		private final String workflowPrefix;

		private Settings(Workflow workflow) {
			this.workflowPrefix = RegistrationConstants.LIVENESS_PREFIX + workflow.name().toLowerCase(Locale.ROOT)
					+ ".";
		}

		/** The key that holds a value, then the trimmed value; null when neither key holds one. */
		private String[] lookup(String key) {
			String workflowKey = workflowPrefix + key.substring(RegistrationConstants.LIVENESS_PREFIX.length());
			for (String candidate : new String[] { workflowKey, key }) {
				String value = source.apply(candidate);
				if (value != null && !value.trim().isEmpty()) {
					return new String[] { candidate, value.trim() };
				}
			}
			return null;
		}

		private boolean bool(String key, boolean defaultValue) {
			String[] found = lookup(key);
			if (found == null) {
				return defaultValue;
			}
			switch (found[1].toUpperCase(Locale.ROOT)) {
			case "TRUE":
			case "Y":
				return true;
			case "FALSE":
			case "N":
				return false;
			default:
				return invalid(found, defaultValue);
			}
		}

		private long integer(String key, long defaultValue, long min, long max) {
			String[] found = lookup(key);
			if (found == null) {
				return defaultValue;
			}
			try {
				long value = Long.parseLong(found[1]);
				if (value >= min && value <= max) {
					return value;
				}
			} catch (NumberFormatException exception) {
				// Reported below.
			}
			return invalid(found, defaultValue);
		}

		private double decimal(String key, double defaultValue) {
			String[] found = lookup(key);
			if (found == null) {
				return defaultValue;
			}
			try {
				double value = Double.parseDouble(found[1]);
				if (value >= 0 && value <= 1) {
					return value;
				}
			} catch (NumberFormatException exception) {
				// Reported below.
			}
			return invalid(found, defaultValue);
		}

		private Set<ChallengeType> challengeTypes(String key, Set<ChallengeType> defaultValue) {
			String[] found = lookup(key);
			if (found == null) {
				return defaultValue;
			}
			Set<ChallengeType> types = EnumSet.noneOf(ChallengeType.class);
			for (String item : found[1].split(",", -1)) {
				try {
					types.add(ChallengeType.valueOf(item.trim().toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException exception) {
					return invalid(found, defaultValue);
				}
			}
			return types;
		}

		private OnMaxAttempts onMaxAttempts(String key, OnMaxAttempts defaultValue) {
			String[] found = lookup(key);
			if (found == null) {
				return defaultValue;
			}
			try {
				return OnMaxAttempts.valueOf(found[1].toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException exception) {
				return invalid(found, defaultValue);
			}
		}

		private <T> T invalid(String[] found, T defaultValue) {
			LOGGER.warn("Invalid face liveness setting {}={}, default {} applied", found[0], found[1], defaultValue);
			return defaultValue;
		}
	}
}
