package jang.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Network {
	public static final int SELECT_TIMEOUT = 1000;
	private String svrIp;
	private int svrPort;


	private Selector selector = null;
	public ServerSocketChannel svrChannel;
	public SocketChannel cliChannel;
	
	/* constructor 1 - for server */
	public Network(int svrPort) {
		this.svrPort = svrPort;
	}
	
	/* constructor 2 - for client */
	public Network(String svrIp, int svrPort) {
		this.svrIp = svrIp;
		this.svrPort = svrPort;
	}
	
	public Selector getSelector() {
		if (selector != null) {
			return selector;
		}
		
		try {
			selector = Selector.open();
		} catch (IOException e) {
			Message.myLog(Message.ERR_MSG_001);
		} finally {
			Message.myLog(Message.SYS_MSG_001);
		}
		
		return selector;
	}
	
	public void closeSelector() {
		if (selector != null) {
			try {
				selector.close();
			} catch (IOException e) {
				Message.myLog(Message.ERR_MSG_003);
			}
		}
		selector = null;
	}
	
	// create ServerSocketChannel
	public void createServer() throws IOException, ClosedChannelException {
		//System.getSecurityManager().checkPermission(new SocketPermission("8080", "accept, connect, listen, resolve"));
		//System.getSecurityManager().checkPermission(new RuntimePermission("readerThread"));


		//ServerSocket ss = new ServerSocket(svrPort, 511);
		//svrChannel = ss.getChannel();
		svrChannel = ServerSocketChannel.open();
		svrChannel.bind(new InetSocketAddress(svrPort));
		//svrChannel.bind(new InetSocketAddress(svrIp, svrPort));
		svrChannel.configureBlocking(false); /* nonBlocking */
		svrChannel.register(selector, SelectionKey.OP_ACCEPT);
		Message.myLog(Message.SYS_MSG_002 + " port: " + svrPort);
	}
	
	/** create client */
	public void createClient() throws IOException, ClosedChannelException {
		cliChannel = SocketChannel.open();
		cliChannel.configureBlocking(false); /* nonBlocking */
		cliChannel.connect(new InetSocketAddress(svrIp, svrPort)); /* connect() */
		//cliChannel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ | SelectionKey.OP_WRITE);
		cliChannel.register(selector, SelectionKey.OP_CONNECT);
		Message.myLog(Message.SYS_MSG_002 + "ServerAddress: " + svrIp + ":" + svrPort);
	}
}
