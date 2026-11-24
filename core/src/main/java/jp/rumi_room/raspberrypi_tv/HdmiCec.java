package jp.rumi_room.raspberrypi_tv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

//hdmi-cecコンポーネント(rtv-cec)の起動と制御を担当する。
//通信はプロセスの標準入出力で、専用のバイナリ形式を使う（Displayと同じ）。
//要求: [u8 コマンド][u8 引数の数]([u8 引数サイズ][引数データ])の繰り返し
//応答: [u8 ステータス]（0x20ならOK）
//イベント: テレビからのキー入力などは応答とは別にイベントフレームで届く。
//先頭バイトが0x80台ならイベントフレームで、残りの形は要求と同じ。
//受信は受信スレッドが読み分け、rx_hookで登録したRunnableを呼ぶ。
public class HdmiCec {
	//初期化コマンド。cec側はこれを受けてアダプタを開く。
	private static final byte COMMAND_INIT = 0x00;
	//テレビの電源を入れてアクティブソースになるコマンド。
	private static final byte COMMAND_POWER_ON = 0x01;
	//テレビをスタンバイにするコマンド。
	private static final byte COMMAND_STANDBY = 0x02;
	//アクティブソースになるコマンド。
	private static final byte COMMAND_ACTIVE_SOURCE = 0x03;
	//音量を上げるコマンド。
	private static final byte COMMAND_VOLUME_UP = 0x04;
	//音量を下げるコマンド。
	private static final byte COMMAND_VOLUME_DOWN = 0x05;
	//ミュート切り替えコマンド。
	private static final byte COMMAND_MUTE = 0x06;
	//生フレーム送信コマンド。
	private static final byte COMMAND_SEND_RAW = 0x07;
	private static final int STATUS_OK = 0x20;
	private static final int STATUS_ERROR = 0xFF;

	//イベント: キーが押された。get_event_key()にUIコマンドコードが入る。
	public static final int EVENT_KEY_PRESS = 0x81;
	//イベント: キーが離された。
	public static final int EVENT_KEY_RELEASE = 0x82;
	//イベント: 電源状態の通知。get_event_power_status()に状態が入る
	//（0=オン、1=スタンバイ、2=オンへ移行中、3=スタンバイへ移行中）。
	public static final int EVENT_POWER_STATUS = 0x83;

	//リモコンのUIコマンドコード（EVENT_KEY_PRESSで届く値）。
	public static final int KEY_SELECT = 0x00;
	public static final int KEY_UP = 0x01;
	public static final int KEY_DOWN = 0x02;
	public static final int KEY_LEFT = 0x03;
	public static final int KEY_RIGHT = 0x04;
	public static final int KEY_EXIT = 0x0D;
	public static final int KEY_POWER = 0x40;
	public static final int KEY_VOLUME_UP = 0x41;
	public static final int KEY_VOLUME_DOWN = 0x42;
	public static final int KEY_MUTE = 0x43;
	public static final int KEY_PLAY = 0x44;
	public static final int KEY_STOP = 0x45;
	public static final int KEY_PAUSE = 0x46;
	public static final int KEY_REWIND = 0x48;
	public static final int KEY_FAST_FORWARD = 0x49;
	public static final int KEY_FORWARD = 0x4B;
	public static final int KEY_BACKWARD = 0x4C;
	public static final int KEY_BLUE = 0x71;
	public static final int KEY_RED = 0x72;

	private final String[] command_line;
	private Process process;
	private OutputStream out;
	private InputStream in;
	private Thread reader_thread;
	private Runnable rx_hook;
	private String osd_name;
	//最後に届いたイベントの内容（rx_hookの中から読む）。
	private volatile int event;
	private volatile int event_key;
	private volatile int event_power_status;
	//コマンドの直列化用（応答は1対1なので、同時に送るのは1つだけにする）。
	private final Object send_lock = new Object();
	//応答の受け渡し用。受信スレッドが書き、send_commandが読む。
	private final Object response_lock = new Object();
	private int response_status = -1;
	private boolean response_ready;
	private boolean stream_closed;

	public HdmiCec() {
		this("./rtv-cec");
	}

	public HdmiCec(String... command_line) {
		this.command_line = command_line;
	}

	//プロセスが存在することを確認し、無ければ起動する。
	//受信スレッドもここで始める。まだコマンドは送らない。
	public void init() throws IOException {
		if (this.process != null && this.process.isAlive()) {
			return;
		}
		ProcessBuilder builder = new ProcessBuilder(this.command_line);
		//cecのログ（標準エラー）はそのままこちらへ流す
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		this.process = builder.start();
		this.out = this.process.getOutputStream();
		this.in = this.process.getInputStream();
		this.stream_closed = false;
		this.reader_thread = new Thread(new Runnable() {
			@Override
			public void run() {
				HdmiCec.this.read_loop();
			}
		});
		this.reader_thread.setDaemon(true);
		this.reader_thread.start();
	}

	//テレビの入力一覧などに表示されるOSD名を設定する。start()より前に呼ぶこと。
	//CECの仕様で、印刷可能なASCII文字で14文字まで。
	public void set_osd_name(String name) {
		if (name == null || name.isEmpty() || name.length() > 14) {
			throw new IllegalArgumentException("OSD name must be 1-14 characters");
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 0x20 || c > 0x7E) {
				throw new IllegalArgumentException("OSD name must be printable ASCII");
			}
		}
		this.osd_name = name;
	}

	//初期化コマンドを送る。ここでアダプタが開かれる。
	//OSD名が設定されていれば、引数1として一緒に送る。
	public void start() throws IOException {
		int status;
		if (this.osd_name != null) {
			status = this.send_command(COMMAND_INIT, this.osd_name.getBytes(StandardCharsets.US_ASCII));
		} else {
			status = this.send_command(COMMAND_INIT);
		}
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec init failed: status=0x" + Integer.toHexString(status));
		}
	}

	//イベント受信時のフックを登録する。
	//イベントが届くたびに受信スレッドから呼ばれるので、重い処理は入れないこと。
	//イベントの内容は get_event() / get_event_key() / get_event_power_status() で読む。
	public void rx_hook(Runnable hook) {
		this.rx_hook = hook;
	}

	//最後に届いたイベントの種類（EVENT_KEY_PRESSなど）。まだ無ければ0。
	public int get_event() {
		return this.event;
	}

	//最後に押されたキーのUIコマンドコード（KEY_SELECTなど）。
	public int get_event_key() {
		return this.event_key;
	}

	//最後に通知された電源状態。
	public int get_event_power_status() {
		return this.event_power_status;
	}

	//テレビの電源を入れて、こちらをアクティブソースにする。
	public void power_on() throws IOException {
		int status = this.send_command(COMMAND_POWER_ON);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec power on failed: status=0x" + Integer.toHexString(status));
		}
	}

	//テレビをスタンバイにする。
	public void standby() throws IOException {
		int status = this.send_command(COMMAND_STANDBY);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec standby failed: status=0x" + Integer.toHexString(status));
		}
	}

	//こちらをアクティブソースにする（入力をこちらへ切り替える）。
	public void active_source() throws IOException {
		int status = this.send_command(COMMAND_ACTIVE_SOURCE);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec active source failed: status=0x" + Integer.toHexString(status));
		}
	}

	//音量を上げる。
	public void volume_up() throws IOException {
		int status = this.send_command(COMMAND_VOLUME_UP);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec volume up failed: status=0x" + Integer.toHexString(status));
		}
	}

	//音量を下げる。
	public void volume_down() throws IOException {
		int status = this.send_command(COMMAND_VOLUME_DOWN);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec volume down failed: status=0x" + Integer.toHexString(status));
		}
	}

	//ミュートを切り替える。
	public void mute() throws IOException {
		int status = this.send_command(COMMAND_MUTE);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec mute failed: status=0x" + Integer.toHexString(status));
		}
	}

	//生のCECフレームを送る。
	//引数1: 宛先の論理アドレス（u8）
	//引数2: オペコード（u8）
	//引数3: オペランド列（最大14バイト。無ければ引数自体を付けない）
	public void send_raw(int destination, int opcode, byte[] operands) throws IOException {
		byte[][] args;
		if (operands == null) {
			args = new byte[][]{
				new byte[]{(byte) destination},
				new byte[]{(byte) opcode},
			};
		} else {
			if (operands.length > 14) {
				throw new IllegalArgumentException("operands too long: " + operands.length);
			}
			args = new byte[][]{
				new byte[]{(byte) destination},
				new byte[]{(byte) opcode},
				operands,
			};
		}
		int status = this.send_command(COMMAND_SEND_RAW, args);
		if (status != STATUS_OK) {
			throw new IOException("hdmi-cec send raw failed: status=0x" + Integer.toHexString(status));
		}
	}

	//受信スレッドの本体。応答バイトとイベントフレームを読み分ける。
	//イベントはコマンドの応答待ちの間にも届くので、ここで捌く。
	private void read_loop() {
		try {
			for (;;) {
				int first = this.in.read();
				if (first < 0) {
					break;
				}
				if (first == STATUS_OK || first == STATUS_ERROR) {
					//応答。待っているsend_commandへ渡す。
					synchronized (this.response_lock) {
						this.response_status = first;
						this.response_ready = true;
						this.response_lock.notifyAll();
					}
					continue;
				}
				//イベントフレーム: [u8 種類][u8 引数の数]([u8 サイズ][データ])の繰り返し
				int argc = this.in.read();
				if (argc < 0) {
					break;
				}
				byte[][] args = new byte[argc][];
				boolean broken = false;
				for (int i = 0; i < argc; i++) {
					int size = this.in.read();
					if (size < 0) {
						broken = true;
						break;
					}
					args[i] = new byte[size];
					int offset = 0;
					while (offset < size) {
						int n = this.in.read(args[i], offset, size - offset);
						if (n < 0) {
							broken = true;
							break;
						}
						offset += n;
					}
					if (broken) {
						break;
					}
				}
				if (broken) {
					break;
				}
				this.event = first;
				if (argc >= 1 && args[0].length >= 1) {
					if (first == EVENT_KEY_PRESS) {
						this.event_key = args[0][0] & 0xFF;
					} else if (first == EVENT_POWER_STATUS) {
						this.event_power_status = args[0][0] & 0xFF;
					}
				}
				Runnable hook = this.rx_hook;
				if (hook != null) {
					hook.run();
				}
			}
		} catch (IOException e) {
			//プロセスが終わるとここへ来る。そのまま受信を終える。
		}
		synchronized (this.response_lock) {
			this.stream_closed = true;
			this.response_lock.notifyAll();
		}
	}

	//コマンドを1つ送り、応答のステータスを返す。
	//応答は受信スレッド経由で受け取る（途中にイベントが挟まっても良い）。
	private int send_command(byte command, byte[]... args) throws IOException {
		if (this.process == null || !this.process.isAlive()) {
			throw new IOException("hdmi-cec process is not running");
		}
		synchronized (this.send_lock) {
			synchronized (this.response_lock) {
				this.response_ready = false;
				this.response_status = -1;
			}
			this.out.write(command);
			this.out.write(args.length);
			for (byte[] arg : args) {
				this.out.write(arg.length);
				this.out.write(arg);
			}
			this.out.flush();
			synchronized (this.response_lock) {
				while (!this.response_ready) {
					if (this.stream_closed) {
						throw new IOException("hdmi-cec process closed the connection");
					}
					try {
						this.response_lock.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("interrupted while waiting for hdmi-cec response");
					}
				}
				return this.response_status;
			}
		}
	}
}
