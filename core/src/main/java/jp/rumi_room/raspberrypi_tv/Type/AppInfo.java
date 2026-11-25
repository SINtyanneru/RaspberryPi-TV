package jp.rumi_room.raspberrypi_tv.Type;

public record AppInfo(
	String id,
	String name,
	String working_dir,
	String[] start_cmd
){}
