package jang.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;

public class ResourceInfo {
	
	public static int CHAT_TYPE_SERVER = 1;
	public static int CHAT_TYPE_CLIENT = 2;

	public static void infoSystemProperties(int type) {
		/* properties, env */
		String osName = System.getProperty("os.name");
		String userName = System.getProperty("user.name");
		String userHome = System.getProperty("user.home");
		String curDir = System.getProperty("user.dir");
		String javaVersion = System.getProperty("java.version");
		String javaHome = System.getProperty("java.home");
		String javaHome2 = System.getenv("JAVA_HOME");
		int cpuCnt = Runtime.getRuntime().availableProcessors();
		InetAddress myAddr = null;
		InetAddress[] addrs = null;
		//Enumeration<NetworkInterface> myNifs = null;
		
		try {
			myAddr = InetAddress.getLocalHost();
			addrs = InetAddress.getAllByName(myAddr.getHostName());
			//myNifs = NetworkInterface.getNetworkInterfaces();
		} catch (UnknownHostException uhe) {
			Message.myLog(Message.ERR_MSG_014);
		//} catch (SocketException se) {
		//	Message.myLog(Message.ERR_MSG_015);
		}
		
		FileSystem fileSystem = FileSystems.getDefault();
		
		Message.myLog("================================");
		String sType = "Server";
		if (type == CHAT_TYPE_CLIENT) {
			sType = "Client";
		}
		Message.myLog("Information for Chatting " + sType);
		Message.myLog("OS: " + osName);
		Message.myLog("User: " + userName);
		Message.myLog("HomeDir: " + userHome);
		Message.myLog("CurrentDir: " + curDir);
		Message.myLog("java version: " + javaVersion);
		Message.myLog("java home: " + javaHome);
		Message.myLog("JAVA_HOME: " + javaHome2);
		Message.myLog("CPUS: " + cpuCnt);
		Message.myLog("FileSystem: " + fileSystem);
		
		if (addrs != null) {
			Message.myLog("HostName: " + myAddr.getHostName());
			for (InetAddress addr : addrs) {
				Message.myLog("IPAddress: " + addr.getHostAddress());
			}
		}
		
/*		if (myNifs != null) {  TODO 
			while(myNifs.hasMoreElements()) {
			  NetworkInterface n = (NetworkInterface) myNifs.nextElement();
			  Enumeration<InetAddress> ee = n.getInetAddresses();
			  while (ee.hasMoreElements()) {
			    InetAddress i = (InetAddress) ee.nextElement();
			    System.out.println(i.getHostAddress());
			    }
			}
		}*/
		Message.myLog("================================");
		Message.myLog("YoungHwi's Chatting " + sType + " is starting..");
	}
}
