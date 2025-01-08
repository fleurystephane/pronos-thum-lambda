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
        String fileName = s3Event.getRecords().get(0).getS3().getObject().getKey();
        String fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
        ExtractedInfo infos = extractInfosFromFilenameNew(fileName);
        if (null == infos) {
            callInfos(ERROR_A, false,
                    context);
            return ERROR_A;
        }
        context.getLogger().log("id recupéré pour le nouveau media : " + infos.idExtracted);

        try {
            PicturesURLs picturesURLs = getInfosToCall(s3Event, context, infos, fileName, fileExtension);
            callInfos(picturesURLs.toString(), true, context);
            return getJsonKeysString(picturesURLs, infos.width, infos.height);
        }catch(IOException e){
            callInfos("IOException : " + e.getMessage(), false, context);
            return "Error while reading file from S3 :::" + e.getMessage();
        }

    }

    private PicturesURLs getInfosToCallForVideo(S3Event s3Event, Context context, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws IOException {
        String bucketName = s3Event.getRecords().get(0).getS3().getBucket().getName();

        PicturesURLs picturesURLs = new PicturesURLs();

        String newKeyOrigin = getNewKey(infos.idExtracted, fileExtension, "");

        S3Client s3client = S3Client.builder().build();
        InputStream inputStream = getObject(s3client, bucketName, fileName);
        context.getLogger().log("Video " + fileName + " bien récupéré depuis " + bucketName);

        BufferedImage imageOrigin = null;

        //On crée l'image de la vidéo, cette image ne sera pas envoyée à l'appli via API mais sera stockée dans le Bucket
        /*try {
                    manageVideo(context, inputStream, fileName, s3client, infos, picturesURLs, fileExtension, newKeyOrigin);
                } catch (JCodecException e) {
                    context.getLogger().log("Error JCodeException : "+e.getMessage());
                    removeObject(context, bucketName, fileName, s3client);
                    callInfos("Error... JCodecException "+e.getMessage(), false, context);
                    return "Error... JCodecException";
                }*/
        if (!infos.isPublic) {
            picturesURLs.setKeyblurred("DEFAULT_URL");
        }


        //4. Copie de la video d'origine vers le bucket destination

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
            context.getLogger().log("/!\\ les etags de la copie de la video sont differents donc pas d'appel API pour mise à jour des keys!!!!");
            context.getLogger().log("s3client.headObject(sourceHeadRequest).eTag() = " + s3client.headObject(sourceHeadRequest).eTag());
            context.getLogger().log("copyObjectResponse.copyObjectResult().eTag() = " + copyObjectResponse.copyObjectResult().eTag());
        }
        return picturesURLs;
    }

    public BufferedImage resizeImage(InputStream inputStream, int targetWidth, int targetHeight) throws IOException {
        // Charger l'image d'origine
        BufferedImage originalImage = ImageIO.read(inputStream);
        if (originalImage == null) {
            throw new IOException("L'image d'entrée est invalide ou non supportée.");
        }

        // Dimensions d'origine
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // Calcul des proportions pour respecter l'aspect ratio
        double widthScale = (double) targetWidth / originalWidth;
        double heightScale = (double) targetHeight / originalHeight;
        double scale = Math.min(widthScale, heightScale); // Choix du plus petit facteur de réduction

        int scaledWidth = (int) (originalWidth * scale);
        int scaledHeight = (int) (originalHeight * scale);

        // Création de l'image redimensionnée
        BufferedImage resizedImage = new BufferedImage(scaledWidth, scaledHeight, originalImage.getType());
        Graphics2D g = resizedImage.createGraphics();

        try {
            // Amélioration de la qualité avec l'interpolation bilinéaire
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose(); // Libérer les ressources
        }

        return resizedImage;
    }


    private PicturesURLs getInfosToCallForPicture(S3Event s3Event, Context context, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws IOException {
        String bucketName = s3Event.getRecords().get(0).getS3().getBucket().getName();

        PicturesURLs picturesURLs = new PicturesURLs();

        String newKeyOrigin = getNewKey(infos.idExtracted, fileExtension, "");

        S3Client s3client = S3Client.builder().build();
        InputStream inputStream = getObject(s3client, bucketName, fileName);
        context.getLogger().log("Image " + fileName + " bien récupérée depuis " + bucketName);

        BufferedImage imageOrigin = resizeImage(inputStream, 800, 600);
        inputStream.close();
        infos.width = 800;
        infos.height = 600;


        //Si 1ere image alors on doit créer une vignette
        if (infos.isFirstImage) {
            String key = getNewKey(infos.idExtracted, fileExtension,
                    "thumb");
            String keyThumb = buildKeyThumb(context, fileName, s3client, imageOrigin, key);

            context.getLogger().log("Vignette créée : " + keyThumb);
            picturesURLs.setKeythumb(keyThumb);
        }

        if (!infos.isPublic) {
            //2. Creation de l'image floutée
            BufferedImage imageBlurred = CommonUtility.getBufferedImageFlou(imageOrigin);

            String keyLocked = buildImgFlouttee(context, fileExtension, s3client,
                    imageBlurred, getNewKey(infos.idExtracted, fileExtension, "locked"));
            context.getLogger().log("Image flouttée créée : " + keyLocked);
            picturesURLs.setKeyblurred(keyLocked);

            if (infos.isFirstImage) {
                //3. Creation de la vignette de l'image flouttée
                String keyThumbLocked = buildKeyThumb(context, fileName, s3client, imageOrigin,
                        getNewKey(infos.idExtracted, fileExtension,
                                "thumblocked"));
                context.getLogger().log("Vignette flouttée créée : " + keyThumbLocked);
                picturesURLs.setKeyblurredthumb(keyThumbLocked);
            }
        }

        picturesURLs.setKeyorigin(fileName.substring(0, fileName.lastIndexOf(".")));
        picturesURLs.setNewkeyorigin(saveImageToS3(s3client, newKeyOrigin,
                convertBufferedImageToByteArray(imageOrigin, fileExtension), context));

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

        return picturesURLs;
    }

    public static byte[] convertBufferedImageToByteArray(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        baos.flush();
        return baos.toByteArray();
    }

    private PicturesURLs getInfosToCall(S3Event s3Event, Context context, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws IOException {
        if(isVideo(fileExtension)){
            return getInfosToCallForVideo(s3Event,context,infos,fileName,fileExtension);
        }
        return getInfosToCallForPicture(s3Event,context,infos,fileName,fileExtension);
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

    /*public void manageVideo(Context context, InputStream inputStream, String fileName, S3Client s3client,
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
    }*/

    private String buildKeyThumb(Context context, String fileName, S3Client s3client, BufferedImage originalImage, String key) throws IOException {
        // 1. Création de la vignette à partir de l'image d'origine
        context.getLogger().log("Création d'une vignette pour " + fileName);

        // Créer une vignette de l'image
        BufferedImage thumbnailImage = createSizedBufferedImage(originalImage, 100, 100);
        context.getLogger().log("Thumbnail bien créé");

        // Convertir la vignette en tableau d'octets
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(thumbnailImage, "jpg", baos);
        byte[] imgThumbnailBytesArray = baos.toByteArray();

        // Sauvegarder la vignette dans S3
        context.getLogger().log("Tentative de sauvegarde dans le bucket " + BUCKETNAME_DESTINATION);

        return saveImageToS3(s3client, key, imgThumbnailBytesArray, context);
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
    private static BufferedImage createSizedBufferedImage(BufferedImage originalImage, int width, int height) throws IOException {
        return Thumbnails.of(originalImage)
                .size(width, height)
                .asBufferedImage();
    }



    // Méthode pour sauvegarder une image dans S3
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
}
