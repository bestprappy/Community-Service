package com.navio.communityservice.media;
import com.navio.communityservice.exception.GroupException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.core.exception.SdkException;
import java.net.URI;
import java.time.Duration;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private final S3Client client;
    private final String bucket;
    public S3ObjectStorage(S3Client client, @Value("${navio.community.media.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }
    public void put(String key, byte[] content, String contentType) {
        try {
            client.putObject(r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
        } catch (SdkException ex) { throw unavailable(); }
    }
    public byte[] get(String key) {
        try { return client.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray(); }
        catch (SdkException ex) { throw unavailable(); }
    }
    public void delete(String key) {
        try { client.deleteObject(r -> r.bucket(bucket).key(key)); }
        catch (SdkException ex) { throw unavailable(); }
    }
    private GroupException unavailable() {
        return new GroupException(HttpStatus.SERVICE_UNAVAILABLE, "Picture storage is unavailable; please retry");
    }
    @Configuration
    static class Config {
        @Bean(destroyMethod = "close")
        S3Client communityS3Client(@Value("${navio.community.media.endpoint:}") String endpoint,
                @Value("${navio.community.media.region:us-east-1}") String region,
                @Value("${navio.community.media.path-style:false}") boolean pathStyle) {
            var builder = S3Client.builder().region(Region.of(region))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(8)).build());
            if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
            // AWS's credential chain supports env vars, local profiles and workload roles.
            return builder.build();
        }
    }
}
