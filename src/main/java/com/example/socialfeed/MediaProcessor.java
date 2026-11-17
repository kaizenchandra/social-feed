package com.example.socialfeed;

import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "feed.workers-enabled", havingValue = "true", matchIfMissing = true)
public class MediaProcessor {
    final Store s;
    final Media media;
    final TransactionTemplate tx;
    final MeterRegistry metrics;

    public MediaProcessor(Store s, Media media, TransactionTemplate tx, MeterRegistry metrics) {
        this.s = s;
        this.media = media;
        this.tx = tx;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 1000)
    public void thumbnails() {
        tx.executeWithoutResult(t -> {
            var candidates = s.rows("select * from post_media where complete and thumbnail_key is null and thumbnail_attempts<3 and content_type like 'image/%' order by created_at for update skip locked limit 1");
            if (candidates.isEmpty()) return;
            var m = candidates.getFirst();
            try (var response = media.client().getObject(GetObjectRequest.builder().bucket(media.bucket()).key((String) m.get("object_key")).build()); var input = new MemoryCacheImageInputStream(response)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Unsupported image");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 20000000)
                        throw new IOException("Image dimensions exceed budget");
                    var image = reader.read(0);
                    double ratio = Math.min(1, 320.0 / Math.max(width, height));
                    var thumbnail = new BufferedImage(Math.max(1, (int) (width * ratio)), Math.max(1, (int) (height * ratio)), BufferedImage.TYPE_INT_RGB);
                    var g = thumbnail.createGraphics();
                    try {
                        g.drawImage(image, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
                    } finally {
                        g.dispose();
                    }
                    var bytes = new ByteArrayOutputStream();
                    ImageIO.write(thumbnail, "jpeg", bytes);
                    String key = "thumbnails/" + m.get("id") + ".jpg";
                    media.client().putObject(PutObjectRequest.builder().bucket(media.bucket()).key(key).contentType("image/jpeg").build(), RequestBody.fromBytes(bytes.toByteArray()));
                    s.exec("update post_media set thumbnail_key=? where id=?", key, m.get("id"));
                    metrics.counter("feed.media.thumbnails").increment();
                } finally {
                    reader.dispose();
                }
            } catch (Exception e) {
                s.exec("update post_media set thumbnail_attempts=thumbnail_attempts+1 where id=?", m.get("id"));
                metrics.counter("feed.media.failures").increment();
            }
        });
    }
}
