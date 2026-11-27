package jp.rumi_room.raspberrypi_tv.partymode.Tool;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.security.*;

//IPアドレスからGitHub風のidenticon(64x64)を生成する
public class Identicon {
	final static int SIZE = 64;
	final static int GRID = 5;

	public static BufferedImage generate_icon(InetAddress address) {
		byte[] hash;
		try {
			hash = MessageDigest.getInstance("SHA-256").digest(address.getAddress());
		} catch (NoSuchAlgorithmException e) {
			//SHA-256は全てのJava実行環境で利用可能であることが保証されている
			throw new RuntimeException(e);
		}

		//前景色:ハッシュ末尾の1バイトから色相を決め、彩度と明度は固定
		float hue = (hash[31] & 0xFF) / 256.0f;
		Color foreground = Color.getHSBColor(hue, 0.65f, 0.85f);
		Color background = new Color(0xF0, 0xF0, 0xF0);

		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(background);
		graphics.fillRect(0, 0, SIZE, SIZE);
		graphics.setColor(foreground);

		//左3列をハッシュのビットで決め、右2列へ鏡像コピーする
		for (int y = 0; y < GRID; y++) {
			for (int x = 0; x < 3; x++) {
				int bit_index = y * 3 + x;
				boolean fill = ((hash[bit_index / 8] >> (bit_index % 8)) & 1) == 1;
				if (fill) {
					fill_cell(graphics, x, y);
					if (x != 2) {
						fill_cell(graphics, (GRID - 1) - x, y);
					}
				}
			}
		}
		graphics.dispose();
		return image;
	}

	private static void fill_cell(Graphics2D graphics, int x, int y) {
		//64は5で割り切れないので、マスの境界は都度丸めて敷き詰める
		int x0 = Math.round(x * (float) SIZE / GRID);
		int x1 = Math.round((x + 1) * (float) SIZE / GRID);
		int y0 = Math.round(y * (float) SIZE / GRID);
		int y1 = Math.round((y + 1) * (float) SIZE / GRID);
		graphics.fillRect(x0, y0, x1 - x0, y1 - y0);
	}
}