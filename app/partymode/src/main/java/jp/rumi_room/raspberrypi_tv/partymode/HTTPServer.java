package jp.rumi_room.raspberrypi_tv.partymode;

import java.util.concurrent.CountDownLatch;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;

public class HTTPServer {
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

		cdl.countDown();
	}

	public static void await() throws InterruptedException {
		cdl.await();
	}
}
