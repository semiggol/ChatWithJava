package jang.client;

import java.io.FileReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.LinkedBlockingQueue;

import jang.common.ChatMessage;
import jang.common.ChatProtocol;
import jang.common.ClientInfo;
import jang.common.Message;
import jang.common.Network;
import jang.common.ProtocolParsingException;
import jang.common.ResourceInfo;
import jang.common.ThreadForFile;


public class Main {
	private static final String configFile = "client.properties";
	
	/* configuration */
	private String serverIp;
	private int serverPort;
	private long maxUploadSize;
	private String downloadDir;
	
	private Selector mySelector;
	private ClientInfo myClient;
	private LinkedList<String> inputMsgs;
	private Scanner scan = null;
	private Charset charset = null;
	
	
	public static void main(String[] args) throws Exception {
		ResourceInfo.infoSystemProperties(ResourceInfo.CHAT_TYPE_CLIENT);
		
		System.setSecurityManager(new SecurityManager() {
			@Override
			public void checkExit(int status) {
				if (status == 5) {
					throw new SecurityException();
				}
			}
		});
		
		Main chatClient = new Main();
		chatClient.start();
	}
	
	private void start() throws Exception {
		/* get config */
		initConfig();
		
		int result = 0;
		while (true) {
			/* init - prepare selector, ClientInfo, pipe, .. */
			result = initClient();
			if (result < 0) {
				return;
			}
	
			/* infinite loop for server */
			try {
				loop();
			} catch (Exception e) {
				e.printStackTrace();
				myClient.close();
				closeResource();
				return;
			}
		}
	}
	
	private void initConfig() throws Exception {
		/* get configuration from client.properties */
		String path = Main.class.getResource(configFile).getPath();
		path = URLDecoder.decode(path, "utf-8");
		Properties properties = new Properties();
		properties.load(new FileReader(path));
		
		/* get server.ip */
		serverIp = properties.getProperty("server.ip");
		if (serverIp == null || serverIp.isEmpty()) {
			Message.myLog(Message.ERR_MSG_101);
			throw new Exception(Message.ERR_MSG_101);
		}
		/* get server.port */
		String sPort = properties.getProperty("server.port");
		if (sPort == null || sPort.isEmpty()) {
			Message.myLog(Message.ERR_MSG_102);
			throw new Exception(Message.ERR_MSG_102);
		}
		serverPort = Integer.parseInt(sPort);
		/* get max.upload.size */
		String sMaxUploadSize = properties.getProperty("max.upload.size");
		if (sMaxUploadSize == null || sMaxUploadSize.isEmpty()) {
			/* use default */
			maxUploadSize = ChatProtocol.DFLT_TRANSFER_SIZE;
			Message.myLog(Message.ERR_MSG_103 + "default=" + maxUploadSize);
		}
		else {
			maxUploadSize = Long.parseLong(sMaxUploadSize);
		}
		/* get download.dir */
		downloadDir = properties.getProperty("download.dir");
		if (downloadDir == null || downloadDir.isEmpty()) {
			downloadDir = Main.class.getResource("").getPath();
			Message.myLog(Message.ERR_MSG_104 + "default=" + downloadDir);
		}
		
		Message.myLog("[Configuration]");
		Message.myLog("\t 1)serverIp \t\t= " + serverIp);
		Message.myLog("\t 2)serverPort \t\t= " + serverPort);
		Message.myLog("\t 3)maxUploadSize \t= " + maxUploadSize);
		Message.myLog("\t 4)downadDir \t\t= " + downloadDir);
		Message.myLog("[Configuration]");
	}
	
	/** init selector, client, pipe */
	private int initClient() {
		Network nwk = new Network(serverIp, serverPort);
		mySelector = nwk.getSelector();
		if (mySelector == null) {
			return -1;
		}
		
		if (myClient != null) {
			myClient.close();
		}
		
		try {
			nwk.createClient();
		} catch (ClosedChannelException e) {
			e.printStackTrace();
			return -1;
		} catch (IOException e) {
			e.printStackTrace();
			return -1;
		}
		myClient = new ClientInfo();
		myClient.setCreated(ClientInfo.CREATED_BY_CLIENT);
		myClient.setStatus(ClientInfo.CLIENT_CONNECTING);
		myClient.setDownloadDir(downloadDir);
		
		/* create input message list for System.in */
		inputMsgs = new LinkedList<String>();
		
		/* use UTF-8 encoding/decoding for chat message */
    charset = Charset.forName("UTF-8");
    
		return 0;
	}
	
	/** loop in selector
	 * 1. connect()
	 * 2. read()
	 * 3. write() */
	private void loop() throws Exception {
		while (true) {
			try {
				/* select() */
				mySelector.select(Network.SELECT_TIMEOUT);
			} catch (IOException e) {
				Message.myLog(Message.ERR_MSG_006);
				e.printStackTrace();
				throw e;
			}
			
			/* TODO: get current time: Date vs Canendar.getInstance()? */

			Iterator<SelectionKey> iter = mySelector.selectedKeys().iterator();

			while(iter.hasNext()) {
				/* get SelectionKey */
				SelectionKey key = (SelectionKey) iter.next();
				if (key.isConnectable()) {
					/* 1. a connection was established with a remote server. */
					checkConnection(key);
					registerToServer();
					
					/* set read event */
					SelectionKey clientKey = myClient.getSocketChannel().register(mySelector, SelectionKey.OP_READ);
					myClient.setSelectionKey(clientKey);
				}
				else if (key.isReadable()) {
					/* read from Server and process the message */
					ChatMessage cMsg = readChatMsg(key);
					if (cMsg != null) {
						processChatMsg(cMsg);
					}
				}
				else if(key.isWritable()) {
					/* 3.write() */
					myClient.writeToSocket(null);
				}
				iter.remove();
			}
			
			String inputMsg = manageMsgList(null);
			if (inputMsg != null) {
				 processInputMsg(inputMsg);
			}
			
		} /* end while loop */
	}

	/** process message */
	private void processChatMsg(ChatMessage chatMsg) throws Exception {
		int msgtype = chatMsg.getMsgtype();
		ByteBuffer msgBuf = chatMsg.getMessageBuffer();
		
		switch (msgtype) {
		case ChatProtocol.MSGTYPE_REGISTERED:
			/* Registered */ 
			processRegistered(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_PING:
			/* PING/PONG */
			processPing(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_REPLY_MESSAGE:
			/* Normal chat */
			processNormalMsg(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_REPLY_UPLOAD:
			/* Upload file is finished */
			processUploadResult(chatMsg, msgBuf);
			break;
		case ChatProtocol.MSGTYPE_REPLY_DOWNLOAD:
			/* save file */
			runSaveFileThread(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_TRANSFER_FILE:
			/* Download file .. */
			saveFileContents(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_GIVE_FRIENDS:
			/* Normal chat */
			processNormalMsg(msgBuf);
			break;
		case ChatProtocol.MSGTYPE_REQUEST_KICKOUT:
			/* TODO: kickout */
			break;
		default:
				/* ChatProtocol.MSGTYPE_UNKNOWN */
			Message.printMsg("Unknown Message");
		}

		chatMsg.initMessageBuffer();
	}

	/** start thread for saving file */
	private void runSaveFileThread(ByteBuffer msg) {
		int endtype = msg.get(ChatProtocol.HEADER_ENDTYPE_OFFSET);
		if (endtype == ChatProtocol.ENDTYPE_ERROR) {
			/* failed to download: print chat(error) message */
			msg.position(ChatProtocol.HEADER_LEN);
			String sMsg = charset.decode(msg).toString();
			Message.printMsg(sMsg);
			myClient.setOnDownloading(false);
			return;
		}
		/* get receivedFileName and uploadedCount set */
		msg.position(ChatProtocol.HEADER_LEN);
   	String receivedFileName = charset.decode(msg).toString();
   	myClient.setUploadedFileName(receivedFileName);
   	
   	/* create lbq */
		LinkedBlockingQueue<ByteBuffer> lbq = new LinkedBlockingQueue<ByteBuffer>();
		myClient.setFileContents(lbq);
		lbq.add(msg);
		
		/* worker thread: fileChannel open and waiting */
		ThreadForFile worker = new ThreadForFile(myClient);
		myClient.setWorker(worker);
		worker.setName("workerForFileChannel");
		worker.start();
	}

	/** save received file to disk */
	private void saveFileContents(ByteBuffer msg) {
		LinkedBlockingQueue<ByteBuffer> lbq = myClient.getFileContents();
		lbq.add(msg);
		int endtype = msg.get(ChatProtocol.HEADER_ENDTYPE_OFFSET);
		if (endtype == ChatProtocol.ENDTYPE_LAST ||
				endtype == ChatProtocol.ENDTYPE_ERROR) {
			/* completed to download */
			myClient.setOnDownloading(false);
			return;
		}
	}

	/** upload file is finished
	 * 1) if payload(uploaded fileName) > 0
	 * 		then, success: broadcast()
	 * 2) if payload == 0
	 * 		then, fail: print System.out (notify to myself) */
	private void processUploadResult(ChatMessage chatMsg, ByteBuffer msgBuf) {
		int payload = chatMsg.getPayload();
		if (payload > 0) {
			/* This Message will be replied by Server */
			ChatMessage.setMsgtypeToHeader(msgBuf, ChatProtocol.MSGTYPE_REQUEST_MESSAGE);
			msgBuf.rewind();
			myClient.writeToSocket(msgBuf);
		}
		else {
			/* notify to myself */
			Message.printMsg(Message.SYS_MSG_011);
			myClient.setOnUploading(false);
		}
	}

	/** print System.out */
	private void processNormalMsg(ByteBuffer msgBuf) {
		/* set ID and status */
		msgBuf.rewind();
		msgBuf.position(ChatProtocol.HEADER_LEN);
		
   	String msg = charset.decode(msgBuf).toString();
   	Message.printMsg(msg);
	}

	/** client is registered to server */
	private void processRegistered(ByteBuffer msgBuf) {
		/* set ID and status */
		msgBuf.position(ChatProtocol.HEADER_LEN);
		
    String id = charset.decode(msgBuf).toString();
        
    myClient.setId(id);
		myClient.setStatus(ClientInfo.CLIENT_READY);
		
		Message.myLog(Message.SYS_MSG_005 + "ChatID:" + id);
		
		/* run worker thread for user input(System.in) */
		runInputThread();
	}
	
	/** ping/pong */
	private void processPing(ByteBuffer msg) {
		ChatMessage.setMsgtypeToHeader(msg, ChatProtocol.MSGTYPE_PONG);
		msg.rewind();
		writeToClient(msg);
	}
	
	/** writeToClient: TCP.read() from client	*/
	private void writeToClient(ByteBuffer msg) {
		if (myClient.getStatus() == ClientInfo.CLIENT_READY) {
			msg.rewind();
			myClient.writeToSocket(msg);
		}
	}

	/** read from Server and process message */
	private ChatMessage readChatMsg(SelectionKey key) throws IOException, ProtocolParsingException {
		myClient.readFromSocket();
		if (myClient.getReadData().getStatus() == ChatMessage.READ_PAYLOAD_COMPLETE) {
			return myClient.getReadData();
		}
		
		return null;
	}

	/** read from pipe(worker - System.in) and process message */
	private void processInputMsg(String msg) throws Exception {
		if (myClient.getStatus() != ClientInfo.CLIENT_READY) {
			/* input message is used on READY
			 * So, drop this msg */
		}
		
		/* process input message */
		if (msg.length() == 2) {
			if (msg.equals("/h")) {
				/* help message */
				helpMessage();
				return;
			}
			else if (msg.equals("/v")) {
				/* view users message */
				viewMessage();
				return;
			}
			else if (msg.equals("/q")) {
				/* quit: program exit */
				Message.myLog(Message.SYS_MSG_009);
				try {
					myClient.close();
					closeResource();
				} catch (Exception e) {
					e.printStackTrace();
					throw e;
				}
				/* TODO: throw new close exception */
			}
		}
		else if (msg.length() > 4) {
			String type = (String) msg.subSequence(0, 3);
			if (type.equals("/u ")) {
				/* upload file: this is process by worker */
				return;
			}
			else if (type.equals("/d ")) {
				/* download file message */
				downloadMessage(msg);
				return;
			}
			else if (type.equals("/k ")) {
				/* kick off message */
				/* TODO */
				return;
			}
		}
		
		/* Default: normal chat message */
		sendMsgToServer(msg);
	}

	private void closeResource() throws IOException {
		if (mySelector.isOpen()) {
			mySelector.close();
		}
	}

	/** request to get all users(id) from Server */
	private void viewMessage() throws ProtocolParsingException {
		/* make request message */
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_TAKE_FRIENDS, 0);
		bufMsg.flip();
		myClient.writeToSocket(bufMsg);
	}

	/** print help message */
	private void helpMessage() {
		System.out.println("usage>" +
				              "/h: help message\n" +
											"/v: get all users(id) from Server\n" +
											"/q: quit\n" +
											"/u <absolute path>: upload file\n" +
											"/d <uploaded path>: download file\n" +
											"/k <user id>: kick off\n");
	}

	/** send inputMsg to Server */
	private void sendMsgToServer(String msg) throws ProtocolParsingException {
		/* make normal message: "ChatID: msg" */
		String newMsg = myClient.getId() + ": " + msg;
		ByteBuffer encodedMsg = charset.encode(newMsg);
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REQUEST_MESSAGE, size);
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		myClient.writeToSocket(bufMsg);
	}

	/** connect to server? */
	private void checkConnection(SelectionKey key) throws Exception {
		SocketChannel clientSocket = (SocketChannel) key.channel();
		try {
			while (clientSocket.isConnectionPending()) {
				clientSocket.finishConnect();
				myClient.setSocketChannel(clientSocket);
				myClient.setStatus(ClientInfo.CLIENT_CONNECTED);
				myClient.setSelectionKey(key);
			}
		} catch (Exception e) {
			Message.myLog(Message.ERR_MSG_024 + e.toString());
			key.cancel();
			throw e;
		}
	}
	
	/** send registration message to server */ 
	private void registerToServer() throws Exception {
		SocketChannel clientSocket = myClient.getSocketChannel();
		try {
			clientSocket.register(mySelector, SelectionKey.OP_READ);
		} catch (ClosedChannelException che) {
			Message.myLog(Message.ERR_MSG_025 + che.toString());
			che.printStackTrace();
			throw che;
		}
		
		scan = new Scanner(System.in);
		System.out.print("input your ChatID> ");
		String id = scan.nextLine();
		
		Message.myLog("Registering with your ChatID " + id);
		myClient.setId(id);
		
		ByteBuffer encodedMsg = charset.encode(id);
		int size = encodedMsg.remaining();
		
		/* make register message */
		ByteBuffer newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REGISTER, size);
		newMsg.put(encodedMsg);
		newMsg.flip();
		myClient.writeToSocket(newMsg);
	}
	
	/** start thread for System.in */
	private void runInputThread() {
		Thread thread = new Thread() {
			@Override
			public void run() {
				String inputMsg;
				while (true) {
					System.out.print("> ");
					scan = new Scanner(System.in);
					inputMsg = scan.nextLine();
					
					/* upload File is processed by worker */
					if (inputMsg.length() > 4) {
						String isUploadFile = inputMsg.substring(0, 3);
						if (isUploadFile.equals("/u ")) {
							String filePath = inputMsg.substring(3);
							try {
								uploadMessage(filePath.trim());
							} catch (Exception e) {
								e.printStackTrace();
								Message.myLog(Message.SYS_MSG_011);
							}
							continue;
						}
					}
					
					manageMsgList(inputMsg);
					mySelector.wakeup();
					
					if (inputMsg.length() == 2 && inputMsg.equals("/q")) {
						break;
					}
					
					
				}
				scan.close();
			}
		};
		thread.setName("workerForSystemIn");
		thread.setDaemon(true);
		thread.start();
	}
	
	/** send msg(upload start) to Server */
	private void uploadMessage(String msg) throws Exception {
		/* make upload message: "ChatID_filename" */
		
		/* 1) check if file exists */
		long fileSize = ThreadForFile.checkIfFileExists(msg);
		if (fileSize < 0) {
			/* this upload Message is ignored.. */
			return;
		}
		else if (fileSize > maxUploadSize) {
			Message.myLog(Message.SYS_MSG_012 + "fileSize=" + fileSize + ", limit=" + maxUploadSize);
			return;
		}
		
		/* 2) get properties for file separator and make new filename(id_filename) */
		String fileSeparator = System.getProperty("file.separator");
		int indexLastSeparator = msg.lastIndexOf(fileSeparator);
		String fileName = myClient.getId() + "_" + msg.substring(indexLastSeparator+1);
		
		/* 3) make new buffer and send to server */
		ByteBuffer encodedMsg = charset.encode(fileName);
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REQUEST_UPLOAD, size);
		ChatMessage.setEndtypeToHeader(bufMsg, ChatProtocol.ENDTYPE_START);
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		myClient.writeToSocket(bufMsg);
		/* does not accept multiple upload */
		myClient.setOnUploading(true);
		
		/* 4) file read and write */
		ThreadForFile.transferFileToSocket(msg, myClient);
		myClient.setOnUploading(false);
	}
	
	/** download msg(send download msg and start) */
	private void downloadMessage(String msg) throws ProtocolParsingException {
		/* make new buffer and send to server */
		if (myClient.isOnDownloading()) {
			Message.printMsg("Multiple download is not accepted.");
			return;
		}
		String fileName = msg.substring(3);
		ByteBuffer encodedMsg = charset.encode(fileName.trim());
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REQUEST_DOWNLOAD, size);
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		myClient.writeToSocket(bufMsg);
		myClient.setOnDownloading(true);
	}

	/** inputMsgsQ */
	private synchronized String manageMsgList(String msg) {
		if (msg == null) {
			if (!inputMsgs.isEmpty()) {
				return inputMsgs.pop();
			}
		}
		else {
			inputMsgs.add(msg);
		}
		
		return null;
	}

}
