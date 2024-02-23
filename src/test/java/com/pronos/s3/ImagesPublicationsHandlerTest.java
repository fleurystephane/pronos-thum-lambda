package com.pronos.s3;

import jakarta.ws.rs.core.Response;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ImagesPublicationsHandlerTest {

    //@Test
    void callRestService() {
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        Response response = handler.callRestService(
                "121212",
                new PicturesURLs("A", "Aprime","B", "C", "D")
        );
        //Assertions.assertEquals(200, response.getStatus());
    }

    @Test
    void testFilename(){
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        ImagesPublicationsHandler.ExtractedInfo info1 = handler.extractInfosFromFilename("33fd1sf1ds1f3sd1fd-188_PUB.jpeg");
        assertEquals("188", info1.idPublicationExtracted);
        assertFalse(info1.isFirstImage);
        assertTrue(info1.isPublicPublicationExtracted);
        ImagesPublicationsHandler.ExtractedInfo info2 = handler.extractInfosFromFilename("550e8400-e29b-41d4-a716-446655440000-188_PUB.jpeg");
        assertEquals("188", info2.idPublicationExtracted);
        assertFalse(info2.isFirstImage);
        assertTrue(info2.isPublicPublicationExtracted);
        ImagesPublicationsHandler.ExtractedInfo info3 = handler.extractInfosFromFilename("550e8400-e29b-41d4-a716-446655440000-188_PUBFIRST.jpeg");
        assertEquals("188", info3.idPublicationExtracted);
        assertTrue(info3.isFirstImage);
        assertTrue(info3.isPublicPublicationExtracted);
        ImagesPublicationsHandler.ExtractedInfo info4 = handler.extractInfosFromFilename("550e8400-e29b-41d4-a716-446655440000-188_PRIFIRST.jpeg");
        assertEquals("188", info4.idPublicationExtracted);
        assertTrue(info4.isFirstImage);
        assertFalse(info4.isPublicPublicationExtracted);
        ImagesPublicationsHandler.ExtractedInfo info5 = handler.extractInfosFromFilename("550e8400-e29b-41d4-a716-446655440000-188_PRI.jpeg");
        assertEquals("188", info5.idPublicationExtracted);
        assertFalse(info5.isFirstImage);
        assertFalse(info5.isPublicPublicationExtracted);
    }

    @Test
    void testFirstFrame() throws JCodecException, IOException {
        ImagesPublicationsHandler handler = new ImagesPublicationsHandler();
        File imageFile = new File(getClass().getResource("/video.AVI").getFile());
        Picture pic = ImagesPublicationsHandler.getFrameFromFile(imageFile, 45);
        assertNotNull(pic.getSize());
        /*byte[] imageBytes = new byte[(int) imageFile.length()];
        try (InputStream fis = imageFile.toURL().openStream()) {
            fis.read(imageBytes);
            byte[] res = handler.extractFirstFrame(fis, "test.mp4");
            Assertions.assertTrue(res.length > 0);
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

    public static void main(String[] args) throws JCodecException, IOException {
        InputStream input = new FileInputStream("src/test/resources/opentdi.mp4");
        grab(input);
        input.close();
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