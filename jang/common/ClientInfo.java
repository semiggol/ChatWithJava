package jang.common;

import jang.common.ChatMessage;
import jang.common.Message;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public class ClientInfo {
	public static final int CLIENT_NOTREADY     =  0;
	public static final int CLIENT_CONNECTING   =  1;
	public static final int CLIENT_CONNECTED    =  2;
	public static final int CLIENT_READY        =  3;
	public static final int CLIENT_CLOSED       =  4;
	/* status */
	private int status= CLIENT_NOTREADY;
	
	public static final int READ_MSG_ERROR      = -1;
	public static final int READ_MSG_INCOMPLETE =  0;
	public static final int READ_MSG_COMPLETE   =  1;
	
	/* Client 구분자: key, channel, ip, id */
	private SelectionKey selectionKey;
	private SocketChannel socketChannel;
	private String ip;
	private String id;
	private boolean admin;
	private boolean onUploading;
	private boolean onDownloading;
	
	public static final byte CREATED_BY_SERVER  = 1;
	public static final byte CREATED_BY_CLIENT  = 2;
	private byte created = CREATED_BY_SERVER;
	
	/* socket read/write시 사용될 buffer(message) */
	private ChatMessage readData;
	private List<ByteBuffer> writeData;
	
	/* for uploaded file */
	private LinkedBlockingQueue<ByteBuffer> fileContents;
	private String uploadedFileName;
	private int uploadedCount;
	private ThreadForFile worker;
	
	public SocketChannel getSocketChannel() {
		return socketChannel;
	}
	public void setSocketChannel(SocketChannel socketChannel) {
		this.socketChannel = socketChannel;
	}
	public SelectionKey getsKey() {
		return selectionKey;
	}
	public void setSelectionKey(SelectionKey selectionKey) {
		this.selectionKey = selectionKey;
	}
	public String getIp() {
		return ip;
	}
	public void setIp(String ip) {
		this.ip = ip;
	}
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public int getStatus() {
		return status;
	}
	public void setStatus(int status) {
		this.status = status;
	}
	public void initReadData() {
		this.readData = null;
	}
	public void initWriteData() {
		this.writeData = null;
	}

	/** closesocket */
	public void close() {
		try {
			if (socketChannel.isOpen()) {
				socketChannel.close();
			}
		} catch (IOException e) {
			Message.myLog(Message.ERR_MSG_010 + " " + this.socketChannel.toString());
		}
	}
	
	/** get readData */
	public ChatMessage getReadData() {
		if (readData == null) {
			readData = new ChatMessage();
		}
		return readData;
	}
	
	/** send writeData to remote socket(client/server) */
	public synchronized void writeToSocket(ByteBuffer newMsg) {
		int op = selectionKey.interestOps();
		try {
			if ((SelectionKey.OP_WRITE & op) == 0) {
				/* write event is not set, try to send newMsg */
				socketChannel.write(newMsg);
				if (newMsg.remaining() == 0) { /* same: newMsg.position() == newMsg.limit() */
					/* msg sent */
					return;
				}
				else {
					/* copy newMsg */
					ByteBuffer copyMsg = copyNewMsg(newMsg);
					writeData.add(copyMsg);

					/* write event */
					selectionKey.interestOps(op | SelectionKey.OP_WRITE);
					return;
				}
			}
			else if (newMsg == null) {
				/* writable */
				Iterator<ByteBuffer> iterator = writeData.iterator();
				while (iterator.hasNext()) {
					ByteBuffer msg = (ByteBuffer)iterator.next();
					socketChannel.write(msg);
					if (msg.remaining() == 0) {
						/* msg sent */
						iterator.remove();
					}
					else {
						/* write event */
						selectionKey.interestOps(op | SelectionKey.OP_WRITE);
						break;
					}
				}
			}
			else {
				/* write event is set, and newMsg arrived */
				ByteBuffer copyMsg = copyNewMsg(newMsg);
				writeData.add(copyMsg);
			}
		} catch (IOException ie) {
			Message.myLog(Message.ERR_MSG_021 + " " + socketChannel.toString());
		} catch (Exception e) {
			Message.myLog(Message.ERR_MSG_022 + " " + socketChannel.toString());
		}
		
		if (writeData == null) {
			writeData = new LinkedList<ByteBuffer>();
		}
		if (writeData.isEmpty()) {
			/* clear write event */
			selectionKey.interestOps(op & ~SelectionKey.OP_WRITE);
		}
	}
	
	/** copy ByteBuffer */
	private ByteBuffer copyNewMsg(ByteBuffer msg) {
		int len = msg.limit() - msg.position();
		ByteBuffer newMsg = ByteBuffer.allocateDirect(len);
		System.arraycopy(msg, msg.position(), newMsg, 0, len);
		return newMsg;
	}
	
	/** read from remote socket(client/server) */
	public int readFromSocket() {
		ChatMessage readMsg = getReadData();
		
		ByteBuffer readBuffer;
		int readLen = 0;
		
		try {
			if (readMsg.getStatus() == ChatMessage.READ_HEADER_INCOMPLETE) {
				/* read ChatMessage's Header */
				readBuffer = readMsg.getHeaderBuffer();
				readLen = socketChannel.read(readBuffer);
				if (readLen < 0) {
					/* client send 'close' message */
					return -1;
				}
				else if (readLen == 0) {
					/* TCP.recvBuffer is empty */
					return 0;
				}
				else {
					if (readMsg.parseProtocol() == ChatMessage.PARSE_RESULT_FAIL) {
						return -1;
					}
					/* Now, ChatMessage's Header is complete. */
				}
			}
			
			if (readMsg.getStatus() == ChatMessage.READ_PAYLOAD_INCOMPLETE) {
				/* read ChatMessage's body */
				readBuffer = readMsg.getMessageBuffer();
				readLen = socketChannel.read(readBuffer);
				if (readLen < 0) {
					/* client send 'close' message */
					return -1;
				}
				else if (readLen == 0) {
					/* TCP.recvBuffer is empty */
					return 0;
				}
				else {
					/* Now, check if ChatMessage's body is complete. */
					readMsg.parseProtocol();
				}
			}
		} catch (IOException e) {
			Message.myLog(Message.ERR_MSG_012 + " - " + socketChannel.toString());
			return -1;
		}
		
		if (readMsg != null &&
				readMsg.getStatus() == ChatMessage.READ_PAYLOAD_COMPLETE) {
			return 1;
		}
		return 0;
	}
	public boolean isAdmin() {
		return admin;
	}
	public void setAdmin(boolean admin) {
		this.admin = admin;
	}
	
	/* for file upload */
	public LinkedBlockingQueue<ByteBuffer> getFileContents() {
		return fileContents;
	}
	public void setFileContents(LinkedBlockingQueue<ByteBuffer> fileContents) {
		this.fileContents = fileContents;
	}
	public String getUploadedFileName() {
		return uploadedFileName;
	}
	public void setUploadedFileName(String uploadedFileName) {
		this.uploadedFileName = uploadedFileName;
	}
	public ThreadForFile getWorker() {
		return worker;
	}
	public void setWorker(ThreadForFile worker) {
		this.worker = worker;
	}
	public int getUploadedCount() {
		return uploadedCount;
	}
	public void setUploadedCount(int uploadedCount) {
		this.uploadedCount = uploadedCount;
	}
	public byte getCreated() {
		return created;
	}
	public void setCreated(byte created) {
		this.created = created;
	}
	public boolean isOnUploading() {
		return onUploading;
	}
	public void setOnUploading(boolean onUploading) {
		this.onUploading = onUploading;
	}
	public boolean isOnDownloading() {
		return onDownloading;
	}
	public void setOnDownloading(boolean onDownloading) {
		this.onDownloading = onDownloading;
	}
}
