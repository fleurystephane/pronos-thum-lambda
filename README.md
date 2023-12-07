la lambda function doit être créée dans la même region que le bucket S3

il faut lui créer un execution rôle
    dans IAMConsole il faut créer un rôle avec les permissions suivantes :

Lorsqu'on travaille en local, il faut appeler explicitement une Fonction (Lambda) via l'URL suivante :  
https://le4bsn7p7sf4v7jyn3uoddjxva0nbhmn.lambda-url.eu-west-3.on.aws/

Cette fonction nécessite l'authentifiaction type : AWS_IAM
Authorization: AWS4-HMAC-SHA256 Credential=AKIA43HUNS6SCYQMAKMW/20230921/eu-west-3/lambda/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=2r5HPula1llCkuWt9iQdRO+3lkRfI3Mwyldu4ukA

