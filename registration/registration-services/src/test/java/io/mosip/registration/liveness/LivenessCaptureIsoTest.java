/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.liveness;

import static org.junit.Assert.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.Test;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.registration.liveness.image.BgrImage;

/** The record returned by RCAPTURE: ISO/IEC 19794-5 holding a JPEG 2000 image, as the MOSIP mock device builds it. */
public class LivenessCaptureIsoTest {

	@Test
	public void anIsoFaceRecordBecomesPixels() throws Exception {
		BufferedImage face = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
		face.setRGB(10, 20, 0xFF0000);
		ByteArrayOutputStream jp2 = new ByteArrayOutputStream();
		ImageIO.write(face, "jpeg2000", jp2);

		ConvertRequestDto request = new ConvertRequestDto();
		request.setVersion("ISO19794_5_2011");
		request.setPurpose("Registration");
		request.setModality("Face");
		request.setImageType(0);
		request.setInputBytes(jp2.toByteArray());
		byte[] isoRecord = FaceEncoder.convertFaceImageToISO(request);

		BgrImage image = LivenessCapture.isoToImage(isoRecord);
		assertEquals(64, image.width());
		assertEquals(48, image.height());
	}
}
