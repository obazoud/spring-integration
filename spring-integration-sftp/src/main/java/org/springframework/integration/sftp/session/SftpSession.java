/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.sftp.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.NestedIOException;
import org.springframework.integration.MessagingException;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

/**
 * Default SFTP {@link Session} implementation. Wraps a JSCH session instance.
 *
 * @author Josh Long
 * @author Mario Gray
 * @author Mark Fisher
 * @author Oleg Zhurakousky
 * @since 2.0
 */
class SftpSession implements Session<LsEntry> {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final com.jcraft.jsch.Session jschSession;

	private volatile ChannelSftp channel;


	public SftpSession(com.jcraft.jsch.Session jschSession) {
		Assert.notNull(jschSession, "jschSession must not be null");
		this.jschSession = jschSession;
	}


	public boolean remove(String path) throws IOException {
		Assert.state(this.channel != null, "session is not connected");
		try {
			this.channel.rm(path);
			return true;
		}
		catch (SftpException e) {
			throw new NestedIOException("Failed to remove file: "+ e);
		}
	}

	public LsEntry[] list(String path) throws IOException {
		Assert.state(this.channel != null, "session is not connected");
		try {
			Vector<?> lsEntries = this.channel.ls(path);
			if (lsEntries != null) {
				LsEntry[] entries = new LsEntry[lsEntries.size()];
				for (int i = 0; i < lsEntries.size(); i++) {
					Object next = lsEntries.get(i);
					Assert.state(next instanceof LsEntry, "expected only LsEntry instances from channel.ls()");
					entries[i] = (LsEntry) next;
				}
				return entries;
			}
		}
		catch (SftpException e) {
			throw new NestedIOException("Failed to list files", e);
		}
		return new LsEntry[0];
	}

	public void read(String source, OutputStream os) throws IOException {
		Assert.state(this.channel != null, "session is not connected");
		try {
			InputStream is = this.channel.get(source);
			FileCopyUtils.copy(is, os);
		}
		catch (SftpException e) {
			throw new NestedIOException("failed to read file", e);
		}
	}

	public void write(InputStream inputStream, String destination) throws IOException {
		Assert.state(this.channel != null, "session is not connected");
		try {
			this.channel.put(inputStream, destination);
		}
		catch (SftpException e) {
			throw new NestedIOException("failed to write file", e);
		}
	}

	public void close() {
		if (this.jschSession.isConnected()) {
			this.jschSession.disconnect();
		}
	}

	public boolean isOpen() {
		return this.jschSession.isConnected();
	}

	public void rename(String pathFrom, String pathTo) throws IOException {
		try {	
			this.channel.rename(pathFrom, pathTo);
		} 
		catch (SftpException sftpex) {
			if (logger.isDebugEnabled()){
				logger.debug("Initial File rename failed, possibly because file already exists. Will attempt to delete file: " 
						+ pathTo + " and execute rename again.");
			}
			try {			
				this.remove(pathTo);
				if (logger.isDebugEnabled()) {
					logger.debug("Delete file: " + pathTo + " succeeded. Will attempt rename again");
				}		
			} 
			catch (IOException ioex) {
				throw new NestedIOException("Failed to delete file " + pathTo, ioex);
			}
			try {
				// attempt to rename again
				this.channel.rename(pathFrom, pathTo);
			} 
			catch (SftpException sftpex2) {
				throw new NestedIOException("failed to rename from " + pathFrom + " to " + pathTo, sftpex2);
			}
		}
		if (logger.isDebugEnabled()) {
			logger.debug("File: " + pathFrom + " was successfully renamed to " + pathTo);
		}
	}

	public void mkdir(String remoteDirectory) throws IOException {
		try {	
			this.mkdirRecursively(remoteDirectory, remoteDirectory);
		}
		catch (SftpException e) {
			throw new NestedIOException("failed to create remote directory '" + remoteDirectory + "'.", e);
		}
	}

	void connect() {
		try {
			if (!this.jschSession.isConnected()) {
				this.jschSession.connect();
				this.channel = (ChannelSftp) this.jschSession.openChannel("sftp");
			}
			if (this.channel != null && !this.channel.isConnected()) {
				this.channel.connect();
			}
		}
		catch (JSchException e) {
			throw new IllegalStateException("failed to connect", e);
		}
	}

	/**
	 * Since the underlying SFTP API does not give us a clean method to create directories recursively,
	 * we need to create them one at the time starting from the path that we know actually exists.
	 * To determine the existing path we need to iterate through each delimited segment starting from
	 * the full directory path moving backward until we find it. Once found we need to start creating
	 * individual directories for each segment; so in this method on the initial call the two parameters
	 * will be the same, but for each recursive call the 'currentPath' is the directory with one less
	 * segment from the previous 'currentPath'. For example, if you had '/foo/bar/baz', in the next
	 * iteration it would be '/foo/bar/', and then just '/foo' and so on.
	 */
	private void mkdirRecursively(String currentPath, String fullPath) throws SftpException {
		String remoteFileSeparator = "/";
		if (this.exists(currentPath)) {
			String missingDirectoryPath = fullPath.substring(currentPath.length());
			String[] directories = StringUtils.tokenizeToStringArray(missingDirectoryPath, remoteFileSeparator);
			String directory = currentPath + remoteFileSeparator;
			for (String directorySegment : directories) {
				directory += directorySegment + remoteFileSeparator;
				if (logger.isDebugEnabled()){
					logger.debug("Creating '" + directory + "'");
				}	
				this.channel.mkdir(directory);
			}
		}
		else {
			if (logger.isDebugEnabled()) {
				logger.debug("Directory '" + currentPath + "' does not exist. Will attempt to auto-create it");
			}		
			int nextSeparatorIndex = currentPath.lastIndexOf(remoteFileSeparator);
			if (nextSeparatorIndex <= 0) {
				throw new MessagingException("Failed to auto-create directory '" + fullPath + "'");
			}
			else {
				currentPath = currentPath.substring(0, nextSeparatorIndex);
				this.mkdirRecursively(currentPath, fullPath);
			}
		}
	}

	private boolean exists(String path) {
		try {
			this.channel.lstat(path);
			return true;
		}
		catch (SftpException e) {
			// ignore
		}
		return false;
	}

}
