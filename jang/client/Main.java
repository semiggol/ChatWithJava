package jang.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.LinkedBlockingQueue;

import jang.common.ChatMessage;
import jang.common.ChatProtocol;
import jang.common.ClientInfo;
import jang.common.Message;
import jang.common.Network;
import jang.common.ResourceInfo;
import jang.common.ThreadForFile;


public class Main {
	private static final String SERVER_IP   = "192.168.13.56";
	private static final int SERVER_PORT    = 8080;
	
	private Selector mySelector;
	private ClientInfo myClient;
	private Pipe.SourceChannel readPipe;
	private SelectionKey readPipeKey;
	private Pipe.SinkChannel writePipe;
	private ByteBuffer rData;
	private ByteBuffer wData;
	private LinkedList<String> inputMsgs;
	private Scanner scan = null;
	private Charset charset = null;
	
	
	public static void main(String[] args) {
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
	
	private void start() {
		int result = 0;
		while (true) {
			/* init - prepare selector, ClientInfo, pipe, .. */
			result = initClient();
			if (result < 0) {
				return;
			}
	
			/* infinite loop for server */
			result = loop();
			if (result == 0) {
				return;
			}
			else {
				/* quit */
				if (result == -2) {
					return;
				}
				/* 다시 while().. */
			}
		}
	}
	
	/** init selector, client, pipe */
	private int initClient() {
		Network nwk = new Network(SERVER_IP, SERVER_PORT);
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
		
		/* create pipe for System.in */
		Pipe pipe;
		try {
			pipe = Pipe.open();
		} catch (IOException e) {
			e.printStackTrace();
			try {
				mySelector.close();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			return -1;
		}
		writePipe = pipe.sink();
		readPipe  = pipe.source();
		try {
			readPipe.configureBlocking(false);
			readPipeKey = readPipe.register(mySelector, SelectionKey.OP_READ);
		} catch (ClosedChannelException e) {
			e.printStackTrace();
		} catch (IOException ie) {
			ie.printStackTrace();
		}
		rData = ByteBuffer.allocate(1);
		wData = ByteBuffer.allocate(1);
		byte b = 1;
		wData.put(b);
		inputMsgs = new LinkedList<String>();
		
    charset = Charset.forName("UTF-8");
    
		return 0;
	}
	
	/** loop in selector
	 * 1. connect()
	 * 2. read()
	 * 3. write() */
	private int loop() {
		int result = 0;
		while (true) {
			try {
				/* select() */
				mySelector.select(Network.SELECT_TIMEOUT);
			} catch (IOException e) {
				Message.myLog(Message.ERR_MSG_006);
				e.printStackTrace();
				return -1;
			}
			
			/* TODO: get current time: Date vs Canendar.getInstance()? */

			Iterator<SelectionKey> iter = mySelector.selectedKeys().iterator();

			while(iter.hasNext()) {
				/* get SelectionKey */
				SelectionKey key = (SelectionKey) iter.next();
				if (key.isConnectable()) {
					/* 1. a connection was established with a remote server. */
					checkConnection(key);
					
					if (myClient.getStatus() != ClientInfo.CLIENT_CONNECTED) {
						return 0;
					}
					result = registerToServer();
					if (result < 0) {
						/* register failed */
						Message.myLog(Message.ERR_MSG_027);
						return -1;
					}
					try {
						/* set read event */
						SelectionKey clientKey = myClient.getSocketChannel().register(mySelector, SelectionKey.OP_READ);
						myClient.setSelectionKey(clientKey);
					} catch (ClosedChannelException cce) {
						cce.printStackTrace();
						myClient.close();
						return -1;
					}
				}
				else if (key.isReadable()) {
					/* 2.read() */
					if (key.equals(readPipeKey)) {
						/* read from System.in and process the message */
						result = processInputMsg();
					}
					else {
						/* read from Server and process the message */
						result = readChatMsg(key);
						if (result == 1) {
							processChatMsg();
						}
					}
					if (result < 0) {
						return result;
					} 
				}
				else if(key.isWritable()) {
					/* 3.write() */
					//writeToServer(null, key);
				}
				iter.remove();
			}
		} /* end while loop */
	}

	
	/** process message */
	private void processChatMsg() {
		ChatMessage chatMsg = myClient.getReadData();
		int msgtype = chatMsg.getMsgtype();
		ByteBuffer msgBuf = chatMsg.getMessageBuffer();
		
		if (msgtype == ChatProtocol.MSGTYPE_REGISTERED) {
			/* Registered */ 
			processRegistered(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_PING) {
			/* PING/PONG */
			processPing(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_REPLY_MESSAGE) {
			/* Normal chat */
			processNormalMsg(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_REPLY_UPLOAD) {
			/* Upload file is finished */
			processUploadResult(chatMsg, msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_REPLY_DOWNLOAD) {
			runSaveFileThread(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_TRANSFER_FILE) {
			/* Download file .. */
			saveFileContents(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_GIVE_FRIENDS) {
			/* Normal chat */
			processNormalMsg(msgBuf);
		}
		else if (msgtype == ChatProtocol.MSGTYPE_REQUEST_KICKOUT) {
			
		}
		else { /* ChatProtocol.MSGTYPE_UNKNOWN */
			
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
	private int readChatMsg(SelectionKey key) {
		int readResult = 0;
		readResult = myClient.readFromSocket();
		if (readResult == ClientInfo.READ_MSG_ERROR) {
			myClient.close();
			return -2; /* program exit */
		}
		else if (readResult == ClientInfo.READ_MSG_INCOMPLETE) {
			return 0;
		}
		else if (readResult == ClientInfo.READ_MSG_COMPLETE) {
			return 1;
		}
		else {
			/* unknown result */
		}
		
		return 0;
	}

	/** read from pipe(worker - System.in) and process message */
	private int processInputMsg() {
		try {
			rData.clear();
			readPipe.read(rData);
		} catch (IOException e) {
			/* pipe is broken */
			Message.myLog(Message.ERR_MSG_026);
			e.printStackTrace();
			return -1;
		}
		rData.flip();
		
		String msg = manageMsgList(null);
		if (myClient.getStatus() != ClientInfo.CLIENT_READY) {
			/* input message is used on READY
			 * So, drop this msg */
			return 0;
		}
		
		/* TODO: process input message */
		if (msg.length() == 2) {
			if (msg.equals("/h")) {
				/* help message */
				helpMessage();
				return 0;
			}
			else if (msg.equals("/v")) {
				/* view users message */
				viewMessage();
				return 0;
			}
			else if (msg.equals("/q")) {
				/* quit: program exit */
				Message.myLog(Message.SYS_MSG_009);
				try {
					myClient.close();
					closeResource();
				} catch (Exception e) {
					e.printStackTrace();
				}
				return -2;
			}

		}
		else if (msg.length() > 4) {
			String type = (String) msg.subSequence(0, 3);
			if (type.equals("/u ")) {
				/* upload file: this is process by worker */
				/* TODO: 이거 중복 체크 인가? */
				return 0;
			}
			else if (type.equals("/d ")) {
				/* download file message */
				/* TODO */
				downloadMessage(msg);
				return 0;
			}
			else if (type.equals("/k ")) {
				/* kick off message */
				/* TODO */
				return 0;
			}
		}
		
		/* Default: normal chat message */
		sendMsgToServer(msg);
		return 0;
	}

	private void closeResource() throws IOException {
		if (readPipe.isOpen()) {
			readPipe.close();
		}
		if (writePipe.isOpen()) {
			writePipe.close();
		}
		if (mySelector.isOpen()) {
			mySelector.close();
		}
	}

	/** request to get all users(id) from Server */
	private void viewMessage() {
		/* make request message */
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_TAKE_FRIENDS, 0);
		if (bufMsg == null) {
			return;
		}
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
	private void sendMsgToServer(String msg) {
		/* make normal message: "ChatID: msg" */
		String newMsg = myClient.getId() + ": " + msg;
		ByteBuffer encodedMsg = charset.encode(newMsg);
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REQUEST_MESSAGE, size);
		if (bufMsg == null) {
			return;
		}
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		myClient.writeToSocket(bufMsg);
	}

	/** connect to server? */
	private void checkConnection(SelectionKey key) {
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
			e.printStackTrace();
			return;
		}
	}
	
	/** send registration message to server */ 
	private int registerToServer() {
		SocketChannel clientSocket = myClient.getSocketChannel();
		try {
			clientSocket.register(mySelector, SelectionKey.OP_READ);
		} catch (ClosedChannelException che) {
			Message.myLog(Message.ERR_MSG_025 + che.toString());
			che.printStackTrace();
			return -1;
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
		if (newMsg == null) {
			return -1;
		}
		newMsg.put(encodedMsg);
		newMsg.flip();
		myClient.writeToSocket(newMsg);
		
		return 0;
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
							uploadMessage(filePath.trim());
							continue;
						}
					}
					
					manageMsgList(inputMsg);
					wData.rewind();
					try {
						writePipe.write(wData);
					} catch (IOException e) {
						Message.myLog(Message.ERR_MSG_028 + e.toString());
						e.printStackTrace();
					}
					
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
	private void uploadMessage(String msg) {
		/* make upload message: "ChatID_filename" */
		
		/* 1) check if file exists */
		int result = ThreadForFile.checkIfFileExists(msg);
		if (result < 0) {
			/* this upload Message is ignored.. */
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
		if (bufMsg == null) {
			return;
		}
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
	private void downloadMessage(String msg) {
		/* make new buffer and send to server */
		if (myClient.isOnDownloading()) {
			Message.printMsg("Multiple download is not accepted.");
			return;
		}
		myClient.setOnDownloading(true);
		String fileName = msg.substring(3);
		ByteBuffer encodedMsg = charset.encode(fileName.trim());
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REQUEST_DOWNLOAD, size);
		if (bufMsg == null) {
			return;
		}
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
