/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessEventListener;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;

public class AttemptOffsetListenerTest {

	private final List<LivenessUpdate> updates = new ArrayList<>();
	private final List<LivenessEvent> events = new ArrayList<>();
	private final AttemptOffsetListener listener = new AttemptOffsetListener(2, 3, new LivenessEventListener() {
		@Override
		public void onUpdate(LivenessUpdate update) {
			updates.add(update);
		}

		@Override
		public void onEvent(LivenessEvent event) {
			events.add(event);
		}
	});

	@Test
	public void numbersTheAttemptAcrossScans() {
		listener.onUpdate(new LivenessUpdate(SessionState.FAILED, FeedbackCode.MAX_ATTEMPTS_EXCEEDED, null, null, 0.4,
				1, 1, FailureReason.CHALLENGE_TIMEOUT, false, true, 0.7));
		LivenessUpdate update = updates.get(0);
		assertEquals(3, update.attempt());
		assertEquals(3, update.maxAttempts());
		assertEquals(FeedbackCode.MAX_ATTEMPTS_EXCEEDED, update.feedback());
		assertEquals(FailureReason.CHALLENGE_TIMEOUT, update.failureReason().get());
		assertEquals(0.4, update.progress(), 0);
		assertEquals(0.7, update.diagnosticScore().getAsDouble(), 0);
		assertTrue(update.finished());

		listener.onEvent(new LivenessEvent(LivenessEvent.Type.FAILED, Workflow.RESIDENT, 1, 800, "1.1.0",
				FailureReason.CHALLENGE_TIMEOUT, null, null));
		assertEquals(3, events.get(0).attempt());
		assertEquals(800, events.get(0).durationMs());
		assertEquals(FailureReason.CHALLENGE_TIMEOUT, events.get(0).failureReason().get());
	}

	@Test
	public void beforeTheFirstFrameNoAttemptIsCounted() {
		listener.onUpdate(new LivenessUpdate(SessionState.INIT, FeedbackCode.DEVICE_ERROR, null, null, 0, 0, 1, null,
				false, false, null));
		assertEquals(0, updates.get(0).attempt());
		listener.onEvent(new LivenessEvent(LivenessEvent.Type.DEVICE_ERROR, Workflow.RESIDENT, 0, 0, "1.1.0",
				FailureReason.DEVICE_ERROR, null, null));
		assertEquals(0, events.get(0).attempt());
	}
}
