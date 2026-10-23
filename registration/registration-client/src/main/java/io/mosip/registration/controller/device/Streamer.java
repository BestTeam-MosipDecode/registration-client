package io.mosip.registration.controller.device;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.liveness.StreamFrameListener;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

@Component
public class Streamer {

	private static final Logger LOGGER = AppConfig.getLogger(Streamer.class);

	private InputStream urlStream;

	// Optional receiver of the frames, for face liveness. Cleared when the client closes the stream.
	private volatile StreamFrameListener frameListener;

	// Streams closed by the client: their end is not an interruption of the device.
	private final Set<InputStream> closedStreams = Collections.newSetFromMap(new WeakHashMap<>());

	public void setFrameListener(StreamFrameListener frameListener) {
		this.frameListener = frameListener;
	}

	void notifyFrame(byte[] frame) {
		notifyFrame(frame, null);
	}

	void notifyFrame(byte[] frame, Double livenessScore) {
		StreamFrameListener listener = frameListener;
		if (listener != null) {
			try {
				listener.onFrame(frame, livenessScore);
			} catch (Throwable exception) {
				LOGGER.error("Frame listener failed", exception);
			}
		}
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		} catch (IOException exception) {
			LOGGER.error("Failed to close the stream", exception);
		}
	}

	private void markClosedByClient(InputStream stream) {
		synchronized (closedStreams) {
			closedStreams.add(stream);
		}
	}

	private boolean isClosedByClient(InputStream stream) {
		synchronized (closedStreams) {
			return closedStreams.contains(stream);
		}
	}

	void notifyInterrupted(Throwable cause) {
		StreamFrameListener listener = frameListener;
		if (listener != null) {
			try {
				listener.onStreamInterrupted(cause);
			} catch (Throwable exception) {
				LOGGER.error("Frame listener failed", exception);
			}
		}
	}

	public void setUrlStream(InputStream inputStream) {

		if (urlStream != null) {
			markClosedByClient(urlStream);
			try {
				urlStream.close();
			} catch (IOException exception) {
				LOGGER.error("Failed to close the stream",exception);
			} finally {
				urlStream = null;
				imageBytes = null;
			}
		}

		if (inputStream != null) {
			this.urlStream = inputStream;
			isRunning = true;
		} else {
			this.urlStream = null;
			isRunning = false;
			frameListener = null;
		}

	}

	/**
	 * Stops one stream of the client, whether or not its thread has started reading it: a stream stopped before is
	 * never read. The stream of another screen goes on.
	 */
	public synchronized void stopStream(InputStream stream) {
		if (stream == null) {
			return;
		}
		if (urlStream == stream) {
			setUrlStream(null);
			return;
		}
		markClosedByClient(stream);
		closeQuietly(stream);
	}

	public InputStream getUrlStream() {
		return urlStream;
	}

	private boolean isRunning = true;

	private final String CONTENT_LENGTH = "Content-Length:";

	private static final String LIVENESS_SCORE = "X-Liveness-Score:";

	private Thread streamer_thread = null;

	public byte[] imageBytes = null;

	// Last streaming image
	private static Image streamImage;

	// Image View, which UI need to be shown
	private static ImageView imageView;

	// Set Streaming image
	public void setStreamImage(Image streamImage) {
		this.streamImage = streamImage;
	}

	// Get Streaming image
	public Image getStreamImage() {
		return streamImage;
	}

	public byte[] getStreamImageBytes() {
		return imageBytes;
	}

	// Set ImageView
	public static void setImageView(ImageView imageView) {
		Streamer.imageView = imageView;
	}

	// Set Streaming image to ImageView
	public void setStreamImageToImageView() {
		imageView.setImage(streamImage);
	}

	public void startStream(InputStream inputStream, ImageView streamImage, ImageView scanImage) {

		LOGGER.info("Streamer Thread initiation started for : {}", System.currentTimeMillis());

		streamer_thread = new Thread(new Runnable() {

			public void run() {

				synchronized (Streamer.this) {
					if (inputStream != null && isClosedByClient(inputStream)) {
						// Stopped before this thread started: the stream in progress, if any, is left alone.
						return;
					}
					setUrlStream(inputStream);
				}

				// Each thread reads its own stream and stops when the client closes or replaces it.
				while (urlStream == inputStream && null != inputStream) {
					try {
						StreamFrame frame = readFrame(inputStream);
						imageBytes = frame.image;
						notifyFrame(imageBytes, frame.livenessScore);
						ByteArrayInputStream imageStream = new ByteArrayInputStream(imageBytes);
						Image img = new Image(imageStream);
						streamImage.setImage(img);
						if (null != scanImage) {
							setStreamImage(img);
						}
					} catch (Throwable t) {
						LOGGER.error("Failed in streamer_thread", t);
						if (!isClosedByClient(inputStream)) {
							notifyInterrupted(t);
						}
						closeQuietly(inputStream);
						if (urlStream == inputStream) {
							urlStream = null;
						}
					}
				}
			}
		}, "STREAMER_THREAD");

		streamer_thread.start();

		LOGGER.info("Streamer Thread initiated completed for : {}", System.currentTimeMillis());

	}

	/**
	 * Using the urlStream get the next JPEG image as a byte[]
	 *
	 * @return byte[] of the JPEG
	 * @throws IOException
	 */
	public byte[] retrieveNextImage(InputStream urlStream) throws IOException {
		return readFrame(urlStream).image;
	}

	/** One frame of the device stream, with the passive liveness score the device sent in its header, or null. */
	static final class StreamFrame {
		final byte[] image;
		final Double livenessScore;

		StreamFrame(byte[] image, Double livenessScore) {
			this.image = image;
			this.livenessScore = livenessScore;
		}
	}

	/**
	 * The passive liveness score in the header of a frame ({@code X-Liveness-Score}), as sent by a face device that
	 * computes it (section 4 of the decision flow of face-liveness-pad), or null when there is none or it is not a
	 * number.
	 */
	static Double livenessScore(String frameHeader) {
		int start = frameHeader.indexOf(LIVENESS_SCORE);
		if (start < 0) {
			return null;
		}
		start += LIVENESS_SCORE.length();
		int end = start;
		while (end < frameHeader.length() && frameHeader.charAt(end) != '\r' && frameHeader.charAt(end) != '\n') {
			end++;
		}
		try {
			double score = Double.parseDouble(frameHeader.substring(start, end).trim());
			return Double.isFinite(score) ? score : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	StreamFrame readFrame(InputStream urlStream) throws IOException {

		int currByte = -1;

		boolean captureContentLength = false;
		StringWriter contentLengthStringWriter = new StringWriter(128);
		StringWriter headerWriter = new StringWriter(128);

		int contentLength = 0;

		while ((currByte = urlStream.read()) > -1) {
			if (captureContentLength) {
				if (currByte == 10 || currByte == 13) {
					contentLength = Integer.parseInt(contentLengthStringWriter.toString().replace(" ", ""));
					break;
				}
				contentLengthStringWriter.write(currByte);

			} else {
				headerWriter.write(currByte);
				String tempString = headerWriter.toString();
				int indexOf = tempString.indexOf(CONTENT_LENGTH);
				if (indexOf > 0) {
					captureContentLength = true;
				}
			}
		}

		// 255 indicates the start of the jpeg image
		int nextByte;
		while ((nextByte = urlStream.read()) != 255) {
			if (nextByte == -1) {
				throw new EOFException("End of the device stream");
			}
		}

		// && urlStream.read()!=-1
		// if(urlStream.read()==-1) {
		// throw new RuntimeException("No stream available");
		// }

		// rest is the buffer
		byte[] imageBytes = new byte[contentLength + 1];
		// since we ate the original 255 , shove it back in
		imageBytes[0] = (byte) 255;
		int offset = 1;
		int numRead = 0;
		while (offset < imageBytes.length
				&& (numRead = urlStream.read(imageBytes, offset, imageBytes.length - offset)) >= 0) {
			offset += numRead;
		}

		return new StreamFrame(imageBytes, livenessScore(headerWriter.toString()));
	}

	/**
	 * Stop the loop, and allow it to clean up
	 */
	public synchronized void stop() {

		// A deliberate stop is not an interruption of the stream.
		frameListener = null;

		// Enable Auto-Logout
		SessionContext.setAutoLogout(true);

		if (streamer_thread != null) {
			try {
				isRunning = false;
				if (urlStream != null) {
					markClosedByClient(urlStream);
					urlStream.close();
				}
				streamer_thread = null;
			} catch (IOException e) {
				LOGGER.error("Failed to close the stream thread", e);
			}
		}
	}

}
