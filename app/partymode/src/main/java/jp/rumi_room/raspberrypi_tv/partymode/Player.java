package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;
import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayerRequest;

public class Player {
	private static LinkedBlockingDeque<PlayItem> play_wait_queue = new LinkedBlockingDeque<>(100);

	//再生位置の変化の通知先(SSE配信用)
	public interface PositionListener {
		void on_update();
	}

	private static List<PositionListener> position_listener_list = new CopyOnWriteArrayList<>();

	private static AtomicReference<PlayItem> playing_item = new AtomicReference<>(null);
	private static AtomicLong play_position_ms = new AtomicLong(0);
	private static AtomicLong play_duration_ms = new AtomicLong(0);
	private static AtomicReference<Process> playing_process = new AtomicReference<>(null);
	private static AtomicLong seek_request_ms = new AtomicLong(-1);
	private static AtomicReference<PlayItem> prev_item = new AtomicReference<>(null);
	private static AtomicBoolean paused = new AtomicBoolean(false);
	private static AtomicReference<PlayerRequest> player_request = new AtomicReference<>(PlayerRequest.None);

	public static boolean is_pause() {
		return paused.get();
	}

	//再生中のffmpegを終了させる(SIGKILLなので、止まっているプロセスでも即座に落ちる)
	//出力先はソケットとPulseAudioで、終了処理で書くべきトレーラ等が無いためKILLで問題ない
	private static void kill_playing() {
		Process process = playing_process.get();
		if (process == null) {
			return;
		}
		process.destroyForcibly();
	}

	//指定位置へシークする(再生中のffmpegを落として、次ループで-ss付きで起動し直す)
	public static void seek(long position_ms) {
		if (playing_item.get() == null || playing_process.get() == null) {
			return;
		}
		if (position_ms < 0) {
			position_ms = 0;
		}
		seek_request_ms.set(position_ms);;
		kill_playing();
	}

	//一時停止する(ffmpegへSIGSTOPを送る。映像も音声も同じプロセスなので同期は崩れない)
	public static void pause() {
		Process process = playing_process.get();
		if (process == null || paused.get()) return;

		try {
			new ProcessBuilder("kill", "-STOP", String.valueOf(process.pid())).start().waitFor();
			paused.set(true);;
			notify_position();
		} catch (Exception ex) {
		}
	}

	//再生を再開する(SIGCONT)
	public static void play() {
		Process process = playing_process.get();
		if (process == null || paused.get() == false) return;

		try {
			new ProcessBuilder("kill", "-CONT", String.valueOf(process.pid())).start().waitFor();
			paused.set(false);
			notify_position();
		} catch (Exception ex) {
		}
	}

	//停止する(再生中を落とし、待ちキューも空にする)
	public static void stop() {
		player_request.set(PlayerRequest.Stop);
		play_wait_queue.clear();
		kill_playing();
	}

	//次の動画へ進む(play_queue上で次が無ければ何もしない)
	public static void next() {
		if (playing_item.get() == null) return;

		int index = Core.get_playing_index();
		if (index < 0 || index >= Core.get_play_queue().size() - 1) return;

		player_request.set(PlayerRequest.Next);
		kill_playing();
	}

	//前の動画へ戻る(前の動画が無ければ先頭から再生し直す)
	public static void prev() {
		if (playing_item.get() == null) return;

		if (prev_item.get() == null) {
			seek(0);
			return;
		}
		player_request.set(PlayerRequest.Prev);
		kill_playing();
	}

	public static void add_position_listener(PositionListener listener) {
		position_listener_list.add(listener);
	}

	public static void remove_position_listener(PositionListener listener) {
		position_listener_list.remove(listener);
	}

	//再生中の動画を返す(再生していなければnull)
	public static PlayItem get_playing() {
		return playing_item.get();
	}

	public static long get_position_ms() {
		return play_position_ms.get();
	}

	public static long get_duration_ms() {
		return play_duration_ms.get();
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
				playing_item.set(pi);;
				play_position_ms.set(0);;
				play_duration_ms.set(probe_duration_ms(pi));;
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

					play_position_ms.set(start_offset_ms);
					notify_position();

					Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.to(Main.cache_dir.resolve("ffmpeg.log").toFile())).start();
					playing_process.set(process);;

					//ffmpegの-progress出力を読みながら再生位置を更新する
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					String line;
					long last_notify_ms = start_offset_ms;
					while ((line = reader.readLine()) != null) {
						if (line.startsWith("out_time_ms=")) {
							try {
								//out_time_msは名前に反してマイクロ秒。シーク時は開始位置からの相対値になる
								play_position_ms.set(start_offset_ms + Long.parseLong(line.substring("out_time_ms=".length()).trim()) / 1000);;
							} catch (Exception ex) {
							}
							if (Math.abs(play_position_ms.get() - last_notify_ms) >= 250) {
								last_notify_ms = play_position_ms.get();
								notify_position();
							}
						}
					}

					exit_code = process.waitFor();
					playing_process.set(null);
					paused.set(false);

					//停止要求が来ていたら終わる(待ちキューはstop側でクリア済み)
					if (player_request.get() == PlayerRequest.Stop) {
						player_request.set(PlayerRequest.None);
						seek_request_ms.set(-1);
						prev_item.set(null);
						break;
					}
					//次へ
					if (player_request.get() == PlayerRequest.Next) {
						player_request.set(PlayerRequest.None);
						seek_request_ms.set(-1);
						prev_item.set(pi);
						break;
					}
					//前へ(今の動画は、前の動画の次に再生し直す)
					if (player_request.get() == PlayerRequest.Prev) {
						player_request.set(PlayerRequest.None);
						seek_request_ms.set(-1);
						PlayItem back = prev_item.get();
						play_wait_queue.offerFirst(pi);
						play_wait_queue.offerFirst(back);
						prev_item.set(pi);
						break;
					}
					//シーク要求が来ていたら、同じ動画を指定位置から再生し直す
					if (seek_request_ms.get() >= 0) {
						start_offset_ms = seek_request_ms.get();
						seek_request_ms.set(-1);
						continue;
					}
					//自然に終わった
					prev_item.set(pi);
					break;
				}

				playing_item.set(null);
				play_position_ms.set(0);
				play_duration_ms.set(0);
				notify_position();
				final int final_exit_code = exit_code;
				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "ffmpeg exit code: " + final_exit_code);
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
		play_wait_queue.putLast(pi);
	}
}
