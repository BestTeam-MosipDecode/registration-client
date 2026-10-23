/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.mosip.registration.controller.device.Streamer;
import io.mosip.registration.liveness.LivenessAuthGate;
import io.mosip.registration.liveness.LivenessCapture;
import io.mosip.registration.liveness.LivenessConfig;
import io.mosip.registration.liveness.LivenessConfig.OnMaxAttempts;
import io.mosip.registration.liveness.LivenessService;
import io.mosip.registration.liveness.api.FailureReason;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.service.bio.BioService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;

/** The scan of a face authentication screen: its end when the screen is left, its stream and its attempts. */
public class LivenessAuthScanTest {

	private static boolean toolkit;

	private final LivenessService livenessService = mock(LivenessService.class);
	private final LivenessAuthGate gate = new LivenessAuthGate(livenessService);
	private final Streamer streamer = mock(Streamer.class);
	private final LivenessCapture capture = mock(LivenessCapture.class);
	private final InputStream stream = new ByteArrayInputStream(new byte[0]);
	private final List<String> failedAttempts = new CopyOnWriteArrayList<>();

	/** The JavaFX toolkit needs a display: without one, as on a build server, the screen tests are skipped. */
	@BeforeClass
	public static void startToolkit() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		try {
			Platform.startup(started::countDown);
			toolkit = started.await(10, TimeUnit.SECONDS);
		} catch (IllegalStateException alreadyStarted) {
			toolkit = true;
		} catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
			toolkit = false;
		}
	}

	@Test
	public void theComparisonLimitFollowsTheCaptureTimeout() {
		assertEquals(120000, LivenessAuthScan.comparisonLimitMs("90000"));
		assertEquals(120000, LivenessAuthScan.comparisonLimitMs(" 90000 "));
		assertEquals(90000, LivenessAuthScan.comparisonLimitMs(null));
		assertEquals(90000, LivenessAuthScan.comparisonLimitMs("abc"));
		assertEquals(90000, LivenessAuthScan.comparisonLimitMs("0"));
	}

	@Test(timeout = 20000)
	public void leavingTheScreenEndsTheScanAndStopsItsStream() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		ImageView image = startedScan();
		onFx(() -> image.getScene().setRoot(new Pane()));
		verify(streamer, timeout(5000)).stopStream(stream);
		verify(capture, timeout(5000)).abort();
	}

	@Test(timeout = 20000)
	public void eachFailedAttemptIsReportedOnce() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		startedScan();
		LivenessCapture.Callbacks callbacks = callbacks();
		LivenessUpdate failed = new LivenessUpdate(SessionState.FAILED, FeedbackCode.FAILED, null, null, 0, 1, 3,
				FailureReason.CHALLENGE_TIMEOUT, true, false, null);
		callbacks.onUpdate(failed);
		callbacks.onUpdate(failed);
		onFx(() -> {
		});
		assertEquals(1, failedAttempts.size());
		assertEquals("OPERATOR jdoe", failedAttempts.get(0));
		assertEquals(1, gate.attempts(Workflow.OPERATOR, "jdoe").get());
	}

	@Test(timeout = 20000)
	public void theSameUserOnTheSameScreenKeepsTheScanInProgress() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		ImageView image = startedScan();
		onFx(() -> scan.start(Workflow.OPERATOR, "jdoe", image, () -> {
		}));
		verify(livenessService, timeout(5000).times(1)).startScan(any(), anyInt(), anyString(), anyString(), any());
		assertTrue(failedAttempts.isEmpty());
	}

	@Test(timeout = 20000)
	public void afterTheLastAttemptAnEnrolmentOffersNoOtherSignIn() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		assertEquals(1, buttonsShown(paneAfterTheLastAttempt(false)));
		assertEquals(0, buttonsShown(paneAfterTheLastAttempt(true)));
	}

	@Test(timeout = 20000)
	public void onlyTheEnrolmentPaneIsCompact() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		assertFalse(paneAfterTheLastAttempt(false).getStyleClass().contains(LivenessAuthScan.COMPACT_STYLE));
		assertTrue(paneAfterTheLastAttempt(true).getStyleClass().contains(LivenessAuthScan.COMPACT_STYLE));
	}

	@Test(timeout = 20000)
	public void closingEndsTheScanAndTakesThePaneOffTheScreen() throws Exception {
		Assume.assumeTrue("no display", toolkit);
		ImageView image = startedScan();
		Pane screen = (Pane) image.getParent();
		assertEquals(1, panes(screen));
		onFx(scan::close);
		verify(streamer, timeout(5000)).stopStream(stream);
		verify(capture, timeout(5000)).abort();
		assertEquals(0, panes(screen));
	}

	private LivenessAuthScan scan;

	/** A scan of {@code jdoe} at login, whose stream has started. */
	private ImageView startedScan() throws Exception {
		LivenessConfig config = config();
		when(livenessService.startScan(eq(config), anyInt(), eq("jdoe"), anyString(), any())).thenReturn(capture);
		when(capture.open(any())).thenReturn(stream);
		scan = new LivenessAuthScan(livenessService, gate, mock(BioService.class), streamer, key -> {
		}, () -> {
		});
		scan.setOnFailedAttempt((workflow, user) -> failedAttempts.add(workflow + " " + user));
		ImageView image = new ImageView();
		onFx(() -> {
			AnchorPane screen = new AnchorPane(image);
			new Scene(screen);
			scan.start(Workflow.OPERATOR, "jdoe", image, () -> {
			});
		});
		verify(streamer, timeout(5000)).startStream(stream, image, null);
		return image;
	}

	private LivenessConfig config() {
		LivenessConfig config = new LivenessConfig(Workflow.OPERATOR, true, OnMaxAttempts.BLOCK,
				LivenessPolicy.defaults());
		when(livenessService.config(Workflow.OPERATOR)).thenReturn(config);
		return config;
	}

	/** Starts a scan for a user with no attempt left and returns its pane. */
	private LivenessStatusPane paneAfterTheLastAttempt(boolean enrolment) throws Exception {
		String user = enrolment ? "enrolled" : "signing-in";
		gate.attempts(Workflow.OPERATOR, user).set(config().getPolicy().maxAttempts());
		LivenessAuthScan exhausted = enrolment
				? LivenessAuthScan.forEnrolment(livenessService, gate, mock(BioService.class), streamer, key -> {
				})
				: new LivenessAuthScan(livenessService, gate, mock(BioService.class), streamer, key -> {
				}, () -> {
				});
		// Texts are their keys: the messages of the client need its application context.
		exhausted.paneFactory = () -> new LivenessStatusPane(Workflow.OPERATOR, key -> key, key -> key);
		ImageView image = new ImageView();
		AnchorPane screen = new AnchorPane(image);
		onFx(() -> {
			new Scene(screen);
			exhausted.start(Workflow.OPERATOR, user, image, () -> {
			});
		});
		return (LivenessStatusPane) screen.getChildren().stream().filter(LivenessStatusPane.class::isInstance)
				.findFirst().orElseThrow(AssertionError::new);
	}

	private static long buttonsShown(LivenessStatusPane pane) {
		return pane.getChildren().stream().filter(Node::isVisible).filter(HBox.class::isInstance)
				.flatMap(box -> ((HBox) box).getChildren().stream())
				.filter(button -> button instanceof Button && button.isVisible()).count();
	}

	private static long panes(Pane screen) {
		return screen.getChildren().stream().filter(LivenessStatusPane.class::isInstance).count();
	}

	private LivenessCapture.Callbacks callbacks() throws Exception {
		ArgumentCaptor<LivenessCapture.Callbacks> callbacks = ArgumentCaptor.forClass(LivenessCapture.Callbacks.class);
		verify(livenessService).startScan(any(), anyInt(), anyString(), anyString(), callbacks.capture());
		return callbacks.getValue();
	}

	/** Runs the action on the JavaFX thread and waits for it, and for the tasks queued before it. */
	private static void onFx(Runnable action) throws Exception {
		CompletableFuture<Void> done = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				action.run();
				done.complete(null);
			} catch (Throwable exception) {
				done.completeExceptionally(exception);
			}
		});
		done.get(10, TimeUnit.SECONDS);
	}
}
