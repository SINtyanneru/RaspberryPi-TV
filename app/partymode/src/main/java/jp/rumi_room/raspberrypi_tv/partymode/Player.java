package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;
import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayerRequest;

public class Player {
	private static LinkedBlockingDeque<UUID> play_wait_queue = new LinkedBlockingDeque<>(100);

	//再生位置の変化の通知先(SSE配信用)
	public interface PositionListener {
		void on_update();
	}

	private static List<PositionListener> position_listener_list = new CopyOnWriteArrayList<>();

	private static AtomicReference<UUID> playing_id = new AtomicReference<>(null);
	private static AtomicLong play_position_ms = new AtomicLong(0);
	private static AtomicLong play_duration_ms = new AtomicLong(0);
	private static AtomicReference<Process> playing_process = new AtomicReference<>(null);
	private static AtomicLong seek_request_ms = new AtomicLong(-1);
	//次に再生するアイテムの指定(次へ・前へ・再生中のアイテムが削除された時に使う)
	private static AtomicReference<UUID> jump_target_id = new AtomicReference<>(null);
	private static AtomicBoolean paused = new AtomicBoolean(false);
	private static AtomicReference<PlayerRequest> player_request = new AtomicReference<>(PlayerRequest.None);

	private final static ObjectMapper OM = new ObjectMapper();
	//再生中アイテムの字幕ストリーム一覧と、選択中の字幕(-1は無し)
	private static AtomicReference<List<Map<String, Object>>> subtitle_list = new AtomicReference<>(new ArrayList<>());
	private static AtomicInteger subtitle_selected = new AtomicInteger(-1);

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
		if (playing_id.get() == null || playing_process.get() == null) {
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
		jump_target_id.set(null);
		play_wait_queue.clear();
		kill_playing();
	}

	//次の動画へ進む(再生中のIDからキュー上の次を探し、無ければ何もしない)
	public static void next() {
		UUID id = playing_id.get();
		if (id == null) return;

		Optional<PlayItem> next = Core.get_next_playitem(id);
		if (next.isEmpty()) return;

		jump_target_id.set(next.get().get_id());
		kill_playing();
	}

	//前の動画へ戻る(再生中のIDからキュー上の前を探し、無ければ先頭から再生し直す)
	public static void prev() {
		UUID id = playing_id.get();
		if (id == null) return;

		Optional<PlayItem> prev = Core.get_prev_playitem(id);
		if (prev.isEmpty()) {
			seek(0);
			return;
		}
		jump_target_id.set(prev.get().get_id());
		kill_playing();
	}

	//指定したアイテムへ再生を移す(再生中のアイテムが削除された時など)
	public static void jump_to(UUID id) {
		jump_target_id.set(id);
		kill_playing();
	}

	public static void add_position_listener(PositionListener listener) {
		position_listener_list.add(listener);
	}

	public static void remove_position_listener(PositionListener listener) {
		position_listener_list.remove(listener);
	}

	//再生中の動画のIDを返す(再生していなければnull)
	public static UUID get_playing_id() {
		return playing_id.get();
	}

	//再生中アイテムの字幕ストリーム一覧を返す
	public static List<Map<String, Object>> get_subtitle_list() {
		return subtitle_list.get();
	}

	//選択中の字幕の番号を返す(無しは-1)
	public static int get_subtitle_selected() {
		return subtitle_selected.get();
	}

	//字幕を選ぶ(-1で無し)。選んだ字幕はSRTへ変換して読み込む
	public static void set_subtitle(int index) {
		UUID id = playing_id.get();
		if (id == null) return;

		if (index < 0) {
			subtitle_selected.set(-1);
			Subtitle.clear_cue();
			notify_position();
			return;
		}
		if (index >= subtitle_list.get().size()) return;

		Optional<PlayItem> item = Core.get_playitem_from_id(id);
		if (item.isEmpty()) return;
		PlayItem pi = item.get();

		try {
			Path srt_path = pi.get_file_path().getParent().resolve("subtitle-" + index + ".srt");
			if (Files.exists(srt_path) == false) {
				Process process = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-i", pi.get_file_path().toString(), "-map", "0:s:" + index, "-c:s", "srt", srt_path.toString()).start();
				if (process.waitFor() != 0) {
					return;
				}
			}
			//変換している間に別の動画へ移っていたら適用しない
			if (id.equals(playing_id.get()) == false) return;
			Subtitle.set_cue_list(Subtitle.parse_srt(Files.readString(srt_path)));
			subtitle_selected.set(index);
		} catch (Exception ex) {
			return;
		}
		notify_position();
	}

	//再生を始めるアイテムの字幕ストリームを調べる
	private static void load_subtitles(PlayItem pi) {
		subtitle_list.set(new ArrayList<>());
		subtitle_selected.set(-1);
		Subtitle.clear_cue();
		try {
			Process process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "s", "-show_streams", "-of", "json", pi.get_file_path().toString()).start();
			String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			JsonNode streams = OM.readTree(json).get("streams");
			List<Map<String, Object>> list = new ArrayList<>();
			if (streams != null) {
				for (JsonNode stream : streams) {
					Map<String, Object> entry = new HashMap<>();
					entry.put("INDEX", list.size());
					entry.put("LANGUAGE", stream.path("tags").path("language").asText(""));
					entry.put("TITLE", stream.path("tags").path("title").asText(""));
					entry.put("CODEC", stream.path("codec_name").asText(""));
					list.add(entry);
				}
			}
			subtitle_list.set(list);
		} catch (Exception ex) {
		}
		notify_position();
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
		//字幕の表示を再生位置に合わせるスレッド
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					Subtitle.update(get_position_ms());
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		}).start();

		UUID pending_id = null;
		while (true) {
			try {
				UUID id;
				if (pending_id != null) {
					id = pending_id;
					pending_id = null;
				} else {
					id = play_wait_queue.take();
				}

				//キューから削除されていたら再生しない
				Optional<PlayItem> item = Core.get_playitem_from_id(id);
				if (item.isEmpty()) {
					continue;
				}
				PlayItem pi = item.get();

				//まだダウンロードが終わっていなければ、完了時に待ちキューへ入り直すので待つ
				if (pi.get_downloaded() == false) {
					continue;
				}

				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "ffmpeg起動: " + pi.get_file_path());
				}});

				//再生状態を公開する(SSEで配信するため)
				playing_id.set(pi.get_id());;
				play_position_ms.set(0);;
				play_duration_ms.set(probe_duration_ms(pi));;
				notify_position();
				load_subtitles(pi);

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
					playing_process.set(process);

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
						jump_target_id.set(null);
						break;
					}
					//次に再生する指定が来ていたら、それを再生する(次へ・前へ・再生中の削除)
					UUID jump_id = jump_target_id.getAndSet(null);
					if (jump_id != null) {
						seek_request_ms.set(-1);
						play_wait_queue.remove(jump_id);
						pending_id = jump_id;
						break;
					}
					//シーク要求が来ていたら、同じ動画を指定位置から再生し直す
					if (seek_request_ms.get() >= 0) {
						start_offset_ms = seek_request_ms.get();
						seek_request_ms.set(-1);
						continue;
					}
					//自然に終わった(キュー上で次があり、ダウンロード済みなら続けて再生する)
					Optional<PlayItem> next = Core.get_next_playitem(pi.get_id());
					if (next.isPresent() && next.get().get_downloaded()) {
						play_wait_queue.remove(next.get().get_id());
						pending_id = next.get().get_id();
					}
					break;
				}

				playing_id.set(null);
				subtitle_list.set(new ArrayList<>());
				subtitle_selected.set(-1);
				Subtitle.clear_cue();
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

	public static void add_wait_queue(UUID id) throws InterruptedException {
		play_wait_queue.putLast(id);
	}

	//待ちキューから取り除く(キューから削除された時など)
	public static void remove_wait_queue(UUID id) {
		play_wait_queue.remove(id);
	}
}
