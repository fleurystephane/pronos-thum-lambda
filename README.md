la lambda function doit être créée dans la même region que le bucket S3

il faut lui créer un execution rôle
    dans IAMConsole il faut créer un rôle avec les permissions suivantes :

Lorsqu'on travaille en local, il faut appeler explicitement une Fonction (Lambda) via l'URL suivante :  
https://le4bsn7p7sf4v7jyn3uoddjxva0nbhmn.lambda-url.eu-west-3.on.aws/

Cette fonction nécessite l'authentifiaction type : AWS_IAM
Authorization: AWS4-HMAC-SHA256 Credential=AKIA43HUNS6SCYQMAKMW/20230921/eu-west-3/lambda/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=2r5HPula1llCkuWt9iQdRO+3lkRfI3Mwyldu4ukA


Le principe de fonctionnement est le suivant :
Une image est ajoutée dans un bucket S3 par l'API.
Sur ce bucket un event se déclenche et appelle la lambda.
la lambda, ce projet, va créer les images dans un autre bucket S3 (vignette, image flouttée...)
puis la lambda va appeler l'API pour informer des keyname des images créées.

Pour les vidéos, c'est un peu différent. On ne stocke pas dans l'appli l'image frame ni la vignette ni les flouttées
le nom de ces images sera issu du nom du fichier initial avec les suffix suivant :
Si la video initial est ABCDEFG.png
alors on crée dans le bucked transformed
ABCDEFG-XXX.jpg : la frame (XXX correspond à l'id publication), seule l'extension change
ABCDEFG-XXXthumb.jpg : la vignette de la frame
ABCEDFG-XXXlocked.jpg : la frame lockée
ABCEDFG-XXXthumblocked.jpg : la vignette lockée

Les URLs envoyées dans l'API seront :
ABCDEFG-XXX.mp4
ABCDEFG-XXXthumb.jpg
ABCEDFG-XXXlocked.jpg
ABCEDFG-XXXthumblocked.jpg

Non stockée dans la base : ABCDEFG-XXX.jpg représentant la Frame 1, le client devra le savoir pour appeler la bonne URL


