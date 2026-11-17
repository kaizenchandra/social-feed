package com.example.socialfeed;

import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Structural validation only: this is not a malware scanner or video transcoder.
 */
final class MediaValidation {
    static final long IMAGE_BYTES = 10 * 1024 * 1024;

    private MediaValidation() {
    }

    static void validate(InputStream source, String contentType, long declaredBytes) throws IOException {
        if (contentType.startsWith("image/")) {
            if (declaredBytes > IMAGE_BYTES) throw new IOException("Image exceeds byte budget");
            // Bound reads even if an object store returns more data than its HEAD metadata promised.
            byte[] bytes = source.readNBytes((int) Math.min(declaredBytes, IMAGE_BYTES) + 1);
            if (bytes.length != declaredBytes) throw new IOException("Image size mismatch");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Unsupported image");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName();
                    if (!(contentType.equals("image/png") && format.equalsIgnoreCase("png")) &&
                            !(contentType.equals("image/jpeg") && format.equalsIgnoreCase("JPEG")))
                        throw new IOException("Image type mismatch");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 20_000_000)
                        throw new IOException("Image dimensions exceed budget");
                    if (reader.read(0) == null) throw new IOException("Image cannot be decoded");
                } finally {
                    reader.dispose();
                }
            }
        } else if (contentType.equals("video/mp4")) {
            byte[] header = source.readNBytes(12);
            if (header.length != 12 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p')
                throw new IOException("Missing MP4 file type box");
            long boxSize = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(header).getInt());
            if (boxSize < 16 || boxSize > declaredBytes) throw new IOException("Invalid MP4 file type box");
        } else throw new IOException("Unsupported media type");
    }
}
