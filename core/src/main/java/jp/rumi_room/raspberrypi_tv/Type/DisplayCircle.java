package jp.rumi_room.raspberrypi_tv.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

//円オブジェクト（種別0x04）。塗りつぶしの円。
//引数3: 中心のX/Yと半径（各u64、ビッグエンディアン）
//引数4: 色（R・G・B・A、各u8）
public class DisplayCircle extends DisplayObject {
	private long cx;
	private long cy;
	private long radius;
	private int r;
	private int g;
	private int b;
	private int a;

	public DisplayCircle(long cx, long cy, long radius, int r, int g, int b, int a) {
		super(Type.CIRCLE);
		this.cx = cx;
		this.cy = cy;
		this.radius = radius;
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	//中心と半径をまとめて変える（書き換えコマンド用）。
	public void set_geometry(long cx, long cy, long radius) {
		this.cx = cx;
		this.cy = cy;
		this.radius = radius;
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
		ByteBuffer geometry = ByteBuffer.allocate(24);
		geometry.order(ByteOrder.BIG_ENDIAN);
		geometry.putLong(this.cx);
		geometry.putLong(this.cy);
		geometry.putLong(this.radius);
		byte[] color = new byte[]{(byte) this.r, (byte) this.g, (byte) this.b, (byte) this.a};
		return new byte[][]{geometry.array(), color};
	}
}
