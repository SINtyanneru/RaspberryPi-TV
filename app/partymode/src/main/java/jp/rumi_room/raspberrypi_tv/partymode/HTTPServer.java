package jp.rumi_room.raspberrypi_tv.partymode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;
import io.javalin.http.*;
import io.javalin.http.sse.SseClient;
import jp.rumi_room.raspberrypi_tv.partymode.Type.ObservableList;
import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;

public class HTTPServer {
	private final static ObjectMapper OM = new ObjectMapper();

	private static CountDownLatch cdl = new CountDownLatch(1);

	public static void start() {
		try {
			start_server();
		} catch (Exception ex) {
			//起動失敗してもawaitで永久に止まらないようにする(ウェブページだけ死んで、本体は動く)
			ex.printStackTrace();
			try {
				StringWriter string_writer = new StringWriter();
				ex.printStackTrace(new PrintWriter(string_writer));
				Main.send_message(new HashMap<>(){{
					put("TYPE", "LOG");
					put("TEXT", "HTTPServer起動失敗: " + string_writer.toString());
				}});
			} catch (Exception ignored) {
			}
		} finally {
			cdl.countDown();
		}
	}

	private static void start_server() {
		Javalin app = Javalin.create().start(81);

		app.get("/", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				ctx.header("Content-Type", "text/html; charset=UTF-8");
				ctx.result(Resource.load("/html/index.html").get());
			}
		});

		app.get("/api/queue", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				try {
					List<Map<String, Object>> queue_list = new ArrayList<>();

					for (int i = 0; i < Core.get_play_queue().size(); i++) {
						PlayItem pi = Core.get_play_queue().get(i);

						Map<String, Object> queue = new HashMap<>();
						queue.put("ID", pi.get_id());
						queue.put("ADD_AT", pi.get_add_at().atOffset(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
						queue.put("ADDER", pi.get_adder().getHostAddress());
						queue.put("SOURCE", pi.get_type().name());
						queue.put("CONTENTS_TITLE", pi.get_title());
						queue.put("CONTENTS_AUTHOR", pi.get_author());
						queue_list.add(queue);
					}

					Map<String, Object> result = new HashMap<>();
					result.put("STATUS", true);
					result.put("LIST", queue_list);

					ctx.status(200);
					ctx.header("Content-Type", "text/json; charset=UTF-8");
					ctx.result(OM.writeValueAsString(result));
				} catch (Exception ex) {
					Map<String, Object> error_result = new HashMap<>();
					error_result.put("STATUS", false);

					StringWriter string_writer = new StringWriter();
					ex.printStackTrace(new PrintWriter(string_writer));
					error_result.put("EX", string_writer.toString());

					ctx.status(500);
					ctx.result(OM.writeValueAsString(error_result));
				}
			}
		});

		app.get("/api/queue/{id}/thumbnail", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				String id = ctx.pathParam("id");
				Optional<PlayItem> pi = Core.get_playitem_from_id(UUID.fromString(id));
				if (pi.isEmpty()) {
					ctx.status(404);
					ctx.result("{\"STATUS\": false}");
				}

				BufferedImage image = pi.get().get_thumbnail();
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ImageIO.write(image, "jpg", baos);
				ctx.contentType("image/jpeg");
				ctx.result(baos.toByteArray());
			}
		});

		app.post("/api/queue", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				JsonNode root;
				try {
					root = OM.readTree(ctx.body());
				} catch (JsonProcessingException e) {
					ctx.status(400);
					return;
				}

				try {
					boolean status = Core.add_play(root.get("URL").asText(), InetAddress.getByName(ctx.ip()));

					if (status) {
						ctx.status(200);
						ctx.result("{\"STATUS\": true}");
					} else {
						ctx.status(400);
						ctx.result("{\"STATUS\": false}");
					}
				} catch (Exception ex) {
					Map<String, Object> error_result = new HashMap<>();
					error_result.put("STATUS", false);

					StringWriter string_writer = new StringWriter();
					ex.printStackTrace(new PrintWriter(string_writer));
					error_result.put("EX", string_writer.toString());

					ctx.status(500);
					ctx.result(OM.writeValueAsString(error_result));
				}
			}
		});

		app.post("/api/seek", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				JsonNode root;
				try {
					root = OM.readTree(ctx.body());
				} catch (JsonProcessingException e) {
					ctx.status(400);
					return;
				}

				Player.seek(root.get("position_ms").asLong());
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.post("/api/pause", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				Player.pause();
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.post("/api/play", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				Player.play();
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.post("/api/stop", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				Player.stop();
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.post("/api/prev", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				Player.prev();
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.post("/api/next", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				Player.next();
				ctx.status(200);
				ctx.result("{\"STATUS\": true}");
			}
		});

		app.sse("/api/sse", new Consumer<SseClient>() {
			@Override
			public void accept(SseClient client) {
				client.keepAlive();
				client.sendEvent("init", "hello");

				final ObservableList.ChangeListener<PlayItem> listener = new ObservableList.ChangeListener<PlayItem>() {
					@Override
					public void on_add(int index, PlayItem element) {
						client.sendEvent("playqueue", "{}");
					}
					@Override
					public void on_remove(int index, PlayItem element) {
						client.sendEvent("playqueue", "{}");
					}
					@Override
					public void on_set(int index, PlayItem old_element, PlayItem new_element) {
						client.sendEvent("playqueue", "{}");
					}
				};
				Core.add_playqueue_listener(listener);

				//再生位置の変化をplaytimeイベントで流す
				final Player.PositionListener position_listener = new Player.PositionListener() {
					@Override
					public void on_update() {
						try {
							Map<String, Object> payload = new HashMap<>();
							PlayItem playing = Player.get_playing();
							payload.put("playing", playing != null);
							payload.put("index", Core.get_playing_index());
							payload.put("paused", Player.is_pause());
							payload.put("title", playing != null ? playing.get_title() : null);
							payload.put("position_ms", Player.get_position_ms());
							payload.put("duration_ms", Player.get_duration_ms());
							client.sendEvent("playtime", OM.writeValueAsString(payload));
						} catch (Exception ex) {
						}
					}
				};
				Player.add_position_listener(position_listener);
				//接続直後に現在の状態を1回送る
				position_listener.on_update();

				client.onClose(new Runnable() {
					@Override
					public void run() {
						Core.detach_playqueue_listener(listener);
						Player.remove_position_listener(position_listener);
					}
				});
			}
		});

		cdl.countDown();
	}

	public static void await() throws InterruptedException {
		cdl.await();
	}
}
