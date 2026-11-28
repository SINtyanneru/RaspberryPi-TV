package jp.rumi_room.raspberrypi_tv.Tool;

import java.nio.ByteBuffer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

//文字列をQRコードにして、BGRAフレーム(白地に黒)で返す
public class QrCode {
	public static byte[] build_frame(String text, int size) throws WriterException {
		BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
		ByteBuffer buffer = ByteBuffer.allocate(size * size * 4);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				byte value = matrix.get(x, y) ? (byte) 0x00 : (byte) 0xFF;
				buffer.put(value); //B
				buffer.put(value); //G
				buffer.put(value); //R
				buffer.put((byte) 0xFF); //A
			}
		}
		return buffer.array();
	}
}
