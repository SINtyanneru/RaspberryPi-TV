package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

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
		Javalin app = Javalin.create().start(81);

		app.get("/", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				ctx.header("Content-Type", "text/html; charset=UTF-8");
				ctx.result(Resource.load("/html/index.html").get());
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

				client.onClose(new Runnable() {
					@Override
					public void run() {
						Core.detach_playqueue_listener(listener);
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
