package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
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
	private static volatile Process playing_process = null;
	private static volatile long seek_request_ms = -1;

	//指定位置へシークする(再生中のffmpegを落として、次ループで-ss付きで起動し直す)
	public static void seek(long position_ms) {
		Process process = playing_process;
		if (playing_item == null || process == null) {
			return;
		}
		if (position_ms < 0) {
			position_ms = 0;
		}
		seek_request_ms = position_ms;
		process.destroy();
	}

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

				int exit_code = 0;
				long start_offset_ms = 0;
				while (true) {
					List<String> command = new ArrayList<>();
					command.add("ffmpeg");
					command.add("-progress");
					command.add("pipe:1");
					if (start_offset_ms > 0) {
						//シーク時は入力側に-ssを付けて指定位置から読む
						command.add("-ss");
						command.add(String.valueOf(start_offset_ms / 1000.0));
					}
					command.add("-re");
					command.add("-i");
					command.add(pi.get_file_path().toString());
					//映像
					command.add("-map");
					command.add("0:v:0");
					//処理が間に合わない時はフレームを落として音声の時計に追いつく(遅延が溜まらないように)
					command.add("-fps_mode");
					command.add("drop");
					command.add("-vf");
					command.add("scale=" + Main.screen_width + ":" + Main.screen_height);
					command.add("-f");
					command.add("rawvideo");
					command.add("-pix_fmt");
					command.add("bgra");
					command.add("unix://" + Main.vide_fb);
					//音声(PipeWireのPulse互換へ。音量均一化はダウンロード時に済ませてある)
					command.add("-map");
					command.add("0:a:0");
					command.add("-f");
					command.add("pulse");
					command.add("partymode");

					play_position_ms = start_offset_ms;
					notify_position();

					Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.to(Main.cache_dir.resolve("ffmpeg.log").toFile())).start();
					playing_process = process;

					//ffmpegの-progress出力を読みながら再生位置を更新する
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					String line;
					long last_notify_ms = start_offset_ms;
					while ((line = reader.readLine()) != null) {
						if (line.startsWith("out_time_ms=")) {
							try {
								//out_time_msは名前に反してマイクロ秒。シーク時は開始位置からの相対値になる
								play_position_ms = start_offset_ms + Long.parseLong(line.substring("out_time_ms=".length()).trim()) / 1000;
							} catch (Exception ex) {
							}
							if (Math.abs(play_position_ms - last_notify_ms) >= 250) {
								last_notify_ms = play_position_ms;
								notify_position();
							}
						}
					}

					exit_code = process.waitFor();
					playing_process = null;

					//シーク要求が来ていたら、同じ動画を指定位置から再生し直す
					if (seek_request_ms >= 0) {
						start_offset_ms = seek_request_ms;
						seek_request_ms = -1;
						continue;
					}
					break;
				}

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
