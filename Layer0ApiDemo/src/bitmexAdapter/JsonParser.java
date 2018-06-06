package bitmexAdapter;

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

import quickfix.RuntimeError;
import velox.api.layer0.live.Provider;
import velox.api.layer1.common.Log;
import velox.api.layer1.layers.utils.OrderBook;

public class JsonParser {
	public Provider prov;

	public static <T> T[] getArrayFromJson(String input, Class<T[]> cls) {
		return (T[]) new Gson().fromJson(input, cls);
	}

	// public static <T> ArrayList<T> getGenericFromMessage(String input,
	// Class<T> cls) {
	// Type type = new TypeToken<MessageGeneric<T>>() {
	// }.getType();
	// MessageGeneric<T> msg0 = gson.fromJson(input, type);
	// ArrayList<T> dataUnits = msg0.getData();
	// return dataUnits;
	// }

	private static final Gson gson = new GsonBuilder().create();
	private Map<String, Boolean> partialsParsed = new HashMap<>();

	private Map<String, BmInstrument> activeInstrumentsMap = new HashMap<>();

	public void setActiveInstrumentsMap(Map<String, BmInstrument> activeInstrumentsMap) {
		this.activeInstrumentsMap = activeInstrumentsMap;
	}

	public void parse(String str) {
		Log.info(str);
		// first let's find out what kind of object we have here
		Answer answ = (Answer) gson.fromJson(str, Answer.class);
		if (answ.getSuccess() == null && answ.getError() == null && answ.getTable() == null && answ.getInfo() == null) {
			Log.info("PARSER FAILS TO PARSE " + str);
			throw new RuntimeException();
		}

		if (answ.getSuccess() != null || answ.getInfo() != null) {
			Log.info("PARSER service MSG " + str);
			return;
		}

		if (answ.getError() != null) {
			Log.info("PARSER ERROR MSG " + str);
			return;
		}

		// Options 'No object', 'success' and 'error' are already excluded
		// so only 'message' object (that contains 'data', an array of objects)
		// stays
		Message msg = (Message) gson.fromJson(str, Message.class);

		// skip a messages if it contains empty data
		if (msg.getData() == null) {
			Log.info("PARSER SKIPS " + str);
			return;
		}
		// if (answ.getData() == null || answ.getData().equals("") ||
		// answ.getData().equals("[]") ) {
		// Log.info("PARSER SKIPS " + str);
		// return;
		// }

		// if (!msg.getTable().equals("orderBookL2") &&
		// !msg.getTable().equals("trade")
		// && !msg.getTable().equals("execution") &&
		// !msg.getTable().equals("position")
		// && !msg.getTable().equals("wallet") &&
		// !msg.getTable().equals("order")
		// && !msg.getTable().equals("margin")) {
		// // Log.info("PARSER WS TABLE = " + msg.getTable());
		// return;
		// }

		if (answ.getTable().equals("wallet")) {
			if (answ.getAction().equals("partial")) {
				partialsParsed.put("wallet", true);
			}

			if (partialsParsed.get("wallet") == true) {
				Log.info("PARSER WS WALLET " + str);
				Type type = new TypeToken<MessageGeneric<Wallet>>() {
				}.getType();
				MessageGeneric<Wallet> msg0 = gson.fromJson(str, type);
				ArrayList<Wallet> wallets = msg0.getData();

				if (wallets.size() > 0) {
					for (Wallet wallet : wallets) {
						prov.listenToWallet(wallet);
					}
				}
				// processWalletMessage(wallets);
			}
			return;
		}

		if (msg.getTable().equals("execution")) {
			// Log.info("PARSER WS EXECUTION " + str);
			if (answ.getAction().equals("partial")) {
				partialsParsed.put("execution", true);
			}

			if (partialsParsed.get("execution").equals(true)) {
				Type type = new TypeToken<MessageGeneric<Execution>>() {
				}.getType();
				MessageGeneric<Execution> msg0 = gson.fromJson(str, type);
				ArrayList<Execution> executions = msg0.getData();

				if (executions.size() > 0) {
					for (Execution execution : executions) {
						prov.listenToExecution(execution);
					}
				}
			}
			return;
		}

		if (msg.getTable().equals("margin")) {
			// Log.info("PARSER WS MARGIN " + str);
			Type type = new TypeToken<MessageGeneric<Margin>>() {
			}.getType();
			@SuppressWarnings("unchecked")
			MessageGeneric<Margin> msg0 = gson.fromJson(str, type);
			processMarginMessage(msg0);
			return;
		}

		if (msg.getTable().equals("position")) {
			Log.info("PARSER WS POSITION " + str);
			MessagePosition msgPos = (MessagePosition) gson.fromJson(str, MessagePosition.class);
			processPositionMessage(msgPos);
			return;
		}

		if (msg.getTable().equals("order")) {
			Log.info("PARSER WS ORDER " + str);
			// MessagePosition msgPos = (MessagePosition) gson.fromJson(str,
			// MessagePosition.class);
			// processPositionMessage(msgPos);
			return;
		}

		try {
			BmInstrument instr = activeInstrumentsMap.get(msg.getData().get(0).getSymbol());
		} catch (IndexOutOfBoundsException e) {
			Log.info("PARSER ERROR FOR " + str);
		}

		BmInstrument instr = activeInstrumentsMap.get(msg.getData().get(0).getSymbol());
		if (!instr.isSubscribed()) {
			return;
		}

		if (!instr.isFirstSnapshotParsed()) {
			if (msg.action.equals("partial")) {
				// action is partial so let's fill in the orderBook
				if (!instr.getOrderBook().getAskMap().isEmpty()) {
					// reset the book after reconnect
					resetBookMapOrderBook(instr);
					resetBmInstrumentOrderBook(instr);
				}
				processOrderMessage(msg);
				msg.setData(putBestAskToTheHeadOfList(msg.getData()));
				// instr.getQueue().add(msg);

				// ***********
				instr.setFirstSnapshotParsed(true);
				// this is the trigger for parser to start
				// processing every message

				prov.listenOrderOrTrade(msg);
			} else {
				return; // otherwise wait for partial
			}
		} else if (!msg.getTable().equals("execution")) {
			if (msg.getTable().equals("trade")) {
				// Log.info(str);
				processTradeMessage(msg);
			} else {
				processOrderMessage(msg);
			}
			// instr.getQueue().add(msg);

			// ***********
			prov.listenOrderOrTrade(msg);

			// } else {// table = execution
			// MessageExecution msgExec = (MessageExecution) gson.fromJson(str,
			// MessageExecution.class);
			// processExecutionMessage(msgExec);
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
	 * updates only Trade orders are processed in processTradeMsg method
	 */
	private void processOrderMessage(Message msg) {
		BmInstrument instr = activeInstrumentsMap.get(msg.data.get(0).getSymbol());
		OrderBook book = instr.getOrderBook();

		for (DataUnit unit : msg.data) {
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
					intPrice = createIntPrice(unit.getPrice(), instr.getTickSize());
					pricesMap.put(unit.getId(), intPrice);
				}
			}
			unit.setIntPrice(intPrice);
			book.onUpdate(unit.isBid(), intPrice, unit.getSize());
		}
	}

	private int createIntPrice(double price, double tickSize) {
		// BigDecimal pr = new BigDecimal(price, MathContext.DECIMAL32);
		// BigDecimal ts = new BigDecimal(tickSize, MathContext.DECIMAL32);
		// BigDecimal res = pr.divide(ts, 0, BigDecimal.ROUND_HALF_UP);
		// int intPrice = res.intValue();

		int intPrice = (int) Math.round(price / tickSize);
		// Log.info(price + "=>" + intPrice);
		return intPrice;
	}

	private void processTradeMessage(Message msg) {
		BmInstrument instr = activeInstrumentsMap.get(msg.data.get(0).getSymbol());

		for (DataUnit unit : msg.data) {
			unit.setBid(unit.getSide().equals("Buy"));
			int intPrice = createIntPrice(unit.getPrice(), instr.getTickSize());
			unit.setIntPrice(intPrice);
		}
	}



	private void processPositionMessage(MessagePosition msgPos) {
		// Log.info("EXEC MSG PROCESSED");

		for (Position pos : msgPos.data) {
			// BmInstrument instr = activeInstrumentsMap.get(order.getSymbol());
			// instr.getPositionQueue().add(order);
			prov.listenToPosition(pos);
		}

		// Log.info("EXEC MSG PROCESSED ADDED TO THE QUEUE");
	}

	private void processMarginMessage(MessageGeneric<Margin> msg0) {
		ArrayList<Margin> arr = (ArrayList<Margin>) msg0.getData();
		// LinkedTreeMap <Wallet> arr = msg0.getData();

		for (Margin marg : arr) {
			prov.listenToMargin((Margin) marg);
		}
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

		Message mess = new Message("orderBookL2", "delete", units);
		prov.listenOrderOrTrade(mess);
		// instr.getQueue().add(mess);
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
}
