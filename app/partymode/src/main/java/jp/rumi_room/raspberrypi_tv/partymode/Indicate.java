package jp.rumi_room.raspberrypi_tv.partymode;

import java.awt.*;
import java.awt.image.*;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.*;
import java.nio.channels.SocketChannel;

public class Indicate {
	private static SocketChannel fb = null;
	private static  BufferedImage image;
	private static Graphics2D g;

	public static void open_fb(String path) throws IOException {
		fb = SocketChannel.open(UnixDomainSocketAddress.of(path));
		image = new BufferedImage(Main.indicate_width, Main.indicate_height, BufferedImage.TYPE_INT_ARGB);
		g = image.createGraphics();
	}

	public static Graphics2D get_g() {
		return g;
	}

	public static void redraw() {
		int[] pixel_list = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		ByteBuffer buffer = ByteBuffer.allocateDirect(Main.indicate_width * Main.indicate_height * 4);
		buffer.order(ByteOrder.nativeOrder());

		try {
			//1フレーム送信(imageに描いた後で呼ぶ)
			buffer.asIntBuffer().put(pixel_list);
			buffer.position(0);
			while (buffer.hasRemaining()) {
				fb.write(buffer);
			}
		} catch (Exception ex) {
		}
	}
}
