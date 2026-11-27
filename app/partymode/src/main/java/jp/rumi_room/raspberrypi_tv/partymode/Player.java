package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;

public class Player {
	private static BlockingQueue<PlayItem> play_wait_queue = new ArrayBlockingQueue<>(100);

	public static void main() {
		while (true) {
			try {
				PlayItem pi = play_wait_queue.take();

				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "ffmpeg起動: " + pi.get_file_path());
				}});

				Process process = new ProcessBuilder(
					"ffmpeg", "-re", "-i", pi.get_file_path().toString(),
					//映像
					"-map", "0:v:0",
					"-vf", "scale=" + Main.screen_width + ":" + Main.screen_height,
					"-f", "rawvideo", "-pix_fmt", "bgra",
					"unix://" + Main.vide_fb,
					//音声(PipeWireのPulse互換へ)
					"-map", "0:a:0",
					"-f", "pulse", "partymode"
				).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.to(Main.cache_dir.resolve("ffmpeg.log").toFile())).start();

				int exit_code = process.waitFor();
				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "ffmpeg exit code: " + exit_code);
				}});
			} catch (Exception ex) {
				StringWriter string_writer = new StringWriter();
				ex.printStackTrace(new PrintWriter(string_writer));
				try {
					Main.send_message(new HashMap<>(){{
						put("TYPE", "LOG");
						put("TEXT", string_writer.toString());
					}});
				} catch (Exception ignored) {
				}
				continue;
			}
		}
	}

	public static void add_wait_queue(PlayItem pi) throws InterruptedException {
		play_wait_queue.put(pi);
	}
}
