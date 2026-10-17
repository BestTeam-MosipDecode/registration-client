/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.api.CaptureCheck;
import io.mosip.registration.liveness.api.Workflow;

/**
 * Face authentication of an operator or a supervisor gated on liveness (section 2.3 of docs/workflows.md). The screen
 * arms the gate with its session once liveness is established; the face validators call {@link #accept} between the
 * capture and the comparison with the stored templates. Without an armed session for that user and workflow, the face
 * is refused, so a path that skips liveness fails closed. One armed session lets one comparison through.
 */
@Component
public class LivenessAuthGate {

	private final LivenessService livenessService;
	// One armed session per workflow and user, so that two screens never take each other's session.
	private final Map<String, LivenessCapture> armed = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

	@Autowired
	public LivenessAuthGate(LivenessService livenessService) {
		this.livenessService = livenessService;
	}

	/** Liveness is established on this session: the next face captured for the user may be compared. */
	public void arm(Workflow workflow, String userId, LivenessCapture capture) {
		armed.put(key(workflow, userId), capture);
	}

	/** The session ends without a comparison: it no longer counts. Another session armed meanwhile stays. */
	public void disarm(LivenessCapture capture) {
		armed.values().remove(capture);
	}

	/**
	 * Called by the face validators, off the UI thread, between the capture and the comparison.
	 *
	 * @return true when the comparison may run: liveness is disabled for the workflow, or the armed session of this
	 *         user and workflow accepts the captured face, which must be exactly one record
	 */
	public boolean accept(Workflow workflow, String userId, List<BiometricsDto> captured) {
		if (!livenessService.config(workflow).isEnabled()) {
			return true;
		}
		LivenessCapture capture = armed.remove(key(workflow, userId));
		if (capture == null) {
			return false;
		}
		List<byte[]> records = new ArrayList<>();
		if (captured != null) {
			for (BiometricsDto record : captured) {
				records.add(record.getAttributeISO());
			}
		}
		CaptureCheck check = capture.checkCaptures(records);
		return check != null && check.accepted();
	}

	/** Liveness attempts of the user in the workflow, kept until a successful face authentication. */
	public AtomicInteger attempts(Workflow workflow, String userId) {
		return attempts.computeIfAbsent(key(workflow, userId), k -> new AtomicInteger());
	}

	/** The user is authenticated by face: the attempts start again from zero. */
	public void authenticated(Workflow workflow, String userId) {
		attempts.remove(key(workflow, userId));
	}

	private static String key(Workflow workflow, String userId) {
		return workflow + ":" + (userId == null ? "" : userId.trim().toLowerCase(Locale.ROOT));
	}
}
