package com.pronos.s3;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

public class S3ObjectBucketDeletionHandler implements RequestHandler<S3Event,String> {

    public static final String PUBLICATIONSPRONOSBUCKET_1_UNITTEST = "publicationspronosbucket1unittest";

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        Region region = Region.EU_WEST_3; // Change to your desired region

        // Create S3 client
        S3Client s3Client = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .build();
        ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                .bucket(PUBLICATIONSPRONOSBUCKET_1_UNITTEST)
                .build();

        List<S3Object> objectsToDelete = new ArrayList<>();

        // List all objects in the bucket
        ListObjectsV2Response listObjectsResponse = s3Client.listObjectsV2(listObjectsRequest);
        objectsToDelete.addAll(listObjectsResponse.contents());

        // Delete each object
        objectsToDelete.forEach(object -> {
            s3Client.deleteObject(builder -> builder.bucket(PUBLICATIONSPRONOSBUCKET_1_UNITTEST).key(object.key()));
        });
        return "OK";
    }
}