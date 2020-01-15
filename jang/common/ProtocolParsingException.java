package jang.common;

public class ProtocolParsingException extends Exception {
	/* Default */
	private static final long serialVersionUID = 1L;
	int errorCode;
	
	ProtocolParsingException(String msg, int code) {
		super(msg);
		errorCode = code;
	}
	
	ProtocolParsingException(String msg) {
		super(msg);
		errorCode = 0;
	}
}
