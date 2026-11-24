package jp.rumi_room.raspberrypi_tv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import jp.rumi_room.raspberrypi_tv.Type.DisplayObject;

//displayコンポーネント(rtv-display)の起動と制御を担当する。
//通信はプロセスの標準入出力で、専用のバイナリ形式を使う。
//要求: [u8 コマンド][u8 引数の数]([u8 引数サイズ][引数データ])の繰り返し
//応答: [u8 ステータス]（0x20ならOK）
public class Display {
	//初期化コマンド。display側はこれを受けて初めて画面を出す。
	private static final byte COMMAND_INIT = 0x00;
	//オブジェクト作成コマンド。
	private static final byte COMMAND_CREATE_OBJECT = 0x01;
	//オブジェクト書き換えコマンド。
	private static final byte COMMAND_UPDATE_OBJECT = 0x02;
	//オブジェクト削除コマンド。
	private static final byte COMMAND_DELETE_OBJECT = 0x03;
	//オブジェクト全削除コマンド。
	private static final byte COMMAND_DELETE_ALL_OBJECTS = 0x04;
	//画面サイズ取得コマンド。応答はステータスの後に幅と高さ（各u32、ビッグエンディアン）が続く。
	private static final byte COMMAND_GET_SIZE = 0x05;
	private static final int STATUS_OK = 0x20;

	private final String[] command_line;
	private Process process;
	private OutputStream out;
	private InputStream in;
	private long next_object_id = 1;
	//作成済みオブジェクトの一覧（全削除でIDを戻すために持つ）。
	private final List<DisplayObject> objects = new ArrayList<>();

	public Display() {
		this("./rtv-display");
	}

	public Display(String... command_line) {
		this.command_line = command_line;
	}

	//プロセスが存在することを確認し、無ければ起動する。
	//まだコマンドは送らないので、画面は出ない。
	public void init() throws IOException {
		if (this.process != null && this.process.isAlive()) {
			return;
		}
		ProcessBuilder builder = new ProcessBuilder(this.command_line);
		//displayのログ（標準エラー）はそのままこちらへ流す
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		this.process = builder.start();
		this.out = this.process.getOutputStream();
		this.in = this.process.getInputStream();
	}

	//初期化コマンドを送る。ここで画面が切り替わる。
	public void start() throws IOException {
		int status = this.send_command(COMMAND_INIT);
		if (status != STATUS_OK) {
			throw new IOException("display init failed: status=0x" + Integer.toHexString(status));
		}
	}

	//オブジェクトを作成し、そのオブジェクトを返す。IDはここで生成して割り当てる。
	//引数1: ID（u64、ビッグエンディアン）
	//引数2: 種類（u8）
	//引数3以降: 種類ごとの内容（DisplayObject.build_args）
	public <T extends DisplayObject> T create_object(T object) throws IOException {
		if (object.get_id() == 0) {
			object.set_id(this.next_object_id);
			this.next_object_id++;
		}
		byte[][] object_args = object.build_args();
		byte[][] args = new byte[object_args.length + 2][];
		args[0] = encode_id(object.get_id());
		args[1] = new byte[]{object.get_type().get_code()};
		System.arraycopy(object_args, 0, args, 2, object_args.length);
		int status = this.send_command(COMMAND_CREATE_OBJECT, args);
		if (status != STATUS_OK) {
			throw new IOException("display create object failed: status=0x" + Integer.toHexString(status));
		}
		object.set_display(this);
		if (!this.objects.contains(object)) {
			this.objects.add(object);
		}
		return object;
	}

	//オブジェクトを書き換える（位置・サイズ・色）。作り直さないので軽い。
	//DisplayObject.update()からだけ呼ぶこと。
	//引数1: ID、引数2以降: 種類ごとの内容（DisplayObject.build_args）
	public void __update_object(DisplayObject object) throws IOException {
		if (object.get_id() == 0) {
			throw new IOException("object is not created yet");
		}
		byte[][] object_args = object.build_args();
		byte[][] args = new byte[object_args.length + 1][];
		args[0] = encode_id(object.get_id());
		System.arraycopy(object_args, 0, args, 1, object_args.length);
		int status = this.send_command(COMMAND_UPDATE_OBJECT, args);
		if (status != STATUS_OK) {
			throw new IOException("display update object failed: status=0x" + Integer.toHexString(status));
		}
	}

	//オブジェクトを削除する。削除できたらIDを未割り当てに戻す。
	//DisplayObject.delete()からだけ呼ぶこと。
	//引数1: ID
	public void __delete_object(DisplayObject object) throws IOException {
		if (object.get_id() == 0) {
			throw new IOException("object is not created yet");
		}
		int status = this.send_command(COMMAND_DELETE_OBJECT, encode_id(object.get_id()));
		if (status != STATUS_OK) {
			throw new IOException("display delete object failed: status=0x" + Integer.toHexString(status));
		}
		object.set_id(0);
		object.set_display(null);
		this.objects.remove(object);
	}

	//全オブジェクトを削除する。作成済みオブジェクトのIDも未割り当てに戻す。
	//引数なし。
	public void delete_all_objects() throws IOException {
		int status = this.send_command(COMMAND_DELETE_ALL_OBJECTS);
		if (status != STATUS_OK) {
			throw new IOException("display delete all objects failed: status=0x" + Integer.toHexString(status));
		}
		for (DisplayObject object : this.objects) {
			object.set_id(0);
			object.set_display(null);
		}
		this.objects.clear();
	}

	//画面の幅（ピクセル）を返す。初期化コマンドを送る前は例外になる。
	public int get_width() throws IOException {
		return this.query_size()[0];
	}

	//画面の高さ（ピクセル）を返す。初期化コマンドを送る前は例外になる。
	public int get_height() throws IOException {
		return this.query_size()[1];
	}

	//画面サイズ取得コマンドを送り、{幅, 高さ}を返す。
	private int[] query_size() throws IOException {
		int status = this.send_command(COMMAND_GET_SIZE);
		if (status != STATUS_OK) {
			throw new IOException("display get size failed: status=0x" + Integer.toHexString(status));
		}
		byte[] data = this.in.readNBytes(8);
		if (data.length < 8) {
			throw new IOException("display process closed the connection");
		}
		ByteBuffer buffer = ByteBuffer.wrap(data);
		buffer.order(ByteOrder.BIG_ENDIAN);
		return new int[]{buffer.getInt(), buffer.getInt()};
	}

	//IDをu64ビッグエンディアンのバイト列にする。
	private static byte[] encode_id(long id) {
		ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.order(ByteOrder.BIG_ENDIAN);
		buffer.putLong(id);
		return buffer.array();
	}

	//コマンドを1つ送り、応答のステータスを返す。
	private int send_command(byte command, byte[]... args) throws IOException {
		if (this.process == null || !this.process.isAlive()) {
			throw new IOException("display process is not running");
		}
		this.out.write(command);
		this.out.write(args.length);
		for (byte[] arg : args) {
			this.out.write(arg.length);
			this.out.write(arg);
		}
		this.out.flush();
		int status = this.in.read();
		if (status < 0) {
			throw new IOException("display process closed the connection");
		}
		return status;
	}
}
