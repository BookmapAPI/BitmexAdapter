package velox.api.layer0.live;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer1.Layer1ApiAdminListener;
import velox.api.layer1.Layer1ApiDataListener;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.BalanceInfo;
import velox.api.layer1.data.BalanceInfo.a;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.InstrumentInfoCrypto;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeatures;
import velox.api.layer1.data.LoginData;
import velox.api.layer1.data.LoginFailedReason;
import velox.api.layer1.data.OrderCancelParameters;
import velox.api.layer1.data.OrderDuration;
import velox.api.layer1.data.OrderInfoBuilder;
import velox.api.layer1.data.OrderMoveParameters;
import velox.api.layer1.data.OrderResizeParameters;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.data.OrderStatus;
import velox.api.layer1.data.OrderType;
import velox.api.layer1.data.OrderUpdateParameters;
import velox.api.layer1.data.SimpleOrderSendParameters;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.data.SystemTextMessageType;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.data.UserPasswordDemoLoginData;

import bitmexAdapter.BitmexConnector;
import bitmexAdapter.DataUnit;
import bitmexAdapter.Execution;
import bitmexAdapter.Message;
import bitmexAdapter.Position;
import bitmexAdapter.TradeConnector;
import bitmexAdapter.Wallet;
import java_.lang.reflect.Field_;
import bitmexAdapter.BmInstrument;
import bitmexAdapter.BmOrder;

//@Layer0LiveModule
public class Provider extends ExternalLiveBaseProvider {

	public BitmexConnector connector = new BitmexConnector();
	public TradeConnector connr = new TradeConnector();
	private String tempClientId;
	private HashMap<String, OrderInfoBuilder> workingOrders = new HashMap<>();
	private long orderCount = 0;
	private Wallet validWallet = new Wallet();

	protected class Instrument {

		protected final String alias;
		protected final double pips;

		public Instrument(String alias, double pips) {
			this.alias = alias;
			this.pips = pips;
		}
	}

	protected HashMap<String, Instrument> instruments = new HashMap<>();

	// This thread will perform data generation.
	private Thread connectionThread = null;

	/**
	 * <p>
	 * Generates alias from symbol, exchange and type of the instrument. Alias
	 * is a unique identifier for the instrument, but it's also used in many
	 * places in UI, so it should also be easily readable.
	 * </p>
	 * <p>
	 * Note, that you don't have to use all 3 fields. You can just ignore some
	 * of those, for example use symbol only.
	 * </p>
	 */
	private static String createAlias(String symbol, String exchange, String type) {
		return symbol;
	}

	@Override
	public void subscribe(String symbol, String exchange, String type) {

		String alias = createAlias(symbol, exchange, type);
		// Since instruments also will be accessed from the data generation
		// thread, synchronization is required
		//
		// No need to worry about calling listener from synchronized block,
		// since those will be processed asynchronously
		synchronized (instruments) {

			if (instruments.containsKey(alias)) {
				instrumentListeners.forEach(l -> l.onInstrumentAlreadySubscribed(symbol, exchange, type));
			} else {
				// We are performing subscription synchronously for simplicity,
				// but if subscription process takes long it's better to do it
				// asynchronously (e.g use Executor)

				// This is delivered after REST query response
				// connector.getWebSocketStartingLatch();//why?
				HashMap<String, BmInstrument> activeBmInstruments = this.connector.getActiveInstrumentsMap();
				Set<String> set = new HashSet<>();

				synchronized (activeBmInstruments) {
					if (activeBmInstruments.isEmpty()) {
						try {
							activeBmInstruments.wait();// waiting for the
														// instruments map to be
														// filled...
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					for (String key : activeBmInstruments.keySet()) {
						set.add(key);// copying map's keyset to a new set
					}
				}

				if (set.contains(symbol)) {
					// try {
					// this.connector.getWebSocketStartingLatch().await();
					// } catch (InterruptedException e) {
					// e.printStackTrace();
					// }
					BmInstrument instr = activeBmInstruments.get(symbol);
					double pips = instr.getTickSize();

					final Instrument newInstrument = new Instrument(alias, pips);
					instruments.put(alias, newInstrument);
					final InstrumentInfo instrumentInfo = new InstrumentInfo(symbol, exchange, type, newInstrument.pips,
							1, "", false);

					instrumentListeners.forEach(l -> l.onInstrumentAdded(alias, instrumentInfo));
					this.connector.subscribe(instr);
				} else {
					instrumentListeners.forEach(l -> l.onInstrumentNotFound(symbol, exchange, type));
				}
			}
		}
	}

	@Override
	public void unsubscribe(String alias) {

		synchronized (instruments) {
			if (instruments.remove(alias) != null) {
				instrumentListeners.forEach(l -> l.onInstrumentRemoved(alias));
			}
		}
		BmInstrument instr = this.connector.getActiveInstrumentsMap().get(alias);
		this.connector.unSubscribe(instr);

	}

	@Override
	public String formatPrice(String alias, double price) {
		// Use default Bookmap price formatting logic for simplicity.
		// Values returned by this method will be used on price axis and in few
		// other places.
		double pips;
		synchronized (instruments) {
			pips = instruments.get(alias).pips;
		}

		return formatPriceDefault(pips, price);
	}

	@Override
	public void sendOrder(OrderSendParameters orderSendParameters) {
		Log.info("*******sendOrder*******");
		SimpleOrderSendParameters simpleParameters = (SimpleOrderSendParameters) orderSendParameters;
		// Detecting order type
		OrderType orderType = OrderType.getTypeFromPrices(simpleParameters.stopPrice, simpleParameters.limitPrice);
		Log.info("***orderType = " + orderType.toString());

		String tempOrderId = System.currentTimeMillis() + "-temp-" + orderCount++;

		final OrderInfoBuilder builder = new OrderInfoBuilder(simpleParameters.alias, tempOrderId,
				simpleParameters.isBuy, orderType, simpleParameters.clientId, simpleParameters.doNotIncrease);

		// You need to set these fields, otherwise Bookmap might not handle
		// order correctly
		builder.setStopPrice(simpleParameters.stopPrice).setLimitPrice(simpleParameters.limitPrice)
				.setUnfilled(simpleParameters.size).setDuration(OrderDuration.GTC)
				.setStatus(OrderStatus.PENDING_SUBMIT);

		tradingListeners.forEach(l -> l.onOrderUpdated(builder.build()));
		// Marking all fields as unchanged, since they were just reported and
		// fields will be marked as changed automatically when modified.
		builder.markAllUnchanged();

		if (orderType == OrderType.STP || orderType == OrderType.LMT || orderType == OrderType.STP_LMT
				|| orderType == OrderType.MKT) {
			// ****************** TO BITMEX
			// String tempClientId = simpleParameters.clientId;
			// Log.info("CLIENT ID " + tempClientId);
			Log.info("***Order gets sent to BitMex");
			workingOrders.put(builder.getOrderId(), builder); // still Pending,
																// yet not
																// Working

			connr.processNewOrder(simpleParameters, orderType, tempOrderId);

		} else {
			rejectOrder(builder);
		}

	}

	private void rejectOrder(OrderInfoBuilder builder) {
		Log.info("***Order gets REJECTED");

		// Necessary fields are already populated, so just change status to
		// rejected and send
		builder.setStatus(OrderStatus.REJECTED);
		tradingListeners.forEach(l -> l.onOrderUpdated(builder.build()));
		builder.markAllUnchanged();

		// Provider can complain to user here explaining what was done wrong
		adminListeners.forEach(l -> l.onSystemTextMessage("The order was rejected",
				// adminListeners.forEach(l -> l.onSystemTextMessage("This
				// provider only supports limit orders",
				SystemTextMessageType.ORDER_FAILURE));
	}

	private void rejectOrder(OrderInfoBuilder builder, String reas) {
		String reason = "The order was rejected: \n" + reas;
		Log.info("***Order gets REJECTED");
		// Necessary fields are already populated, so just change status to
		// rejected and send
		builder.setStatus(OrderStatus.REJECTED);
		tradingListeners.forEach(l -> l.onOrderUpdated(builder.build()));
		builder.markAllUnchanged();

		// Provider can complain to user here explaining what was done wrong
		adminListeners.forEach(l -> l.onSystemTextMessage(reason,
				// adminListeners.forEach(l -> l.onSystemTextMessage("This
				// provider only supports limit orders",
				SystemTextMessageType.ORDER_FAILURE));
	}

	@Override
	public void updateOrder(OrderUpdateParameters orderUpdateParameters) {

		Log.info("*******updateOrder*******");

		// OrderMoveToMarketParameters will not be sent as we did not declare
		// support for it, 3 other requests remain

		synchronized (workingOrders) {
			// instanceof is not recommended here because subclass, if it
			// appears,
			// will anyway mean an action that existing code can not process as
			// expected
			if (orderUpdateParameters.getClass() == OrderCancelParameters.class) {

				Log.info("***order with provided ID gets CANCELLED");
				OrderCancelParameters orderCancelParameters = (OrderCancelParameters) orderUpdateParameters;
				connr.cancelOrder(orderCancelParameters.orderId);

			} else if (orderUpdateParameters.getClass() == OrderResizeParameters.class) {

				Log.info("***order with provided ID gets RESIZED");
				OrderResizeParameters params = (OrderResizeParameters) orderUpdateParameters;

				// Resize order with provided ID
				OrderResizeParameters orderResizeParameters = (OrderResizeParameters) orderUpdateParameters;
				Log.info("RESIZE ORDER BY " + orderResizeParameters.size);

				int newSize = orderResizeParameters.size;
				OrderInfoBuilder builder = workingOrders.get(orderResizeParameters.orderId);

				if (builder.getFilled() == 0) {// unfilled order
					connr.resizeOrder(orderResizeParameters.orderId, newSize);
				} else {// partially filled order
					connr.resizePartiallyFilledOrder(orderResizeParameters.orderId, newSize);
				}
				connr.resizeOrder(orderResizeParameters.orderId, newSize);

			} else if (orderUpdateParameters.getClass() == OrderMoveParameters.class) {

				Log.info("***Change stop/limit prices of an order with provided ID");

				// Change stop/limit prices of an order with provided ID
				OrderMoveParameters orderMoveParameters = (OrderMoveParameters) orderUpdateParameters;

				Log.info("LimP " + Double.toString(orderMoveParameters.limitPrice) + "\tStP "
						+ Double.toString(orderMoveParameters.stopPrice));

				//

				connr.moveOrder(orderMoveParameters, workingOrders.get(orderMoveParameters.orderId).isStopTriggered());
				// connr.moveOrder(orderMoveParameters.orderId,
				// orderMoveParameters.limitPrice);

			} else {
				throw new UnsupportedOperationException("Unsupported order type");
			}

		}
	}

	@Override
	public void login(LoginData loginData) {
		UserPasswordDemoLoginData userPasswordDemoLoginData = (UserPasswordDemoLoginData) loginData;
		// If connection process takes a while then it's better to do it in
		// separate thread
		connectionThread = new Thread(() -> handleLogin(userPasswordDemoLoginData));
		connectionThread.setName("-> INSTRUMENT");
		connectionThread.start();
	}

	private void handleLogin(UserPasswordDemoLoginData userPasswordDemoLoginData) {
		// With real connection provider would attempt establishing connection
		// here.

		// there is no need in password check for demo purposes
		// boolean isValid = "pass".equals(userPasswordDemoLoginData.password)
		// && "user".equals(userPasswordDemoLoginData.user) &&
		// userPasswordDemoLoginData.isDemo == true;

		// if (isValid) {
		// Report succesful login
		adminListeners.forEach(Layer1ApiAdminListener::onLoginSuccessful);

		// CONNECTOR
		// this.connector = new BitmexConnector();
		this.connector.prov = this;
		this.connector.setTrConn(connr);
		Thread thread = new Thread(this.connector);
		thread.setName("->BitmexAdapter: connector");
		thread.start();

	}

	public void listenOrderOrTrade(Message message) {
		// Log.info("LISTENER USED");

		if (message == null || message.getAction() == null || message.getData() == null) {
			Log.info("***********NULL POINTER AT MESSAGE " + message);
		}

		String symbol = message.data.get(0).getSymbol();

		BmInstrument bmInstrument = connector.getActiveInstrumentsMap().get(symbol);

		if (!bmInstrument.isFirstSnapshotParsed()) {
			return;
		}

		List<DataUnit> units = message.data;

		if (message.table.equals("orderBookL2")) {
			// if (bmInstrument.isFirstSnapshotParsed()) {//!!!!!!!!
			for (DataUnit unit : units) {
				for (Layer1ApiDataListener listener : dataListeners) {
					listener.onDepth(symbol, unit.isBid(), unit.getIntPrice(), (int) unit.getSize());
				}
			}
			// }
		} else {
			for (DataUnit unit : units) {
				for (Layer1ApiDataListener listener : dataListeners) {
					final boolean isOtc = false;
					listener.onTrade(symbol, unit.getIntPrice(), (int) unit.getSize(),
							new TradeInfo(isOtc, unit.isBid()));
				}
			}
		}

	}

	public void listenToExecution(Execution orderExec) {
		String realOrderId = orderExec.getOrderID();
		OrderInfoBuilder builder = workingOrders.get(realOrderId);

		if (builder == null) {
			Log.info("BUILDER IS NULL (LISTEN TO EXEC");
		}

		if (orderExec.getExecType().equals("TriggeredOrActivatedBySystem")) {
			Log.info("****LISTEN EXEC - TRIGGERED");
			// if(orderExec.getTriggered().equals(arg0))
			builder.setStopTriggered(true);
			final OrderInfoBuilder finBuilder = builder;
			tradingListeners.forEach(l -> l.onOrderUpdated(finBuilder.build()));

		} else if (orderExec.getExecType().equals("Rejected")) {
			Log.info("****LISTEN EXEC - REJECTED");
			if (builder == null) {
				builder = workingOrders.get(orderExec.getClOrdID());
			}
			rejectOrder(builder, orderExec.getOrdRejReason());
		} else if (orderExec.getExecType().equals("Canceled")) {
			Log.info("****LISTEN EXEC - CANCELED");
			updateOrdersCount(builder, -builder.getUnfilled());

			OrderInfoBuilder canceledBuilder = workingOrders.remove(realOrderId);
			canceledBuilder.setStatus(OrderStatus.CANCELLED);
			tradingListeners.forEach(l -> l.onOrderUpdated(canceledBuilder.build()));

		} else if (orderExec.getExecType().equals("New")) {
			Log.info("****LISTEN EXEC - NEW");
			String tempOrderId = orderExec.getClOrdID();
			OrderInfoBuilder buildertemp = workingOrders.get(tempOrderId);
			// there will be either new id if the order is accepted
			// or the order will be rejected so no need to keep it in the map
			workingOrders.remove(tempOrderId);

			Log.info("BM_ID " + realOrderId);
			// ****************** TO BITMEX ENDS
			updateOrdersCount(buildertemp, buildertemp.getUnfilled());

			buildertemp.setOrderId(realOrderId);
			buildertemp.setStatus(OrderStatus.WORKING);
			tradingListeners.forEach(l -> l.onOrderUpdated(buildertemp.build()));
			buildertemp.markAllUnchanged();

			synchronized (workingOrders) {
				workingOrders.put(buildertemp.getOrderId(), buildertemp);
			}
		} else if (orderExec.getExecType().equals("Replaced")) {

			// quantity was changed
			if (orderExec.getText().equals("Amended orderQty: Amended via API.\nSubmitted via API.")) {
				Log.info("****LISTEN EXEC - REPLACED QUANTITY");
				int oldSize = builder.getUnfilled();
				int newSize = (int) orderExec.getOrderQty();
				Log.info("****oldSize " + oldSize + "   newSize " + newSize);

				updateOrdersCount(builder, newSize - oldSize);

				builder.setUnfilled(newSize);
				final OrderInfoBuilder finBuilder = builder;
				tradingListeners.forEach(l -> l.onOrderUpdated(finBuilder.build()));
				Log.info("*********RESIZED*********");
			} else if (orderExec.getText().equals("Amended price: Amended via API.\nSubmitted via API.")) {
				Log.info("****LISTEN EXEC - REPLACED PRICE");
				// price was changed
				OrderInfoBuilder order = workingOrders.get(builder.getOrderId());
				order.setLimitPrice(orderExec.getPrice());
				tradingListeners.forEach(l -> l.onOrderUpdated(order.build()));

			} else if (orderExec.getText().equals("Amended stopPx: Amended via API.\nSubmitted via API.")) {
				Log.info("****LISTEN EXEC - REPLACED STOP PRICE");
				// price was changed
				OrderInfoBuilder order = workingOrders.get(builder.getOrderId());
				order.setStopPrice(orderExec.getStopPx());
				tradingListeners.forEach(l -> l.onOrderUpdated(order.build()));
			} else if (orderExec.getText().equals("Amended price stopPx: Amended via API.\nSubmitted via API.")) {
				Log.info("****LISTEN EXEC - REPLACED STOP AND LIMIT PRICE");
				// price was changed
				OrderInfoBuilder order = workingOrders.get(builder.getOrderId());
				order.setStopPrice(orderExec.getStopPx());
				order.setLimitPrice(orderExec.getPrice());
				tradingListeners.forEach(l -> l.onOrderUpdated(order.build()));
			}

		} else if (orderExec.getOrdStatus().equals("PartiallyFilled") || orderExec.getOrdStatus().equals("Filled")) {
			Log.info("****LISTEN EXEC - (PARTIALLY) FILLED");

			String symbol = orderExec.getSymbol();
			BmInstrument instr = connector.getActiveInstrumentsMap().get(symbol);

			// if unfilled value has changed
			if (builder.getUnfilled() != orderExec.getLeavesQty()) {
				Execution exec = (Execution) orderExec;
				int filled = (int) Math.abs(exec.getForeignNotional());
				Log.info("FILLED " + filled);
				// if (orderExec.getOrdStatus().equals("Filled")) {
				final long executionTime = System.currentTimeMillis();

				// filled = (int) Math.round(filled / instr.getTickSize());
				ExecutionInfo executionInfo = new ExecutionInfo(orderExec.getOrderID(), filled, exec.getLastPx(),
						orderExec.getExecID(), executionTime);

				tradingListeners.forEach(l -> l.onOrderExecuted(executionInfo));

				// updating filled orders volume
				instr.setExecutionsVolume(instr.getExecutionsVolume() + filled);
				updateOrdersCount(builder, -filled);

				// Changing the order itself
				OrderInfoBuilder order = workingOrders.get(orderExec.getOrderID());
				order.setAverageFillPrice(orderExec.getAvgPx());

				if (orderExec.getOrdStatus().equals("Filled")) {
					order.setUnfilled(0);
					order.setFilled((int) exec.getCumQty());
					order.setStatus(OrderStatus.FILLED);
				} else {
					order.setUnfilled((int) orderExec.getLeavesQty());
					order.setFilled((int) orderExec.getCumQty());
					// Log.info("setUnfilled " + (int)
					// orderExec.getLeavesQty());
					// Log.info("setFilled" + (int) orderExec.getCumQty());
				}

				tradingListeners.forEach(l -> l.onOrderUpdated(order.build()));
				order.markAllUnchanged();
			}
		}
	}

	private void updateOrdersCount(OrderInfoBuilder builder, int changes) {
		// Log.info("****ORDER COUNT UPD " + changes);
		// String symbol = connr.isolateSymbol(builder.getInstrumentAlias());
		// BmInstrument instr = connector.getActiveInstrumentsMap().get(symbol);
		// if (builder.isBuy()) {
		// instr.setBuyOrdersCount(instr.getBuyOrdersCount() + changes);
		// } else {
		// instr.setSellOrdersCount(instr.getSellOrdersCount() + changes);
		// }
	}

	public void listenToPosition(Position pos) {
		String symbol = pos.getSymbol();
		BmInstrument instr = connector.getActiveInstrumentsMap().get(symbol);
		Position validPosition = instr.getValidPosition();
		updateValidPosition(validPosition, pos);
		// Log.info("NEW VAL" + validPosition.toString());

		// public StatusInfo(java.lang.String instrumentAlias,
		// double unrealizedPnl,
		// double realizedPnl,
		// java.lang.String currency,
		// int position,
		// double averagePrice,
		// int volume,
		// int workingBuys,
		// int workingSells)
		// BalanceInfo info = new BalanceInfo();

		StatusInfo info = new StatusInfo(validPosition.getSymbol(),
				(double) validPosition.getUnrealisedPnl() / (double) instr.getMultiplier(),
				(double) validPosition.getRealisedPnl() / (double) instr.getMultiplier(), validPosition.getCurrency(),
				(int) Math.round(
						(double) (validPosition.getMarkValue()) / (double) instr.getUnderlyingToSettleMultiplier()),
				// (int) Math.round((double) (validPosition.getMarkValue() -
				// validPosition.getUnrealisedPnl())
				// / (double) instr.getMultiplier()),
				validPosition.getAvgEntryPrice(), instr.getExecutionsVolume(),
				// instr.getBuyOrdersCount(),
				// instr.getSellOrdersCount());
				validPosition.getOpenOrderBuyQty().intValue(), 
				validPosition.getOpenOrderSellQty().intValue());

		Log.info(info.toString());

		tradingListeners.forEach(l -> l.onStatus(info));

	}

	public void listenToWallet(Wallet wallet) {

		updateValidWallet(wallet);

		// public BalanceInCurrency(double balance,
		// double realizedPnl,
		// double unrealizedPnl,
		// double previousDayBalance,
		// double netLiquidityValue,
		// java.lang.String currency,
		// java.lang.Double rateToBase)
		// BalanceInfo info = new BalanceInfo(null);
		List<a> list = new LinkedList<>();
		a bica = new a(validWallet.getAmount(), 0.0, 0.0, validWallet.getPrevAmount(), 0.0, validWallet.getCurrency(),
				0.0);
		list.add(bica);
		BalanceInfo info = new BalanceInfo(list);

		// Class<?> [] inCls = BalanceInfo.class.getDeclaredClasses();
		// Class<?> bic = inCls[0];
		// Constructor[] constr = bic.getConstructors();
		// try {
		// Object obj = bic.getConstructors()[0].newInstance(0L, 0L);
		// } catch (InstantiationException | IllegalAccessException |
		// IllegalArgumentException | InvocationTargetException
		// | SecurityException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }

		// Field field = bic.getField("balancesInCurrency");
		// bic.getI balInCur = new bic.getClass();
		//
		//
		//
		// Field[] fields = cls.getDeclaredFields();
		// for (Field field : fields){
		// Log.info("field " + field.toString());
		// try {
		// field.setAccessible(true);
		// if(field.get(wallet)!=null){
		//
		// BalanceInfo info = new BalanceInfo(new
		// List<BalanceInfo.BalanceInCurrency> );
		//

		// StatusInfo info = new StatusInfo(validPosition.getSymbol(),
		// (double) validPosition.getUnrealisedPnl() / (double)
		// instr.getMultiplier(),
		// (double) validPosition.getRealisedPnl() / (double)
		// instr.getMultiplier(), validPosition.getCurrency(),
		// (int) Math.round((double) (validPosition.getMarkValue() -
		// validPosition.getUnrealisedPnl())
		// / (double) instr.getMultiplier()),
		// validPosition.getAvgEntryPrice(), instr.getExecutionsVolume(),
		// instr.getBuyOrdersCount(),
		// instr.getSellOrdersCount());
		//
		// Log.info(info.toString());
		//
		// tradingListeners.forEach(l -> l.onStatus(info));

		tradingListeners.forEach(l -> l.onBalance(info));
		Log.info(info.toString());

	}

	private void updateValidWallet(Wallet wallet) {
		Class<?> cls = wallet.getClass();
		Field[] fields = cls.getDeclaredFields();
		for (Field field : fields) {
			// Log.info("field " + field.toString());
			try {
				field.setAccessible(true);
				if (field.get(wallet) != null) {
					field.set(validWallet, field.get(wallet));
				}
			} catch (IllegalArgumentException | IllegalAccessException e) {
				e.printStackTrace();
			}
		}

	}

	private void updateValidPosition(Position validPosition, Position pos) {
		if (validPosition.getAccount().equals(0L)) {
			if (pos.getAccount() != null) {
				validPosition.setAccount(pos.getAccount());
			}
		}
		if (validPosition.getSymbol().equals("") && pos.getSymbol() != null) {
			validPosition.setSymbol(pos.getSymbol());
		}
		if (validPosition.getCurrency().equals("") && pos.getCurrency() != null) {
			validPosition.setCurrency(pos.getCurrency());
		}
		if (pos.getMarkValue() != null) {
			validPosition.setMarkValue(pos.getMarkValue());
		}
		if (pos.getRealisedPnl() != null) {
			validPosition.setRealisedPnl(pos.getRealisedPnl());
		}
		if (pos.getUnrealisedPnl() != null) {
			validPosition.setUnrealisedPnl(pos.getUnrealisedPnl());
		}
		if (pos.getAvgEntryPrice() != null) {
			validPosition.setAvgEntryPrice(pos.getAvgEntryPrice());
		}
		if (pos.getOpenOrderBuyQty() != null) {
			validPosition.setOpenOrderBuyQty(pos.getOpenOrderBuyQty());
		}
		if (pos.getOpenOrderSellQty() != null) {
			validPosition.setOpenOrderSellQty(pos.getOpenOrderSellQty());
		}
		// Log.info("WTN MTH" + validPosition.toString());
	}

	public void createBookmapOrder(BmOrder order) {
		String symbol = order.getSymbol();
		String orderId = order.getOrderID();
		boolean isBuy = order.getSide().equals("Buy") ? true : false;
		// OrderType type = order.getOrdType().equals("Limit") ? OrderType.LMT :
		// OrderType.STP;
		OrderType type = OrderType.getTypeFromPrices(order.getStopPx(), order.getPrice());
		Log.info("+++++  " + type.toString());
		// String clientId =order.getClientId();//this field is being left blank
		// so far
		String clientId = tempClientId;
		// Log.info("To BM CLIENT ID " + tempClientId);

		boolean doNotIncrease = true;// this field is being left true so far

		// double stopPrice = type.equals(OrderType.STP) ? order.getPrice() :
		// Double.NaN;
		// double limitPrice = type.equals(OrderType.LMT) ? order.getPrice() :
		// Double.NaN;
		// int size = (int) order.getLeavesQty();
		// int size = (int) order.getSimpleLeavesQty();
		// Log.info("SIMPLE LEAVES QTY = " + size);

		final OrderInfoBuilder builder = new OrderInfoBuilder(symbol, orderId, isBuy, type, clientId, doNotIncrease);

		// You need to set these fields, otherwise Bookmap might not handle
		// order correctly
		// builder.setStopPrice(order.getStopPx()).setLimitPrice(order.getPrice()).setUnfilled(size).setDuration(OrderDuration.GTC)
		builder.setStopPrice(order.getStopPx()).setLimitPrice(order.getPrice()).setUnfilled((int) order.getLeavesQty())
				.setFilled((int) order.getCumQty()).setDuration(OrderDuration.GTC)
				.setStatus(OrderStatus.PENDING_SUBMIT);
		tradingListeners.forEach(l -> l.onOrderUpdated(builder.build()));
		builder.markAllUnchanged();

		builder.setStatus(OrderStatus.WORKING);
		tradingListeners.forEach(l -> l.onOrderUpdated(builder.build()));
		builder.markAllUnchanged();

		// BmInstrument instr = connector.getActiveInstrumentsMap().get(symbol);

		updateOrdersCount(builder, (int) order.getLeavesQty());
		// if (isBuy) {
		// instr.setBuyOrdersCount(instr.getBuyOrdersCount() + size);
		// // buyOrdersCount += simpleParameters.size;
		// } else {
		// instr.setSellOrdersCount(instr.getSellOrdersCount() + size);
		// // sellOrdersCount += simpleParameters.size;
		// }

		synchronized (workingOrders) {
			// workingOrders.put(builder.orderId, builder);
			workingOrders.put(orderId, builder);
			Log.info("BM ORDER PUT");
		}
	}

	@Override
	public Layer1ApiProviderSupportedFeatures getSupportedFeatures() {
		// Expanding parent supported features, reporting basic trading support
		return super.getSupportedFeatures().toBuilder().setTrading(true)
				.setSupportedOrderDurations(Arrays.asList(new OrderDuration[] { OrderDuration.GTC }))
				// At the moment of writing this method it was not possible to
				// report limit orders support, but no stop orders support
				// If you actually need it, you can report stop orders support
				// but reject stop orders when those are sent.
				.setSupportedStopOrders(Arrays.asList(new OrderType[] { OrderType.LMT, OrderType.MKT })).build();
	}

	@Override
	public String getSource() {
		// String identifying where data came from.
		// For example you can use that later in your indicator.
		return "realtime demo";
	}

	@Override
	public void close() {
		// Stop events generation
		connectionThread.interrupt();
	}

}
