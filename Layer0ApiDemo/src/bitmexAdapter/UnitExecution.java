package bitmexAdapter;

public class UnitExecution extends UnitOrder{
	private String execType;
	private double lastPx;//from UnitExecution
	private long lastQty;
	
	//this field is added processing timestamp acquired from Bitmex
	private long execTransactTime;
	
	private long foreignNotional;
	
	public long getForeignNotional() {
		return foreignNotional;
	}
	public void setForeignNotional(long foreignNotional) {
		this.foreignNotional = foreignNotional;
	}
	public double getLastPx() {
		return lastPx;
	}
	public void setLastPx(double lastPx) {
		this.lastPx = lastPx;
	}
	
	public String getExecType() {
		return execType;
	}
	public void setExecType(String execType) {
		this.execType = execType;
	}
	
	public long getLastQty() {
		return lastQty;
	}
	public void setLastQty(long lastQty) {
		this.lastQty = lastQty;
	}
	
	public long getExecTransactTime() {
		return execTransactTime;
	}
	public void setExecTransactTime(long execTransactTime) {
		this.execTransactTime = execTransactTime;
	}
}
