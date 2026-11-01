package com.clockdash;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** 二维码生成与解码（zxing core） */
public final class QrCodec {

    private QrCodec() {}

    /** 生成二维码位图（白底黑点） */
    public static Bitmap encode(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, Integer.valueOf(1));
            com.google.zxing.common.BitMatrix m =
                    new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int[] pixels = new int[w * w];
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
            b.setPixels(pixels, 0, w, 0, 0, w, w);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从相机 NV21 预览帧解码二维码文本；失败返回 null（会再尝试旋转 90°） */
    public static String decodeNv21(byte[] data, int width, int height) {
        try {
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
            String r = decode(new BinaryBitmap(new HybridBinarizer(src)));
            if (r == null) {
                r = decode(new BinaryBitmap(new HybridBinarizer(src.rotateCounterClockwise())));
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String decode(BinaryBitmap bitmap) {
        try {
            Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result r = new MultiFormatReader().decode(bitmap, hints);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }
}
