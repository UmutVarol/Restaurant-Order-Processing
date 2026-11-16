package com.umutvarol.restaurant.push;

import java.util.Map;

/**
 * The words of a notification in the six languages of the app. The device chose its language when it subscribed;
 * the server writes the message in it, because the browser shows it as it is.
 */
final class PushMessages {

    record Text(String title, String body) {
    }

    private static final Map<String, Map<String, String>> WORDS = Map.of(
            "tr", Map.ofEntries(
                    Map.entry("NEW_ORDER", "Yeni sipariş #{id}"), Map.entry("READY", "Hazır: #{id}"),
                    Map.entry("table", "Masa {table}"), Map.entry("TAKEAWAY", "Gel-al"), Map.entry("DELIVERY", "Paket"),
                    Map.entry("CALL_WAITER", "Masa {table} garson çağırıyor"), Map.entry("REQUEST_BILL", "Masa {table} hesap istiyor"),
                    Map.entry("CART", "Masa {table} sipariş gönderdi"), Map.entry("guestBody", "Gelen kutusunda bekliyor"),
                    Map.entry("TEST", "Bildirimler açık"), Map.entry("testBody", "Bu cihaz Sofra bildirimlerini alacak.")),
            "en", Map.ofEntries(
                    Map.entry("NEW_ORDER", "New order #{id}"), Map.entry("READY", "Ready: #{id}"),
                    Map.entry("table", "Table {table}"), Map.entry("TAKEAWAY", "Takeaway"), Map.entry("DELIVERY", "Delivery"),
                    Map.entry("CALL_WAITER", "Table {table} is calling a waiter"), Map.entry("REQUEST_BILL", "Table {table} asks for the bill"),
                    Map.entry("CART", "Table {table} sent an order"), Map.entry("guestBody", "Waiting in the inbox"),
                    Map.entry("TEST", "Notifications are on"), Map.entry("testBody", "This device will get Sofra notifications.")),
            "de", Map.ofEntries(
                    Map.entry("NEW_ORDER", "Neue Bestellung #{id}"), Map.entry("READY", "Fertig: #{id}"),
                    Map.entry("table", "Tisch {table}"), Map.entry("TAKEAWAY", "Zum Mitnehmen"), Map.entry("DELIVERY", "Lieferung"),
                    Map.entry("CALL_WAITER", "Tisch {table} ruft die Bedienung"), Map.entry("REQUEST_BILL", "Tisch {table} möchte zahlen"),
                    Map.entry("CART", "Tisch {table} hat bestellt"), Map.entry("guestBody", "Wartet im Posteingang"),
                    Map.entry("TEST", "Benachrichtigungen sind an"), Map.entry("testBody", "Dieses Gerät erhält Sofra-Benachrichtigungen.")),
            "fr", Map.ofEntries(
                    Map.entry("NEW_ORDER", "Nouvelle commande #{id}"), Map.entry("READY", "Prête : #{id}"),
                    Map.entry("table", "Table {table}"), Map.entry("TAKEAWAY", "À emporter"), Map.entry("DELIVERY", "Livraison"),
                    Map.entry("CALL_WAITER", "La table {table} appelle un serveur"), Map.entry("REQUEST_BILL", "La table {table} demande l’addition"),
                    Map.entry("CART", "La table {table} a envoyé une commande"), Map.entry("guestBody", "En attente dans la boîte de réception"),
                    Map.entry("TEST", "Notifications activées"), Map.entry("testBody", "Cet appareil recevra les notifications Sofra.")),
            "ru", Map.ofEntries(
                    Map.entry("NEW_ORDER", "Новый заказ #{id}"), Map.entry("READY", "Готов: #{id}"),
                    Map.entry("table", "Стол {table}"), Map.entry("TAKEAWAY", "С собой"), Map.entry("DELIVERY", "Доставка"),
                    Map.entry("CALL_WAITER", "Стол {table} зовёт официанта"), Map.entry("REQUEST_BILL", "Стол {table} просит счёт"),
                    Map.entry("CART", "Стол {table} отправил заказ"), Map.entry("guestBody", "Ждёт во входящих"),
                    Map.entry("TEST", "Уведомления включены"), Map.entry("testBody", "Это устройство будет получать уведомления Sofra.")),
            "ar", Map.ofEntries(
                    Map.entry("NEW_ORDER", "طلب جديد ‎#{id}"), Map.entry("READY", "جاهز: ‎#{id}"),
                    Map.entry("table", "طاولة {table}"), Map.entry("TAKEAWAY", "سفري"), Map.entry("DELIVERY", "توصيل"),
                    Map.entry("CALL_WAITER", "الطاولة {table} تطلب النادل"), Map.entry("REQUEST_BILL", "الطاولة {table} تطلب الفاتورة"),
                    Map.entry("CART", "الطاولة {table} أرسلت طلبًا"), Map.entry("guestBody", "بانتظارك في صندوق الوارد"),
                    Map.entry("TEST", "الإشعارات مفعّلة"), Map.entry("testBody", "سيتلقى هذا الجهاز إشعارات Sofra.")));

    private PushMessages() {
    }

    static boolean supports(String lang) {
        return WORDS.containsKey(lang);
    }

    /** A new or ready order: "#1043", where it goes, and who for. */
    static Text order(String lang, PushTopic topic, long id, String type, String table, String customer) {
        Map<String, String> w = words(lang);
        String where = table != null ? w.get("table").replace("{table}", table) : w.getOrDefault(type, type);
        return new Text(w.get(topic.name()).replace("{id}", Long.toString(id)), where + " · " + customer);
    }

    /** A guest at a table calls, asks for the bill or sends a cart. */
    static Text guest(String lang, String kind, String table) {
        Map<String, String> w = words(lang);
        return new Text(w.getOrDefault(kind, w.get("CALL_WAITER")).replace("{table}", table), w.get("guestBody"));
    }

    static Text test(String lang) {
        Map<String, String> w = words(lang);
        return new Text(w.get("TEST"), w.get("testBody"));
    }

    private static Map<String, String> words(String lang) {
        return WORDS.getOrDefault(lang, WORDS.get("en"));
    }
}
