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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.waiters.S3Waiter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pronos.s3.CommonUtility.bufferedImageToByteArray;

public class ImagesPublicationsHandler implements RequestHandler<S3Event,String> {
    private static final String BUCKETNAME_DESTINATION = System.getenv("BUCKETNAME_DESTINATION");
    public static final String ERROR_A = "Erreur lors de la recherche de l'id publication dans le nom de l'image d'origine...";
    static String EMAIL_TEC = "tecnical@example.com";
    public static final String PUBLIC_READ = "public-read";
    private static final boolean CALL_API = Boolean.parseBoolean(System.getenv("CALL_API"));

    private static final String REGION = System.getenv("REGION");

    public static String generateToken(String email, String userAgent) throws Exception {
        Map<String, String> creds = SecretsFetcher.getSecrets("lazonio/allsecrets", REGION);
        String secretKey = creds.get("jwtsecretkey");
        return JWT.create()
                .withClaim("email", email)
                .withClaim("user-agent", userAgent)
                .withIssuer(System.getenv("ISSUER"))
                .withSubject(email)
                .withExpiresAt(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .sign(Algorithm.HMAC256(secretKey));
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        String fileName = s3Event.getRecords().get(0).getS3().getObject().getKey();
        String fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
        ExtractedInfo infos = extractInfosFromFilenameNew(fileName);
        if (null == infos) {
            try {
                callInfos(ERROR_A, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return ERROR_A;
        }
        System.out.println("id recupéré pour le nouveau media : " + infos.idExtracted);

        try {
            try {
                PicturesURLs picturesURLs = getInfosToCall(s3Event, infos, fileName, fileExtension);
                callInfos(picturesURLs.toString(), true);
                return getJsonKeysString(picturesURLs, infos.width, infos.height);
            } catch (IOException e) {
                callInfos("IOException : " + e.getMessage(), false);
                return "Error while reading file from S3 :::" + e.getMessage();
            }
        }catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private PicturesURLs getInfosToCallForVideo(S3Event s3Event, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws Exception {
        String bucketName = s3Event.getRecords().get(0).getS3().getBucket().getName();

        PicturesURLs picturesURLs = new PicturesURLs();

        String newKeyOrigin = getNewKey(infos.idExtracted, fileExtension, "");

        S3Client s3client = S3Client.builder().build();
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
                    System.out.println("Attention retour du service des Keys : " + resultat.getStatus());
                } else {
                    System.out.println("Service des keys retour OK  !!!");
                    //6. Suppression de l'image du bucket d'origine
                    removeObject(bucketName, fileName, s3client);
                }
            }
        } else {
            System.out.println("/!\\ les etags de la copie de la video sont differents donc pas d'appel API pour mise à jour des keys!!!!");
            System.out.println("s3client.headObject(sourceHeadRequest).eTag() = " + s3client.headObject(sourceHeadRequest).eTag());
            System.out.println("copyObjectResponse.copyObjectResult().eTag() = " + copyObjectResponse.copyObjectResult().eTag());
        }
        return picturesURLs;
    }

    public BufferedImage resizeImage(InputStream inputStream, int targetWidth) throws IOException {
        // Charger l'image d'origine
        BufferedImage originalImage = ImageIO.read(inputStream);
        if (originalImage == null) {
            throw new IOException("L'image d'entrée est invalide ou non supportée.");
        }

        // Dimensions d'origine
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();
        // Calcul de la targetHeight pour respecter l'aspect ratio
        double aspectRatio = (double) originalHeight / originalWidth;
        int targetHeight = (int) (targetWidth * aspectRatio);


        // Création de l'image redimensionnée
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();

        try {
            // Amélioration de la qualité avec l'interpolation bilinéaire
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose(); // Libérer les ressources
        }

        return resizedImage;
    }


    private PicturesURLs getInfosToCallForPicture(S3Event s3Event, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws Exception {
        String bucketName = s3Event.getRecords().get(0).getS3().getBucket().getName();

        PicturesURLs picturesURLs = new PicturesURLs();

        String newKeyOrigin = getNewKey(infos.idExtracted, fileExtension, "");

        S3Client s3client = S3Client.builder().build();
        InputStream inputStream = getObject(s3client, bucketName, fileName);
        System.out.println("Image " + fileName + " bien récupérée depuis " + bucketName);

        BufferedImage imageOrigin = resizeImage(inputStream, 1170);
        System.out.println("ResizeImage ok");
        inputStream.close();
        infos.width = 1170;
        infos.height = imageOrigin.getHeight();


        //Si 1ere image alors on doit créer une vignette
        if (infos.isFirstImage) {
            String key = getNewKey(infos.idExtracted, fileExtension,
                    "thumb");
            System.out.println("buidKeyThumb...");
            String keyThumb = buildKeyThumb(fileName, s3client, imageOrigin, key);

            System.out.println("Vignette créée : " + keyThumb);
            picturesURLs.setKeythumb(keyThumb);
        }

        if (!infos.isPublic) {
            //2. Creation de l'image floutée
            BufferedImage imageBlurred = CommonUtility.getBufferedImageFlou(imageOrigin);

            String keyLocked = buildImgFlouttee(fileExtension, s3client,
                    imageBlurred, getNewKey(infos.idExtracted, fileExtension, "locked"));
            System.out.println("Image flouttée créée : " + keyLocked);
            picturesURLs.setKeyblurred(keyLocked);

            if (infos.isFirstImage) {
                //3. Creation de la vignette de l'image flouttée
                String keyThumbLocked = buildKeyThumb(fileName, s3client, imageBlurred,
                        getNewKey(infos.idExtracted, fileExtension,
                                "thumblocked"));
                System.out.println("Vignette flouttée créée : " + keyThumbLocked);
                picturesURLs.setKeyblurredthumb(keyThumbLocked);
            }
        }

        picturesURLs.setKeyorigin(fileName.substring(0, fileName.lastIndexOf(".")));
        picturesURLs.setNewkeyorigin(saveImageToS3(s3client, newKeyOrigin,
                convertBufferedImageToByteArray(imageOrigin, fileExtension)));

        if (CALL_API) {
            Response resultat = callRestService(infos, picturesURLs);

            if (resultat.getStatus() != Response.Status.OK.getStatusCode()) {
                System.out.println("Attention retour du service des Keys : " + resultat.getStatus());
            } else {
                System.out.println("Service des keys retour OK  !!!");
                System.out.println("le fichier d'origine : "+fileName);
                //6. Suppression de l'image du bucket d'origine
                removeObject(bucketName, fileName, s3client);

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

    private PicturesURLs getInfosToCall(S3Event s3Event, ExtractedInfo infos,
                                        String fileName, String fileExtension) throws Exception {
        if(isVideo(fileExtension)){
            return getInfosToCallForVideo(s3Event,infos,fileName,fileExtension);
        }
        return getInfosToCallForPicture(s3Event, infos,fileName,fileExtension);
    }

    private void callInfos(String message, boolean isOk) throws Exception {
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
            System.out.println("Erreur 500 sur infosstored");
        }
    }

    private static void removeObject(String bucketName, String fileName, S3Client s3client) {
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build();

        try {
            s3client.deleteObject(deleteObjectRequest);
            System.out.println("Objet supprimé avec succès : " + fileName);
        } catch (S3Exception s3Exception) {
            System.out.println("ECHEC de suppression de  " + fileName);
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

    private String buildKeyThumb(String fileName, S3Client s3client, BufferedImage originalImage, String key) throws IOException {
        // 1. Création de la vignette à partir de l'image d'origine
        System.out.println("Création d'une vignette pour " + fileName);

        // Créer une vignette de l'image
        BufferedImage thumbnailImage = createSizedBufferedImage(originalImage);
        System.out.println("Thumbnail bien créé");

        // Convertir la vignette en tableau d'octets
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(thumbnailImage, "jpg", baos);
        byte[] imgThumbnailBytesArray = baos.toByteArray();

        // Sauvegarder la vignette dans S3
        System.out.println("Tentative de sauvegarde dans le bucket " + BUCKETNAME_DESTINATION);

        return saveImageToS3(s3client, key, imgThumbnailBytesArray);
    }


    private String buildImgFlouttee(String imgExtension, S3Client s3client,
                                    BufferedImage imageBlurred, String key) throws IOException {
        return saveImageToS3(
                s3client,
                key,
                bufferedImageToByteArray(imageBlurred,
                        imgExtension)
        );
    }


    private boolean isVideo(String imgExtension) {
        return imgExtension.equals("mp4") ||
                imgExtension.equals("mov") ||
                imgExtension.equals("avi") ||
                imgExtension.equals("wmv");
    }

    public Response callRestService(ExtractedInfo infos, PicturesURLs urLs) throws Exception {
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
        return "{\"keyorigin\":\"" +
                urLs.getKeyorigin() + "\"," +
                "\"newkeyorigin\":\"" +
                urLs.getNewkeyorigin() + "\"," +
                "\"keythumb\":\"" + urLs.getKeythumb() + "\"," +
                "\"keyblurred\":\"" + urLs.getKeyblurred() + "\"," +
                "\"keyblurredthumb\":\"" + urLs.getKeyblurredthumb() + "\"," +
                "\"width\":"+width +"," +
                "\"height\":"+height+"}";
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
    private static BufferedImage createSizedBufferedImage(BufferedImage originalImage) throws IOException {
        return Thumbnails.of(originalImage)
                .size(400, originalImage.getHeight()*400/originalImage.getWidth())
                .asBufferedImage();
    }



    // Méthode pour sauvegarder une image dans S3
    private String saveImageToS3(S3Client s3Client,
                                 String newImageKey,
                                 byte[] bytes) throws IOException {
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
