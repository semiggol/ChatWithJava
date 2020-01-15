package jang.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
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
	/* TODO: need configuration */
	private static final String SERVER_IP = "192.168.13.56";
	private static final int SERVER_PORT  = 8080;
	private static final int MAX_CLIENT   = 100;
	private Selector mySelector = null;
	private Charset charset = null;
	
	private int currentClients;
	private ArrayList<ClientInfo> users;
	
	public static void main(String[] args) {
		ResourceInfo.infoSystemProperties(ResourceInfo.CHAT_TYPE_SERVER);
		
		System.setSecurityManager(new SecurityManager() {
			@Override
			public void checkExit(int status) {
				if (status == 5) {
					throw new SecurityException();
				}
			}
		});
		
		Main chatServer = new Main();
		chatServer.start();
	}
	
	private void start() {
		Network myNetwork = null;
		while (true) {
			/* init network() - prepare selector/serverSocket */
			myNetwork = initNetwork(SERVER_IP, SERVER_PORT);
			if (myNetwork == null) {
				return;
			}
	
			/* infinite loop for server */
			loop();
		}
	}
	
	/** init selector */
	private Network initNetwork(String listenIp, int listenPort) {
		boolean err = false;
		Network nwk = new Network(listenIp, listenPort);
		mySelector = nwk.getSelector();
		try {
			nwk.createServer();
		} catch (ClosedChannelException e) {
			Message.myLog(Message.ERR_MSG_004);
			e.printStackTrace();
			err = true; 
		} catch (IOException e) {
			Message.myLog(Message.ERR_MSG_005);
			e.printStackTrace();
			err = true;
		}
		
		if (err == true || mySelector == null) {
			nwk.closeSelector();
			mySelector = null;
			return null;
		}
		
		charset = Charset.forName("UTF-8");
		
		return nwk;
	}
	
	/** loop in selector
	 * 1. accept()
	 * 2. read()
	 * 3. write() */
	private void loop() {
		while (true) {
			try {
				/* select() */
				mySelector.select(Network.SELECT_TIMEOUT);
			} catch (IOException e) {
				Message.myLog(Message.ERR_MSG_006);
				e.printStackTrace();
				return;
			}
			
			/* TODO: get current time: Date vs Canendar.getInstance()? */

			Iterator<SelectionKey> iterators = mySelector.selectedKeys().iterator();

			while(iterators.hasNext()) {
				/* get SelectionKey */
				SelectionKey key = (SelectionKey) iterators.next();
				if (!key.isValid()) {
					/* 0. check valid() */
					iterators.remove();
					continue;
				}
				else if (key.isAcceptable()) {
					/* 1.accept() */
					acceptClient(key);
				}
				else if (key.isReadable()) {
					/* 2.read() */
					try {
					ChatMessage cMsg = readFromClient(key);
					if (cMsg != null) {
						processMessage(cMsg, key);
					}
					} catch (Exception e) {
						e.printStackTrace();
						SocketChannel socketChannel = (SocketChannel) key.channel();
						ClientInfo client = getClientBySocketChannel(socketChannel);
						delClient(client);
					}
				}
				else if(key.isWritable()) {
					/* 3.write() */
					bufferToSelectionKey(null, key);
				}
				iterators.remove();
			}
		} /* end while loop */
	}
	
	/** acceptClient: TCP.accept() for new client & add clientInfo(with nonBlocking readEvent) */
	private void acceptClient(SelectionKey key) {
		/* get ServerSocketChannel */
		ServerSocketChannel server = (ServerSocketChannel) key.channel();
		SocketChannel clientSocket = null;
		SelectionKey clientKey = null;
		try {
			/* get Client SocketChannel */
			clientSocket = server.accept();
		} catch (IOException e) {
			/* failed to accept().  */
			Message.myLog(Message.ERR_MSG_007);
			return;
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (clientSocket == null) {
				/* failed to accept(). */
				Message.myLog(Message.ERR_MSG_008);
				return;
			}
		}
		
		/* set nonBlocking, readEvent */
		try {
			clientSocket.configureBlocking(false);
			clientKey = clientSocket.register(mySelector, SelectionKey.OP_READ);
		} catch (IOException e) {
			/* failed to register() */
			Message.myLog(Message.ERR_MSG_009);
			try {
				clientSocket.close();
			}
			catch (IOException ce) {
				/* failed to close() */
				Message.myLog(Message.ERR_MSG_010);
			}
			return;
		}

		/* add NewClientInfo */
		ClientInfo client = addClientInfo(clientSocket);
		if (client == null) {
			/* failed to add(new client) */
			Message.myLog(Message.ERR_MSG_011);
			return;	
		}
		client.setSelectionKey(clientKey);
		try {
			client.setIp(clientSocket.getRemoteAddress().toString());
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		/* Now, new client connected.. */
		Message.myLog(Message.SYS_MSG_003 + " ClientAddress=" + client.getIp());
		
		return;
	}

	/** add new client to users(ArrayList) */
	public ClientInfo addClientInfo(SocketChannel clientSocket) {
		if (users == null) {
			users = new ArrayList<ClientInfo>();
			currentClients = 0;
		}
		
		if (currentClients >= MAX_CLIENT) {
			return null;
		}
		
		ClientInfo client = new ClientInfo();
		client.setSocketChannel(clientSocket);
		client.setStatus(ClientInfo.CLIENT_CONNECTED);
		
		users.add(client);
		currentClients++;
		if (currentClients == 1) {
			client.setAdmin(true);
		}
		
		return client;
	}
	
	/** get client from users ArrayList */
	public ClientInfo getClientBySocketChannel(SocketChannel clientSocket) {
		ClientInfo client = null;
		Iterator<ClientInfo> iterators = users.iterator();
		while (iterators.hasNext()) {
			client = iterators.next();
			if (Objects.equals(client.getSocketChannel(), clientSocket)) {
				return client;
			}
		}
		
		return null;
	}
	
	/** remove client from users ArrayList */
	private void delClient(ClientInfo oldClient) {
		if (oldClient == null) {
			return;
		}
		String sMsg = null;
		ByteBuffer encodedMsg = null;
		int payload = 0;
		ByteBuffer newMsg = null;
		String oldClientId = oldClient.getId();
		
		Message.myLog(Message.ERR_MSG_023 + " - " + oldClientId + ", " + oldClient.getSocketChannel().toString());
		users.remove(oldClient);
		initClient(oldClient);
		currentClients--;
		
		if (currentClients > 0) {
			/* notify to all Client: client logoff */
			sMsg = oldClientId + " has gone.";
			encodedMsg = charset.encode(sMsg);
			payload = encodedMsg.remaining();
			try {
				newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_MESSAGE, payload);
			} catch (ProtocolParsingException ppe) {
				ppe.printStackTrace();
				return;
			}
			newMsg.put(encodedMsg);
			newMsg.flip();
			processBroadcast(newMsg, null);
			
			
			/*ClientInfo firstClient = users.get(0);
			if (!firstClient.isAdmin()) {
				 //Admin is changed.. 
				Message.myLog(Message.SYS_MSG_006 + firstClient.getId());
				firstClient.setAdmin(true);
				
				 //notify to all Client 
				sMsg = Message.SYS_MSG_006 + firstClient.getId();
				encodedMsg = charset.encode(sMsg);
				payload = encodedMsg.remaining();
				newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_MESSAGE, payload);
				if (newMsg == null) {
					return;
				}
				newMsg.put(encodedMsg);
				newMsg.flip();
				processBroadcast(newMsg, null);
			}*/
		}
	}

	/** readFromClient: TCP.read() from client */
	private ChatMessage readFromClient(SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ClientInfo client = getClientBySocketChannel(socketChannel);
		if (client == null) {
			try {
				socketChannel.close();
			} catch (IOException e) {
				Message.myLog(Message.ERR_MSG_013 + " " + socketChannel.toString());
			}
			return null;
		}
		
		try {
			/* read from client */
			client.readFromSocket();
			if (client.getReadData().getStatus() == ChatMessage.READ_PAYLOAD_COMPLETE) {
				return client.getReadData();
			}
		} catch (IOException ie) {
			client.close();
			delClient(client);
		} catch (ProtocolParsingException ppe) {
			client.close();
			delClient(client);
		}
		
		return null;
	}

	/** writeToClient: TCP.write(msg) to client	*/
	private void bufferToSelectionKey(ByteBuffer msg, SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ClientInfo client = getClientBySocketChannel(socketChannel);
		if (client.getStatus() == ClientInfo.CLIENT_READY) {
			client.writeToSocket(msg);
		}
	}

	/** init ClientInfo */
	private void initClient(ClientInfo client) {
		client.setStatus(ClientInfo.CLIENT_NOTREADY);
		client.setId(null);
		client.setIp(null);
		
		client.getsKey().cancel();
		client.setSelectionKey(null);

		if (client.getSocketChannel().isOpen()) {
			try {
				client.getSocketChannel().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		client.setSocketChannel(null);
		
		client.initReadData();
		client.initWriteData();
	}

	/** process message */
	private void processMessage(ChatMessage chatMsg, SelectionKey fromKey) throws Exception {
		int msgtype = chatMsg.getMsgtype();
		ByteBuffer msgBuf = chatMsg.getMessageBuffer();
		switch (msgtype) {
		case ChatProtocol.MSGTYPE_REGISTER:
			/* Register */
			processRegister(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_PING:
			/* PING/PONG */
			processPing(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_REQUEST_MESSAGE:
			/* Normal chat */
			ChatMessage.setMsgtypeToHeader(msgBuf, ChatProtocol.MSGTYPE_REPLY_MESSAGE);
			msgBuf.rewind();
			processBroadcast(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_REQUEST_UPLOAD:
			/* Upload file */
			runSaveFileThread(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_TRANSFER_FILE:
			/* save file */
			saveFileContents(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_REQUEST_DOWNLOAD:
			/* Download file */
			processDownload(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_TAKE_FRIENDS:
			/* user info */
			processTakeFriends(msgBuf, fromKey);
			break;
		case ChatProtocol.MSGTYPE_REQUEST_KICKOUT:
			/* TODO: close the client by Admin */
			break;
		default:
			/* ChatProtocol.MSGTYPE_UNKNOWN */
			Message.myLog(Message.ERR_MSG_033);
		}
		
		chatMsg.initMessageBuffer();
	}
	
	/** download start */
	private void processDownload(ByteBuffer msg, SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ClientInfo client = getClientBySocketChannel(socketChannel);
		
		/* get receivedFileName */
		msg.position(ChatProtocol.HEADER_LEN);
   	String receivedFileName = ThreadForFile.DFLT_UPLOAD_PATH + charset.decode(msg).toString();
   	client.setUploadedFileName(receivedFileName);
   	
   	/* create lbq */
		LinkedBlockingQueue<ByteBuffer> lbq = new LinkedBlockingQueue<ByteBuffer>();
		client.setFileContents(lbq);
		lbq.add(msg);
		
		/* worker thread: fileChannel open and waiting */
		ThreadForFile worker = new ThreadForFile(client);
		client.setWorker(worker);
		worker.setName("workerForFileChannel");
		worker.start();
	}

	/** start thread for saving file */
	private void runSaveFileThread(ByteBuffer msg, SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ClientInfo client = getClientBySocketChannel(socketChannel);
		
		/* get receivedFileName and uploadedCount set */
		msg.position(ChatProtocol.HEADER_LEN);
		int uploadedCount = client.getUploadedCount() + 1;
   	String receivedFileName = uploadedCount + "_" + charset.decode(msg).toString();
   	client.setUploadedFileName(receivedFileName);
   	client.setUploadedCount(uploadedCount);
   	
   	/* create lbq */
		LinkedBlockingQueue<ByteBuffer> lbq = new LinkedBlockingQueue<ByteBuffer>();
		client.setFileContents(lbq);
		lbq.add(msg);
		
		/* worker thread: fileChannel open and waiting */
		ThreadForFile worker = new ThreadForFile(client);
		client.setWorker(worker);
		worker.setName("workerForFileChannel");
		worker.start();
	}

	/** save received file to disk */
	private void saveFileContents(ByteBuffer msg, SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ClientInfo client = getClientBySocketChannel(socketChannel);
		LinkedBlockingQueue<ByteBuffer> lbq = client.getFileContents();
		lbq.add(msg);
	}


	/** send all client info to client */
	private void processTakeFriends(ByteBuffer msg, SelectionKey key) throws ProtocolParsingException {
		String allClientsId = getAllClientsId();
		ByteBuffer encodedMsg = charset.encode(allClientsId);
		int size = encodedMsg.remaining();
		ByteBuffer bufMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_GIVE_FRIENDS, size);
		bufMsg.put(encodedMsg);
		bufMsg.flip();
		bufferToSelectionKey(bufMsg, key);		
	}

	/** get all client info */
	private String getAllClientsId() {
		StringBuilder ids = new StringBuilder("All Chat Users: (" + currentClients + ") ");
		ClientInfo client = null;
		Iterator<ClientInfo> iterators = users.iterator();
		while (iterators.hasNext()) {
			client = iterators.next();
			if (client.getStatus() == ClientInfo.CLIENT_READY) {
				ids.append(client.getId()).append(";");
			}
		}
		return ids.toString();
	}

	/** register */
	private void processRegister(ByteBuffer msg, SelectionKey key) throws ProtocolParsingException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		ByteBuffer encodedMsg = null;
		int payload = 0;
		
		/* check ID (중복체크, 겹치면?) */
		msg.position(ChatProtocol.HEADER_LEN);
		String id = charset.decode(msg).toString();
		String newId = id;
		boolean result = false;
		int i = 0;
		while (!result) {
			if (i > 0) {
				newId = id + i;
			}
			i++;
			result = checkClientId(newId);
		}
		
		ClientInfo client = getClientBySocketChannel(socketChannel);
		client.setStatus(ClientInfo.CLIENT_READY);
		client.setId(newId);
		
		Message.myLog(Message.SYS_MSG_007 + " " + newId);
		
		/* Register reply message */
		ChatMessage.setMsgtypeToHeader(msg, ChatProtocol.MSGTYPE_REGISTERED);
		if (id.equals(newId)) {
			/* received id is unique */
			msg.rewind();
			bufferToSelectionKey(msg, key);
		}
		else {
			/* received id is changed to newId */
			encodedMsg = charset.encode(newId);
			payload = encodedMsg.remaining();
			ByteBuffer newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REGISTERED, payload);
			newMsg.put(encodedMsg);
			newMsg.flip();
			bufferToSelectionKey(newMsg, key);
		}
		
		/* notify "new user entered" to all client */
		encodedMsg = charset.encode(Message.SYS_MSG_008 + newId);
		payload = encodedMsg.remaining();
		ByteBuffer newMsg = ChatMessage.makeNewMessage(ChatProtocol.MSGTYPE_REPLY_MESSAGE, payload);
		if (newMsg == null) {
			return;
		}
		newMsg.put(encodedMsg);
		newMsg.flip();
		processBroadcast(newMsg, null);
	}
	
	private boolean checkClientId(String id) {
		ClientInfo client = null;
		Iterator<ClientInfo> iterators = users.iterator();
		while (iterators.hasNext()) {
			client = iterators.next();
			if (client.getStatus() == ClientInfo.CLIENT_READY &&
					id.equals(client.getId())) {
				return false;
			}
		}
		return true;
	}
	
	/** ping/pong */
	private void processPing(ByteBuffer msg, SelectionKey key) {
		ChatMessage.setMsgtypeToHeader(msg, ChatProtocol.MSGTYPE_PONG);
		msg.rewind();
		bufferToSelectionKey(msg, key);
	}
	
	/** broadcast */
	private void processBroadcast(ByteBuffer msg, SelectionKey key) {
		ClientInfo client = null;
		Iterator<ClientInfo> iterators = users.iterator();
		while (iterators.hasNext()) {
			client = iterators.next();
			if (client.getStatus() == ClientInfo.CLIENT_READY) {
				msg.rewind(); /* init position */
				client.writeToSocket(msg);
			}
		}
	}
	
	/*
	private static void programExit(int type) {
		System.exit(type);
	} */
}
