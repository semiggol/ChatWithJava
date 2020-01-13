package jang.common;

public class ChatProtocol {
	public static final int HEADER_LEN = 16;
	/*
	 * Char Protocol Header
	 *  1 (byte): magic
	 *  1 (byte): version
	 *  1 (byte): msgtype
	 *  1 (byte): endtype
	 *  4 (byte): payload(body length)
	 *  8 (byte): reserved
	 */
	public static final int HEADER_MAGIC_OFFSET       = 0;
	public static final int HEADER_VERSION_OFFSET     = 1;
	public static final int HEADER_MSGTYPE_OFFSET     = 2;
	public static final int HEADER_ENDTYPE_OFFSET     = 3;
	public static final int HEADER_PAYLOAD_OFFSET     = 4;
	public static final int HEADER_RESERVED_OFFSET    = 12;
	
	public static final byte MAGIC                    = 0x60;
	
	public static final byte VERSION                  = 0x01;
	
	/* file upload */
	public static final byte ENDTYPE_START            = 1;
	public static final byte ENDTYPE_CONT             = 2;
	public static final byte ENDTYPE_LAST             = 3;
	public static final byte ENDTYPE_ERROR            = 9;
	
	public static final byte MSGTYPE_REGISTER         =  1;
	public static final byte MSGTYPE_REGISTERED       = 11;
	public static final byte MSGTYPE_PING             =  2;
	public static final byte MSGTYPE_PONG             = 12;
	public static final byte MSGTYPE_REQUEST_MESSAGE  =  3;
	public static final byte MSGTYPE_REPLY_MESSAGE    = 13;
	public static final byte MSGTYPE_REQUEST_UPLOAD   =  4;
	public static final byte MSGTYPE_REPLY_UPLOAD     = 14;
	public static final byte MSGTYPE_REQUEST_DOWNLOAD =  5;
	public static final byte MSGTYPE_REPLY_DOWNLOAD   = 15;
	public static final byte MSGTYPE_TRANSFER_FILE    =  6;
	public static final byte MSGTYPE_TAKE_FRIENDS     =  7;
	public static final byte MSGTYPE_GIVE_FRIENDS     = 17;
	public static final byte MSGTYPE_REQUEST_KICKOUT  =  8;
	public static final byte MSGTYPE_REPLY_KICKOUT    = 18;
	public static final byte MSGTYPE_UNKNOWN          = 20;
	public static final byte MSGTYPE_REPLY            = 10;
	
	public static final int MAX_TRANSFER_SIZE         = 100 * 1024 * 1024; /* 100 Mbytes */
	public static final int DFLT_TRANSFER_SIZE        =   1 * 1024 * 1024; /*   1 Mbytes */
	
}

