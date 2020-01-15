package jang.common;

import java.nio.ByteBuffer;

public class ChatMessage {

	/* message's status */
	public static final byte READ_HEADER_INCOMPLETE  = 0;
	public static final byte READ_HEADER_COMPLETE    = 1;
	public static final byte READ_PAYLOAD_INCOMPLETE = READ_HEADER_COMPLETE;
	public static final byte READ_PAYLOAD_COMPLETE   = 2;
	private byte status = READ_HEADER_INCOMPLETE;
	
	private static final int PAYLOAD_LIMIT           = 32768; /* 32K */
	
	private ByteBuffer headerBuffer;

	private int readLength = 0;

	private ByteBuffer messageBuffer;
	private int payload;
	private byte msgtype;
	private byte version;
	private byte endtype;
	private byte[] reserved = new byte[8];
	private boolean parseResult;
	
	/** parsing Header */
	public void parseProtocol() throws ProtocolParsingException {
		/* check body */
		if (status == READ_PAYLOAD_COMPLETE) {
			return;
		}
		else if (status == READ_PAYLOAD_INCOMPLETE) {
			if (payload == messageBuffer.position() - ChatProtocol.HEADER_LEN) {
				status = READ_PAYLOAD_COMPLETE;
			}
			return;
		}
		
		/* parse Header */
		headerBuffer.flip();
		byte magic = headerBuffer.get();
		if (magic != ChatProtocol.MAGIC) {
			Message.myLog(Message.ERR_MSG_016 + magic);
			throw new ProtocolParsingException(Message.ERR_MSG_016, ChatProtocol.HEADER_MAGIC_OFFSET);
		}
		version = headerBuffer.get();
		if (version != ChatProtocol.VERSION) {
			Message.myLog(Message.ERR_MSG_017 + version);
			throw new ProtocolParsingException(Message.ERR_MSG_017, ChatProtocol.HEADER_VERSION_OFFSET);
		}
		msgtype = headerBuffer.get();
		if (msgtype >= ChatProtocol.MSGTYPE_UNKNOWN) {
			Message.myLog(Message.ERR_MSG_018 + msgtype);
			throw new ProtocolParsingException(Message.ERR_MSG_018, ChatProtocol.HEADER_MSGTYPE_OFFSET);
		}
		/* Message.myLog("[Debug]New Message arrived. -> " + msgtype);" */
		endtype = headerBuffer.get();
		if (endtype != ChatProtocol.ENDTYPE_START &&
				endtype != ChatProtocol.ENDTYPE_CONT &&
				endtype != ChatProtocol.ENDTYPE_ERROR &&
				endtype != ChatProtocol.ENDTYPE_LAST) {
			Message.myLog(Message.ERR_MSG_019 + endtype);
			throw new ProtocolParsingException(Message.ERR_MSG_019, ChatProtocol.HEADER_ENDTYPE_OFFSET);
		}
		
		payload = headerBuffer.getInt();
		if (payload < 0 || payload > PAYLOAD_LIMIT) {
			Message.myLog(Message.ERR_MSG_020 + payload);
			throw new ProtocolParsingException(Message.ERR_MSG_020, ChatProtocol.HEADER_PAYLOAD_OFFSET);
		}

		headerBuffer.get(reserved, 0, 8);
		
		parseResult = true;
		
		status = READ_HEADER_COMPLETE;
		if (payload == 0) {
			status = READ_PAYLOAD_COMPLETE;
		}
		
		/* make first messageBuffer */
		getMessageBuffer();
		headerBuffer.clear();
		messageBuffer.put(headerBuffer);
		messageBuffer.position(ChatProtocol.HEADER_LEN);
		
		return; /* ok */
	}
	
	public int getReadLength() {
		return readLength;
	}
	public void setReadLength(int readLength) {
		this.readLength += readLength;
	}
	public byte getMsgtype() {
		return msgtype;
	}
	public byte getVersion() {
		return version;
	}
	public byte getEndtype() {
		return endtype;
	}
	public int getPayload() {
		return payload;
	}
	public boolean isParseResult() {
		return parseResult;
	}

	public int getStatus() {
		return status;
	}

	public void setStatus(byte status) {
		this.status = status;
	}

	/** Header 초기화 */
	private void initParseInfo() {
		/* status */
		status = ChatMessage.READ_HEADER_INCOMPLETE;
		/* headerBuffer */
		if (headerBuffer != null) {
			headerBuffer.clear();
		}
		parseResult = false;
		msgtype = 0;
		version = 0;
		endtype = 0;
		payload = 0;
		reserved[0] = 0;reserved[1] = 0;reserved[2] = 0;reserved[3] = 0;
		reserved[4] = 0;reserved[5] = 0;reserved[6] = 0;reserved[7] = 0;
	}
	public ByteBuffer getHeaderBuffer() {
		if (headerBuffer == null) {
			headerBuffer = ByteBuffer.allocateDirect(ChatProtocol.HEADER_LEN);
			initParseInfo();
		}
		return headerBuffer;
	}
	public ByteBuffer getMessageBuffer() {
		if (messageBuffer == null) {
			messageBuffer = ByteBuffer.allocateDirect(ChatProtocol.HEADER_LEN + payload);
		}
		return messageBuffer;
	}
	public void initMessageBuffer() {
		messageBuffer = null;
		initParseInfo();
	}
	
	/** make new ByteBuffer for ChatMessage */
	public static ByteBuffer makeNewMessage(byte msgtype, int payload) throws ProtocolParsingException {
		if (msgtype >= ChatProtocol.MSGTYPE_UNKNOWN) {
			Message.myLog(Message.ERR_MSG_018 + msgtype);
			throw new ProtocolParsingException(Message.ERR_MSG_018, ChatProtocol.HEADER_MSGTYPE_OFFSET);
		}
		if (payload < 0) {
			Message.myLog(Message.ERR_MSG_019 + payload);
			throw new ProtocolParsingException(Message.ERR_MSG_019, ChatProtocol.HEADER_PAYLOAD_OFFSET);
		}
		
		int size = ChatProtocol.HEADER_LEN + payload;
		ByteBuffer newMsg = ByteBuffer.allocateDirect(size);
		newMsg.put(ChatProtocol.MAGIC);
		newMsg.put(ChatProtocol.VERSION);
		newMsg.put(msgtype);  
		newMsg.put(ChatProtocol.ENDTYPE_LAST); 
		newMsg.putInt(payload); 
		newMsg.putLong(0L);
		
		newMsg.position(ChatProtocol.HEADER_LEN);
		return newMsg;
	}
	
	/** set msgtype to bytebuffer(header) */
	public static void setMsgtypeToHeader(ByteBuffer header, byte msgtype) {
		if (header == null) {
			return;
		}
		
		int oldPosition = header.position();
		/* set new msgtype */
		header.position(ChatProtocol.HEADER_MSGTYPE_OFFSET);
		header.put(msgtype);
		
		/* restore position */
		header.position(oldPosition);
		
		return;
	}
	
	/** set payload to bytebuffer(header) */
	public static void setPayloadToHeader(ByteBuffer header, int payload) {
		if (header == null) {
			return;
		}
		
		int oldPosition = header.position();
		/* set new payload */
		header.position(ChatProtocol.HEADER_PAYLOAD_OFFSET);
		header.putInt(payload);
		
		/* restore position */
		header.position(oldPosition);
		
		return;
	}
	
	/** set endtype to bytebuffer(header) */
	public static void setEndtypeToHeader(ByteBuffer header, byte endtype) {
		if (header == null) {
			return;
		}
		
		int oldPosition = header.position();
		/* set new endtype */
		header.position(ChatProtocol.HEADER_ENDTYPE_OFFSET);
		header.put(endtype);
		
		/* restore position */
		header.position(oldPosition);
		
		return;
	}
}
