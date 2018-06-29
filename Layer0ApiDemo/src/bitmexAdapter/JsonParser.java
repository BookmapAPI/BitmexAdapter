package bitmexAdapter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import bitmexAdapter.ConnectorUtils.Topic;
import velox.api.layer0.live.Provider;
import velox.api.layer1.common.Log;
import velox.api.layer1.layers.utils.OrderBook;

public class JsonParser {
	public Provider prov;

	public static <T> T[] getArrayFromJson(String input, Class<T[]> cls) {
		return (T[]) new Gson().fromJson(input, cls);
	}

	public static <T> ArrayList<T> getGenericFromMessage(String input, Class<T> cls) {
		Type type = new TypeToken<MessageGeneric<T>>() {
		}.getType();
		MessageGeneric<T> msg0 = gson.fromJson(input, type);
		ArrayList<T> dataUnits = msg0.getData();
		return dataUnits;
	}

	public static final Gson gson = new GsonBuilder().create();
	// private Set<String> partialsParsed = new HashSet<>();

	private Map<String, BmInstrument> activeInstrumentsMap = new HashMap<>();
	private Set<String> nonInstrumentPartialsParsed = new HashSet<>();

	public void setNonInstrumentPartialsParsed(Set<String> nonInstrumentPartialsParsed) {
		this.nonInstrumentPartialsParsed = nonInstrumentPartialsParsed;
	}

	public void setActiveInstrumentsMap(Map<String, BmInstrument> activeInstrumentsMap) {
		this.activeInstrumentsMap = activeInstrumentsMap;
	}

	public void parse(String str) {

		// Log.info("PARSER STR => " + str);
		// first let's find out what kind of object we have here
		Answer answ = (Answer) gson.fromJson(str, Answer.class);
		// Log.info("PARSER ANSW Error " + answ.getError());
		if (answ.getTable() == null) {
			Log.info("PARSER STR => " + str);

			if (answ.getInfo() != null) {
				return;
			}

			if (answ.getStatus() != null && answ.getStatus() != 200) {
				prov.connector.socket.close();
				Log.info(answ.getError());
				prov.reportWrongCredentials(answ.getError());
				return;
			}

			if (answ.getSuccess() == true && answ.getRequest().getOp().equals("authKey")) {
				prov.connector.webSocketAuthLatch.countDown();
			}

			if (answ.getSuccess() == null && answ.getError() == null && answ.getTable() == null
					&& answ.getInfo() == null) {
				Log.info("PARSER FAILS TO PARSE " + str);
				throw new RuntimeException();
			}

			if (answ.getSuccess() != null || answ.getInfo() != null) {
				Log.info("PARSER service MSG " + str);
				return;
			}

			if (answ.getError() != null) {
				Log.info("PARSER ERROR MSG " + str);
				BmErrorMessage error = new Gson().fromJson(str, BmErrorMessage.class);
				Log.info(error.getMessage());
				return;
			}
			return;
		}

		// Options 'No object', 'success' and 'error' are already excluded
		// so only 'message' object (that contains 'data', an array of objects)
		// stays
		Message msg = (Message) gson.fromJson(str, Message.class);

		// skip a messages if it contains empty data
		if (msg.getData() == null) {
			Log.info("PARSER SKIPS (DATA == NULL ) " + str);

		}

		if (ConnectorUtils.stringToTopic.keySet().contains(msg.getTable())) {
			Topic Topic = ConnectorUtils.stringToTopic.get(msg.getTable());
			func(str, Topic);

		}
	}

	/**
	 * data units in the snapshot are sorted from highest price to lowest This
	 * may result in a huge red bestAsk peak on the screen because for a couple
	 * of milliseconds the bestAsk is actually the most expensive ask What is
	 * even worse, to fit this peak to the screen the picture gets zoomed out
	 * which looks pretty weird To avoid this bestAsk is moved to the beginning
	 * of the list
	 **/
	private ArrayList<DataUnit> putBestAskToTheHeadOfList(ArrayList<DataUnit> units) {
		if (units.size() < 2)
			return units;

		int firstAskIndex = 0;

		for (int i = 0; i < units.size(); i++) {
			if (units.get(i + 1).isBid()) {
				firstAskIndex = i;
				break;
			}
		}
		if (firstAskIndex != 0) {
			Collections.swap(units, 0, firstAskIndex);
		}
		return units;
	}

	/*
	 * setting missing values for dataunits' fields adding missing prices to
	 * <id,intPrice> map updating the orderBook This refers to order book
	 * updates only BmTrade orders are processed in processTradeMsg method
	 */
	private void processOrderMessage(MessageGeneric<DataUnit> msg) {
		BmInstrument instr = activeInstrumentsMap.get(msg.getData().get(0).getSymbol());
		OrderBook book = instr.getOrderBook();

		for (DataUnit unit : msg.getData()) {
			unit.setBid(unit.getSide().equals("Buy"));
			HashMap<Long, Integer> pricesMap = instr.getPricesMap();
			int intPrice;

			if (msg.getAction().equals("delete")) {
				intPrice = pricesMap.get(unit.getId());
				unit.setSize(0);
			} else {
				if (msg.getAction().equals("update")) {
					intPrice = pricesMap.get(unit.getId());
				} else {// action is partial or insert
					// intPrice = createIntPrice(unit.getPrice(),
					// instr.getTickSize());
					intPrice = (int) Math.round(unit.getPrice() / instr.getTickSize());
					pricesMap.put(unit.getId(), intPrice);
				}
			}
			unit.setIntPrice(intPrice);
			book.onUpdate(unit.isBid(), intPrice, unit.getSize());
		}
	}

	// private int createIntPrice(double price, double tickSize) {
	// int intPrice = (int) Math.round(price / tickSize);
	// return intPrice;
	// }

	private void processTradeUnit(BmTrade unit) {
		unit.setBid(unit.getSide().equals("Buy"));
		BmInstrument instr = activeInstrumentsMap.get(unit.getSymbol());
		// int intPrice = createIntPrice(unit.getPrice(), instr.getTickSize());
		int intPrice = (int) Math.round(unit.getPrice() / instr.getTickSize());
		unit.setIntPrice(intPrice);
	}

	/**
	 * resets orderBooks (both for Bookmap and for BmInstrument) after
	 * disconnect and reconnect For better visualization purposes besAsk and
	 * bestBid will go last in this method and come first in
	 * putBestAskToTheHeadOfList method (see the description for
	 * putBestAskToTheHeadOfList method)
	 **/
	private void resetBookMapOrderBook(BmInstrument instr) {
		// Extracting lists of levels from ask and Bid maps
		String symbol = instr.getSymbol();
		ArrayList<DataUnit> units = new ArrayList<>();

		TreeMap<Integer, Long> askMap = instr.getOrderBook().getAskMap();
		ArrayList<Integer> askList = new ArrayList<>(askMap.keySet());
		Collections.sort(askList, Collections.reverseOrder());
		int i = askList.size() - 1;
		int bestAsk = askList.get(i);
		askList.remove(i);

		for (Integer intPrice : askList) {
			units.add(new DataUnit(symbol, intPrice, false));
		}

		TreeMap<Integer, Long> bidMap = instr.getOrderBook().getBidMap();
		ArrayList<Integer> bidList = new ArrayList<>(bidMap.keySet());
		Collections.sort(bidList);
		i = bidList.size() - 1;
		int bestBid = bidList.get(i);
		bidList.remove(bidList.get(i));

		for (Integer intPrice : bidList) {
			units.add(new DataUnit(symbol, intPrice, true));
		}

		units.add(new DataUnit(symbol, bestAsk, false));
		units.add(new DataUnit(symbol, bestBid, true));

		MessageGeneric<DataUnit> mess = new MessageGeneric<>("orderBookL2", "delete", DataUnit.class, units);
		for (DataUnit unit : mess.getData()) {
			prov.listenOnOrderBookL2(unit);
		}
		// prov.listenOrderOrTrade(mess);
	}

	private void resetBmInstrumentOrderBook(BmInstrument instr) {
		OrderBook book = instr.getOrderBook();
		TreeMap<Integer, Long> askMap = instr.getOrderBook().getAskMap();
		Set<Integer> askSet = new HashSet<>(askMap.keySet());
		for (Integer intPrice : askSet) {
			book.onUpdate(false, intPrice, 0);
		}

		TreeMap<Integer, Long> bidMap = instr.getOrderBook().getAskMap();
		Set<Integer> bidSet = new HashSet<>(bidMap.keySet());
		for (Integer intPrice : bidSet) {
			book.onUpdate(true, intPrice, 0);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> void func(String str, Topic topic) {
		TopicContainer container = ConnectorUtils.containers.get(topic);
		Type type = container.unitType;
		MessageGeneric<T> msg0 = gson.fromJson(str, type);

		if (msg0.getAction().equals("partial")) {
			nonInstrumentPartialsParsed.add(container.name);
//			if (msg0.getData().isEmpty()) {
//				Log.info("PARSER SKIPS (DATA == [] ) " + str);
//				return;
//			}
			if (topic.equals(Topic.ORDERBOOKL2)) {
				performOrderBookL2SpecificOpSetOne((MessageGeneric<DataUnit>) msg0);
			}
		}

		if (nonInstrumentPartialsParsed.contains(container.name)) {
			if (topic.equals(Topic.ORDER)) {
				performOrderSpecificOp();
				Log.info("PARSER WS ORD " + str);
			}

			if (msg0.getData().isEmpty()) {
				Log.info("PARSER SKIPS (DATA == [] ) " + str);
				return;
			}
			
			ArrayList<T> units = (ArrayList<T>) msg0.getData();

			if (topic.equals(Topic.ORDERBOOKL2) && !units.isEmpty()) {
				performOrderBookL2SpecificOpSetTwo((MessageGeneric<DataUnit>) msg0);
			}

			if (!units.isEmpty()) {
				dispatchRawUnits(units, container.clazz);
			}

			if (topic.equals(Topic.EXECUTION)) {
				Log.info("PARSER WS EXEC " + str);
				write(str);

			}

		}
		return;
	}

	private void performOrderSpecificOp() {
		nonInstrumentPartialsParsed.remove("order");
		Log.info("PARSER : ORDER REMOVED FROM PARTIALS.PARSED");
		// we need only the snapshot.
		// the rest of info comes from execution Topic.
		// it will be a good idea to get unsubscribed from orders
		// right at this point.
	}

	private void performOrderBookL2SpecificOpSetOne(MessageGeneric<DataUnit> msg) {
		BmInstrument instr = activeInstrumentsMap.get(msg.getData().get(0).getSymbol());
		if (!instr.getOrderBook().getAskMap().isEmpty()) {
			// orderbook is filled already (after reconnect).
			// reset the book after reconnect
			resetBookMapOrderBook(instr);
			resetBmInstrumentOrderBook(instr);
		}
	}

	private void performOrderBookL2SpecificOpSetTwo(MessageGeneric<DataUnit> msg) {
		processOrderMessage(msg);

		if (msg.getAction().equals("partial")) {
			msg.setData(putBestAskToTheHeadOfList(msg.getData()));
		}
	}

	public <T> void dispatchRawUnits(ArrayList<T> units, Class<?> clazz) {
		// Log.info("PARSER DISPATCH NEXT");
		for (T unit : units) {
			if (clazz == Wallet.class) {
				prov.listenToWallet((Wallet) unit);
			} else if (clazz == Execution.class) {
				prov.listenToExecution((Execution) unit);
			} else if (clazz == Margin.class) {
				prov.listenToMargin((Margin) unit);
			} else if (clazz == Position.class) {
				prov.listenToPosition((Position) unit);
			} else if (clazz == BmOrder.class) {
				BmOrder ord = (BmOrder) unit;
				Log.info("PARSER DISPATCH ORD ID " + ord.getOrderID());
				prov.createBookmapOrder((BmOrder) unit);
			} else if (clazz == BmTrade.class) {
				// specific
				processTradeUnit((BmTrade) unit);
				prov.listenOnTrade((BmTrade) unit);
			} else if (clazz == DataUnit.class) {
				prov.listenOnOrderBookL2((DataUnit) unit);
			}
		}

	}

	public void write(String str) {
		
		try(BufferedWriter bw = new BufferedWriter(new FileWriter("C:\\Bm.log", true));) {
			bw.write(str);
			bw.write("\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
}
