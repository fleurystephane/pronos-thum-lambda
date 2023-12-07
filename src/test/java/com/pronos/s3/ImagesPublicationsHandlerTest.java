package com.pronos.s3;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}