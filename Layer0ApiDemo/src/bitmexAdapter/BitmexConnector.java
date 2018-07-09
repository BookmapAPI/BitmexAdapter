package bitmexAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

//import velox.api.layer0.live.DemoExternalRealtimeTradingProvider_2;
import velox.api.layer0.live.Provider;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.SystemTextMessageType;

public class BitmexConnector implements Runnable {

	public boolean interruptionNeeded = false;
	String wssUrl;
	String restApi;
	String restActiveInstrUrl;

	private HashMap<String, BmInstrument> activeBmInstrumentsMap = new HashMap<>();
	private CountDownLatch webSocketStartingLatch = new CountDownLatch(1);
	public CountDownLatch webSocketAuthLatch = new CountDownLatch(1);
	public ClientSocket socket;
	public JsonParser parser = new JsonParser();

	public boolean isReconnecting = false;

	public Provider provider;
	TradeConnector tradeConnector;

	public TradeConnector getTradeConnector() {
		return tradeConnector;
	}

	public void setTradeConnector(TradeConnector tradeConnector) {
		this.tradeConnector = tradeConnector;
	}

	public CountDownLatch getWebSocketStartingLatch() {
		return webSocketStartingLatch;
	}

	private boolean isConnectionEstablished() {
		if (getServerResponse(restApi) == null) {
			return false;
		}
		return true;
	}

	public void setWssUrl(String wssUrl) {
		this.wssUrl = wssUrl;
	}

	public void setRestApi(String restApi) {
		this.restApi = restApi;
	}

	public void setRestActiveInstrUrl(String restActiveInstrUrl) {
		this.restActiveInstrUrl = restActiveInstrUrl;
	}

	public String wssAuthTwo() {
		String method = "GET";
		String subPath = "/realtime";
		String orderAPiKEy = tradeConnector.getOrderApiKey();
		String orderApiSecret = tradeConnector.getOrderApiSecret();
		long moment = getMoment();

		String data = "";
		String res = null;

		try {
			String messageBody = createMessageBody(method, subPath, data, moment);
			String signature = generateSignature(orderApiSecret, messageBody);
			res = "{\"op\": \"authKey\", \"args\": [\"" + orderAPiKEy + "\", " + moment + ", \"" + signature + "\"]}";
		} catch (InvalidKeyException | NoSuchAlgorithmException e) {
		}
		return res;
	}

	public void wcConnect() {
		SslContextFactory ssl = new SslContextFactory();
		WebSocketClient client = new WebSocketClient(ssl);
		Log.info("BITM CONN ** WSCONNECT STARTED");

		try {
			Log.info("BITM CONN ** WSCONNECT SOCKET BEING CREATED");
			ClientSocket socket = new ClientSocket();
			this.socket = socket;
			this.parser.setActiveInstrumentsMap(Collections.unmodifiableMap(activeBmInstrumentsMap));
			this.socket.setParser(parser);
			parser.prov = this.provider;

			Log.info("BITM CONN ** WSCONNECT CLIENT STARTING");

			client.start();
			URI echoUri = new URI(wssUrl);
			ClientUpgradeRequest request = new ClientUpgradeRequest();

			Log.info("BITM CONN ** WSCONNECT CLIENT CONNECTTING");
			client.connect(socket, echoUri, request);
			this.socket.getOpeningLatch().await();

			if (provider.isCredentialsEmpty) {// no authentication is needed
				provider.adminListeners.forEach(
						l -> l.onSystemTextMessage("You are not subscribed to topics that require authentication",
								SystemTextMessageType.UNCLASSIFIED));
			} else {// authentication needed
				Log.info("BITM CONN ** WSCONNECT AUTH");
				String mes = wssAuthTwo();
				Log.info("AUTH MESSAGE PASSED");
				this.socket.sendMessage(mes);
				webSocketAuthLatch.await();
				socket.sendMessage(
						"{\"op\":\"subscribe\", \"args\":[\"position\",\"wallet\",\"margin\",\"execution\",\"order\"]}");
			}
			this.webSocketStartingLatch.countDown();
			Log.info("BITM CONN ** LATCH IS DOWN");

			if (isReconnecting) {
				provider.reportRestoredCoonection();

				for (BmInstrument instr : activeBmInstrumentsMap.values()) {
					if (instr.isSubscribed()) {
						subscribe(instr);
					}
				}
				isReconnecting = false;
			}

			Log.info("BITM CONN ** INSTRS SUBSCRIBED");

			// WAITING FOR THE SOCKET TO CLOSE
			socket.getClosingLatch().await();
			this.socket = null;
			isReconnecting = true;

		} catch (org.eclipse.jetty.websocket.api.UpgradeException e) {
			e.printStackTrace();
			Log.info("CONNECTOR: CLIENT CANNOT CONNECT");
		} catch (java.nio.channels.UnresolvedAddressException e) {
			e.printStackTrace();
			Log.info("CONNECTOR: CLIENT CANNOT CONNECT");
		} catch (org.eclipse.jetty.websocket.api.WebSocketException e) {
			e.printStackTrace();
			Log.info("CONNECTOR: CONNECTION MUST BE LOST");
		} catch (Exception | Error e) {
			Log.debug("CONNECTOR: THROWABLE THROWN FROM WEBSOCKET");
			throw new RuntimeException(e);
		} finally {
			try {
				client.stop();
			} catch (Exception e) {
				Log.debug("CLIENT STOPPING TROUBLE");
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
	}

	public void sendWebsocketMessage(String message) {
		try {
			getWebSocketStartingLatch().await();
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
		Log.info("BITM CONN  * SEND WS MESSG");
		synchronized (socket) {
			socket.sendMessage(message);
		}
	}

	private String getServerResponse(String address) {
		String response = null;

		try {
			URL url = new URL(address);
			HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");

			if (conn.getResponseCode() == 200) {
				BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
				StringBuilder sb = new StringBuilder("");
				String output = null;

				while ((output = br.readLine()) != null) {
					sb.append(output);
				}
				conn.disconnect();
				response = sb.toString();
			}
		} catch (UnknownHostException | NoRouteToHostException e) {
			Log.info("NO RESPONSE FROM SERVER");
		} catch (java.net.SocketException e) {
			Log.info("NETWORK IS UNREACHABLE");
		} catch (IOException e) {
			Log.debug("BUFFER READING ERROR");
			e.printStackTrace();
		}
		return response;
	}

	public void fillActiveBmInstrumentsMap() {
		synchronized (activeBmInstrumentsMap) {
			String str = getServerResponse(restActiveInstrUrl);
			if (str == null)
				return;

			BmInstrument[] instrs = JsonParser.getArrayFromJson(str, BmInstrument[].class);

			for (BmInstrument instr : instrs) {
				this.activeBmInstrumentsMap.put(instr.getSymbol(), instr);
			}
			activeBmInstrumentsMap.notify();
		}
	}

	public HashMap<String, BmInstrument> getActiveInstrumentsMap() {
		return activeBmInstrumentsMap;
	}

	private void launchSnapshotTimer(BitmexConnector connector, BmInstrument instr) {
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				if (!instr.getPartialsParsed().contains("orderBookL2")) {
					connector.unSubscribe(instr);
					connector.subscribe(instr);
				}
			}
		};
		Timer timer = new Timer();
		timer.schedule(task, 10000);
	}

	public void subscribe(BmInstrument instr) {
		Log.info("BITM CONN INST TO SUBSCRIBE " + instr.getSymbol());
		instr.setSubscribed(true);

		sendWebsocketMessage(instr.getSubscribeReq());
		
//		this is should normally be called in general.
//		better to turn on off while debugging
//		launchSnapshotTimer(this, instr);

		if (!provider.isCredentialsEmpty) {// if authenticated
			instr.setExecutionsVolume(countExecutionsVolume(instr.getSymbol()));
			reportFilled(instr.getSymbol());
			reportCancelled(instr.getSymbol());

		}
	}

	public void unSubscribe(BmInstrument instr) {
		instr.setSubscribed(false);
		sendWebsocketMessage(instr.getUnSubscribeReq());
	}

	public static long getMoment() {
		return System.currentTimeMillis() + 10000;
	}

	public static String hash256(String data) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(data.getBytes());
		return bytesToHex(md.digest());
	}

	public static String bytesToHex(byte[] bytes) {
		StringBuffer result = new StringBuffer();
		for (byte byt : bytes)
			result.append(Integer.toString((byt & 0xff) + 0x100, 16).substring(1));
		return result.toString();
	}

	public static String createMessageBody(String method, String path, String data, long moment) {
		String messageBody = method + path + Long.toString(moment) + data;
		Log.info("messageBody\t" + messageBody);
		return messageBody;
	}

	public static String generateSignature(String apiSecret, String messageBody)
			throws NoSuchAlgorithmException, InvalidKeyException {
		Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
		SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(), "HmacSHA256");
		sha256_HMAC.init(secretKey);
		byte[] hash = sha256_HMAC.doFinal(messageBody.getBytes());
		String check = Hex.encodeHexString(hash);
		Log.info("signature\t" + check);
		return check;
	}

	private int countExecutionsVolume(String symbol) {
		String z = ConnectorUtils.getDateTwentyFourHoursAgoAsUrlEncodedString0();
		System.out.println("Z = " + z);
		int sum = 0;
		// long moment = ConnectorUtils.getMomentAndTimeToLive();
		String addr = "/api/v1/execution?symbol=" + symbol
				+ "&filter=%7B%22ordStatus%22%3A%22Filled%22%7D&count=100&reverse=false&startTime=" + z;
		// String messagebody = ConnectorUtils.createMessageBody("GET", addr,
		// "", moment);
		// String sign =
		// ConnectorUtils.generateSignature(tradeConnector.getOrderApiSecret(),
		// messagebody);
		//
		// Log.info("*** ADDR " + addr);
		// Log.info("*** MBDY " + messagebody);
		// Log.info("*** SIGN " + sign);
		// String st1 = tradeConnector.get("https://testnet.bitmex.com" + addr,
		// tradeConnector.getOrderApiKey(), sign, moment);

		String st0 = tradeConnector.makeRestGetQuery(addr);
		UnitOrder[] orders = JsonParser.getArrayFromJson(st0, UnitOrder[].class);
		if (orders != null && orders.length > 0) {
			for (UnitOrder order : orders) {
				sum += order.getOrderQty();
				// Log.info("VOLUME ELEMENT " + order.getOrderQty());
			}
		}

		System.out.println("=> " + st0);
		return sum;
	}

	private void reportFilled(String symbol) {
		String z = ConnectorUtils.getDateTwentyFourHoursAgoAsUrlEncodedString0();
		String addr = "/api/v1/execution?symbol=" + symbol
				+ "&filter=%7B%22ordStatus%22%3A%20%22Filled%22%7D&count=500&reverse=true&startTime=" + z;
		String st0 = tradeConnector.makeRestGetQuery(addr);

		UnitExecution[] execs = JsonParser.getArrayFromJson(st0, UnitExecution[].class);
		if (execs != null && execs.length > 0) {
			provider.updateExecutionsHistory(execs);
		}

		System.out.println("=> " + st0);
	}

	private void reportCancelled(String symbol) {
		String z = ConnectorUtils.getDateTwentyFourHoursAgoAsUrlEncodedString0();
		String addr = "/api/v1/execution?symbol=" + symbol
				+ "&filter=%7B%22ordStatus%22%3A%20%22Canceled%22%7D&count=500&reverse=true&startTime=" + z;
		String st0 = tradeConnector.makeRestGetQuery(addr);

		UnitExecution[] execs = JsonParser.getArrayFromJson(st0, UnitExecution[].class);
		if (execs != null && execs.length > 0) {
			provider.updateExecutionsHistory(execs);
		}

		System.out.println("=> " + st0);
	}

	@Override
	public void run() {
		while (!interruptionNeeded) {

			if (!isConnectionEstablished()) {
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					throw new RuntimeException();
				}
				continue;
			}

			if (this.activeBmInstrumentsMap.isEmpty()) {
				fillActiveBmInstrumentsMap();
				if (this.activeBmInstrumentsMap.isEmpty())
					continue;
			}
			if (!interruptionNeeded) {
				wcConnect();
			}
			if (!interruptionNeeded) {
				provider.reportLostCoonection();
			}

		}
		if (socket != null) {
			socket.close();
		}
		Log.info("BM CONNECTOR CLOSES");
	}
	// @Override
	// public void run() {
	// while (!Thread.currentThread().isInterrupted()) {
	//
	//
	// if (!isConnectionEstablished()) {
	// try {
	// Thread.sleep(5000);
	// } catch (InterruptedException e) {
	// e.printStackTrace();
	// throw new RuntimeException();
	// }
	// continue;
	// }
	//
	// if (this.activeBmInstrumentsMap.isEmpty()) {
	// fillActiveBmInstrumentsMap();
	// if (this.activeBmInstrumentsMap.isEmpty())
	// continue;
	// }
	// wSconnect();
	//
	// }
	// socket.close();
	// Log.info("BM CONNECTOR CLOSES");
	// }

}
