package jp.rumi_room.raspberrypi_tv.partymode;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;

public class Player {
	private static BlockingQueue<PlayItem> play_wait_queue = new ArrayBlockingQueue<>(100);

	public static void main() {
		while (true) {
			try {
				PlayItem pi = play_wait_queue.take();

				Process process = new ProcessBuilder(
					"ffmpeg", "-re", "-i", pi.get_file_path().toString(),
					"-vf", "scale=" + Main.screen_width + ":" + Main.screen_height,
					"-f", "rawvideo", "-pix_fmt", "bgra",
					"unix://" + Main.vide_fb
				).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				process.waitFor();
			} catch (Exception ex) {
				continue;
			}
		}
	}

	public static void add_wait_queue(PlayItem pi) throws InterruptedException {
		play_wait_queue.put(pi);
	}
}
