/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.net.URL;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.function.Function;

import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.constants.RegistrationUIConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.liveness.LivenessStatusModel;
import io.mosip.registration.liveness.LivenessStatusModel.Line;
import io.mosip.registration.liveness.LivenessStatusModel.Tone;
import io.mosip.registration.liveness.api.LivenessUpdate;
import io.mosip.registration.liveness.api.Workflow;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Face liveness status of a capture or authentication screen, as in docs/ui of face-liveness-pad: the position of the
 * challenge, a band with the main text, the lines under it, the progress, the liveness status and the buttons for
 * what the person decides. What to show comes from {@link LivenessStatusModel}. The controller places the pane in its
 * screen, wires the buttons and calls it on the JavaFX thread only.
 */
public final class LivenessStatusPane extends VBox {

	private static final String STYLESHEET = "liveness.css";

	private final Workflow workflow;
	private final Function<String, String> messages;
	private final Function<String, String> labels;
	private final Label challengeCount = new Label();
	private final HBox top = new HBox(challengeCount);
	private final Label bandText = new Label();
	private final HBox band = new HBox(bandText);
	private final VBox details = new VBox();
	private final ProgressBar progress = new ProgressBar();
	private final Label statusName = new Label();
	private final Label statusValue = new Label();
	private final HBox status = new HBox(statusName, statusValue);
	private final Label diagnosticName = new Label();
	private final Label diagnosticValue = new Label();
	private final HBox diagnostic = new HBox(diagnosticName, diagnosticValue);
	private final Button retry = new Button();
	private final Button continueUnderException = new Button();
	private final Button otherSignIn = new Button();
	private final HBox buttons = new HBox(retry, continueUnderException, otherSignIn);
	private boolean exceptionAllowed;
	private Runnable onRetry = () -> {
	};
	private Runnable onContinueUnderException = () -> {
	};
	private Runnable onOtherSignIn = () -> {
	};

	/** Texts in the application language of the client, right to left when that language is written so. */
	public LivenessStatusPane(Workflow workflow) {
		this(workflow, RegistrationUIConstants::getMessageLanguageSpecific, LivenessStatusPane::applicationLabel);
		String language = ApplicationContext.getInstance().getApplicationLanguage();
		if (language != null && ApplicationContext.getInstance().isLanguageRightToLeft(language)) {
			setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
		}
	}

	/** Texts from the given sources, for example in tests. A missing text shows its key. */
	LivenessStatusPane(Workflow workflow, Function<String, String> messages, Function<String, String> labels) {
		this.workflow = workflow;
		this.messages = messages;
		this.labels = labels;
		this.exceptionAllowed = workflow == Workflow.RESIDENT;
		getStyleClass().addAll("liveness-pane",
				workflow == Workflow.RESIDENT ? "liveness-resident" : "liveness-authentication");
		URL stylesheet = LivenessStatusPane.class.getClassLoader().getResource(STYLESHEET);
		if (stylesheet != null) {
			getStylesheets().add(stylesheet.toExternalForm());
		}
		top.setAlignment(Pos.CENTER_RIGHT);
		challengeCount.getStyleClass().add("liveness-challenge-count");
		band.getStyleClass().add("liveness-band");
		band.setAlignment(Pos.CENTER_LEFT);
		bandText.getStyleClass().add("liveness-band-text");
		bandText.setWrapText(true);
		details.getStyleClass().add("liveness-details");
		progress.getStyleClass().add("liveness-progress");
		progress.setMaxWidth(Double.MAX_VALUE);
		status.getStyleClass().add("liveness-status");
		statusName.getStyleClass().add("liveness-status-name");
		statusName.setText(labels.apply("liveness"));
		statusValue.getStyleClass().add("liveness-status-value");
		diagnostic.getStyleClass().add("liveness-diagnostic");
		diagnosticName.getStyleClass().add("liveness-status-name");
		diagnosticName.setText(labels.apply("livenessDiagnostic"));
		diagnosticValue.getStyleClass().add("liveness-diagnostic-value");
		buttons.getStyleClass().add("liveness-buttons");
		setUpButton(retry, "livenessTryAgain", false);
		setUpButton(continueUnderException, "livenessContinueException", true);
		setUpButton(otherSignIn, "livenessSignInPassword", true);
		retry.setOnAction(event -> onRetry.run());
		continueUnderException.setOnAction(event -> onContinueUnderException.run());
		otherSignIn.setOnAction(event -> onOtherSignIn.run());
		getChildren().addAll(top, band, details, progress, status, diagnostic, buttons);
		reset();
	}

	/** Shows an update of the engine. */
	public void show(LivenessUpdate update) {
		render(LivenessStatusModel.of(update, workflow, exceptionAllowed));
	}

	/** Attempts exhausted; {@code exceptionAllowed} applies to the resident only. */
	public void showMaxAttempts(boolean exceptionAllowed) {
		this.exceptionAllowed = exceptionAllowed;
		render(LivenessStatusModel.maxAttempts(workflow, exceptionAllowed));
	}

	/**
	 * Whether the exception process follows the last attempt of a resident, from {@code on_max_attempts}. True by
	 * default for the resident, as in the default configuration.
	 */
	public void setExceptionAllowed(boolean exceptionAllowed) {
		this.exceptionAllowed = exceptionAllowed;
	}

	/** Back to the state before the first analysed frame. */
	public void reset() {
		render(LivenessStatusModel.idle());
	}

	public void setOnRetry(Runnable action) {
		onRetry = action == null ? () -> {
		} : action;
	}

	public void setOnContinueUnderException(Runnable action) {
		onContinueUnderException = action == null ? () -> {
		} : action;
	}

	public void setOnOtherSignIn(Runnable action) {
		onOtherSignIn = action == null ? () -> {
		} : action;
	}

	private void render(LivenessStatusModel model) {
		Line count = model.challengeCount();
		setShown(top, count != null);
		if (count != null) {
			challengeCount.setText(format(labels.apply(count.key()), count.args()));
		}
		band.getStyleClass().removeIf(styleClass -> styleClass.startsWith("liveness-tone-"));
		setShown(band, model.band() != null);
		if (model.band() != null) {
			band.getStyleClass().add(toneClass(model.tone()));
			bandText.setText(message(model.band()));
		}
		details.getChildren().clear();
		for (Line line : model.details()) {
			Label detail = new Label(message(line));
			detail.setWrapText(true);
			detail.getStyleClass().add("liveness-detail");
			details.getChildren().add(detail);
		}
		setShown(details, !model.details().isEmpty());
		setShown(progress, model.progress() >= 0);
		if (model.progress() >= 0) {
			progress.setProgress(model.progress());
		}
		status.getStyleClass().removeIf(styleClass -> styleClass.startsWith("liveness-tone-"));
		if (model.band() != null) {
			status.getStyleClass().add(toneClass(model.tone()));
		}
		statusValue.setText(labels.apply(model.statusLabel()));
		Double score = model.diagnosticScore();
		setShown(diagnostic, score != null);
		if (score != null) {
			diagnosticValue.setText(String.format(Locale.ROOT, "%.3f", score));
		}
		setShown(retry, model.retry());
		setShown(continueUnderException, model.continueUnderException());
		setShown(otherSignIn, model.otherSignIn());
		setShown(buttons, model.retry() || model.continueUnderException() || model.otherSignIn());
	}

	private void setUpButton(Button button, String labelKey, boolean main) {
		button.setText(labels.apply(labelKey));
		button.getStyleClass().add(main ? "liveness-button-main" : "liveness-button");
	}

	private String message(Line line) {
		return format(messages.apply(line.key()), line.args());
	}

	private static String format(String text, Object[] args) {
		return args.length == 0 ? text : MessageFormat.format(text, args);
	}

	private static String toneClass(Tone tone) {
		return "liveness-tone-" + tone.name().toLowerCase(Locale.ROOT);
	}

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
		node.setManaged(shown);
	}

	private static String applicationLabel(String key) {
		String language = ApplicationContext.getInstance().getApplicationLanguage();
		ResourceBundle bundle = language == null ? null : ApplicationContext.getBundle(language, RegistrationConstants.LABELS);
		if (bundle == null) {
			return key;
		}
		try {
			return bundle.getString(key);
		} catch (MissingResourceException exception) {
			return key;
		}
	}
}
