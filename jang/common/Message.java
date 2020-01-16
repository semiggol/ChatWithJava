package jang.common;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
//import java.time.LocalDateTime;

public class Message {
	/* errorlog */
	public static final String ERR_MSG_001 = "Selector.open() is faled.";
	public static final String ERR_MSG_002 = "Selector is destroyed.";
	public static final String ERR_MSG_003 = "Selector.close() is failed.";
	public static final String ERR_MSG_004 = "ClosedChannelException on createServer().";
	public static final String ERR_MSG_005 = "IOException on createServer().";
	public static final String ERR_MSG_006 = "Selector.select() is failed.";
	public static final String ERR_MSG_007 = "Server.accept() is failed.";
	public static final String ERR_MSG_008 = "Server.accept() returns null.";
	public static final String ERR_MSG_009 = "NonBlocking/ReadEvent is failed.";
	public static final String ERR_MSG_010 = "ClientSocket.close() is failed.";
	public static final String ERR_MSG_011 = "MaxClient is reached.";
	public static final String ERR_MSG_012 = "Read IOException.";
	public static final String ERR_MSG_013 = "Invalid Client.";
	public static final String ERR_MSG_014 = "Unknown Host Exception.";
	public static final String ERR_MSG_015 = "Unknown Interface(Socket) Exception.";
	public static final String ERR_MSG_016 = "Invalid magic number.";
	public static final String ERR_MSG_017 = "Invalid version number.";
	public static final String ERR_MSG_018 = "Invalid msgtype.";
	public static final String ERR_MSG_019 = "Invalid endtype.";
	public static final String ERR_MSG_020 = "Invalid payload.";
	public static final String ERR_MSG_021 = "Failed to send data to client.";
	public static final String ERR_MSG_022 = "Write Exception on client.";
	public static final String ERR_MSG_023 = "Client had gone.";
	public static final String ERR_MSG_024 = "Client.connect() is failed.";
	public static final String ERR_MSG_025 = "ClosedChannelException on client.";
	/* public static final String ERR_MSG_026 = "Pipe for worker is broken."; */
	public static final String ERR_MSG_027 = "Registration to server is failed.";
	/* public static final String ERR_MSG_028 = "write error on pipe."; */
	public static final String ERR_MSG_029 = "Opening file or getting size to transfer failed.";
	public static final String ERR_MSG_030 = "Reading file to transfer failed.";
	public static final String ERR_MSG_031 = "Sending file to transfer failed.";
	public static final String ERR_MSG_032 = "fileChannel.close() is failed.";
	public static final String ERR_MSG_033 = "Unknown Message arrived.";
	public static final String ERR_MSG_034 = "Normal file is not found.";
	
	/* for configuration */
	public static final String ERR_MSG_101 = "server.ip is required in client.properties file.";
	public static final String ERR_MSG_102 = "server.port is required in client.properties file.";
	public static final String ERR_MSG_103 = "max.upload.size is not set in client.properties file.";
	public static final String ERR_MSG_104 = "download.dir is not set in client.properties file.";
	public static final String ERR_MSG_201 = "port is required in server.properties file.";
	public static final String ERR_MSG_202 = "max.clients is not set in server.properties file.";
	public static final String ERR_MSG_203 = "upload.dir is not set in server.properties file.";
	
	/* syslog */
	public static final String SYS_MSG_001 = "Selector.open() is done.";
	public static final String SYS_MSG_002 = "Server Port is opened.";
	public static final String SYS_MSG_003 = "New Client is connected.";
	public static final String SYS_MSG_004 = "Connecting..";
	public static final String SYS_MSG_005 = "Registered to Server.";
	public static final String SYS_MSG_006 = "Admin is changed to ";
	public static final String SYS_MSG_007 = "New Client is registered.";
	public static final String SYS_MSG_008 = "New User is on. ChatID=";
	public static final String SYS_MSG_009 = "Program is closed by user(/q).";
	public static final String SYS_MSG_010 = "File is transfered.";
	public static final String SYS_MSG_011 = "File transfer is failed.";
	public static final String SYS_MSG_012 = "Size of File to transfer is over MAX(default=100M).";
	
	
	private static SimpleDateFormat formatDate = new SimpleDateFormat("yyyy-MM-dd");
	private static SimpleDateFormat formatTime = new SimpleDateFormat("HH:mm:ss");
	
	public static void myLog(String message) {
		/* currentTime */
		/* TODO: need to optimize Calendar.getInstance() */
		
		
		/* java8 */
		//LocalDateTime currDateTime = LocalDateTime.now();
		// System.out.println("[" + currDateTime + "] " + message);
		
		/* java7 */
		Calendar calendar = Calendar.getInstance();
		Date date = calendar.getTime();
		String time1 = formatDate.format(date);
		String time2 = formatTime.format(date);
		System.out.println("["+time1+"T" +time2+ "] " + message);
	}
	
	public static void printMsg(String message) {
		myLog(message);
	}
}
