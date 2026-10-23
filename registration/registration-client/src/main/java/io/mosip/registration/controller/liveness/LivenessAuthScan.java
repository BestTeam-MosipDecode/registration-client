/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.controller.device.Streamer;
import io.mosip.registration.liveness.LivenessAuthGate;
import io.mosip.registration.liveness.LivenessCapture;
import io.mosip.registration.liveness.LivenessConfig;
import io.mosip.registration.liveness.LivenessService;
import io.mosip.registration.liveness.api.FeedbackCode;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.service.bio.BioService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.VPos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;

/**
 * Face authentication of an operator or a supervisor gated on liveness (section 2.3 of docs/workflows.md), for one
 * screen. A scan streams the face to a new session of the engine (one attempt) and shows its state in the pane. Once
 * liveness is established, it arms the {@link LivenessAuthGate} and asks the screen to capture and compare the face:
 * the user no longer presses the scan button. After the last attempt the face is blocked for that user until a
 * successful face authentication; there is no exception process for authentication.
 *
 * <p>
 * The onboarding of an operator or a supervisor uses the same scan for the face it enrols ({@link #forEnrolment}):
 * the user is already signed in, so after the last attempt the pane sends the user to the supervisor instead of
 * offering another sign-in method.
 *
 * <p>
 * Every method is called on the JavaFX thread.
 */
public final class LivenessAuthScan {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessAuthScan.class);

	// After liveness, the capture request waits up to the capture timeout of the client; then the comparison.
	private static final long DEFAULT_CAPTURE_TIMEOUT_MS = 60000;
	private static final long COMPARISON_MARGIN_MS = 30000;

	// Opening and ending sessions in order, off the JavaFX thread: the engine loads its models on first use.
	private static final ExecutorService SESSIONS = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "liveness-authentication");
		thread.setDaemon(true);
		return thread;
	});

	private final LivenessService livenessService;
	private final LivenessAuthGate gate;
	private final BioService bioService;
	private final Streamer streamer;
	private final Consumer<String> errorAlert;
	private final Runnable otherSignIn;
	// Onboarding: no other sign-in method after the last attempt.
	private final boolean enrolment;
	private BiConsumer<Workflow, String> onFailedAttempt = (workflow, userId) -> {
	};
	private LivenessStatusPane pane;
	// Operator and supervisor screens look the same. Tests give texts of their own.
	Supplier<LivenessStatusPane> paneFactory = () -> new LivenessStatusPane(Workflow.OPERATOR);
	private Scan scan;

	/**
	 * @param errorAlert
	 *            shows an error message key of the client when the pane cannot say it, for example when the engine
	 *            or the device cannot start
	 * @param otherSignIn
	 *            action of the button shown after the last attempt
	 */
	public LivenessAuthScan(LivenessService livenessService, LivenessAuthGate gate, BioService bioService,
			Streamer streamer, Consumer<String> errorAlert, Runnable otherSignIn) {
		this(livenessService, gate, bioService, streamer, errorAlert, otherSignIn, false);
	}

	private LivenessAuthScan(LivenessService livenessService, LivenessAuthGate gate, BioService bioService,
			Streamer streamer, Consumer<String> errorAlert, Runnable otherSignIn, boolean enrolment) {
		this.livenessService = livenessService;
		this.gate = gate;
		this.bioService = bioService;
		this.streamer = streamer;
		this.errorAlert = errorAlert;
		this.otherSignIn = otherSignIn;
		this.enrolment = enrolment;
	}

	/**
	 * The scan of an onboarding screen, where the user enrols a face: after the last attempt the pane sends the user
	 * to the supervisor, with no action.
	 */
	public static LivenessAuthScan forEnrolment(LivenessService livenessService, LivenessAuthGate gate,
			BioService bioService, Streamer streamer, Consumer<String> errorAlert) {
		return new LivenessAuthScan(livenessService, gate, bioService, streamer, errorAlert, null, true);
	}

	/**
	 * Called on the JavaFX thread for each failed attempt, with the workflow and the user of the scan, for example to
	 * count it as a failed login.
	 */
	public void setOnFailedAttempt(BiConsumer<Workflow, String> action) {
		onFailedAttempt = action == null ? (workflow, userId) -> {
		} : action;
	}

	/**
	 * The time left to the capture request and the comparison once liveness is established: the capture timeout of
	 * the client ({@code mosip.registration.capture_time_out}, in milliseconds) and a margin.
	 */
	static long comparisonLimitMs(Object captureTimeout) {
		long timeoutMs = DEFAULT_CAPTURE_TIMEOUT_MS;
		if (captureTimeout != null) {
			try {
				long configured = Long.parseLong(String.valueOf(captureTimeout).trim());
				if (configured > 0) {
					timeoutMs = configured;
				}
			} catch (NumberFormatException exception) {
				LOGGER.warn("Invalid capture timeout {}, {} ms applied", captureTimeout, DEFAULT_CAPTURE_TIMEOUT_MS);
			}
		}
		return timeoutMs + COMPARISON_MARGIN_MS;
	}

	/** Liveness applies to the workflow: the screen starts a scan instead of capturing at once. */
	public boolean isEnabled(Workflow workflow) {
		return livenessService.config(workflow).isEnabled();
	}

	/**
	 * The pane of the screen, placed over the bottom of the face image. Placed again when the screen was loaded anew,
	 * since the controllers outlive their FXML.
	 */
	private LivenessStatusPane pane(ImageView image) {
		if (pane == null) {
			pane = paneFactory.get();
			pane.setOnOtherSignIn(otherSignIn);
		}
		if (pane.getParent() == null || pane.getParent() != image.getParent()) {
			if (pane.getParent() instanceof Pane) {
				((Pane) pane.getParent()).getChildren().remove(pane);
			}
			placeOver(image, pane);
		}
		return pane;
	}

	/**
	 * Ends the scan in progress and starts one for the user: the stream goes to the image and to a new session.
	 *
	 * @param onPassed
	 *            called once liveness is established, to capture and compare the face; the gate is armed by then
	 */
	public void start(Workflow workflow, String userId, ImageView image, Runnable onPassed) {
		if (scan != null && !scan.ended && scan.workflow == workflow && scan.userId.equals(userId)
				&& scan.image == image) {
			// Stream then Scan: the same check goes on, no attempt is lost.
			return;
		}
		end();
		LivenessStatusPane statusPane = pane(image);
		statusPane.setOnRetry(() -> start(workflow, userId, image, onPassed));
		LivenessConfig config = livenessService.config(workflow);
		AtomicInteger attempts = gate.attempts(workflow, userId);
		if (LivenessService.decide(config, attempts.get()) != LivenessService.ScanDecision.LIVENESS) {
			showMaxAttempts();
			return;
		}
		statusPane.reset();
		image.setImage(null);
		Scan started = new Scan(workflow, config, attempts, userId, image, onPassed);
		scan = started;
		image.sceneProperty().addListener(started.sceneListener);
		SESSIONS.execute(started::open);
	}

	/**
	 * Ends the scan, after the comparison or when the screen is left: stops its stream and closes its session. An
	 * attempt still in progress ends as an interruption and counts.
	 */
	public void end() {
		Scan ended = scan;
		scan = null;
		if (ended != null) {
			ended.end();
		}
	}

	/**
	 * Ends the scan and takes the pane off the screen, for a screen that shows another modality in the same image.
	 * The next scan places the pane again.
	 */
	public void close() {
		end();
		if (pane != null && pane.getParent() instanceof Pane) {
			((Pane) pane.getParent()).getChildren().remove(pane);
		}
	}

	private void showMaxAttempts() {
		if (enrolment) {
			pane.showBlocked();
		} else {
			pane.showMaxAttempts(false);
		}
	}

	/** One attempt: one session of the engine. */
	private final class Scan implements LivenessCapture.Callbacks {

		private final Workflow workflow;
		private final LivenessConfig config;
		private final AtomicInteger attempts;
		private final String userId;
		private final ImageView image;
		private final Runnable onPassed;
		private final AtomicBoolean counted = new AtomicBoolean();
		private volatile LivenessCapture capture;
		private volatile boolean ended;
		private InputStream stream;
		private PauseTransition watchdog;
		// Leaving the screen by any way (Home, logout, another page) takes the image out of its scene.
		private final ChangeListener<Scene> sceneListener = (observable, before, now) -> {
			if (now == null && scan == this) {
				scan = null;
				end();
			}
		};

		private Scan(Workflow workflow, LivenessConfig config, AtomicInteger attempts, String userId, ImageView image,
				Runnable onPassed) {
			this.workflow = workflow;
			this.config = config;
			this.attempts = attempts;
			this.userId = userId;
			this.image = image;
			this.onPassed = onPassed;
		}

		/** On the session thread: opens the session and the stream, then starts the stream on the JavaFX thread. */
		private void open() {
			if (ended) {
				return;
			}
			InputStream opened;
			try {
				capture = livenessService.startScan(config, attempts.get(), userId,
						AuditReferenceIdTypes.USER_ID.getReferenceTypeId(), this);
				opened = capture.open(() -> bioService.getStream(RegistrationConstants.FACE_FULLFACE));
			} catch (IllegalStateException exception) {
				// The attempt of the previous scan was counted meanwhile: none is left.
				Platform.runLater(() -> {
					if (scan == this) {
						scan = null;
						end();
						showMaxAttempts();
					}
				});
				return;
			} catch (Exception | LinkageError exception) {
				// Engine or device unavailable: no face can be authenticated now.
				LOGGER.error("Face liveness scan not started", exception);
				Platform.runLater(() -> {
					if (scan == this) {
						scan = null;
						end();
						errorAlert.accept("LIVENESS_GENERIC_FAILURE");
					}
				});
				return;
			}
			Platform.runLater(() -> {
				if (ended || scan != this) {
					end();
					closeQuietly(opened);
					return;
				}
				stream = opened;
				streamer.setFrameListener(capture.frameListener());
				streamer.startStream(opened, image, null);
				startWatchdog();
			});
		}

		@Override
		public void onUpdate(LivenessUpdate update) {
			// Counted on the engine thread, so that an attempt ended by end() counts too.
			boolean countedNow = update.state() == SessionState.FAILED && counted.compareAndSet(false, true);
			if (countedNow) {
				attempts.incrementAndGet();
				Platform.runLater(() -> onFailedAttempt.accept(workflow, userId));
			}
			boolean failed = update.state() == SessionState.FAILED || update.feedback() == FeedbackCode.DEVICE_ERROR;
			Platform.runLater(() -> {
				// The updates of this scan, or the failure that ended it while no newer scan owns the pane.
				if (scan != this && !(scan == null && ended && failed)) {
					return;
				}
				if (update.state() == SessionState.FAILED && LivenessService.decide(config,
						attempts.get()) != LivenessService.ScanDecision.LIVENESS) {
					showMaxAttempts();
				} else {
					pane.show(update);
				}
				if (failed && scan == this && !capture.isCaptureRequested()) {
					scan = null;
					end();
				}
			});
		}

		@Override
		public void onPassed() {
			Platform.runLater(() -> {
				LivenessUpdate current = capture.current();
				if (scan != this || ended || current.state() != SessionState.PASSED || current.finished()) {
					return;
				}
				// The comparison ends the scan; if it never runs, the scan still ends.
				restartWatchdog(comparisonLimitMs(
						ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.CAPTURE_TIME_OUT)));
				capture.captureRequested();
				gate.arm(workflow, userId, capture);
				onPassed.run();
			});
		}

		private void end() {
			if (ended) {
				return;
			}
			ended = true;
			stopWatchdog();
			image.sceneProperty().removeListener(sceneListener);
			// Only the scan's own stream, even before its thread reads it: another screen may already stream.
			// Stopping it while it streams also clears the frame listener of the scan.
			streamer.stopStream(stream);
			SESSIONS.execute(() -> {
				LivenessCapture opened = capture;
				if (opened != null) {
					gate.disarm(opened);
					opened.abort();
				}
			});
		}

		/** Engine timeouts follow the frames: bound the whole check if the device stops sending them. */
		private void startWatchdog() {
			restartWatchdog(config.getPolicy().passiveTimeoutMs()
					+ config.getPolicy().minChallenges() * config.getPolicy().challengeTimeoutMs() + 15000);
		}

		private void restartWatchdog(long limitMs) {
			stopWatchdog();
			watchdog = new PauseTransition(javafx.util.Duration.millis(limitMs));
			watchdog.setOnFinished(event -> {
				if (scan == this) {
					scan = null;
					end();
				}
			});
			watchdog.play();
		}

		private void stopWatchdog() {
			if (watchdog != null) {
				watchdog.stop();
			}
		}
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		} catch (Exception exception) {
			LOGGER.error("Face stream not closed", exception);
		}
	}

	/** Places the pane over the bottom of the face image, in the layout that holds the image. */
	static void placeOver(ImageView image, LivenessStatusPane pane) {
		Parent parent = image.getParent();
		pane.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
		if (parent instanceof GridPane) {
			Integer column = GridPane.getColumnIndex(image);
			Integer row = GridPane.getRowIndex(image);
			((GridPane) parent).add(pane, column == null ? 0 : column, row == null ? 0 : row);
			GridPane.setValignment(pane, VPos.BOTTOM);
		} else if (parent instanceof AnchorPane) {
			AnchorPane anchorPane = (AnchorPane) parent;
			anchorPane.getChildren().add(pane);
			AnchorPane.setLeftAnchor(pane, image.getLayoutX());
			pane.setPrefWidth(image.getFitWidth());
			double bottom = anchorPane.getPrefHeight() - image.getLayoutY() - image.getFitHeight();
			AnchorPane.setBottomAnchor(pane, Math.max(0, bottom));
		} else if (parent instanceof Pane) {
			((Pane) parent).getChildren().add(pane);
		}
	}
}
