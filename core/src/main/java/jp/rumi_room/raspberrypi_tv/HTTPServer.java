package jp.rumi_room.raspberrypi_tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsBinaryMessageHandler;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsCloseHandler;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsConnectHandler;
import io.javalin.websocket.WsMessageContext;
import io.javalin.websocket.WsMessageHandler;
import jp.rumi_room.raspberrypi_tv.Tool.Resource;

public class HTTPServer {
	public static BlockingQueue<byte[]> ws_queue = new LinkedBlockingQueue<>();

	public static void start() {
		Javalin app = Javalin.create().start(80);
		app.get("/", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				ctx.redirect("/index.html");
			}
		});

		app.get("/index.html", new Handler() {
			@Override
			public void handle(Context ctx) throws Exception {
				ctx.header("Content-Type", "text/html; charset=UTF-8");
				ctx.result(Resource.load("/html/index.html").get());
			}
		});

		app.ws("/ws", new Consumer<WsConfig>() {
			@Override
			public void accept(WsConfig ws) {
				ws.onConnect(new WsConnectHandler() {
					@Override
					public void handleConnect(WsConnectContext ctx) {
						try {
							ByteArrayOutputStream baos = new ByteArrayOutputStream();
							baos.write(new byte[] { 0x20 });
							baos.write(ByteBuffer.allocate(4).putInt(Main.VERSION.length()).array());
							baos.write(Main.VERSION.getBytes(StandardCharsets.UTF_8));

							ctx.send(ByteBuffer.wrap(baos.toByteArray()));
						} catch (Exception e) {
							ctx.closeSession();
						}

						new Thread(new Runnable() {
							@Override
							public void run() {
								try {
									byte[] data = ws_queue.take();
									ctx.send(ByteBuffer.wrap(data));
								} catch (InterruptedException ex) {
									return;
								}
							}
						}).start();
					}
				});

				ws.onBinaryMessage(new WsBinaryMessageHandler() {
					@Override
					public void handleBinaryMessage(WsBinaryMessageContext ctx) {
						byte[] data = ctx.data();
						ByteArrayInputStream in = new ByteArrayInputStream(data);

						try {
							byte cmd = in.readNBytes(1)[0];

							if (cmd == 0x00) {
								ctx.send(ByteBuffer.wrap(new byte[] { 0x20 }));
								return;
							}

							if (cmd == 0x01) {
								Core.key_queue.put(in.readNBytes(1)[0]);
								return ;
							}
						} catch (Exception ex) {
							return;
						}
					}
				});

				// ws.onClose(new WsCloseHandler() {
				// 	@Override
				// 	public void handleClose(WsCloseContext ctx) {
				// 	}
				// });
			}
		});
	}
}
