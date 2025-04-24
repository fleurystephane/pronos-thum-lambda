package com.pronos.s3;

import jakarta.ws.rs.core.Response;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ImagesPublicationsHandlerTest {

    //@Test
    void callRestService() {
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        ImagesPublicationsHandler.ExtractedInfo infos = handler.extractInfosFromFilenameNew("950d9a44-004e-47d4-ba07-f8586e2fceb2-P107-PUB");
        try {
            handler.callRestService(
                    infos,
                    new PicturesURLs("A", "Aprime","B", "C", "D")
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        //Assertions.assertEquals(200, response.getStatus());
    }

    @Test
    void testnewKey(){
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        ImagesPublicationsHandler.ExtractedInfo info1 = handler.extractInfosFromFilenameNew("950d9a44-004e-47d4-ba07-f8586e2fceb2-P107-PUB");
        assertEquals("107", info1.idExtracted);
        assertTrue(info1.isPublic);
    }

    @Test
    void testFilename(){
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        ImagesPublicationsHandler.ExtractedInfo info1 = handler.extractInfosFromFilenameNew("33fd1sf1ds1f3sd1fd-P188-PUB.jpeg");
        assertEquals("188", info1.idExtracted);
        assertEquals("P", info1.type);
        assertFalse(info1.isFirstImage);
        assertTrue(info1.isPublic);
        ImagesPublicationsHandler.ExtractedInfo info2 = handler.extractInfosFromFilenameNew("550e8400-e29b-41d4-a716-446655440000-P188-PUB.jpeg");
        assertEquals("188", info2.idExtracted);
        assertFalse(info2.isFirstImage);
        assertTrue(info2.isPublic);
        ImagesPublicationsHandler.ExtractedInfo info3 = handler.extractInfosFromFilenameNew("550e8400-e29b-41d4-a716-446655440000-P188-PUBFIRST.jpeg");
        assertEquals("188", info3.idExtracted);
        assertTrue(info3.isFirstImage);
        assertTrue(info3.isPublic);
        ImagesPublicationsHandler.ExtractedInfo info4 = handler.extractInfosFromFilenameNew("550e8400-e29b-41d4-a716-446655440000-P188-PRIFIRST.jpeg");
        assertEquals("188", info4.idExtracted);
        assertTrue(info4.isFirstImage);
        assertFalse(info4.isPublic);
        ImagesPublicationsHandler.ExtractedInfo info5 = handler.extractInfosFromFilenameNew("550e8400-e29b-41d4-a716-446655440000-P188-PRI.jpeg");
        assertEquals("188", info5.idExtracted);
        assertFalse(info5.isFirstImage);
        assertFalse(info5.isPublic);
    }

    @Test
    void testFilenameNew(){
        ImagesPublicationsHandler h = new ImagesPublicationsHandler();
        ImagesPublicationsHandler.ExtractedInfo res1 = h.extractInfosFromFilenameNew("fqzfkgfeuygf213213-C13233-FIRST.png");
        assertEquals("13233", res1.idExtracted);
        assertEquals("C", res1.type);
        ImagesPublicationsHandler.ExtractedInfo res2 = h.extractInfosFromFilenameNew("fqzfkgfeuygf213213-P234-NUL.png");
        assertEquals("234", res2.idExtracted);
        assertEquals("P", res2.type);
    }

    private static void grab(InputStream inputStream) throws IOException, JCodecException {
        final ByteBuffer byteBuffer = readInputStreamToByteBuffer(inputStream);
        final ByteBufferSeekableByteChannel byteChannel =
                new ByteBufferSeekableByteChannel(byteBuffer, byteBuffer.limit());
        Picture pic = FrameGrab.getFrameFromChannel(byteChannel, 42);
        // Convertir la Picture en BufferedImage
        BufferedImage image = AWTUtil.toBufferedImage(pic);

        // Enregistrer l'image dans un fichier
        File outputImageFile = new File("output_image.jpg");
        ImageIO.write(image, "jpg", outputImageFile);

    }

    private static ByteBuffer readInputStreamToByteBuffer(InputStream inputStream) throws IOException {
        int bufferSize = 131072; // Taille du tampon
        byte[] buffer = new byte[bufferSize];
        int bytesRead;
        ByteBuffer byteBuffer = ByteBuffer.allocate(0); // Commence avec une allocation de taille 0

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            // Agrandir le ByteBuffer en fonction de la taille lue
            byteBuffer = growByteBuffer(byteBuffer, bytesRead);
            byteBuffer.put(buffer, 0, bytesRead);
        }

        byteBuffer.flip(); // Prepare le ByteBuffer pour la lecture ulterieure
        return byteBuffer;
    }

    private static ByteBuffer growByteBuffer(ByteBuffer byteBuffer, int additionalSize) {
        int newSize = byteBuffer.capacity() + additionalSize;
        if (newSize > byteBuffer.capacity()) {
            ByteBuffer newByteBuffer = ByteBuffer.allocate(newSize);
            byteBuffer.flip();
            newByteBuffer.put(byteBuffer);
            return newByteBuffer;
        } else {
            return byteBuffer;
        }
    }

}