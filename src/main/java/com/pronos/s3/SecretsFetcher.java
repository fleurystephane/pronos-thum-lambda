package com.pronos.s3;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class SecretsFetcher {

    public static Map<String, String> getSecrets(String secretName, String region) throws Exception {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(software.amazon.awssdk.regions.Region.of(region))
                .build();

        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
        String secretString = getSecretValueResponse.secretString();

        // Utilise Jackson pour parser le JSON
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(secretString, Map.class);
    }
}

