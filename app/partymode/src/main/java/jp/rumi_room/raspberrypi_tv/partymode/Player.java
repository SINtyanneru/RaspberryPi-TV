package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;

public class Player {
	private static BlockingQueue<PlayItem> play_wait_queue = new ArrayBlockingQueue<>(100);

	//再生位置の変化の通知先(SSE配信用)
	public interface PositionListener {
		void on_update();
	}

	private static List<PositionListener> position_listener_list = new CopyOnWriteArrayList<>();

	private static volatile PlayItem playing_item = null;
	private static volatile long play_position_ms = 0;
	private static volatile long play_duration_ms = 0;

	public static void add_position_listener(PositionListener listener) {
		position_listener_list.add(listener);
	}

	public static void remove_position_listener(PositionListener listener) {
		position_listener_list.remove(listener);
	}

	public static PlayItem get_playing_item() {
		return playing_item;
	}

	public static long get_position_ms() {
		return play_position_ms;
	}

	public static long get_duration_ms() {
		return play_duration_ms;
	}

	private static void notify_position() {
		for (PositionListener listener : position_listener_list) {
			try {
				listener.on_update();
			} catch (Exception ignored) {
			}
		}
	}

	//ファイルの長さを取得する(取得できなければ0)
	private static long probe_duration_ms(PlayItem pi) {
		try {
			Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", pi.get_file_path().toString()).start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line = reader.readLine();
			process.waitFor();
			if (line != null) {
				return (long)(Double.parseDouble(line.trim()) * 1000);
			}
		} catch (Exception ex) {
		}
		return 0;
	}

	public static void main() {
		while (true) {
			try {
				PlayItem pi = play_wait_queue.take();

				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "ffmpeg起動: " + pi.get_file_path());
				}});

				//再生状態を公開する(SSEで配信するため)
				playing_item = pi;
				play_position_ms = 0;
				play_duration_ms = probe_duration_ms(pi);
				notify_position();

				Process process = new ProcessBuilder(
					"ffmpeg", "-progress", "pipe:1", "-re", "-i", pi.get_file_path().toString(),
					//映像
					"-map", "0:v:0",
					//処理が間に合わない時はフレームを落として音声の時計に追いつく(遅延が溜まらないように)
					"-fps_mode", "drop",
					"-vf", "scale=" + Main.screen_width + ":" + Main.screen_height,
					"-f", "rawvideo", "-pix_fmt", "bgra",
					"unix://" + Main.vide_fb,
					//音声(PipeWireのPulse互換へ。音量均一化はダウンロード時に済ませてある)
					"-map", "0:a:0",
					"-f", "pulse", "partymode"
				).redirectError(ProcessBuilder.Redirect.to(Main.cache_dir.resolve("ffmpeg.log").toFile())).start();

				//ffmpegの-progress出力を読みながら再生位置を更新する
				BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
				String line;
				long last_notify_ms = 0;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("out_time_ms=")) {
						try {
							//out_time_msは名前に反してマイクロ秒
							play_position_ms = Long.parseLong(line.substring("out_time_ms=".length()).trim()) / 1000;
						} catch (Exception ex) {
						}
						if (Math.abs(play_position_ms - last_notify_ms) >= 250) {
							last_notify_ms = play_position_ms;
							notify_position();
						}
					}
				}

				int exit_code = process.waitFor();

				playing_item = null;
				play_position_ms = 0;
				play_duration_ms = 0;
				notify_position();
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
