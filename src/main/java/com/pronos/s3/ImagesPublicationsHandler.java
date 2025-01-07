package com.pronos.s3;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.ws.rs.client.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.coobird.thumbnailator.Thumbnails;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.waiters.S3Waiter;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pronos.s3.CommonUtility.*;

public class ImagesPublicationsHandler implements RequestHandler<S3Event,String> {
    private static final String BUCKETNAME_DESTINATION = System.getenv("BUCKETNAME_DESTINATION");
    public static final String ERROR_A = "Erreur lors de la recherche de l'id publication dans le nom de l'image d'origine...";
    static String EMAIL_TEC = "tecnical@example.com";
    public static final String PUBLIC_READ = "public-read";
    private static final boolean CALL_API = Boolean.parseBoolean(System.getenv("CALL_API"));

    public static String generateToken(String email, String userAgent){
        return JWT.create()
                .withClaim("email", email)
                .withClaim("user-agent", userAgent)
                .withIssuer(System.getenv("ISSUER"))
                .withSubject(email)
                .withExpiresAt(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .sign(Algorithm.HMAC256(System.getenv("SECRETKEY")));
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        String bucketName = s3Event.getRecords().get(0).getS3().getBucket().getName();
        String fileName = s3Event.getRecords().get(0).getS3().getObject().getKey();
        String imgExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
        ExtractedInfo infos = extractInfosFromFilenameNew(fileName);
        if (null == infos) {
            callInfos(ERROR_A, false,
                    context);
            return ERROR_A;
        }
        context.getLogger().log("id recupéré pour le nouveau media : " + infos.idExtracted);

        PicturesURLs picturesURLs = new PicturesURLs();

        String newKeyOrigin = getNewKey(infos.idExtracted, imgExtension, "");

        try (S3Client s3client = S3Client.builder().build()) {
            InputStream inputStream = getObject(s3client, bucketName, fileName);
            context.getLogger().log("Objet " + fileName + " bien récupéré depuis " + bucketName);

            BufferedImage imageOrigin = null;
            if(!isVideo(imgExtension)){
                imageOrigin = ImageIO.read(inputStream);
                if(imageOrigin != null){
                    infos.width = imageOrigin.getWidth();
                    infos.height = imageOrigin.getHeight();
                }
            }

            //On crée l'image de la vidéo, cette image ne sera pas envoyée à l'appli via API mais sera stockée dans le Bucket
            if (isVideo(imgExtension)) {
                /*try {
                    manageVideo(context, inputStream, fileName, s3client, infos, picturesURLs, imgExtension, newKeyOrigin);
                } catch (JCodecException e) {
                    context.getLogger().log("Error JCodeException : "+e.getMessage());
                    removeObject(context, bucketName, fileName, s3client);
                    callInfos("Error... JCodecException "+e.getMessage(), false, context);
                    return "Error... JCodecException";
                }*/
                if (!infos.isPublic) {
                    picturesURLs.setKeyblurred("DEFAULT_URL");
                }
            } else {//IMAGE
                //Si 1ere image alors on doit créer une vignette
                if (infos.isFirstImage) {
                    String key = getNewKey(infos.idExtracted, imgExtension,
                            "thumb");
                    String keyThumb = buildKeyThumb(context, fileName, s3client, inputStream, key);

                    context.getLogger().log("Vignette créée : " + keyThumb);
                    picturesURLs.setKeythumb(keyThumb);
                    inputStream.close();
                    inputStream = null;
                }

                if (!infos.isPublic) {
                    //2. Creation de l'image floutée
                    //BufferedImage imageOrigin = ImageIO.read(inputStream);
                    BufferedImage imageBlurred = CommonUtility.getBufferedImageFlou(imageOrigin);

                    String keyLocked = buildImgFlouttee(context, imgExtension, s3client,
                            imageBlurred, getNewKey(infos.idExtracted, imgExtension, "locked"));
                    context.getLogger().log("Image flouttée créée : " + keyLocked);
                    picturesURLs.setKeyblurred(keyLocked);

                    if (infos.isFirstImage) {
                        //3. Creation de la vignette de l'image flouttée
                        InputStream thumbnailLockedInputStream =
                                createSizedImage(bufferedImageToInputStream(imageBlurred, imgExtension),
                                        100,100);

                        byte[] imgBlurredThumbBytesArray = buildBAOS(thumbnailLockedInputStream).toByteArray();
                        String keyThumbLocked = saveImageToS3(s3client,
                                getNewKey(infos.idExtracted, imgExtension,
                                        "thumblocked"), imgBlurredThumbBytesArray, context);

                        context.getLogger().log("Vignette flouttée créée : " + keyThumbLocked);
                        picturesURLs.setKeyblurredthumb(keyThumbLocked);
                    }
                }
            }


            //4. Copie de l'image d'origine vers le bucket destination

            CopyObjectRequest copyObjectRequest = CopyObjectRequest.builder()
                    .copySource(bucketName + "/" + fileName)
                    .destinationBucket(BUCKETNAME_DESTINATION)
                    .destinationKey(newKeyOrigin)
                    .acl(PUBLIC_READ)
                    .build();

            CopyObjectResponse copyObjectResponse = s3client.copyObject(copyObjectRequest);
            HeadObjectRequest sourceHeadRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .build();
            if (s3client.headObject(sourceHeadRequest).eTag().equals(
                    copyObjectResponse.copyObjectResult().eTag())) {
                //5. Mise à jour de la publication avec les clés des images
                URL newKeyOriginUrl = s3client.utilities().getUrl(
                        builder -> builder.bucket(
                                ImagesPublicationsHandler.BUCKETNAME_DESTINATION).key(newKeyOrigin).build());
                picturesURLs.setKeyorigin(fileName.substring(0, fileName.lastIndexOf(".")));
                picturesURLs.setNewkeyorigin(newKeyOriginUrl.toString());
                if (CALL_API) {
                    Response resultat = callRestService(infos, picturesURLs);

                    if (resultat.getStatus() != Response.Status.OK.getStatusCode()) {
                        context.getLogger().log("Attention retour du service des Keys : " + resultat.getStatus());
                    } else {
                        context.getLogger().log("Service des keys retour OK  !!!");
                        //6. Suppression de l'image du bucket d'origine
                        removeObject(context, bucketName, fileName, s3client);

                    }
                }
            } else {
                context.getLogger().log("/!\\ les etags sont differents donc pas d'appel API pour mise à jour des keys!!!!");
                context.getLogger().log("s3client.headObject(sourceHeadRequest).eTag() = " + s3client.headObject(sourceHeadRequest).eTag());
                context.getLogger().log("copyObjectResponse.copyObjectResult().eTag() = " + copyObjectResponse.copyObjectResult().eTag());
            }
        } catch (IOException e) {
            callInfos("IOException : " + e.getMessage(), false, context);
            return "Error while reading file from S3 :::" + e.getMessage();
        }

        callInfos(picturesURLs.toString(), true, context);
        return getJsonKeysString(picturesURLs, infos.width, infos.height);

    }

    private boolean isContentExMedia(String fileName) {
        return false;
    }

    private void callInfos(String message, boolean isOk, Context context) {
        Client client = ClientBuilder.newClient();
        WebTarget webTarget
                = client.target(System.getenv("API_URL_CONTEXT"));

        WebTarget infosTarget =
                webTarget.path("/v1/publications/infosstored");
        Invocation.Builder invocationBuilder
                = infosTarget.request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer "+generateToken(EMAIL_TEC, "Tecnical"))
                .header("email", EMAIL_TEC);

        Response response = invocationBuilder
                .post(Entity.entity(getJsonInfosCallString(message, isOk), MediaType.APPLICATION_JSON));
        if(response.getStatus() == 500){
            context.getLogger().log("Erreur 500 sur infosstored");
        }
    }

    private static void removeObject(Context context, String bucketName, String fileName, S3Client s3client) {
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build();

        try {
            s3client.deleteObject(deleteObjectRequest);
            context.getLogger().log("Objet supprimé avec succès : " + fileName);
        } catch (S3Exception s3Exception) {
            context.getLogger().log("ECHEC de suppression de  " + fileName);
        }
    }

    public void manageVideo(Context context, InputStream inputStream, String fileName, S3Client s3client,
                            ExtractedInfo infos, PicturesURLs picturesURLs, String imgExtension, String newKey) throws IOException, JCodecException {
        context.getLogger().log("Video, on extrait une frame de "+fileName);
        //byte[] firstFrameBytes = extractFirstFrame(inputStream, fileName);
        byte[] firstFrameBytes = extractFirstFrameBis(inputStream);
        context.getLogger().log("On sauvegarde l'image de la frame... "+getFrameKey(newKey));
        String imgVideo = saveImageToS3(s3client, getFrameKey(newKey), firstFrameBytes, context);
        context.getLogger().log("Image de la video créée : " + imgVideo);
        if(infos.isFirstImage){
            //Il faut créer une vignette de l'image de la 1ere frame de la video
            String keyThumb = buildKeyThumb(context, fileName, s3client,
                    new ByteArrayInputStream(firstFrameBytes), getThumbFrameKey(newKey));
            context.getLogger().log("Vignette de la video créée : " + keyThumb);
            picturesURLs.setKeythumb(keyThumb);
        }
        if(!infos.isPublic){
            // il faut en plus créer les versions floutées des 2 images précédentes
            //2. Creation de l'image floutée
            BufferedImage imageOrigin = ImageIO.read(new ByteArrayInputStream(firstFrameBytes));
            BufferedImage imageBlurred = CommonUtility.getBufferedImageFlou(imageOrigin);
            String keyLocked = saveImageToS3(
                    s3client,
                    getFrameLockedKey(newKey),
                    bufferedImageToByteArray(imageBlurred,
                            imgExtension), context
            );
            context.getLogger().log("Image flouttée de la video créée : " + keyLocked);
            picturesURLs.setKeyblurred(keyLocked);

            if(infos.isFirstImage) {
                //3. Creation de la vignette de l'image flouttée
                InputStream thumbnailLockedInputStream =
                        createSizedImage(bufferedImageToInputStream(imageBlurred, imgExtension),
                                100,100);

                byte[] imgBlurredThumbBytesArray = buildBAOS(thumbnailLockedInputStream).toByteArray();
                String keyThumbLocked = saveImageToS3(s3client,
                        getThumbFrameLockedKey(newKey), imgBlurredThumbBytesArray, context);

                context.getLogger().log("Vignette flouttée créée : " + keyThumbLocked);
                picturesURLs.setKeyblurredthumb(keyThumbLocked);
            }
        }
    }

    private String getFrameKey(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = fileName.substring(0, dotIndex);
        return baseName+".jpg";
    }
    private String getFrameLockedKey(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = fileName.substring(0, dotIndex);
        return baseName+"locked.jpg";
    }
    private String getThumbFrameKey(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = fileName.substring(0, dotIndex);
        return baseName + "thumb.jpg";
    }
    private String getThumbFrameLockedKey(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = fileName.substring(0, dotIndex);
        return baseName+"thumblocked.jpg";
    }

    private String buildKeyThumb(Context context, String fileName, S3Client s3client, InputStream inputStream, String key) throws IOException {
        //1. Creation de la vignette et stockage dans le bucket destination
        context.getLogger().log("Creation d'une vignette pour " + fileName);
        // Créer la vignette de l'image SSI filename se termine par FIRST
        InputStream thumbnailInputStream = createSizedImage(inputStream, 100, 100);
        context.getLogger().log("thumbnailInputStream bien créé");

        // Sauvegarder la vignette dans S3
        context.getLogger().log("Tentative de sauvegarde dans le bucket " + BUCKETNAME_DESTINATION);

        byte[] imgOrginThumbBytesArray = buildBAOS(thumbnailInputStream).toByteArray();
        return saveImageToS3(s3client,
                key, imgOrginThumbBytesArray, context);
    }

    private String buildImgFlouttee(Context context, String imgExtension, S3Client s3client,
                                    BufferedImage imageBlurred, String key) throws IOException {
        return saveImageToS3(
                s3client,
                key,
                bufferedImageToByteArray(imageBlurred,
                        imgExtension), context
        );
    }

    public byte[] extractFirstFrame(InputStream video, String filename) throws IOException {

        int frameNumber = 42;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()){
            Picture picture = FrameGrab.getFrameFromFile(
                    convertInputStreamToFile(video, filename), frameNumber);
            BufferedImage bufferedImage = org.jcodec.scale.AWTUtil.toBufferedImage(picture);

            ImageIO.write(bufferedImage, "jpg", baos);
            baos.flush();
            return baos.toByteArray();
        } catch (IOException | JCodecException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] extractFirstFrameBis(InputStream video) throws IOException, JCodecException {

        final ByteBuffer byteBuffer = readInputStreamToByteBuffer(video);
        final ByteBufferSeekableByteChannel byteChannel =
                new ByteBufferSeekableByteChannel(byteBuffer, byteBuffer.limit());
        Picture pic = FrameGrab.getFrameFromChannel(byteChannel, 42);
        // Convertir la Picture en BufferedImage
        BufferedImage image = AWTUtil.toBufferedImage(pic);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        baos.flush();
        byte[] byteArray = baos.toByteArray();
        baos.close();

        return byteArray;
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

    public static Picture getFrameFromFile(File file, int frameNumber) throws IOException, JCodecException {
        FileChannelWrapper ch = null;
        try {
            ch = NIOUtils.readableChannel(file);
            return FrameGrab.createFrameGrab(ch).seekToFramePrecise(frameNumber).getNativeFrame();
        } finally {
            NIOUtils.closeQuietly(ch);
        }
    }


    private boolean isVideo(String imgExtension) {
        return imgExtension.equals("mp4") ||
                imgExtension.equals("mov") ||
                imgExtension.equals("avi") ||
                imgExtension.equals("wmv");
    }

    public Response callRestService(ExtractedInfo infos, PicturesURLs urLs) {
        Client client = ClientBuilder.newClient();
        WebTarget webTarget
                = client.target(System.getenv("API_URL_CONTEXT"));
        String ressourcePath = "publications";
        if("C".equals(infos.type)) {
            ressourcePath = "contentex";
        }
        WebTarget picturesTarget =
                webTarget.path("/v1/"+ressourcePath+"/" + infos.idExtracted + "/transformedpictures");
        Invocation.Builder invocationBuilder
                = picturesTarget.request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + generateToken(EMAIL_TEC, "Tecnical"))
                .header("email", EMAIL_TEC);

        return invocationBuilder
                .put(Entity.entity(getJsonKeysString(urLs,infos.width, infos.height), MediaType.APPLICATION_JSON));
    }

    private static String getJsonKeysString(PicturesURLs urLs,int width,int height) {
        String json = "{\"keyorigin\":\"" +
                urLs.getKeyorigin() + "\"," +
                "\"newkeyorigin\":\"" +
                urLs.getNewkeyorigin() + "\"," +
                "\"keythumb\":\"" + urLs.getKeythumb() + "\"," +
                "\"keyblurred\":\"" + urLs.getKeyblurred() + "\"," +
                "\"keyblurredthumb\":\"" + urLs.getKeyblurredthumb() + "\"," +
                "\"width\":"+width +"," +
                "\"height\":"+height+"}";
        return json;
    }

    private static String getJsonInfosCallString(String message, boolean ok){
        return "{\"message\":\""+message+"\", \"success\": "+ok+"}";
    }

    public ExtractedInfo extractInfosFromFilenameNew(String filename){
        String regex = ".*-(\\w)(\\d+)-([A-Z]+)(?:\\..*)?$";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(filename);

        if (matcher.matches()) {
            String visibility = matcher.group(3);
            ExtractedInfo res = new ExtractedInfo();
            res.type = matcher.group(1);
            res.idExtracted = matcher.group(2);
            res.isFirstImage = (visibility.contains("FIRST"));
            res.isPublic = (visibility.contains("PUB"));

            return res;
        }
        return null;
    }

    class ExtractedInfo {
        String idExtracted;
        boolean isPublic;
        boolean isFirstImage;
        String type;

        int width;
        int height;
    }

    private String getNewKey(String id, String extension, String suffix) {
        return UUID.randomUUID() + "-" + id +suffix+"." + extension;
    }

    // Méthode pour créer une vignette d'une image
    private static InputStream createSizedImage(InputStream inputStream, int width, int height) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Thumbnails.of(inputStream)
                .size(width, height)
                .toOutputStream(outputStream);
        return new ByteArrayInputStream(outputStream.toByteArray());
    }


    // Méthode pour sauvegarder la vignette dans S3
    private String saveImageToS3(S3Client s3Client,
                                 String newImageKey,
                                 byte[] bytes, Context context) throws IOException {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(ImagesPublicationsHandler.BUCKETNAME_DESTINATION)
                .key(newImageKey)
                .acl(PUBLIC_READ) // Rendre la vignette accessible publiquement
                .build();

        s3Client.putObject(
                request, RequestBody.fromBytes(bytes));
        S3Waiter waiter = s3Client.waiter();
        HeadObjectRequest requestWait = HeadObjectRequest.builder().bucket(ImagesPublicationsHandler.BUCKETNAME_DESTINATION)
                .key(newImageKey).build();

        waiter.waitUntilObjectExists(requestWait);

        // Récupérer l'URL complet de l'objet (en utilisant l'URL publique)
        URL objectUrl = s3Client.utilities().getUrl(builder -> builder.bucket(ImagesPublicationsHandler.BUCKETNAME_DESTINATION).key(newImageKey).build());
        return objectUrl.toString();
    }

    private InputStream getObject(S3Client s3Client, String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return s3Client.getObject(getObjectRequest);
    }

    private long calculateInputStreamSize(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        long sizeInBytes = 0;

        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            sizeInBytes += bytesRead;
        }

        return sizeInBytes;
    }

    private BufferedImage addLockToImage(BufferedImage mainImage, BufferedImage lockImage) {
        int mainImageWidth = mainImage.getWidth();
        int mainImageHeight = mainImage.getHeight();

        int lockImageWidth = lockImage.getWidth();
        int lockImageHeight = lockImage.getHeight();

        // Calculer les coordonnées pour placer l'image du cadenas au centre de l'image principale
        int x = (mainImageWidth - lockImageWidth) / 2;
        int y = (mainImageHeight - lockImageHeight) / 2;

        // Créer une image résultante en copiant l'image principale
        BufferedImage resultImage = new BufferedImage(mainImageWidth, mainImageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resultImage.createGraphics();
        graphics.drawImage(mainImage, 0, 0, null);

        // Dessiner l'image du cadenas au centre de image principale
        graphics.drawImage(lockImage, x, y, null);

        graphics.dispose();

        return resultImage;
    }

    public File convertInputStreamToFile(InputStream inputStream, String fileName) throws IOException {
        Path tempFilePath = Files.createTempFile(fileName, ".tmp");

        try (FileOutputStream outputStream = new FileOutputStream(tempFilePath.toFile())) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
        return tempFilePath.toFile();
    }

}
