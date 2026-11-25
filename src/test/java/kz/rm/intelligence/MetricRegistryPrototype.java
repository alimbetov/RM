package kz.rm.intelligence;

import java.util.Objects;

/** Test-source executable whitelist; no data access or permission grants. */
final class MetricRegistryPrototype {
    enum Code { PLACED_ORDERS,BOOKED_ORDER_AMOUNT,CONFIRMED_ORDERS,FULFILLED_ORDERS,CANCELLED_ORDERS,STOCK_IN_QTY,STOCK_OUT_QTY,CURRENT_AVAILABLE }
    enum Selection { ORDER_PERIOD,MOVEMENT_PERIOD,LIVE_POSITION }
    enum Basis { STATUS_OCCURRED,STOCK_RECORDED,LIVE_POSITION }
    enum Unit { COUNT,CURRENCY_AMOUNT,SKU_QUANTITY }
    record Definition(Code code,int version,Selection selection,Basis basis,Unit unit,String permission,boolean warehouseAssignment) {}
    static Definition require(Code code,int version,Selection selection) {
        Objects.requireNonNull(code); Objects.requireNonNull(selection);
        if(version!=1) throw new IllegalArgumentException("Unknown method version");
        Definition d=switch(code) {
            case PLACED_ORDERS,CONFIRMED_ORDERS,FULFILLED_ORDERS,CANCELLED_ORDERS ->
                new Definition(code,1,Selection.ORDER_PERIOD,Basis.STATUS_OCCURRED,Unit.COUNT,"SALES_ORDER_READ",false);
            case BOOKED_ORDER_AMOUNT ->
                new Definition(code,1,Selection.ORDER_PERIOD,Basis.STATUS_OCCURRED,Unit.CURRENCY_AMOUNT,"SALES_ORDER_READ",false);
            case STOCK_IN_QTY,STOCK_OUT_QTY ->
                new Definition(code,1,Selection.MOVEMENT_PERIOD,Basis.STOCK_RECORDED,Unit.SKU_QUANTITY,"INVENTORY_STOCK_READ",true);
            case CURRENT_AVAILABLE ->
                new Definition(code,1,Selection.LIVE_POSITION,Basis.LIVE_POSITION,Unit.SKU_QUANTITY,"INVENTORY_STOCK_READ",true);
        };
        if(d.selection()!=selection) throw new IllegalArgumentException("Unsupported selection");
        return d;
    }
}
