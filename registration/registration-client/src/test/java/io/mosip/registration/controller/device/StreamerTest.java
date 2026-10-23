/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.mosip.registration.liveness.StreamFrameListener;

public class StreamerTest {

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9 };

	/** Records what the streamer gives to its listener. */
	static final class RecordingListener implements StreamFrameListener {
		final List<byte[]> frames = new ArrayList<>();
		Throwable interruption;

		@Override
		public void onFrame(byte[] jpeg) {
			frames.add(jpeg);
		}

		@Override
		public void onStreamInterrupted(Throwable cause) {
			interruption = cause;
		}
	}

	private final Streamer streamer = new Streamer();

	private static byte[] part(byte[] jpeg) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(("--mosip\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
		out.write(jpeg);
		out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
		return out.toByteArray();
	}

	@Test
	public void readsOneFrameOfTheMultipartStream() throws IOException {
		byte[] image = streamer.retrieveNextImage(new ByteArrayInputStream(part(JPEG)));
		assertArrayEquals(JPEG, Arrays.copyOf(image, JPEG.length));
	}

	@Test
	public void readsThePassiveScoreSentByTheDeviceWithAFrame() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(("--BoundaryString\r\nContent-Type:image/jpeg\r\nX-Liveness-Score:0.9731\r\nContent-Length:"
				+ JPEG.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.write(JPEG);
		out.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		out.write(part(JPEG));
		ByteArrayInputStream stream = new ByteArrayInputStream(out.toByteArray());
		Streamer.StreamFrame scored = streamer.readFrame(stream);
		assertArrayEquals(JPEG, Arrays.copyOf(scored.image, JPEG.length));
		assertEquals(0.9731, scored.livenessScore, 0.0);
		assertNull("a frame without the header has no score", streamer.readFrame(stream).livenessScore);
	}

	@Test
	public void aScoreThatIsNotANumberIsNoScore() {
		assertNull(Streamer.livenessScore("Content-Type:image/jpeg\r\nX-Liveness-Score:high\r\nContent-Length:"));
		assertNull(Streamer.livenessScore("Content-Type:image/jpeg\r\nX-Liveness-Score:NaN\r\nContent-Length:"));
		assertEquals(0.5, Streamer.livenessScore("X-Liveness-Score: 0.5\r\n"), 0.0);
	}

	@Test
	public void theListenerReceivesTheScoreWithTheFrame() {
		List<Double> scores = new ArrayList<>();
		streamer.setFrameListener(new StreamFrameListener() {
			@Override
			public void onFrame(byte[] jpeg) {
				scores.add(null);
			}

			@Override
			public void onFrame(byte[] jpeg, Double livenessScore) {
				scores.add(livenessScore);
			}

			@Override
			public void onStreamInterrupted(Throwable cause) {
			}
		});
		streamer.notifyFrame(JPEG, 0.42);
		streamer.notifyFrame(JPEG);
		assertEquals(Arrays.asList(0.42, null), scores);
	}

	@Test(expected = EOFException.class, timeout = 5000)
	public void theEndOfTheStreamStopsTheReading() throws IOException {
		streamer.retrieveNextImage(new ByteArrayInputStream("--mosip\r\n".getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	public void withoutAListenerFramesChangeNothing() {
		streamer.notifyFrame(JPEG);
		streamer.notifyInterrupted(new IOException("unplugged"));
	}

	@Test
	public void theListenerReceivesFramesAndInterruptions() {
		RecordingListener listener = new RecordingListener();
		streamer.setFrameListener(listener);
		streamer.notifyFrame(JPEG);
		IOException unplugged = new IOException("unplugged");
		streamer.notifyInterrupted(unplugged);
		assertEquals(1, listener.frames.size());
		assertSame(JPEG, listener.frames.get(0));
		assertSame(unplugged, listener.interruption);
	}

	@Test
	public void aFailingListenerDoesNotStopThePreview() {
		streamer.setFrameListener(new StreamFrameListener() {
			@Override
			public void onFrame(byte[] jpeg) {
				throw new IllegalStateException("liveness bug");
			}

			@Override
			public void onStreamInterrupted(Throwable cause) {
				throw new IllegalStateException("liveness bug");
			}
		});
		streamer.notifyFrame(JPEG);
		streamer.notifyInterrupted(new IOException("unplugged"));
	}

	@Test
	public void aRemovedListenerReceivesNothing() {
		RecordingListener listener = new RecordingListener();
		streamer.setFrameListener(listener);
		streamer.setFrameListener(null);
		streamer.notifyFrame(JPEG);
		streamer.notifyInterrupted(new IOException("unplugged"));
		assertEquals(0, listener.frames.size());
		assertNull(listener.interruption);
	}
}
