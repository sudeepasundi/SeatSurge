package com.seatsurge.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

@Component
public class QrCodeRenderer {

    private static final int SIZE_PX = 320;

    public byte[] png(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode QR code", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
