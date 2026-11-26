package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;

public class Indicate {
	private static SocketChannel fb = null;

	public static void open_fb(String path) throws IOException {
		fb = SocketChannel.open(UnixDomainSocketAddress.of(path));
	}
}
