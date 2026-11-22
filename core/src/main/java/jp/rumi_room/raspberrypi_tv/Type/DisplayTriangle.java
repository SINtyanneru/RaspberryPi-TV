package jp.rumi_room.raspberrypi_tv.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

//三角形オブジェクト（種別0x02）。3頂点を塗りつぶす。
//引数3: 頂点1・頂点2・頂点3のX/Y（各u64、ビッグエンディアン）
//引数4: 色（R・G・B・A、各u8）
public class DisplayTriangle extends DisplayObject {
	private long x1;
	private long y1;
	private long x2;
	private long y2;
	private long x3;
	private long y3;
	private int r;
	private int g;
	private int b;
	private int a;

	public DisplayTriangle(long x1, long y1, long x2, long y2, long x3, long y3, int r, int g, int b, int a) {
		super(Type.TRIANGLE);
		this.x1 = x1;
		this.y1 = y1;
		this.x2 = x2;
		this.y2 = y2;
		this.x3 = x3;
		this.y3 = y3;
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	//頂点をまとめて変える（書き換えコマンド用）。
	public void set_points(long x1, long y1, long x2, long y2, long x3, long y3) {
		this.x1 = x1;
		this.y1 = y1;
		this.x2 = x2;
		this.y2 = y2;
		this.x3 = x3;
		this.y3 = y3;
	}

	//色をまとめて変える（書き換えコマンド用）。
	public void set_color(int r, int g, int b, int a) {
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	@Override
	public byte[][] build_args() {
		ByteBuffer points = ByteBuffer.allocate(48);
		points.order(ByteOrder.BIG_ENDIAN);
		points.putLong(this.x1);
		points.putLong(this.y1);
		points.putLong(this.x2);
		points.putLong(this.y2);
		points.putLong(this.x3);
		points.putLong(this.y3);
		byte[] color = new byte[]{(byte) this.r, (byte) this.g, (byte) this.b, (byte) this.a};
		return new byte[][]{points.array(), color};
	}
}
