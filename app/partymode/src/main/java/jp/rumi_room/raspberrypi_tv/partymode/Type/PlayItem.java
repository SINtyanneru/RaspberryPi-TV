package jp.rumi_room.raspberrypi_tv.partymode.Type;

import java.awt.image.BufferedImage;
import java.net.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

public class PlayItem {
	private UUID id;
	private LocalDateTime add_at;
	private InetAddress adder;
	private URI url;
	private Path file_path;
	private PlayItemType type;

	private String title;
	private String author;
	private BufferedImage thumbnail;

	private boolean downloaded;
	private boolean error;
	private int download_progress;

	private boolean played;

	public PlayItem(UUID id, LocalDateTime add_at, InetAddress adder, URI url, Path file_path, PlayItemType type, String title, String author, BufferedImage thumbnail, boolean downloaded, boolean error, int download_progress) {
		this.id = id;
		this.add_at = add_at;
		this.adder = adder;
		this.url = url;
		this.file_path = file_path;
		this.title = title;
		this.author = author;
		this.thumbnail = thumbnail;
		this.downloaded = downloaded;
		this.error = error;
		this.download_progress = download_progress;
		this.played = false;
	}

	public UUID get_id() {
		return id;
	}

	public LocalDateTime get_add_at() {
		return add_at;
	}

	public InetAddress get_adder() {
		return adder;
	}

	public URI get_url() {
		return url;
	}

	public Path get_file_path() {
		return file_path;
	}

	public PlayItemType get_type() {
		return type;
	}

	public String get_title() {
		return title;
	}

	public String get_author() {
		return author;
	}

	public BufferedImage get_thumbnail() {
		return thumbnail;
	}

	public boolean get_downloaded() {
		return downloaded;
	}

	public void set_downloaded(boolean downloaded) {
		this.downloaded = downloaded;
	}

	public boolean get_error() {
		return error;
	}

	public boolean is_played() {
		return played;
	}

	public void set_error(boolean error) {
		this.error = error;
	}

	public int get_download_progress() {
		return download_progress;
	}

	public void set_download_progress(int download_progress) {
		this.download_progress = download_progress;
	}
}