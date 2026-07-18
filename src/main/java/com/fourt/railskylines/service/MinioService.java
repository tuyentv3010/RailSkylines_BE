package com.fourt.railskylines.service;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;

import jakarta.annotation.PostConstruct;

/**
 * Uploads files to MinIO (S3-compatible) object storage and returns their
 * public URL. Replaces local-disk storage for user uploads (e.g. article
 * thumbnails).
 */
@Service
public class MinioService {
    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    @Value("${minio.endpoint}")
    private String endpoint;
    @Value("${minio.public-url}")
    private String publicUrl;
    @Value("${minio.access-key}")
    private String accessKey;
    @Value("${minio.secret-key}")
    private String secretKey;
    @Value("${minio.bucket}")
    private String bucket;

    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
    }

    /** Create the bucket if missing and make its objects publicly readable. */
    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info(">>> MinIO bucket created: {}", bucket);
            }
            // Allow anonymous read so images can be served directly via public URL
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(bucket);
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
        } catch (Exception e) {
            // Do not crash startup if MinIO is unreachable; uploads fail later with a clear error
            log.warn(">>> MinIO init skipped (unreachable?): {}", e.getMessage());
        }
    }

    private String sanitize(String name) {
        if (name == null) {
            return "unnamed";
        }
        String s = name.replaceAll("[^a-zA-Z0-9.-]", "_");
        return s.isEmpty() ? "unnamed" : s;
    }

    /**
     * Store a file under {folder}/{timestamp}-{name} and return its public URL.
     */
    public String upload(MultipartFile file, String folder) {
        String objectKey = folder + "/" + System.currentTimeMillis() + "-" + sanitize(file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Upload to MinIO failed: " + e.getMessage(), e);
        }
        // Path-style public URL: {publicUrl}/{bucket}/{objectKey}
        return publicUrl + "/" + bucket + "/" + objectKey;
    }
}
