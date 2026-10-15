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
