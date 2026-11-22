//HDMI-CECコンポーネント。libcecの上に乗り、coreとは標準入出力で
//共通のバイナリプロトコルを話す。再生機器として振る舞い、
//OSD名は "RaspberryPi TV"（CECの上限14文字ちょうど）。
//
//コマンド（core -> cec）:
//  0x00 初期化（アダプタを開き、論理アドレスを取る）
//       引数1: OSD名（任意。印刷可能なASCIIで14バイトまで。
//       省略時は "RaspberryPi TV"）
//  0x01 テレビの電源を入れてアクティブソースになる
//  0x02 テレビをスタンバイにする
//  0x03 アクティブソースになる
//  0x04 音量を上げる
//  0x05 音量を下げる
//  0x06 ミュート切り替え
//  0x07 生フレーム送信（引数: 宛先(u8)、オペコード(u8)、オペランド列）
//イベント（cec -> core）:
//  応答の1バイト（0x20/0xFF）と区別するため、先頭バイトは0x80台にする。
//  0x81 キーが押された（引数: UIコマンドコード(u8)）
//  0x82 キーが離された（引数なし）
//  0x83 電源状態の通知（引数: 状態(u8)。0=オン、1=スタンバイ、
//       2=オンへ移行中、3=スタンバイへ移行中）
//
//標準出力はプロトコル専用。ログは標準エラーへ出す。
//標準入力がEOFになったら（coreが死んだら）片付けて終了する。
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <objc/objc.h>
#include <objc/runtime.h>

#include <libcec/cec.h>

#define STATUS_OK 0x20
#define STATUS_ERROR 0xFF

#define COMMAND_INIT 0x00
#define COMMAND_POWER_ON 0x01
#define COMMAND_STANDBY 0x02
#define COMMAND_ACTIVE_SOURCE 0x03
#define COMMAND_VOLUME_UP 0x04
#define COMMAND_VOLUME_DOWN 0x05
#define COMMAND_MUTE 0x06
#define COMMAND_SEND_RAW 0x07

#define EVENT_KEY_PRESS 0x81
#define EVENT_KEY_RELEASE 0x82
#define EVENT_POWER_STATUS 0x83

struct proto_arg {
	const uint8_t *data;
	uint8_t size;
};

@class CecClient;

static void on_log_message(void *param, const CEC::cec_log_message *message) {
	(void)param;
	if (message->level <= CEC::CEC_LOG_WARNING) {
		fprintf(stderr, "rtv-cec: libcec: %s\n", message->message);
	}
}

static void on_command_received(void *param, const CEC::cec_command *command);

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wobjc-root-class"
@interface CecClient {
	//ルートクラスではisaを先頭に明示する（無いとivarの配置がずれて壊れる）
	Class isa;
	CEC::ICECAdapter *adapter;
	CEC::libcec_configuration *config;
	CEC::ICECCallbacks callbacks;
	pthread_mutex_t write_mutex;
}
+ (id)alloc;
- (id)init;
- (void)write_bytes:(const uint8_t *)bytes length:(size_t)length;
- (void)send_status:(uint8_t)status;
- (void)send_event:(uint8_t)event arg:(const uint8_t *)arg has_arg:(BOOL)has_arg;
- (BOOL)initialize_adapter:(const char *)osd_name;
- (void)shutdown_adapter;
- (void)on_command:(const CEC::cec_command *)command;
- (uint8_t)handle_command:(uint8_t)command args:(struct proto_arg *)args count:(int)count;
@end
#pragma clang diagnostic pop

@implementation CecClient

+ (id)alloc {
	return class_createInstance(self, 0);
}

- (id)init {
	pthread_mutex_init(&write_mutex, NULL);
	return self;
}

- (void)write_bytes:(const uint8_t *)bytes length:(size_t)length {
	pthread_mutex_lock(&write_mutex);
	size_t written = 0;
	while (written < length) {
		ssize_t n = write(STDOUT_FILENO, bytes + written, length - written);
		if (n < 0) {
			if (errno == EINTR) {
				continue;
			}
			break;
		}
		written += (size_t)n;
	}
	pthread_mutex_unlock(&write_mutex);
}

- (void)send_status:(uint8_t)status {
	[self write_bytes:&status length:1];
}

- (void)send_event:(uint8_t)event arg:(const uint8_t *)arg has_arg:(BOOL)has_arg {
	uint8_t frame[4];
	frame[0] = event;
	if (has_arg) {
		frame[1] = 1;
		frame[2] = 1;
		frame[3] = *arg;
		[self write_bytes:frame length:4];
	} else {
		frame[1] = 0;
		[self write_bytes:frame length:2];
	}
}

- (BOOL)initialize_adapter:(const char *)osd_name {
	if (adapter != NULL) {
		return YES;
	}
	config = new CEC::libcec_configuration();
	snprintf(config->strDeviceName, LIBCEC_OSD_NAME_SIZE, "%s", osd_name);
	config->deviceTypes.Add(CEC::CEC_DEVICE_TYPE_PLAYBACK_DEVICE);
	config->bActivateSource = 0;
	memset(&callbacks, 0, sizeof(callbacks));
	callbacks.logMessage = on_log_message;
	callbacks.commandReceived = on_command_received;
	config->callbacks = &callbacks;
	config->callbackParam = (void *)self;
	adapter = CECInitialise(config);
	if (adapter == NULL) {
		fprintf(stderr, "rtv-cec: CECInitialise failed\n");
		delete config;
		config = NULL;
		return NO;
	}
	CEC::cec_adapter_descriptor devices[4];
	int8_t found = adapter->DetectAdapters(devices, 4, NULL, false);
	if (found < 1) {
		fprintf(stderr, "rtv-cec: no CEC adapter found\n");
		CECDestroy(adapter);
		adapter = NULL;
		delete config;
		config = NULL;
		return NO;
	}
	fprintf(stderr, "rtv-cec: adapter: %s (%s)\n", devices[0].strComName, devices[0].strComPath);
	if (!adapter->Open(devices[0].strComName)) {
		fprintf(stderr, "rtv-cec: failed to open the adapter\n");
		CECDestroy(adapter);
		adapter = NULL;
		delete config;
		config = NULL;
		return NO;
	}
	CEC::cec_logical_addresses addresses = adapter->GetLogicalAddresses();
	fprintf(stderr, "rtv-cec: initialized (OSD name: %s, logical address: %d)\n", osd_name, addresses.primary);
	return YES;
}

- (void)shutdown_adapter {
	if (adapter != NULL) {
		adapter->Close();
		CECDestroy(adapter);
		adapter = NULL;
	}
	if (config != NULL) {
		delete config;
		config = NULL;
	}
}

//テレビから届いたコマンドのうち、coreへ伝えるべきものをイベントにする。
- (void)on_command:(const CEC::cec_command *)command {
	if (command->opcode == CEC::CEC_OPCODE_USER_CONTROL_PRESSED && command->parameters.size >= 1) {
		uint8_t key = command->parameters.data[0];
		[self send_event:EVENT_KEY_PRESS arg:&key has_arg:YES];
	} else if (command->opcode == CEC::CEC_OPCODE_USER_CONTROL_RELEASE) {
		[self send_event:EVENT_KEY_RELEASE arg:NULL has_arg:NO];
	} else if (command->opcode == CEC::CEC_OPCODE_REPORT_POWER_STATUS && command->parameters.size >= 1) {
		uint8_t status = command->parameters.data[0];
		[self send_event:EVENT_POWER_STATUS arg:&status has_arg:YES];
	}
}

- (uint8_t)handle_command:(uint8_t)command args:(struct proto_arg *)args count:(int)count {
	if (command == COMMAND_INIT) {
		//引数1があればそれをOSD名にする（CECの仕様: 印刷可能なASCII、14バイトまで）
		char osd_name[LIBCEC_OSD_NAME_SIZE];
		snprintf(osd_name, sizeof(osd_name), "RaspberryPi TV");
		if (count >= 1) {
			if (args[0].size == 0 || args[0].size > 14) {
				fprintf(stderr, "rtv-cec: invalid OSD name length: %u\n", args[0].size);
				return STATUS_ERROR;
			}
			for (int i = 0; i < args[0].size; i++) {
				if (args[0].data[i] < 0x20 || args[0].data[i] > 0x7E) {
					fprintf(stderr, "rtv-cec: OSD name must be printable ASCII\n");
					return STATUS_ERROR;
				}
			}
			memcpy(osd_name, args[0].data, args[0].size);
			osd_name[args[0].size] = '\0';
		}
		return [self initialize_adapter:osd_name] ? STATUS_OK : STATUS_ERROR;
	}
	if (adapter == NULL) {
		return STATUS_ERROR;
	}
	switch (command) {
	case COMMAND_POWER_ON: {
		bool ok = adapter->PowerOnDevices(CEC::CECDEVICE_TV);
		adapter->SetActiveSource(CEC::CEC_DEVICE_TYPE_PLAYBACK_DEVICE);
		return ok ? STATUS_OK : STATUS_ERROR;
	}
	case COMMAND_STANDBY:
		return adapter->StandbyDevices(CEC::CECDEVICE_TV) ? STATUS_OK : STATUS_ERROR;
	case COMMAND_ACTIVE_SOURCE:
		return adapter->SetActiveSource(CEC::CEC_DEVICE_TYPE_PLAYBACK_DEVICE) ? STATUS_OK : STATUS_ERROR;
	case COMMAND_VOLUME_UP:
		//音量操作は応答で成否が分からないので、投げっぱなしにする
		adapter->VolumeUp();
		return STATUS_OK;
	case COMMAND_VOLUME_DOWN:
		adapter->VolumeDown();
		return STATUS_OK;
	case COMMAND_MUTE:
		adapter->MuteAudio();
		return STATUS_OK;
	case COMMAND_SEND_RAW: {
		if (count < 2 || args[0].size != 1 || args[1].size != 1) {
			return STATUS_ERROR;
		}
		const uint8_t *operands = NULL;
		uint8_t operand_count = 0;
		if (count >= 3) {
			operands = args[2].data;
			operand_count = args[2].size;
			if (operand_count > 14) {
				return STATUS_ERROR;
			}
		}
		CEC::cec_command frame;
		CEC::cec_logical_addresses addresses = adapter->GetLogicalAddresses();
		CEC::cec_command::Format(frame, addresses.primary,
			(CEC::cec_logical_address)args[0].data[0], (CEC::cec_opcode)args[1].data[0]);
		for (int i = 0; i < operand_count; i++) {
			frame.parameters.PushBack(operands[i]);
		}
		return adapter->Transmit(frame) ? STATUS_OK : STATUS_ERROR;
	}
	default:
		return STATUS_ERROR;
	}
}

@end

static void on_command_received(void *param, const CEC::cec_command *command) {
	[(CecClient *)param on_command:command];
}

int main(int argc, char *argv[]) {
	(void)argc;
	(void)argv;
	signal(SIGPIPE, SIG_IGN);
	CecClient *client = [[CecClient alloc] init];
	fprintf(stderr, "rtv-cec: waiting for commands on stdin\n");
	static uint8_t buffer[131072];
	size_t buffered = 0;
	for (;;) {
		ssize_t n = read(STDIN_FILENO, buffer + buffered, sizeof(buffer) - buffered);
		if (n == 0) {
			break;
		}
		if (n < 0) {
			if (errno == EINTR) {
				continue;
			}
			break;
		}
		buffered += (size_t)n;
		size_t offset = 0;
		while (buffered - offset >= 2) {
			uint8_t command = buffer[offset];
			uint8_t arg_count = buffer[offset + 1];
			struct proto_arg args[255];
			size_t pos = offset + 2;
			int complete = 1;
			for (int i = 0; i < arg_count; i++) {
				if (pos >= buffered) {
					complete = 0;
					break;
				}
				uint8_t size = buffer[pos];
				if (pos + 1 + size > buffered) {
					complete = 0;
					break;
				}
				args[i].data = buffer + pos + 1;
				args[i].size = size;
				pos += 1 + size;
			}
			if (!complete) {
				break;
			}
			uint8_t status = [client handle_command:command args:args count:arg_count];
			[client send_status:status];
			offset = pos;
		}
		if (offset > 0) {
			memmove(buffer, buffer + offset, buffered - offset);
			buffered -= offset;
		}
		if (buffered == sizeof(buffer)) {
			fprintf(stderr, "rtv-cec: protocol buffer overflow\n");
			[client shutdown_adapter];
			return 1;
		}
	}
	[client shutdown_adapter];
	return 0;
}
