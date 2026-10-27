package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Gives every order that existed before payments were introduced a bill, so that the new rule "an order is
 * completed only when its bill is paid" holds for old data too:
 * <ul>
 *     <li>completed orders get a closed bill with one successful payment of their total, in the payment method
 *     recorded on the order;</li>
 *     <li>cancelled orders get a void bill;</li>
 *     <li>orders still in progress get an open bill, one per table for dine-in orders, nothing paid yet.</li>
 * </ul>
 * Written in Java rather than SQL so that the generated bill ids can be read back portably on PostgreSQL and H2.
 */
public class V4__BackfillBills extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<Long, Long> openBillByTable = new HashMap<>();
        try (Statement select = connection.createStatement();
             ResultSet orders = select.executeQuery("""
                     SELECT id, type, status, table_id, payment_method, total, created_at, updated_at
                     FROM orders ORDER BY created_at, id""");
             PreparedStatement insertBill = connection.prepareStatement(
                     "INSERT INTO bills (status, table_id, opened_at, closed_at) VALUES (?, ?, ?, ?)", new String[] {"id"});
             PreparedStatement link = connection.prepareStatement(
                     "INSERT INTO bill_orders (bill_id, order_id) VALUES (?, ?)");
             PreparedStatement insertPayment = connection.prepareStatement("""
                     INSERT INTO payments (bill_id, method, status, amount, tip, created_at, completed_at, created_by)
                     VALUES (?, ?, 'SUCCEEDED', ?, 0, ?, ?, 'migration')""")) {
            while (orders.next()) {
                long orderId = orders.getLong("id");
                String status = orders.getString("status");
                Number table = (Number) orders.getObject("table_id");
                long tableId = table == null ? 0 : table.longValue();
                boolean dineIn = "DINE_IN".equals(orders.getString("type")) && table != null;
                Timestamp createdAt = orders.getTimestamp("created_at");
                Timestamp updatedAt = orders.getTimestamp("updated_at");
                boolean active = !"COMPLETED".equals(status) && !"CANCELLED".equals(status);

                Long billId = active && dineIn ? openBillByTable.get(tableId) : null;
                if (billId == null) {
                    insertBill.setString(1, active ? "OPEN" : "COMPLETED".equals(status) ? "CLOSED" : "VOID");
                    if (dineIn) {
                        insertBill.setLong(2, tableId);
                    } else {
                        insertBill.setNull(2, java.sql.Types.BIGINT);
                    }
                    insertBill.setTimestamp(3, createdAt);
                    insertBill.setTimestamp(4, active ? null : updatedAt);
                    insertBill.executeUpdate();
                    try (ResultSet keys = insertBill.getGeneratedKeys()) {
                        keys.next();
                        billId = keys.getLong(1);
                    }
                    if (active && dineIn) {
                        openBillByTable.put(tableId, billId);
                    }
                }
                link.setLong(1, billId);
                link.setLong(2, orderId);
                link.executeUpdate();

                if ("COMPLETED".equals(status) && orders.getBigDecimal("total").signum() > 0) {
                    insertPayment.setLong(1, billId);
                    insertPayment.setString(2, orders.getString("payment_method"));
                    insertPayment.setBigDecimal(3, orders.getBigDecimal("total"));
                    insertPayment.setTimestamp(4, updatedAt);
                    insertPayment.setTimestamp(5, updatedAt);
                    insertPayment.executeUpdate();
                }
            }
        }
    }
}
