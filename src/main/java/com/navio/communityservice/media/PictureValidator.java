package com.navio.communityservice.media;
import com.navio.communityservice.exception.GroupException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.Locale;

public final class PictureValidator {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private PictureValidator() { }
    public record Picture(byte[] bytes, String contentType) { }
    public static Picture validate(MultipartFile file) {
        if (file.isEmpty()) throw GroupException.invalid("Choose a PNG or JPEG picture");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw tooLarge();
            try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw GroupException.invalid("Picture must be a valid PNG or JPEG");
                var reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg")) throw GroupException.invalid("Only PNG and JPEG pictures are supported");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 12_000_000) {
                        throw GroupException.invalid("Picture must be at most 4096 pixels per side and 12 megapixels");
                    }
                    var image = reader.read(0);
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, format, output)) throw GroupException.invalid("Picture cannot be decoded");
                    if (output.size() > MAX_BYTES) throw tooLarge();
                    // Re-encode decoded pixels: never serve uploaded metadata or trailing payloads.
                    return new Picture(output.toByteArray(), "image/" + format);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw GroupException.invalid("Picture must be a valid PNG or JPEG");
        }
    }
    private static GroupException tooLarge() {
        return new GroupException(HttpStatus.PAYLOAD_TOO_LARGE, "Picture must be at most 5 MiB");
    }
}
