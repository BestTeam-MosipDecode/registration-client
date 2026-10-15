package io.mosip.registration.controller.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import javafx.scene.image.ImageView;

import io.mosip.registration.liveness.StreamFrameListener;

/** The streaming thread, the listener and the ways a stream ends. */
public class StreamerLifecycleTest {

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9 };

	/** Records frames and interruptions, from the streaming thread. */
	static final class Recorder implements StreamFrameListener {
		final List<byte[]> frames = new CopyOnWriteArrayList<>();
		final List<Throwable> interruptions = new CopyOnWriteArrayList<>();
		final CountDownLatch interrupted = new CountDownLatch(1);

		@Override
		public void onFrame(byte[] jpeg) {
			frames.add(jpeg);
		}

		@Override
		public void onStreamInterrupted(Throwable cause) {
			interruptions.add(cause);
			interrupted.countDown();
		}
	}

	private final Streamer streamer = new Streamer();
	private final Recorder recorder = new Recorder();

	private static byte[] part(byte[] jpeg) {
		String header = "--mosip\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n";
		byte[] head = header.getBytes(StandardCharsets.US_ASCII);
		byte[] out = new byte[head.length + jpeg.length + 2];
		System.arraycopy(head, 0, out, 0, head.length);
		System.arraycopy(jpeg, 0, out, head.length, jpeg.length);
		out[out.length - 2] = '\r';
		out[out.length - 1] = '\n';
		return out;
	}

	/** Bytes of a device stream that records whether the streamer closed it. */
	static final class TrackedStream extends java.io.ByteArrayInputStream {
		volatile boolean closed;

		TrackedStream(byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	/** Waits until the streaming thread reads from {@code stream}. */
	private void awaitReading(java.io.InputStream stream) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000;
		while (streamer.getUrlStream() != stream && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue("the streaming thread did not start", streamer.getUrlStream() == stream);
		Thread.sleep(100);
	}

	/** Like a socket stream: a blocked read fails as soon as the stream is closed. */
	static final class SocketLikeStream extends java.io.InputStream {
		private final CountDownLatch closed = new CountDownLatch(1);

		@Override
		public int read() throws IOException {
			try {
				closed.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			throw new IOException("Socket closed");
		}

		@Override
		public void close() {
			closed.countDown();
		}
	}

	/** Starts the streaming thread on a pipe and waits until it reads from it. */
	private PipedOutputStream startOnPipe() throws Exception {
		PipedOutputStream device = new PipedOutputStream();
		PipedInputStream stream = new PipedInputStream(device, 4096);
		streamer.setFrameListener(recorder);
		streamer.startStream(stream, null, null);
		long deadline = System.currentTimeMillis() + 2000;
		while (streamer.getUrlStream() != stream && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue("the streaming thread did not start", streamer.getUrlStream() == stream);
		return device;
	}

	@Test(timeout = 10000)
	public void framesOfTheStreamReachTheListener() throws Exception {
		PipedOutputStream device = startOnPipe();
		device.write(part(JPEG));
		device.flush();
		// Without an image view the preview fails after the frame; the frame must have been passed first.
		assertTrue(recorder.interrupted.await(5, TimeUnit.SECONDS));
		assertEquals(1, recorder.frames.size());
	}

	@Test(timeout = 10000)
	public void aStreamClosedByTheClientIsNotAnInterruption() throws Exception {
		SocketLikeStream stream = new SocketLikeStream();
		streamer.setFrameListener(recorder);
		streamer.startStream(stream, null, null);
		long deadline = System.currentTimeMillis() + 2000;
		while (streamer.getUrlStream() != stream && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		Thread.sleep(100);
		streamer.setUrlStream(null);
		assertTrue("no interruption expected", !recorder.interrupted.await(2, TimeUnit.SECONDS));
		assertTrue(recorder.interruptions.isEmpty());
	}

	@Test(timeout = 10000)
	public void closingTheStreamRemovesTheListener() throws Exception {
		startOnPipe();
		streamer.setUrlStream(null);
		streamer.notifyFrame(JPEG);
		assertTrue(recorder.frames.isEmpty());
	}

	@Test(timeout = 10000)
	public void theDeviceEndingItsStreamIsAnInterruption() throws Exception {
		PipedOutputStream device = startOnPipe();
		device.close();
		assertTrue(recorder.interrupted.await(5, TimeUnit.SECONDS));
		assertTrue(recorder.interruptions.get(0) instanceof IOException);
	}

	@Test
	public void anErrorFromTheListenerDoesNotStopThePreview() {
		streamer.setFrameListener(new StreamFrameListener() {
			@Override
			public void onFrame(byte[] jpeg) {
				throw new NoClassDefFoundError("ai/onnxruntime/OrtSession");
			}

			@Override
			public void onStreamInterrupted(Throwable cause) {
				throw new NoClassDefFoundError("ai/onnxruntime/OrtSession");
			}
		});
		streamer.notifyFrame(JPEG);
		streamer.notifyInterrupted(new EOFException());
	}

	@Test(timeout = 10000)
	public void aReplacedStreamIsNotAnInterruption() throws Exception {
		SocketLikeStream first = new SocketLikeStream();
		streamer.setFrameListener(recorder);
		streamer.startStream(first, null, null);
		awaitReading(first);
		SocketLikeStream second = new SocketLikeStream();
		streamer.startStream(second, null, null);
		awaitReading(second);
		assertTrue("the replaced stream must not be reported", !recorder.interrupted.await(1, TimeUnit.SECONDS));
		assertTrue("the new stream keeps running", streamer.getUrlStream() == second);
		streamer.setUrlStream(null);
	}

	@Test(timeout = 10000)
	public void severalFramesReachTheListenerAndThePreview() throws Exception {
		byte[] jpeg = tinyJpeg();
		byte[] one = part(jpeg);
		byte[] three = new byte[one.length * 3];
		for (int i = 0; i < 3; i++) {
			System.arraycopy(one, 0, three, i * one.length, one.length);
		}
		ImageView preview = new ImageView();
		streamer.setFrameListener(recorder);
		streamer.startStream(new java.io.ByteArrayInputStream(three), preview, null);
		assertTrue(recorder.interrupted.await(5, TimeUnit.SECONDS));
		assertEquals(3, recorder.frames.size());
		assertTrue(recorder.interruptions.get(0) instanceof EOFException);
		assertTrue("the preview received the frames", preview.getImage() != null);
	}

	@Test(timeout = 10000)
	public void theStreamIsClosedWhenTheDeviceEndsIt() throws Exception {
		TrackedStream stream = new TrackedStream(part(JPEG));
		streamer.setFrameListener(recorder);
		streamer.startStream(stream, new ImageView(), null);
		assertTrue(recorder.interrupted.await(5, TimeUnit.SECONDS));
		long deadline = System.currentTimeMillis() + 2000;
		while (!stream.closed && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue("the connection must be released", stream.closed);
	}

	/** A small valid JPEG, so that the preview can decode it. */
	private static byte[] tinyJpeg() throws IOException {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
