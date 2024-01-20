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
import java.io.*;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pronos.s3.CommonUtility.*;

public class ImagesPublicationsHandler implements RequestHandler<S3Event,String> {
    private static final String BUCKETNAME_DESTINATION = System.getenv("BUCKETNAME_DESTINATION");
    static String EMAIL_TEC = "tecnical@example.com";
    public static final String PUBLIC_READ = "public-read";
    private static final boolean CALL_API = Boolean.parseBoolean(System.getenv("CALL_API"));
    /*static BufferedImage lockImage;
    static {
        try {
            lockImage = ImageIO.read(
                    Objects.requireNonNull(
                            ImagesPublicationsHandler.class.getResourceAsStream("/cadenas.png")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }*/

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
        ExtractedInfo infos = extractInfosFromFilename(fileName);
        if(null == infos){
            return "Erreur lors de la recherche de l'id publication dans le nom de l'image d'origine...";
        }
        context.getLogger().log("publication id recupéré pour la nouvelle image : "+infos.idPublicationExtracted);

        PicturesURLs picturesURLs = new PicturesURLs();

        try(S3Client s3client = S3Client.builder().build()) {
            InputStream inputStream = getObject(s3client, bucketName, fileName);
            context.getLogger().log("Objet "+fileName+" bien récupéré depuis "+bucketName);

            if(infos.isFirstImage) {
                //1. Creation de la vignette et stockage dans le bucket destination
                context.getLogger().log("Creation d'une vignette pour " + fileName);
                // Créer la vignette de l'image SSI filename se termine par FIRST
                InputStream thumbnailInputStream = createThumbnail(inputStream);
                context.getLogger().log("thumbnailInputStream bien créé");

                // Sauvegarder la vignette dans S3
                context.getLogger().log("Tentative de sauvegarde dans le bucket " + BUCKETNAME_DESTINATION);

                byte[] imgOrginThumbBytesArray = buildBAOS(thumbnailInputStream).toByteArray();
                String keyThumb = saveImageToS3(s3client,
                        getNewKey(infos.idPublicationExtracted, imgExtension,
                                "thumb"), imgOrginThumbBytesArray, context);

                context.getLogger().log("Vignette créée : " + keyThumb);
                picturesURLs.setKeythumb(keyThumb);
            }

            if(!infos.isPublicPublicationExtracted && !isVideo(imgExtension)) {
                //2. Creation de l'image floutée
                inputStream = getObject(s3client, bucketName, fileName);
                BufferedImage imageOrigin = ImageIO.read(inputStream);
                BufferedImage imageBlurred = CommonUtility.getBufferedImageFlou(imageOrigin);

                /*lockImage = CommonUtility.resizeImage(lockImage,
                        imageOrigin.getWidth() / 5, imageOrigin.getWidth() / 5);//Cadenas etant un carré a peu pres
                BufferedImage blurredImage = addLockToImage(imageBlurred, lockImage);*/

                String keyLocked = saveImageToS3(
                        s3client,
                        getNewKey(infos.idPublicationExtracted, imgExtension, "locked"),
                        bufferedImageToByteArray(imageBlurred,
                                imgExtension), context
                );
                context.getLogger().log("Image flouttée créée : " + keyLocked);
                picturesURLs.setKeyblurred(keyLocked);

                if(infos.isFirstImage) {
                    //3. Creation de la vignette de l'image flouttée
                    InputStream thumbnailLockedInputStream =
                            createThumbnail(bufferedImageToInputStream(imageBlurred, imgExtension));

                    byte[] imgBlurredThumbBytesArray = buildBAOS(thumbnailLockedInputStream).toByteArray();
                    String keyThumbLocked = saveImageToS3(s3client,
                            getNewKey(infos.idPublicationExtracted, imgExtension,
                                    "thumblocked"), imgBlurredThumbBytesArray, context);

                    context.getLogger().log("Vignette flouttée créée : " + keyThumbLocked);
                    picturesURLs.setKeyblurredthumb(keyThumbLocked);
                }
            }


            //4. Copie de l'image d'origine vers le bucket destination
            String newKeyOrigin = getNewKey(infos.idPublicationExtracted, imgExtension, "");
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
            if(s3client.headObject(sourceHeadRequest).eTag().equals(
                    copyObjectResponse.copyObjectResult().eTag())){
                //5. Mise à jour de la publication avec les clés des images
                URL newKeyOriginUrl = s3client.utilities().getUrl(
                        builder -> builder.bucket(
                                ImagesPublicationsHandler.BUCKETNAME_DESTINATION).key(newKeyOrigin).build());
                URL keyOriginUrl = s3client.utilities().getUrl(
                        builder -> builder.bucket(
                                bucketName).key(fileName).build());
                picturesURLs.setKeyorigin(keyOriginUrl.toString());
                picturesURLs.setNewkeyorigin(newKeyOriginUrl.toString());
                if(CALL_API) {
                    Response resultat = callRestService(infos.idPublicationExtracted,
                            picturesURLs
                    );

                    if (resultat.getStatus() != Response.Status.OK.getStatusCode()) {
                        context.getLogger().log("Attention retour du service des Keys : " + resultat.getStatus());
                    } else {
                        //6. Suppression de l'image du bucket d'origine
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
                }
            }
        }catch (IOException e){
            return "Error while reading file from S3 :::" +e.getMessage();
        }

        return getJsonKeysString(picturesURLs);
    }

    private boolean isVideo(String imgExtension) {
        return imgExtension.equals("mp4") ||
                imgExtension.equals("mov") ||
                imgExtension.equals("avi") ||
                imgExtension.equals("wmv");
    }

    public Response callRestService(String idPublication, PicturesURLs urLs) {
        Client client = ClientBuilder.newClient();
        WebTarget webTarget
                = client.target(System.getenv("API_URL_CONTEXT"));

        WebTarget picturesTarget =
                webTarget.path("/v1/publications/"+idPublication+"/transformedpictures");
        Invocation.Builder invocationBuilder
                = picturesTarget.request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer "+generateToken(EMAIL_TEC, "Tecnical"))
                .header("email", EMAIL_TEC);

        return invocationBuilder
                .put(Entity.entity(getJsonKeysString(urLs), MediaType.APPLICATION_JSON));
    }

    private static String getJsonKeysString(PicturesURLs urLs) {
        StringBuilder json = new StringBuilder();
        json.append("{\"keyorigin\":\"")
                .append(urLs.getKeyorigin()).append("\",")
                .append("\"newkeyorigin\":\"")
                .append(urLs.getNewkeyorigin()).append("\",")
                .append("\"keythumb\":\"").append(urLs.getKeythumb()).append("\",")
                .append("\"keyblurred\":\"").append(urLs.getKeyblurred()).append("\",")
                .append("\"keyblurredthumb\":\"").append(urLs.getKeyblurredthumb()).append("\"}");
        return json.toString();
    }

    private String extractPublicationFromFilename(String filename) {
        Pattern pattern = Pattern.compile("-(\\d+)\\.(?:jpeg|png|jpg)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(filename);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public ExtractedInfo extractInfosFromFilename(String filename){
        String regex = ".*-(\\d+)_([A-Z]+)(?:\\..*)?$";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(filename);

        if (matcher.matches()) {
            String visibility = matcher.group(2);
            ExtractedInfo res = new ExtractedInfo();
            res.idPublicationExtracted = matcher.group(1);
            res.isFirstImage = (visibility.contains("FIRST"));
            res.isPublicPublicationExtracted = (visibility.contains("PUB"));

            return res;
        }
        return null;
    }

    class ExtractedInfo {
        String idPublicationExtracted;
        boolean isPublicPublicationExtracted;
        boolean isFirstImage;
    }

    private String getNewKey(String idPublication, String extension, String suffix) {
        return UUID.randomUUID() + "-" + idPublication +suffix+"." + extension;
    }

    // Méthode pour créer une vignette d'une image
    private static InputStream createThumbnail(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Thumbnails.of(inputStream)
                .size(100, 100)
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
}
