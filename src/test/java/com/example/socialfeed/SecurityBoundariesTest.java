package com.example.socialfeed;

import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityBoundariesTest {
    static byte[] image(String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }

    @Test
    void imagesMustDecodeAndMatchDeclaredType() throws Exception {
        for (String format : List.of("png", "jpeg")) {
            byte[] valid = image(format);
            MediaValidation.validate(new ByteArrayInputStream(valid), "image/" + format, valid.length);
        }
        byte[] png = image("png");
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(png), "image/jpeg", png.length)).isInstanceOf(IOException.class);
        byte[] garbage = "<script>alert(1)</script>".getBytes();
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(garbage), "image/png", garbage.length)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(Arrays.copyOf(png, 20)), "image/png", 20)).isInstanceOf(IOException.class);
    }

    @Test
    void imageBudgetsApplyBeforeLargeAllocation() throws Exception {
        byte[] png = image("png");
        java.nio.ByteBuffer.wrap(png, 16, 8).putInt(100_000).putInt(100_000);
        var crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        java.nio.ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(png), "image/png", png.length)).isInstanceOf(IOException.class).hasMessageContaining("dimensions");
        assertThatThrownBy(() -> MediaValidation.validate(InputStream.nullInputStream(), "image/png", MediaValidation.IMAGE_BYTES + 1)).isInstanceOf(IOException.class).hasMessageContaining("budget");
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(image("png")), "image/png", 1)).isInstanceOf(IOException.class).hasMessageContaining("size");
    }

    @Test
    void mp4RequiresBoundedFileTypeBox() throws Exception {
        byte[] ftyp = java.nio.ByteBuffer.allocate(16).putInt(16).put("ftypisom".getBytes()).putInt(0).array();
        MediaValidation.validate(new ByteArrayInputStream(ftyp), "video/mp4", 16);
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(ftyp), "video/mp4", 12)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MediaValidation.validate(new ByteArrayInputStream(new byte[16]), "video/mp4", 16)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsOversizedDeclaredAndChunkedBodies() throws Exception {
        var filter = new RequestBodyLimits();
        for (boolean chunked : List.of(false, true)) {
            var request = new MockHttpServletRequest("POST", "/api/v1/posts") {
                @Override
                public long getContentLengthLong() {
                    return chunked ? -1 : super.getContentLengthLong();
                }
            };
            request.setContent(new byte[RequestBodyLimits.MAX_BYTES + 1]);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void boundedBodyIsPreservedAndLimitIsInclusive() throws Exception {
        var request = new MockHttpServletRequest("PATCH", "/api/v1/users/me");
        byte[] body = new byte[RequestBodyLimits.MAX_BYTES];
        Arrays.fill(body, (byte) 'x');
        request.setContent(body);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestBodyLimits().doFilter(request, response, chain);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void failedDeadLetterSendCannotBeReportedAsRecovered() {
        var kafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("DLT unavailable")));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(mock(javax.sql.DataSource.class)));
        var worker = new EventWorkers(mock(Store.class), kafka, tx, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var recoverer = worker.deadLetterRecoverer(kafka);
        recoverer.setVerifyPartition(false);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("feed.events", 0, 1L, "key", "bad-event");
        assertThatThrownBy(() -> recoverer.accept(record, null, new IllegalArgumentException("invalid event"))).isInstanceOf(org.springframework.kafka.KafkaException.class).hasMessageContaining("Dead-letter publication");
        var sent = org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("feed.events.DLT");
    }
}
