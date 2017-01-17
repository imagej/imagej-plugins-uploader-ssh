/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2017 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.plugins.uploaders.ssh;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import net.imagej.updater.AbstractUploader;
import net.imagej.updater.FilesUploader;
import net.imagej.updater.Uploadable;
import net.imagej.updater.Uploader;
import net.imagej.updater.util.InputStream2OutputStream;
import net.imagej.updater.util.UpdateCanceledException;
import net.imagej.updater.util.UpdaterUserInterface;

import org.scijava.log.LogService;
import org.scijava.log.StderrLogService;
import org.scijava.plugin.Plugin;

/**
 * Uploads files to an update server using SSH. In addition to writing files, it
 * uses 'mv' and permissions to provide safe locking.
 * 
 * @author Johannes Schindelin
 * @author Yap Chin Kiet
 */
@Plugin(type = Uploader.class)
public class SSHFileUploader extends AbstractUploader {

	private Session session;
	private Channel channel;
	private OutputStream out;
	protected OutputStream err;
	private InputStream in;
	private LogService log;

	public SSHFileUploader() {
		err = UpdaterUserInterface.get().getOutputStream();
	}

	@Override
	public boolean login(final FilesUploader uploader) {
		if (!super.login(uploader)) return false;
		log = uploader.getLog();
		session = SSHSessionCreator.getSession(uploader);
		return session != null;
	}

	protected boolean debugLogin(final String host) {
		if (log == null) {
			log = new StderrLogService();
			log.setLevel(LogService.DEBUG);
		}

		try {
			session = SSHSessionCreator.debugConnect(host, log);
		} catch (final JSchException e) {
			log.error(e);
			return false;
		}
		return true;
	}

	@Override
	public void logout() {
		try {
			disconnectSession();
		} catch (IOException e) {
			log.error(e);
		}
	}

	// Steps to accomplish entire upload task
	@Override
	public synchronized void upload(final List<Uploadable> sources,
		final List<String> locks) throws IOException
	{

		setCommand("date -u +%Y%m%d%H%M%S");
		timestamp = readNumber(in);
		setTitle("Uploading");

		final String uploadFilesCommand = "scp -p -t -r " + uploadDir;
		setCommand(uploadFilesCommand);
		if (checkAck(in) != 0) {
			throw new IOException("Failed to set command " + uploadFilesCommand);
		}

		try {
			uploadFiles(sources);
		}
		catch (final UpdateCanceledException cancel) {
			for (final String lock : locks)
				setCommand("rm " + uploadDir + lock + ".lock");
			out.close();
			channel.disconnect();
			throw cancel;
		}

		// Unlock process
		for (final String lock : locks)
			setCommand("mv -f " + uploadDir + lock + ".lock " + uploadDir + lock);

		out.close();
		disconnectSession();
	}

	private void uploadFiles(final List<Uploadable> sources) throws IOException {
		calculateTotalSize(sources);
		int count = 0;

		String prefix = "";
		final byte[] buf = new byte[16384];
		for (final Uploadable source : sources) {
			final String target = source.getFilename();
			while (!target.startsWith(prefix))
				prefix = cdUp(prefix);

			// maybe need to enter directory
			final int slash = target.lastIndexOf('/');
			final String directory = target.substring(0, slash + 1);
			cdInto(directory.substring(prefix.length()));
			prefix = directory;

			// notification that file is about to be written
			final String command =
				source.getPermissions() + " " + source.getFilesize() + " " +
					target.substring(slash + 1) + "\n";
			out.write(command.getBytes());
			out.flush();
			checkAckUploadError(target);

			/*
			 * Make sure that the file is there; this is critical
			 * to get the server timestamp from db.xml.gz.lock.
			 */
			addItem(source);

			// send contents of file
			final InputStream input = source.getInputStream();
			int currentCount = 0;
			final int currentTotal = (int) source.getFilesize();
			for (;;) {
				final int len = input.read(buf, 0, buf.length);
				if (len <= 0) break;
				out.write(buf, 0, len);
				currentCount += len;
				setItemCount(currentCount, currentTotal);
				setCount(count + currentCount, total);
			}
			input.close();
			count += currentCount;

			// send '\0'
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();
			checkAckUploadError(target);
			itemDone(source);
		}

		while (!prefix.equals("")) {
			prefix = cdUp(prefix);
		}

		done();
	}

	private String cdUp(final String directory) throws IOException {
		out.write("E\n".getBytes());
		out.flush();
		checkAckUploadError(directory);
		final int slash = directory.lastIndexOf('/', directory.length() - 2);
		return directory.substring(0, slash + 1);
	}

	private void cdInto(String directory) throws IOException {
		while (!directory.equals("")) {
			final int slash = directory.indexOf('/');
			final String name =
				(slash < 0 ? directory : directory.substring(0, slash));
			final String command = "D2775 0 " + name + "\n";
			out.write(command.getBytes());
			out.flush();
			if (checkAck(in) != 0) throw new IOException("Cannot enter directory " +
				name);
			if (slash < 0) return;
			directory = directory.substring(slash + 1);
		}
	}

	protected void setCommand(final String command) throws IOException {
		if (out != null) {
			out.close();
			channel.disconnect();
		}
		try {
			UpdaterUserInterface.get().debug("launching command " + command);
			channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(command);
			channel.setInputStream(null);
			((ChannelExec) channel).setErrStream(err);

			// get I/O streams for remote scp
			out = channel.getOutputStream();
			in = channel.getInputStream();
			channel.connect();
		}
		catch (final JSchException e) {
			log.error(e);
			throw new IOException(e.getMessage());
		}
	}

	private void checkAckUploadError(final String target) throws IOException {
		if (checkAck(in) != 0) throw new IOException("Failed to upload " + target);
	}

	public void disconnectSession() throws IOException {
		if (in != null)
			new InputStream2OutputStream(in, UpdaterUserInterface.get().getOutputStream());
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {
			/* ignore */
		}
		if (out != null)
			out.close();
		try {
			Thread.sleep(1000);
		}
		catch (final InterruptedException e) {
			/* ignore */
		}
		int exitStatus = 0;
		if (channel != null) {
			exitStatus = channel.getExitStatus();
			UpdaterUserInterface.get().debug(
				"disconnect session; exit status is " + exitStatus);
			channel.disconnect();
		}
		if (session != null)
			session.disconnect();
		if (err != null)
			err.close();
		if (exitStatus != 0) throw new IOException("Command failed with status " +
			exitStatus + " (see Log)!");
	}

	protected long readNumber(final InputStream in) throws IOException {
		long result = 0;
		for (;;) {
			final int b = in.read();
			if (b >= '0' && b <= '9') result = 10 * result + b - '0';
			else if (b == '\n') return result;
		}
	}

	private int checkAck(final InputStream in) throws IOException {
		final int b = in.read();
		// b may be 0 for success,
		// 1 for error,
		// 2 for fatal error,
		// -1
		if (b == 0) return b;
		UpdaterUserInterface.get().handleException(new Exception("checkAck returns " + b));
		if (b == -1) return b;

		if (b == 1 || b == 2) {
			final StringBuffer sb = new StringBuffer();
			int c;
			do {
				c = in.read();
				sb.append((char) c);
			}
			while (c != '\n');
			UpdaterUserInterface.get().log("checkAck returned '" + sb.toString() + "'");
			UpdaterUserInterface.get().error(sb.toString());
		}
		return b;
	}

	@Override
	public String getProtocol() {
		return "ssh";
	}

}
