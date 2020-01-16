package jang.common;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;

public class ThreadForFile extends Thread {
	
	private ClientInfo client;
	
	private LinkedBlockingQueue<ByteBuffer> lbq;
	private ByteBuffer msg;
	
	private String fileName;
	private String dstFileName;
	private Path dstPath;
	private FileChannel dstFileChannel;
	
	private Charset charset;
	
	public ThreadForFile(ClientInfo client) {
		this.client = client;
		lbq = client.getFileContents();
		charset = Charset.forName("UTF-8");
	}
	@Override
	public void run() {
		int result = 0;
		while (true) {
			try {
				msg = lbq.take();
			} catch (InterruptedException e) {
				// TODO message..
				e.printStackTrace();
				break;
			}
			byte msgtype = msg.get(ChatProtocol.HEADER_MSGTYPE_OFFSET);
			try {
				if (msgtype == ChatProtocol.MSGTYPE_REQUEST_DOWNLOAD) {
					/* read file and transfer */
					result = readFileAndTransfer(msg);
				}
				else {
					/* save file: server/client */
					result = saveMsgToFile(msg);
				}
			} catch (Exception e) {
				e.printStackTrace();
				break;
			}
			if (result != 0) {
				break;
			}
		} /* end while */

		/* close fileChannel */
		if (dstFileChannel != null && dstFileChannel.isOpen()) {
			try {
				dstFileChannel.close();
			} catch (IOException e) {
				e.printStackTrace();
			} 
		}
	}
	
	/** read file and write to Socket */
	private int saveMsgToFile(ByteBuffer msg) throws Exception {
		int endtype = msg.get(ChatProtocol.HEADER_ENDTYPE_OFFSET);
		if (endtype == ChatProtocol.ENDTYPE_START) {
			/* 1.open FileChannel */
			fileName = client.getUploadedFileName();
			if (client.getCreated() == ClientInfo.CREATED_BY_CLIENT) {
				dstFileName = client.getDownloadDir() + fileName;
			}
			else {
				dstFileName = client.getUploadDir() + fileName;
			}
			dstPath = Paths.get(dstFileName);
			dstFileChannel = FileChannel.open(dstPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		}
		else {
			/* 2.write to FileChannel*/
			msg.position(ChatProtocol.HEADER_LEN);
			dstFileChannel.write(msg);
			
			if (endtype == ChatProtocol.ENDTYPE_LAST) {
				String s = Message.SYS_MSG_010 + "[ " + fileName + " ]";
				Message.myLog(s);
				
				if (client.getCreated() == ClientInfo.CREATED_BY_SERVER) {
					ByteBuffer encodedMsg = charset.encode(s);
					int size = encodedMsg.remaining();
					
					/* make register message */
					ByteBuffer newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_UPLOAD, size);
					newMsg.put(encodedMsg);
					newMsg.flip();
					client.writeToSocket(newMsg);
				}
				return 1;
			}
			else if (endtype == ChatProtocol.ENDTYPE_ERROR) {
				Message.myLog(Message.SYS_MSG_011 + "error" + fileName);
				return 1;
			}
		}
		return 0; /* incomplete */
	}
	
	private int readFileAndTransfer(ByteBuffer msg) throws Exception {
		msg.position(ChatProtocol.HEADER_LEN);
		String fileName = charset.decode(msg).toString();
		String filePath = client.getUploadDir() + fileName;
		/* 1) check if file exists */
		long fileSize = ThreadForFile.checkIfFileExists(filePath);
		if (fileSize < 0) {
			/* 1-1) write chat(error) message */
			ByteBuffer encodedMsg = charset.encode(fileName + " is not found.");
			int size = encodedMsg.remaining();
			ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_DOWNLOAD, size);
			ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_ERROR);
			bufMsg.put(encodedMsg);
			bufMsg.flip();
			client.writeToSocket(bufMsg);
			return 1;
		}
		
		/* 2) write chat(start) message */
		ByteBuffer encodedMsg = charset.encode(fileName);
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_DOWNLOAD, size);
		ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_START);
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		client.writeToSocket(bufMsg);
		
		/* file read and write */
		ThreadForFile.transferFileToSocket(filePath, client);
		return 1;
	}
	
	/** read file and write to Socket */
	public static void transferFileToSocket(String srcFileName, ClientInfo remote) throws Exception {
		Path srcPath = Paths.get(srcFileName);
		FileChannel srcFileChannel = null;
		long fileSize = 0L;
		int size = ChatProtocol.DFLT_TRANSFER_SIZE;
		ByteBuffer bufMsg = null;
		try {
			srcFileChannel = FileChannel.open(srcPath, StandardOpenOption.READ);
			fileSize = srcFileChannel.size();
			if (fileSize > ChatProtocol.MAX_TRANSFER_SIZE) {
				Message.myLog(Message.SYS_MSG_012 + " - " + fileSize);
				return;
			}
			/* default transfer size */
			if (size > fileSize) {
				/* min transfer size */
				size = (int)fileSize;
			}
		} catch (Exception e) {
			e.printStackTrace();
			Message.myLog(Message.ERR_MSG_029 + srcFileName);
		}

		int byteCount = 0;
		do {
			
			if (bufMsg == null || bufMsg.remaining() != 0) {
				/* create new buffer */
				bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_TRANSFER_FILE, size);
			}
			
			try {
				/* read File */
				bufMsg.clear();
				bufMsg.position(ChatProtocol.HEADER_LEN);
				byteCount = srcFileChannel.read(bufMsg);
				if (byteCount == -1) {
					/* EOF */
					ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_LAST);
					ChatMessage.setPayloadToHeader(bufMsg, 0);
				}
				else {
					ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_CONT);
					ChatMessage.setPayloadToHeader(bufMsg, byteCount);
				}
			} catch (Exception e) {
				Message.myLog(Message.ERR_MSG_030 + srcFileName);

				/* write to server: error */
				ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_ERROR);
				ChatMessage.setPayloadToHeader(bufMsg, 0);
				bufMsg.flip();
				remote.writeToSocket(bufMsg);
			}
			
			try {
				/* write to server: success */
				bufMsg.flip();
				remote.writeToSocket(bufMsg);
			} catch (Exception e) {
				Message.myLog(Message.ERR_MSG_031 + srcFileName);
			}
				
		} while (byteCount > 0);
		
		try {
			srcFileChannel.close();
		} catch (IOException e1) {
			e1.printStackTrace();
			Message.myLog(Message.ERR_MSG_032  + srcFileName);
		}
		
	}
	
	/** check if file exists */
	public static long checkIfFileExists(String msg) {
		if (msg == null || msg.length() < 0) {
			return -1;
		}
		
		File file = new File(msg);
		if (file.exists() && file.isFile()) {
			/* file exists */
			return file.length();
		}
		
		Message.myLog(Message.ERR_MSG_034  + " input: " + msg);
		
		return -1;
	}
}
