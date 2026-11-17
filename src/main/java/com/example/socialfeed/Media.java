package com.example.socialfeed;

import java.util.*;
import java.time.Duration;
import java.net.URI;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;

@Service
public class Media {
    final Store s;
    final S3Client client;
    final S3Presigner signer;
    final String bucket;
    final java.util.concurrent.Semaphore validationSlots = new java.util.concurrent.Semaphore(2);

    public Media(Store s, @Value("${feed.s3-endpoint}") String endpoint, @Value("${feed.s3-public-endpoint:${feed.s3-endpoint}}") String publicEndpoint, @Value("${feed.s3-access}") String access, @Value("${feed.s3-secret}") String secret, @Value("${feed.s3-bucket}") String bucket) {
        this.s = s;
        this.bucket = bucket;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
        client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).credentialsProvider(credentials).forcePathStyle(true).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(2))).build();
        signer = S3Presigner.builder().endpointOverride(URI.create(publicEndpoint)).region(Region.US_EAST_1).credentialsProvider(credentials).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    public S3Client client() {
        return client;
    }

    public String bucket() {
        return bucket;
    }

    @Transactional
    public Map<String, Object> initiate(UUID actor, Contracts.Upload u) {
        s.active(actor);
        if (u.contentType().startsWith("image/") && u.bytes() > MediaValidation.IMAGE_BYTES)
            throw new ResponseStatusException(BAD_REQUEST, "Images are limited to 10 MiB");
        UUID id = UUID.randomUUID();
        String key = "uploads/" + actor + "/" + id;
        s.exec("insert into post_media(id,owner,object_key,content_type,bytes) values(?,?,?,?,?)", id, actor, key, u.contentType(), u.bytes());
        // Upload into staging. Completion copies to an immutable, unguessable final key to prevent URL reuse changing published media.
        var req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(u.contentType()).contentLength(u.bytes()).build();
        var url = signer.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5)).putObjectRequest(req).build());
        return Map.of("uploadId", id, "url", url.url().toString(), "headers", url.signedHeaders());
    }

    @Transactional
    public void complete(UUID actor, UUID id) {
        s.active(actor);
        var m = s.one("select * from post_media where id=? and owner=? for update", id, actor);
        if ((boolean) m.get("complete")) return;
        var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key((String) m.get("object_key")).build());
        if (head.contentLength() != ((Number) m.get("bytes")).longValue() || !head.contentType().equals(m.get("content_type")))
            throw new ResponseStatusException(BAD_REQUEST, "Media metadata mismatch");
        // Validate the same object version that conditional copy will freeze, despite staging URL reuse.
        if (!validationSlots.tryAcquire())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Media validation busy; retry with backoff");
        try {
            try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key((String) m.get("object_key")).ifMatch(head.eTag()).build())) {
                try {
                    MediaValidation.validate(input, (String) m.get("content_type"), ((Number) m.get("bytes")).longValue());
                } finally {
                    input.abort();
                } // MP4 validation reads only a prefix; do not drain a large video on close.
            } catch (java.io.IOException e) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid or oversized media content");
            }
        } finally {
            validationSlots.release();
        }
        String finalKey = "objects/" + UUID.randomUUID();
        client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey((String) m.get("object_key")).destinationBucket(bucket).destinationKey(finalKey).copySourceIfMatch(head.eTag()).build());
        s.exec("update post_media set object_key=?,complete=true where id=?", finalKey, id);
    }

    public Map<String, String> download(UUID actor, UUID id) {
        return download(actor, id, false);
    }

    public Map<String, String> download(UUID actor, UUID id, boolean thumbnail) {
        s.active(actor);
        var m = s.one("select * from post_media where id=? and complete", id);
        if (m.get("post") != null) s.visible(actor, (UUID) m.get("post"));
        else if (!actor.equals(m.get("owner"))) throw new ResponseStatusException(NOT_FOUND);
        if (thumbnail && m.get("thumbnail_key") == null)
            throw new ResponseStatusException(NOT_FOUND, "Thumbnail pending or unavailable");
        var req = GetObjectRequest.builder().bucket(bucket).key((String) m.get(thumbnail ? "thumbnail_key" : "object_key")).responseContentDisposition("attachment").build();
        return Map.of("url", signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(30)).getObjectRequest(req).build()).url().toString(), "expiresInSeconds", "30");
    }
}
