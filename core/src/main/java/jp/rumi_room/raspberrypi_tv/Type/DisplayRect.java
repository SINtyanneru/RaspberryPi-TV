package jp.rumi_room.raspberrypi_tv.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

//四角形オブジェクト（種別0x01）。
//引数3: X座標・Y座標・横幅・縦幅（各u64、ビッグエンディアン）
//引数4: 色（R・G・B・A、各u8）
public class DisplayRect extends DisplayObject {
	private long x;
	private long y;
	private long width;
	private long height;
	private int r;
	private int g;
	private int b;
	private int a;

	public DisplayRect(long x, long y, long width, long height, int r, int g, int b, int a) {
		super(Type.RECT);
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	public long get_x() {
		return this.x;
	}

	public long get_y() {
		return this.y;
	}

	public long get_width() {
		return this.width;
	}

	public long get_height() {
		return this.height;
	}

	//位置とサイズをまとめて変える（書き換えコマンド用）。
	public void set_geometry(long x, long y, long width, long height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
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
		ByteBuffer geometry = ByteBuffer.allocate(32);
		geometry.order(ByteOrder.BIG_ENDIAN);
		geometry.putLong(this.x);
		geometry.putLong(this.y);
		geometry.putLong(this.width);
		geometry.putLong(this.height);
		byte[] color = new byte[]{(byte) this.r, (byte) this.g, (byte) this.b, (byte) this.a};
		return new byte[][]{geometry.array(), color};
	}
}
