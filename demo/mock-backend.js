// In-browser stand-in for the Sofra Spring Boot API, used by the static demo (GitHub Pages / embedded preview).
//
// It implements the same endpoints, payloads, roles, validation and error codes as the real backend — pricing,
// coupons, the order state machine, bills and payments (including the QR checkout with a simulated provider), analytics
// and the Server-Sent Events stream — so the unmodified web client runs
// against it. State lives in localStorage and is shared live between tabs through a BroadcastChannel, so a waiter
// tab and a chef tab behave like two devices in the same restaurant.
(function () {
  'use strict';

  const STORE_KEY = 'sofra.demo.state.v3';
  const TAX_RATE_PERCENT = 10;
  const DELIVERY_FEE = 3990;           // in kuruş
  const FREE_DELIVERY_THRESHOLD = 75000;
  const ACTIVE = ['PENDING', 'PREPARING', 'READY', 'SERVED', 'OUT_FOR_DELIVERY'];
  const MINUTE = 60_000;

  // ------------------------------------------------------------------------------------------- reference data
  const MENU = [
    [1, 'Mercimek Çorbası', 'Velvety red lentil soup with lemon and paprika butter', 'STARTER', 165, '🥣', 5, true, ['VEGETARIAN', 'GLUTEN_FREE']],
    [2, 'Sigara Böreği', 'Crispy filo rolls filled with white cheese and parsley', 'STARTER', 185, '🥟', 8, true, ['VEGETARIAN']],
    [3, 'Humus', 'Chickpea purée with tahini, warm butter and pine nuts', 'STARTER', 175, '🧆', 4, false, ['VEGETARIAN', 'GLUTEN_FREE']],
    [4, 'Çoban Salata', 'Tomato, cucumber, green pepper, red onion and sumac', 'STARTER', 150, '🥗', 5, false, ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE']],
    [5, 'Acılı Ezme', 'Fiery tomato and pepper relish with pomegranate molasses', 'STARTER', 140, '🌶️', 4, false, ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE', 'SPICY']],
    [6, 'Paçanga Böreği', 'Golden filo parcels with pastırma and melted kaşar', 'STARTER', 210, '🥐', 9, false, []],
    [7, 'Adana Kebap', 'Hand-minced lamb kebab with chilli, lavash and grilled peppers', 'MAIN', 520, '🍢', 18, true, ['SPICY']],
    [8, 'İskender', 'Döner over pide with tomato sauce, browned butter and yogurt', 'MAIN', 560, '🥩', 15, true, []],
    [9, 'Lahmacun', 'Thin crispy flatbread with spiced minced lamb, herbs and lemon', 'MAIN', 220, '🍕', 10, true, []],
    [10, 'Mantı', 'Tiny beef dumplings with garlic yogurt and chilli butter', 'MAIN', 410, '🥟', 14, true, ['SPICY']],
    [11, 'Izgara Tavuk', 'Marinated chicken thigh, bulgur pilaf and smoked aubergine', 'MAIN', 450, '🍗', 16, false, []],
    [12, 'Köfte Tabağı', 'Grilled Turkish meatballs with piyaz and roasted tomatoes', 'MAIN', 470, '🍖', 14, false, []],
    [13, 'Karnıyarık', 'Aubergine stuffed with spiced beef, served with rice pilaf', 'MAIN', 430, '🍆', 12, false, []],
    [14, 'Levrek Izgara', 'Charcoal-grilled sea bass with rocket and lemon', 'MAIN', 640, '🐟', 20, false, ['GLUTEN_FREE']],
    [15, 'Sebzeli Güveç', 'Clay-pot vegetable stew with herbs and rice', 'MAIN', 380, '🥘', 15, false, ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE']],
    [16, 'Künefe', 'Hot shredded pastry with melting cheese and pistachio syrup', 'DESSERT', 260, '🧀', 12, true, ['VEGETARIAN']],
    [17, 'Fıstıklı Baklava', 'Four pieces of pistachio baklava with clotted cream', 'DESSERT', 280, '🍯', 2, true, ['VEGETARIAN']],
    [18, 'Fırın Sütlaç', 'Oven-baked rice pudding dusted with cinnamon', 'DESSERT', 160, '🍮', 2, false, ['VEGETARIAN', 'GLUTEN_FREE']],
    [19, 'San Sebastian Cheesecake', 'Burnt Basque cheesecake with a creamy centre', 'DESSERT', 230, '🍰', 2, false, ['VEGETARIAN']],
    [20, 'Türk Kahvesi', 'Turkish coffee served with lokum', 'DRINK', 110, '☕', 5, true, []],
    [21, 'Demlik Çay', 'Rize black tea, brewed in a double teapot', 'DRINK', 45, '🍵', 1, true, []],
    [22, 'Ayran', 'Frothy salted yogurt drink', 'DRINK', 70, '🥛', 1, false, []],
    [23, 'Taze Portakal Suyu', 'Freshly squeezed orange juice', 'DRINK', 130, '🍊', 3, false, []],
    [24, 'Ev Yapımı Limonata', 'Homemade lemonade with fresh mint', 'DRINK', 120, '🍋', 3, false, []],
    [25, 'Şalgam', 'Fermented purple carrot juice, hot or mild', 'DRINK', 75, '🥤', 1, false, ['SPICY']],
    [26, 'Maden Suyu', 'Sparkling mineral water', 'DRINK', 55, '💧', 1, false, []],
  ].map(([id, name, description, category, price, icon, prepTimeMinutes, popular, tags]) => ({
    id, name, description, category, priceC: price * 100, icon, prepTimeMinutes, popular, tags, available: true, archived: false,
  }));

  // Kitchen stations, as seeded by V12__courses_and_stations.sql, and where each category goes by default.
  const STATIONS = [[1, 'COLD', 'Soğuk mutfak', 8], [2, 'HOT', 'Sıcak mutfak', 18], [3, 'DESSERT', 'Tatlı', 10], [4, 'BAR', 'Bar', 5]]
    .map(([id, code, name, targetMinutes]) => ({ id, code, name, targetMinutes }));
  const DEFAULT_STATION = { STARTER: 'COLD', MAIN: 'HOT', DESSERT: 'DESSERT', DRINK: 'BAR' };
  const DEFAULT_COURSE = { STARTER: 1, DRINK: 1, MAIN: 2, DESSERT: 3 };

  // Product options, as seeded by V6__product_options.sql: [dish id, [group, min, max, [[choice, extra lira], …]], …]
  const OPTIONS = [
    [7, ['Acılık', 1, 1, [['Normal', 0], ['Az acılı', 0], ['Bol acılı', 0]]], ['Ekstra', 0, 2, [['Közlenmiş domates', 25], ['Ekstra lavaş', 20]]]],
    [8, ['Tereyağı', 1, 1, [['Normal', 0], ['Az', 0], ['Bol', 0]]], ['Ekstra', 0, 1, [['Ekstra yoğurt', 30]]]],
    [9, ['Acılık', 1, 1, [['Acısız', 0], ['Acılı', 0]]]],
    [10, ['Sos', 1, 1, [['Sarımsaklı yoğurt', 0], ['Sarımsaksız yoğurt', 0]]], ['Ekstra', 0, 1, [['Ekstra tereyağı', 25]]]],
    [16, ['Yanında', 0, 2, [['Dondurma', 60], ['Kaymak', 50]]]],
    [20, ['Şeker', 1, 1, [['Sade', 0], ['Az şekerli', 0], ['Orta', 0], ['Şekerli', 0]]]],
    [21, ['Bardak', 1, 1, [['İnce belli', 0], ['Büyük bardak', 15]]]],
  ];

  const TABLES = [['A1', 2, 'INDOOR'], ['A2', 4, 'INDOOR'], ['A3', 4, 'INDOOR'], ['A4', 6, 'INDOOR'], ['A5', 4, 'INDOOR'],
    ['A6', 8, 'INDOOR'], ['W1', 2, 'WINDOW'], ['W2', 2, 'WINDOW'], ['T1', 4, 'TERRACE'], ['T2', 4, 'TERRACE'],
    ['T3', 6, 'TERRACE'], ['T4', 2, 'TERRACE']].map(([code, seats, zone], i) => ({ id: i + 1, code, seats, zone }));

  const COUPONS = [
    { code: 'HOSGELDIN', description: 'Welcome offer: 10% off', discountType: 'PERCENTAGE', discountValue: 10, minimumSubtotal: 0, maxDiscount: 150, validUntil: null },
    { code: 'OGRENCI15', description: 'Student discount: 15% off', discountType: 'PERCENTAGE', discountValue: 15, minimumSubtotal: 200, maxDiscount: 200, validUntil: null },
    { code: 'SOFRA20', description: '20% off orders of 1,000 TL or more', discountType: 'PERCENTAGE', discountValue: 20, minimumSubtotal: 1000, maxDiscount: 400, validUntil: null },
    { code: 'TATLI50', description: '50 TL off when you spend 400 TL', discountType: 'FIXED_AMOUNT', discountValue: 50, minimumSubtotal: 400, maxDiscount: null, validUntil: null },
    { code: 'YAZ2025', description: 'Summer 2025 campaign', discountType: 'PERCENTAGE', discountValue: 25, minimumSubtotal: 0, maxDiscount: null, validUntil: '2025-08-31T21:00:00Z' },
  ];

  const USERS = {
    admin: { password: 'admin123', displayName: 'Elif Yılmaz', role: 'ADMIN' },
    waiter: { password: 'waiter123', displayName: 'Can Demir', role: 'WAITER' },
    chef: { password: 'chef123', displayName: 'Mehmet Usta', role: 'KITCHEN' },
  };

  // ------------------------------------------------------------------------------------------- helpers
  const toLira = (cents) => Math.round(cents) / 100;
  const toCents = (lira) => Math.round(Number(lira) * 100);
  const halfUp = (value) => Math.floor(value + 0.5);
  const iso = (ms) => new Date(ms).toISOString();
  const blank = (value) => value == null || String(value).trim() === '';
  const clean = (value) => (blank(value) ? null : String(value).trim());

  class Problem extends Error {
    constructor(status, code, detail, extra = {}) {
      super(detail);
      Object.assign(this, { status, code, extra });
    }
  }

  function nextStatus(status, type) {
    switch (status) {
      case 'PENDING': return 'PREPARING';
      case 'PREPARING': return 'READY';
      case 'READY': return type === 'DINE_IN' ? 'SERVED' : type === 'DELIVERY' ? 'OUT_FOR_DELIVERY' : 'COMPLETED';
      case 'SERVED': case 'OUT_FOR_DELIVERY': return 'COMPLETED';
      default: return null;
    }
  }

  function price(subtotalC, type, coupon, manual = null) {
    let discountC = 0;
    if (coupon) {
      discountC = coupon.discountType === 'PERCENTAGE'
        ? halfUp((subtotalC * coupon.discountValue) / 100)
        : toCents(coupon.discountValue);
      discountC = Math.max(0, Math.min(discountC, subtotalC));
      if (coupon.maxDiscount != null) discountC = Math.min(discountC, toCents(coupon.maxDiscount));
    }
    if (manual) {
      // like ManualDiscount: after the coupon, never more than what is left
      const left = subtotalC - discountC;
      discountC += Math.max(0, Math.min(left, manual.percent != null ? halfUp((left * manual.percent) / 100) : manual.amountC));
    }
    const discounted = subtotalC - discountC;
    const taxC = halfUp((discounted * TAX_RATE_PERCENT) / 100);
    const feeC = type === 'DELIVERY' && discounted < FREE_DELIVERY_THRESHOLD ? DELIVERY_FEE : 0;
    return { subtotal: subtotalC, discount: discountC, tax: taxC, deliveryFee: feeC, total: discounted + taxC + feeC };
  }

  function validateCoupon(code, subtotalC, now) {
    const normalized = String(code || '').trim().toUpperCase();
    const coupon = COUPONS.find((c) => c.code === normalized);
    if (!coupon) return { code: normalized, error: ['COUPON_NOT_FOUND', `Coupon ${normalized} does not exist`] };
    if (coupon.validUntil && now > Date.parse(coupon.validUntil)) return { code: normalized, error: ['COUPON_EXPIRED', `Coupon ${normalized} has expired`] };
    if (subtotalC < toCents(coupon.minimumSubtotal)) {
      return { code: normalized, error: ['COUPON_MINIMUM_NOT_MET', `Coupon ${normalized} requires a subtotal of at least ${coupon.minimumSubtotal} TL`, { code: normalized, minimumSubtotal: coupon.minimumSubtotal }] };
    }
    return { code: normalized, coupon };
  }

  // ------------------------------------------------------------------------------------------- state
  let state = null;

  function freshState() {
    const menu = MENU.map((item) => ({ ...item, tags: [...item.tags], optionGroups: [] }));
    const ids = { group: 1, choice: 1 };
    for (const [dishId, ...groups] of OPTIONS) {
      menu.find((m) => m.id === dishId).optionGroups = groups.map(([name, minSelect, maxSelect, choices]) => ({
        id: ids.group++, name, minSelect, maxSelect, choices: choices.map(([choice, extra]) => ({ id: ids.choice++, name: choice, priceDeltaC: extra * 100 })),
      }));
    }
    const orders = generateHistory(menu);
    const fresh = {
      createdAt: Date.now(), nextOrderId: 1001 + orders.length, nextMenuId: MENU.length + 1, menu, orders,
      nextGroupId: ids.group, nextChoiceId: ids.choice,
      bills: [], links: [], sims: {}, closings: {}, nextBillId: 5001, nextPaymentId: 1, nextRefundId: 1,
    };
    seedBills(fresh);
    return fresh;
  }

  function load() {
    try {
      const stored = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
      if (stored && Date.now() - stored.createdAt < 12 * 60 * MINUTE) return stored;
    } catch { /* ignore */ }
    return null;
  }

  function save() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch { /* storage full or blocked — keep in memory */ }
  }

  state = load() || freshState();
  save();

  const channel = 'BroadcastChannel' in window ? new BroadcastChannel('sofra-demo') : null;
  const streams = new Set();

  /** Pushes an order event to this tab's open event streams (the simulated SSE connections). */
  function publish(event, name = 'order') {
    const frame = new TextEncoder().encode(`id:${Date.now()}\nevent:${name}\ndata:${JSON.stringify(event)}\n\n`);
    streams.forEach((controller) => { try { controller.enqueue(frame); } catch { streams.delete(controller); } });
  }

  // Other tabs send the changed record itself: localStorage writes can reach another tab's renderer process later
  // than the BroadcastChannel message, so the message — not storage — is the source of truth for live updates.
  channel?.addEventListener('message', ({ data }) => {
    if (data?.type === 'order') {
      const index = state.orders.findIndex((o) => o.id === data.order.id);
      if (index >= 0) state.orders[index] = data.order; else state.orders.push(data.order);
      state.nextOrderId = Math.max(state.nextOrderId, data.order.id + 1);
      publish(data.event);
    } else if (data?.type === 'payments') {
      Object.assign(state, data.payments);
      publish(data.event, 'payment');
    } else if (data?.type === 'guest') {
      state.guestRequests = data.guestRequests;
      state.nextGuestRequestId = Math.max(state.nextGuestRequestId || 1, data.nextGuestRequestId);
      publish(data.event, 'guest');
    } else if (data?.type === 'stock') {
      state.stock = data.stock;
      state.menu = data.menu;
      if (data.event) publish(data.event, 'stock');
    } else if (data?.type === 'menu') {
      state.menu = data.menu;
      state.nextMenuId = Math.max(state.nextMenuId, data.nextMenuId);
    }
  });

  function changed(order, kind, previousStatus) {
    save();
    const event = {
      kind, orderId: order.id, orderType: order.type, status: order.status, previousStatus: previousStatus || null,
      tableCode: order.table ? order.table.code : null, customerName: order.customerName, occurredAt: iso(order.updatedAt),
    };
    publish(event);
    channel?.postMessage({ type: 'order', event, order });
  }

  function menuChanged() {
    save();
    channel?.postMessage({ type: 'menu', menu: state.menu, nextMenuId: state.nextMenuId });
  }

  // ------------------------------------------------------------------------------------------- domain
  function buildLines(items) {
    return items.map((item) => {
      const dish = state.menu.find((m) => m.id === Number(item.menuItemId));
      if (!dish || dish.archived) throw new Problem(422, 'MENU_ITEM_NOT_FOUND', `Menu item ${item.menuItemId} does not exist`, { parameters: { menuItemId: item.menuItemId } });
      if (!dish.available || dish.stockOut) throw new Problem(422, 'MENU_ITEM_UNAVAILABLE', `${dish.name} is sold out`, { parameters: { menuItemId: dish.id, name: dish.name } });
      const quantity = Number(item.quantity);
      const options = resolveOptions(dish, item.optionIds || []);
      const unitPriceC = dish.priceC + options.reduce((sum, o) => sum + o.priceDeltaC, 0);
      if (item.course != null && !(Number.isInteger(item.course) && item.course >= 1 && item.course <= 3)) {
        throw new Problem(400, 'VALIDATION_FAILED', 'Request body is invalid.', { errors: [{ field: 'items[].course', message: 'must be between 1 and 3' }] });
      }
      return {
        menuItemId: dish.id, name: dish.name, icon: dish.icon, category: dish.category, unitPriceC,
        quantity, note: clean(item.note), lineTotalC: unitPriceC * quantity, options,
        station: dish.stationCode ?? DEFAULT_STATION[dish.category], course: item.course ?? DEFAULT_COURSE[dish.category] ?? 1,
      };
    });
  }

  /** Same rules as MenuItem.resolveOptions: known choices only, no group over its maximum, required groups defaulted. */
  function resolveOptions(dish, ids) {
    const groups = dish.optionGroups || [];
    const wanted = new Set(ids.map(Number));
    for (const id of wanted) {
      if (!groups.some((g) => g.choices.some((c) => c.id === id))) {
        throw new Problem(422, 'OPTION_NOT_FOUND', `That option does not belong to ${dish.name}`, { parameters: { menuItemId: String(id), name: dish.name } });
      }
    }
    return groups.flatMap((group) => {
      let picked = group.choices.filter((c) => wanted.has(c.id));
      if (picked.length > group.maxSelect) {
        throw new Problem(422, 'OPTION_LIMIT', `${group.name} allows at most ${group.maxSelect} choices`, { parameters: { group: group.name, max: group.maxSelect } });
      }
      if (picked.length < group.minSelect) picked = [...picked, ...group.choices.filter((c) => !picked.includes(c)).slice(0, group.minSelect - picked.length)];
      return picked.map((c) => ({ choiceId: c.id, group: group.name, choice: c.name, priceDeltaC: c.priceDeltaC }));
    });
  }

  const optionText = (line) => (line.options?.length ? line.options.map((o) => o.choice).join(' · ') : null);

  function createOrder(details, lines, coupon, at, actor) {
    const subtotal = lines.reduce((sum, line) => sum + line.lineTotalC, 0);
    const pricing = price(subtotal, details.type, coupon);
    return {
      id: state.nextOrderId++,
      type: details.type,
      status: 'PENDING',
      customerName: String(details.customerName).trim(),
      customerPhone: clean(details.customerPhone),
      table: details.type === 'DINE_IN' ? details.table : null,
      deliveryAddress: details.type === 'DELIVERY' ? clean(details.deliveryAddress) : null,
      note: clean(details.note),
      paymentMethod: details.paymentMethod,
      couponCode: coupon ? coupon.code : null,
      lines,
      pricing,
      createdAt: at,
      updatedAt: at,
      createdBy: actor,
      cancellationReason: null,
      history: [{ status: 'PENDING', at, by: actor }],
    };
  }

  function transition(order, status, at, actor) {
    order.status = status;
    order.updatedAt = at;
    order.history.push({ status, at, by: actor });
  }

  function orderView(order) {
    const next = nextStatus(order.status, order.type);
    return {
      id: order.id,
      type: order.type,
      status: order.status,
      nextStatus: next,
      cancellable: order.status === 'PENDING' || order.status === 'PREPARING',
      customerName: order.customerName,
      customerPhone: order.customerPhone,
      table: order.table ? { id: order.table.id, code: order.table.code } : null,
      deliveryAddress: order.deliveryAddress,
      note: order.note,
      paymentMethod: order.paymentMethod,
      couponCode: order.couponCode,
      lines: order.lines.map((line, index) => lineView(line, index, order)),
      heldCourses: heldCourses(order),
      channel: order.channel ?? null,
      itemCount: order.lines.reduce((sum, line) => sum + line.quantity - (line.voided || 0), 0),
      manualDiscount: order.manual ? { percent: order.manual.percent ?? null, amount: order.manual.amountC != null ? toLira(order.manual.amountC) : null } : null,
      adjustments: (order.adjustments || []).map((a) => ({ ...a, amount: a.amountC != null ? toLira(a.amountC) : null, amountC: undefined, at: iso(a.at) })),
      pricing: pricingView(order.pricing),
      createdAt: iso(order.createdAt),
      updatedAt: iso(order.updatedAt),
      statusSince: iso(order.history[order.history.length - 1].at),
      createdBy: order.createdBy,
      cancellationReason: order.cancellationReason,
      history: order.history.map((change) => ({ status: change.status, at: iso(change.at), by: change.by })),
    };
  }

  // Line ids are derived from the order, so demo history needs no stored ids: order 1042, line 2 → 104202.
  // Lines from before courses existed went to the kitchen with their order; a held line has firedAt null.
  const lineView = (line, index, order) => ({
    id: order?.id ? order.id * 100 + index + 1 : null, voidedQuantity: line.voided || 0, compedQuantity: line.comped || 0,
    menuItemId: line.menuItemId, name: line.name, icon: line.icon, category: line.category, unitPrice: toLira(line.unitPriceC),
    quantity: line.quantity, note: line.note, lineTotal: toLira(line.lineTotalC),
    options: (line.options || []).map((o) => ({ choiceId: o.choiceId, group: o.group, choice: o.choice, priceDelta: toLira(o.priceDeltaC) })),
    station: line.station ?? DEFAULT_STATION[line.category], course: line.course ?? 1,
    firedAt: line.firedAt === null ? null : iso(line.firedAt ?? order?.createdAt ?? Date.now()), readyAt: line.readyAt ? iso(line.readyAt) : null,
  });
  const isHeld = (line) => line.firedAt === null && line.quantity - (line.voided || 0) > 0;
  const heldCourses = (order) => [...new Set(order.lines.filter(isHeld).map((l) => l.course))].sort((a, b) => a - b);
  const pricingView = (p) => ({ subtotal: toLira(p.subtotal), discount: toLira(p.discount), tax: toLira(p.tax), deliveryFee: toLira(p.deliveryFee), total: toLira(p.total) });
  const menuView = (m) => ({
    id: m.id, name: m.name, description: m.description, category: m.category, price: toLira(m.priceC), icon: m.icon,
    prepTimeMinutes: m.prepTimeMinutes, popular: m.popular, available: m.available && !m.archived && !m.stockOut, tags: [...m.tags].sort(),
    stockOut: Boolean(m.stockOut),
    stationCode: m.stationCode ?? DEFAULT_STATION[m.category],
    optionGroups: (m.optionGroups || []).map((g) => ({ id: g.id, name: g.name, minSelect: g.minSelect, maxSelect: g.maxSelect,
      choices: g.choices.map((c) => ({ id: c.id, name: c.name, priceDelta: toLira(c.priceDeltaC) })) })),
  });

  // ------------------------------------------------------------------------------------------- demo history
  function generateHistory(menu) {
    let seed = 20260101;
    const random = () => {
      seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const gaussian = () => Math.sqrt(-2 * Math.log(random() || 1e-9)) * Math.cos(2 * Math.PI * random());
    const pick = (list) => list[Math.floor(random() * list.length)];
    const guests = ['Ayşe Kaya', 'Mehmet Yıldız', 'Zeynep Arslan', 'Emre Tunç', 'Elif Doğan', 'Burak Şahin', 'Selin Öztürk',
      'Can Erdem', 'Deniz Bulut', 'Ece Güneş', 'Mert Çelik', 'Gizem Sarı', 'Onur Koç', 'Derya Metin', 'Kerem Aydın',
      'İrem Toprak', 'Barış Yalçın', 'Melis Çakır', 'Hakan Oral', 'Sena Uslu', 'Emma Walker', 'Lucas Martin', 'Sofia Rossi'];
    const addresses = ['Moda Cad. No:12 D:3, Kadıköy', 'Bağdat Cad. No:245 D:8, Kadıköy', 'Vali Konağı Cad. No:41, Şişli',
      'Akarsu Yokuşu No:7, Cihangir, Beyoğlu', 'Bebek Yolu No:15, Arnavutköy, Beşiktaş', 'İcadiye Cad. No:22, Kuzguncuk, Üsküdar'];
    const lineNotes = ['No onions', 'Extra lemon', 'Mild, please', 'Sauce on the side'];
    const byCategory = (category) => menu.filter((m) => m.category === category);
    const weighted = (category) => {
      const candidates = byCategory(category);
      let target = random() * candidates.reduce((sum, m) => sum + (m.popular ? 3 : 1), 0);
      for (const m of candidates) { target -= m.popular ? 3 : 1; if (target < 0) return m; }
      return candidates[candidates.length - 1];
    };
    const minutes = (min, max) => (min + random() * (max - min)) * MINUTE;
    const now = Date.now();
    const today = new Date(now); today.setHours(0, 0, 0, 0);
    const drafts = [];

    const draft = (createdAt) => {
      const p = random();
      const type = p < 0.56 ? 'DINE_IN' : p < 0.78 ? 'TAKEAWAY' : 'DELIVERY';
      const basket = new Map();
      const people = type === 'DINE_IN' ? 1 + Math.floor(random() * 4) : 1 + Math.floor(random() * 2);
      const add = (dish, n = 1) => basket.set(dish, (basket.get(dish) || 0) + n);
      for (let i = 0; i < people; i++) {
        if (random() < 0.92) add(weighted('MAIN'));
        if (random() < 0.75) add(weighted('DRINK'));
      }
      if (random() < 0.5) add(weighted('STARTER'), 1 + Math.floor(random() * 2));
      if (random() < 0.38) add(weighted('DESSERT'), 1 + Math.floor(random() * people));
      if (!basket.size) add(weighted('MAIN'));
      const lines = [...basket].map(([dish, quantity]) => ({
        menuItemId: dish.id, name: dish.name, icon: dish.icon, category: dish.category, unitPriceC: dish.priceC, quantity,
        note: random() < 0.07 ? pick(lineNotes) : null, lineTotalC: dish.priceC * quantity,
      }));
      const kitchen = Math.max(...lines.map((l) => menu.find((m) => m.id === l.menuItemId).prepTimeMinutes)) + Math.floor(random() * 7);
      const cancelled = createdAt < now - 40 * MINUTE && random() < 0.035;
      const steps = [];
      const preparing = createdAt + minutes(1, 4);
      if (cancelled) {
        const cancelAt = createdAt + minutes(1, 7);
        if (cancelAt > preparing) steps.push(['PREPARING', preparing, 'chef']);
        steps.push(['CANCELLED', cancelAt, 'waiter']);
      } else {
        const ready = preparing + kitchen * MINUTE + random() * 2 * MINUTE;
        steps.push(['PREPARING', preparing, 'chef'], ['READY', ready, 'chef']);
        if (type === 'DINE_IN') {
          const served = ready + minutes(1, 4);
          steps.push(['SERVED', served, 'waiter'], ['COMPLETED', served + minutes(25, 60), 'waiter']);
        } else if (type === 'DELIVERY') {
          const out = ready + minutes(2, 7);
          steps.push(['OUT_FOR_DELIVERY', out, 'waiter'], ['COMPLETED', out + minutes(12, 30), 'waiter']);
        } else {
          steps.push(['COMPLETED', ready + minutes(3, 12), 'waiter']);
        }
      }
      return { createdAt, type, lines, steps, cancelled };
    };

    for (let d = 13; d >= 0; d--) {
      const day = new Date(today); day.setDate(today.getDate() - d);
      const weekend = [0, 5, 6].includes(day.getDay());
      const count = Math.round((22 + Math.floor(random() * 10) + (weekend ? 12 : 0)) * (1 + 0.03 * (13 - d)));
      for (let i = 0; i < count; i++) {
        const q = random();
        let minute = q < 0.38 ? 12 * 60 + 50 + gaussian() * 45 : q < 0.88 ? 19 * 60 + 50 + gaussian() * 70 : 10 * 60 + random() * 13 * 60;
        minute = Math.min(Math.max(minute, 600), 1410);
        const createdAt = day.getTime() + minute * MINUTE;
        if (createdAt < now - 35 * MINUTE) drafts.push(draft(createdAt));
      }
    }
    for (const ago of [31, 26, 22, 18, 14, 11, 8, 5, 3, 1]) drafts.push(draft(now - ago * MINUTE - random() * 50000));
    drafts.sort((a, b) => a.createdAt - b.createdAt);

    const occupied = new Set();
    const orders = [];
    let id = 1001;
    for (const d of drafts) {
      const reached = d.steps.filter(([, at]) => at <= now);
      const finalStatus = reached.length ? reached[reached.length - 1][0] : 'PENDING';
      let table = null;
      let type = d.type;
      if (type === 'DINE_IN') {
        if (ACTIVE.includes(finalStatus)) {
          const free = TABLES.filter((t) => !occupied.has(t.id));
          table = free.length ? pick(free) : null;
          if (table) occupied.add(table.id); else type = 'TAKEAWAY';
        } else {
          table = pick(TABLES);
        }
      }
      const subtotal = d.lines.reduce((sum, l) => sum + l.lineTotalC, 0);
      const usable = COUPONS.filter((c) => !c.validUntil && subtotal >= toCents(c.minimumSubtotal));
      const coupon = random() < 0.12 && usable.length ? pick(usable) : null;
      const pay = random();
      const order = {
        id: id++, type, status: 'PENDING', customerName: pick(guests),
        customerPhone: type === 'DELIVERY' || random() < 0.3 ? `+90 5${30 + Math.floor(random() * 25)} ${String(Math.floor(random() * 1000)).padStart(3, '0')} ${String(Math.floor(random() * 100)).padStart(2, '0')} ${String(Math.floor(random() * 100)).padStart(2, '0')}` : null,
        table: table ? { ...table } : null,
        deliveryAddress: type === 'DELIVERY' ? pick(addresses) : null,
        note: null,
        paymentMethod: type === 'DELIVERY' ? (pay < 0.55 ? 'ONLINE' : pay < 0.85 ? 'CARD' : 'CASH') : (pay < 0.68 ? 'CARD' : 'CASH'),
        couponCode: coupon ? coupon.code : null,
        lines: d.lines,
        pricing: price(subtotal, type, coupon),
        createdAt: d.createdAt, updatedAt: d.createdAt, createdBy: 'waiter', cancellationReason: null,
        history: [{ status: 'PENDING', at: d.createdAt, by: 'waiter' }],
      };
      // A dine-in guest who found no free table took the food away instead, so skip table service.
      const steps = type === d.type ? reached : reached.filter(([status]) => status !== 'SERVED');
      for (const [status, at, by] of steps) {
        if (status === 'CANCELLED') order.cancellationReason = pick(['Guest changed their mind', 'Item ran out in the kitchen', 'Guest left']);
        transition(order, status, at, by);
      }
      orders.push(order);
    }
    return orders;
  }

  // ------------------------------------------------------------------------------------------- analytics
  function dashboard(days) {
    const now = Date.now();
    const today = new Date(now); today.setHours(0, 0, 0, 0);
    const from = new Date(today); from.setDate(today.getDate() - (days - 1));
    const start = from.getTime();
    const end = today.getTime() + 24 * 60 * MINUTE;
    const previousStart = new Date(from); previousStart.setDate(from.getDate() - days);
    const current = state.orders.filter((o) => o.createdAt >= start && o.createdAt < end);
    const previous = state.orders.filter((o) => o.createdAt >= previousStart.getTime() && o.createdAt < start);
    const sold = (list) => list.filter((o) => o.status !== 'CANCELLED');
    const revenue = (list) => list.reduce((sum, o) => sum + o.pricing.total, 0);

    const kpis = (list) => {
      const s = sold(list);
      const minutesList = s.flatMap((o) => {
        const ready = o.history.find((c) => c.status === 'READY');
        return ready ? [(ready.at - o.createdAt) / MINUTE] : [];
      });
      return {
        revenue: toLira(revenue(s)),
        orders: s.length,
        averageOrderValue: s.length ? toLira(halfUp(revenue(s) / s.length)) : 0,
        cancelledOrders: list.length - s.length,
        itemsSold: s.reduce((sum, o) => sum + o.lines.reduce((n, l) => n + l.quantity, 0), 0),
        averageKitchenMinutes: minutesList.length ? Math.round((minutesList.reduce((a, b) => a + b, 0) / minutesList.length) * 10) / 10 : null,
      };
    };

    const daily = [];
    for (let i = 0; i < days; i++) {
      const dayStart = new Date(from); dayStart.setDate(from.getDate() + i);
      const dayEnd = new Date(dayStart); dayEnd.setDate(dayStart.getDate() + 1);
      const list = sold(current).filter((o) => o.createdAt >= dayStart.getTime() && o.createdAt < dayEnd.getTime());
      const date = `${dayStart.getFullYear()}-${String(dayStart.getMonth() + 1).padStart(2, '0')}-${String(dayStart.getDate()).padStart(2, '0')}`;
      daily.push({ date, revenue: toLira(revenue(list)), orders: list.length });
    }
    const hourly = Array.from({ length: 24 }, (_, hour) => {
      const list = sold(current).filter((o) => new Date(o.createdAt).getHours() === hour);
      return { hour, orders: list.length, revenue: toLira(revenue(list)) };
    });
    const lineStats = new Map();
    const categoryStats = new Map();
    for (const o of sold(current)) {
      for (const l of o.lines) {
        const item = lineStats.get(l.name) || { name: l.name, icon: l.icon, quantity: 0, revenue: 0 };
        item.quantity += l.quantity; item.revenue += l.lineTotalC; lineStats.set(l.name, item);
        const cat = categoryStats.get(l.category) || { category: l.category, quantity: 0, revenue: 0 };
        cat.quantity += l.quantity; cat.revenue += l.lineTotalC; categoryStats.set(l.category, cat);
      }
    }
    const statuses = ['PENDING', 'PREPARING', 'READY', 'SERVED', 'OUT_FOR_DELIVERY', 'COMPLETED', 'CANCELLED']
      .map((status) => ({ status, count: current.filter((o) => o.status === status).length }));
    const breakdown = (key, values, name) => values.map((value) => {
      const list = sold(current).filter((o) => o[key] === value);
      return { [name]: value, orders: list.length, revenue: toLira(revenue(list)) };
    });
    const toDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return {
      from: toDate(from), to: toDate(today), days,
      current: kpis(current), previous: kpis(previous),
      activeOrders: state.orders.filter((o) => ACTIVE.includes(o.status)).length,
      daily, hourly,
      categories: [...categoryStats.values()].sort((a, b) => b.revenue - a.revenue).map((c) => ({ ...c, revenue: toLira(c.revenue) })),
      topItems: [...lineStats.values()].sort((a, b) => b.quantity - a.quantity || b.revenue - a.revenue).slice(0, 5)
        .map((i) => ({ ...i, revenue: toLira(i.revenue) })),
      statuses,
      orderTypes: breakdown('type', ['DINE_IN', 'TAKEAWAY', 'DELIVERY'], 'type'),
      payments: breakdown('paymentMethod', ['CASH', 'CARD', 'ONLINE'], 'method'),
    };
  }

  // ------------------------------------------------------------------------------------------- routing
  const ROLES = { staff: ['ADMIN', 'WAITER', 'KITCHEN'], till: ['ADMIN', 'WAITER'], manager: ['ADMIN'] };

  function authenticate(headers, access) {
    if (access === 'public') return null;
    const header = headers.Authorization || headers.authorization || '';
    // demo tokens: "demo.<user>" after a password, "demo.pin.<user>" after a PIN
    const subject = header.startsWith('Bearer demo.') ? header.slice('Bearer demo.'.length) : null;
    const method = subject?.startsWith('pin.') ? 'pin' : 'pwd';
    const username = method === 'pin' ? subject.slice(4) : subject;
    const user = username && USERS[username];
    if (!user) throw new Problem(401, 'UNAUTHORIZED', 'Authentication is required to access this resource.');
    if (!ROLES[access].includes(user.role)) throw new Problem(403, 'FORBIDDEN', 'Your role is not allowed to perform this action.');
    return { username, method, ...user };
  }

  /** Like StepUpAuthentication: a PIN session never moves money or registers tills. */
  function requirePassword(user) {
    if (user.method !== 'pwd') throw new Problem(403, 'REAUTH_REQUIRED', 'Confirm your password to continue.', { parameters: { windowMinutes: 5 } });
  }

  const pins = () => (state.pins ??= { admin: '2468', waiter: '1357', chef: '9024' });
  const devices = () => (state.devices ??= []);
  const userView = (username, method) => ({ username, displayName: USERS[username].displayName, role: USERS[username].role, hasPin: Boolean(pins()[username]), method,
    totpEnabled: Boolean(state.totp?.[username]?.enabled), mfaSetupRequired: false });
  const deviceView = (d) => ({ id: d.id, name: d.name, createdBy: d.createdBy, createdAt: iso(d.createdAt), lastSeenAt: d.lastSeenAt ? iso(d.lastSeenAt) : null, active: !d.revokedAt });

  function deviceFor(headers) {
    const token = headers['X-Device-Token'] || headers['x-device-token'];
    const device = token && devices().find((d) => d.token === token && !d.revokedAt);
    if (!device) throw new Problem(403, 'DEVICE_NOT_REGISTERED', 'This device is not registered for PIN sign-in.');
    device.lastSeenAt = Date.now();
    return device;
  }

  function weakPin(pin) {
    const digits = [...pin].map(Number);
    const steps = digits.slice(1).map((d, i) => d - digits[i]);
    return new Set(digits).size === 1 || steps.every((x) => x === 1) || steps.every((x) => x === -1)
      || (pin.length % 2 === 0 && pin === pin.slice(0, 2).repeat(pin.length / 2));
  }

  // ------------------------------------------------------------------------------------------- table operations
  // Mirrors OrderAdjustmentService and SeatingService: voids, comps and discounts need a manager's PIN from a waiter
  // (a void only once the kitchen started), are repriced, and are refused when the bill is already paid beyond them.
  function approvedBy(user, approval, needed) {
    if (user.role === 'ADMIN') return user.username;
    if (!needed) return null;
    if (!approval) throw new Problem(403, 'APPROVAL_REQUIRED', 'A manager needs to approve this.');
    const manager = String(approval.manager || '').toLowerCase();
    if (USERS[manager]?.role !== 'ADMIN' || pins()[manager] !== String(approval.pin)) throw new Problem(403, 'APPROVAL_DENIED', 'The manager PIN is not correct.');
    return manager;
  }

  function adjustOrder(order, user, change) {
    if (!ACTIVE.includes(order.status)) throw new Problem(409, 'ORDER_NOT_EDITABLE', `Order #${order.id} can no longer be changed`, { parameters: { orderId: String(order.id), status: order.status } });
    const snapshot = structuredClone(order);
    const adjustment = change();
    const coupon = order.couponCode ? COUPONS.find((c) => c.code === order.couponCode) : null;
    order.pricing = price(order.lines.reduce((sum, l) => sum + l.lineTotalC, 0), order.type, coupon, order.manual);
    const bill = billFor(order);
    const m = money(bill);
    if (m.balance - m.pending < 0) {
      Object.assign(order, snapshot);
      throw new Problem(422, 'ADJUSTMENT_BELOW_PAID', 'The bill has already been paid beyond the new total; refund first');
    }
    order.updatedAt = Date.now();
    (order.adjustments ??= []).push({ ...adjustment, by: user.username, at: order.updatedAt });
    changed(order, 'UPDATED');
    return orderView(order);
  }

  function seatingAt(tableId) {
    return state.bills.filter((b) => b.status === 'OPEN' && b.table && b.table.id === tableId && hasActive(b)).pop() || null;
  }

  function tableById(id) {
    const table = TABLES.find((x) => x.id === Number(id));
    if (!table) throw new Problem(404, 'NOT_FOUND', `Table ${id} was not found`);
    return table;
  }

  function moveOrders(bill, to, kind, by) {
    for (const order of ordersOf(bill)) {
      (order.adjustments ??= []).push({ kind, itemName: null, quantity: null, amountC: null, detail: `${order.table?.code ?? '-'} → ${to.code}`, by, approvedBy: null, at: Date.now() });
      order.table = { id: to.id, code: to.code };
      order.updatedAt = Date.now();
    }
  }

  // ------------------------------------------------------------------------------------------- ordering from the table
  // Mirrors GuestService: a table's QR token opens its page, carts wait for a waiter, calls fold into the open one.
  const guestRequests = () => (state.guestRequests ??= []);
  const qr = () => (state.qr ??= { tokens: {}, issued: {} });

  function tableForToken(token) {
    const tableId = qr().tokens[token];
    const table = tableId && TABLES.find((x) => x.id === tableId);
    if (!table) throw new Problem(404, 'NOT_FOUND', 'Table ? was not found');
    return table;
  }

  function guestChanged(request) {
    save();
    const event = { id: request.id, kind: request.kind, status: request.status, tableCode: request.tableCode, occurredAt: iso(Date.now()) };
    publish(event, 'guest');
    // the guest's phone is another tab in the demo: tell the staff tabs
    channel?.postMessage({ type: 'guest', event, guestRequests: state.guestRequests, nextGuestRequestId: state.nextGuestRequestId });
  }

  const guestView = (r) => ({ id: r.publicId, kind: r.kind, status: r.status, total: r.totalC == null ? null : toLira(r.totalC), reason: r.reason,
    createdAt: iso(r.createdAt), lines: r.lines.map((l) => ({ name: l.name, icon: l.icon, quantity: l.quantity, options: l.options, lineTotal: toLira(l.lineTotalC) })) });
  const staffGuestView = (r) => ({ id: r.id, kind: r.kind, status: r.status, tableId: r.tableId, tableCode: r.tableCode, guestName: r.guestName, note: r.note,
    quotedTotal: r.totalC == null ? null : toLira(r.totalC), orderId: r.orderId ?? null, reason: r.reason, createdAt: iso(r.createdAt),
    handledAt: r.handledAt ? iso(r.handledAt) : null, handledBy: r.handledBy ?? null,
    lines: r.lines.map((l) => ({ name: l.name, icon: l.icon, quantity: l.quantity, options: l.options, note: l.note, lineTotal: toLira(l.lineTotalC) })) });

  function newGuestRequest(table, kind, extra = {}) {
    const open = guestRequests().filter((r) => r.tableId === table.id && r.status === 'OPEN');
    if (open.length >= 5) throw new Problem(429, 'GUEST_TOO_MANY_OPEN', 'This table already has several requests waiting', { parameters: { retryAfterSeconds: 60 } });
    state.nextGuestRequestId ??= 1;
    const request = { id: state.nextGuestRequestId++, publicId: crypto.randomUUID(), tableId: table.id, tableCode: table.code, kind, status: 'OPEN',
      guestName: null, note: null, totalC: null, reason: null, createdAt: Date.now(), lines: [], ...extra };
    guestRequests().push(request);
    guestChanged(request);
    return request;
  }

  function findGuestRequest(id) {
    const request = guestRequests().find((r) => r.id === Number(id));
    if (!request) throw new Problem(404, 'NOT_FOUND', `Guest request ${id} was not found`);
    if (request.status !== 'OPEN') throw new Problem(409, 'GUEST_REQUEST_HANDLED', 'This request has already been dealt with');
    return request;
  }

  // ------------------------------------------------------------------------------------------- printers
  // Mirrors PrintingService, except that a browser cannot open a socket: in the demo a ticket "prints" at once.
  const printers = () => {
    state.printers ??= [];
    // Printers saved by an older demo listed menu categories; V12 maps them to the default stations.
    state.printers.forEach((p) => { p.stations ??= [...new Set((p.categories || []).map((c) => DEFAULT_STATION[c]))].sort(); delete p.categories; });
    return state.printers;
  };
  const lineStation = (line) => line.station ?? DEFAULT_STATION[line.category];

  function privateIPv4(host) {
    const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
    if (!m) return false;
    const [a, b] = [Number(m[1]), Number(m[2])];
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }

  function validatePrinter(body, exceptId) {
    requireFields({ name: blank(body.name) ? 'must not be blank' : null, stations: !(body.stations || []).length ? 'must not be empty' : null });
    requireStations(body.stations);
    if (!privateIPv4(String(body.host || '').trim())) throw new Problem(422, 'PRINTER_HOST_NOT_ALLOWED', 'Not on the local network', { parameters: { host: String(body.host) } });
    if (!(body.port >= 9100 && body.port <= 9109)) throw new Problem(422, 'PRINTER_PORT_NOT_ALLOWED', 'Port not allowed', { parameters: { min: 9100, max: 9109 } });
    const name = String(body.name).trim();
    if (printers().some((p) => p.id !== exceptId && p.name.toLowerCase() === name.toLowerCase())) throw new Problem(422, 'PRINTER_NAME_TAKEN', 'Taken', { parameters: { name } });
  }

  const printerView = (p) => ({ ...p });

  function ticketText(printer, order) {
    const cols = printer.paperWidth === 58 ? 32 : 48;
    const center = (text) => ' '.repeat(Math.max(0, Math.floor((cols - text.length) / 2))) + text;
    const out = [center(printer.name.toUpperCase())];
    if (!order) {
      out.push(center('TEST SAYFASI'), `${printer.host}:${printer.port}`, '-'.repeat(cols), 'Yazici hazir. Turkce: ÇĞİÖŞÜ çğıöşü');
      return out.join('\n');
    }
    out.push(center(order.table ? `MASA ${order.table.code}` : `PAKET #${order.id}`), `#${order.id}  ${order.createdBy || ''}  ${order.customerName}`, '-'.repeat(cols));
    const held = heldCourses(order);
    for (const l of order.lines.filter((x) => printer.stations.includes(lineStation(x)) && x.firedAt !== null && x.quantity > (x.voided || 0))) {
      out.push(`${l.quantity - (l.voided || 0)} X ${l.name.toUpperCase()}`);
      if (optionText(l)) out.push(`   + ${optionText(l)}`);
      if (l.note) out.push(`   ! ${l.note}`);
    }
    if (held.length) out.push('-'.repeat(cols), `Kurs ${held.join(', ')} bekliyor`);
    return out.join('\n');
  }


  // ------------------------------------------------------------------------------------------- stock and recipes
  // Mirrors StockService and RecipeService: an append-only ledger of movements in base units (g, ml, pieces), prices
  // per kg / l / piece, recipes per dish and option (matched by option names), deduction when an order completes.
  const BULK = { GRAM: 1000, MILLILITRE: 1000, PIECE: 1 };
  const BULK_UNIT = { GRAM: 'kg', MILLILITRE: 'l', PIECE: 'piece' };
  const INPUT_UNITS = { G: ['GRAM', 1], KG: ['GRAM', 1000], ML: ['MILLILITRE', 1], L: ['MILLILITRE', 1000], PIECE: ['PIECE', 1] };
  const qty = (value) => Math.round(Number(value) * 1000) / 1000;
  // Same data as DemoStockSeeder: name, unit, price per bulk unit, low level, opening stock, tracked.
  const STOCK_SEED = [
    ['Kırmızı mercimek', 'GRAM', 90, 2000, 12000], ['Soğan', 'GRAM', 30, 3000, 15000], ['Tereyağı', 'GRAM', 520, 1500, 6000],
    ['Yufka', 'PIECE', 12, 20, 120], ['Beyaz peynir', 'GRAM', 380, 1000, 5000], ['Nohut', 'GRAM', 85, 1500, 8000],
    ['Tahin', 'MILLILITRE', 240, 500, 3000], ['Domates', 'GRAM', 45, 4000, 20000], ['Salatalık', 'GRAM', 40, 1500, 8000],
    ['Kuzu kıyma', 'GRAM', 780, 4000, 15000], ['Dana kıyma', 'GRAM', 650, 3000, 12000], ['Döner eti', 'GRAM', 700, 3000, 10000],
    ['Lavaş', 'PIECE', 6, 30, 150], ['Pide', 'PIECE', 10, 15, 60], ['Yoğurt', 'GRAM', 110, 2000, 12000],
    ['Sarımsak', 'GRAM', 160, 300, 2000], ['Mantı hamuru', 'GRAM', 240, 1500, 8000], ['Tavuk but', 'GRAM', 260, 3000, 12000],
    ['Bulgur', 'GRAM', 70, 1500, 8000], ['Patlıcan', 'GRAM', 50, 2000, 10000], ['Pirinç', 'GRAM', 75, 2000, 10000],
    ['Levrek', 'PIECE', 280, 6, 18], ['Kadayıf', 'GRAM', 180, 800, 4000], ['Künefe peyniri', 'GRAM', 420, 800, 4000],
    ['Antep fıstığı', 'GRAM', 1400, 500, 560], ['Baklava', 'PIECE', 25, 40, 160], ['Kaymak', 'GRAM', 600, 300, 1500],
    ['Dondurma', 'MILLILITRE', 300, 800, 4000], ['Süt', 'MILLILITRE', 40, 4000, 20000], ['Türk kahvesi', 'GRAM', 900, 300, 2000],
    ['Siyah çay', 'GRAM', 320, 400, 3000], ['Portakal', 'PIECE', 4, 40, 200], ['Limon', 'PIECE', 3, 30, 150],
    ['Ayran (şişe)', 'PIECE', 9, 24, 120], ['Maden suyu', 'PIECE', 8, 24, 120],
    ['Zeytinyağı', 'MILLILITRE', 450, null, 0, false], ['Şeker', 'GRAM', 40, null, 0, false],
  ];
  // dish → [ingredient, grams], or [ingredient, grams, group, choice, replaces]
  const RECIPE_SEED = {
    'Mercimek Çorbası': [['Kırmızı mercimek', 60], ['Soğan', 20], ['Tereyağı', 10]],
    'Sigara Böreği': [['Yufka', 1], ['Beyaz peynir', 60]],
    Humus: [['Nohut', 80], ['Tahin', 20], ['Tereyağı', 10]],
    'Çoban Salata': [['Domates', 120], ['Salatalık', 80], ['Soğan', 20], ['Zeytinyağı', 10], ['Limon', 0.25]],
    'Acılı Ezme': [['Domates', 100], ['Soğan', 20], ['Zeytinyağı', 10]],
    'Adana Kebap': [['Kuzu kıyma', 200], ['Lavaş', 1], ['Domates', 60], ['Domates', 80, 'Ekstra', 'Közlenmiş domates'], ['Lavaş', 1, 'Ekstra', 'Ekstra lavaş']],
    'İskender': [['Döner eti', 180], ['Pide', 1], ['Yoğurt', 80], ['Tereyağı', 25], ['Tereyağı', 12, 'Tereyağı', 'Az', 'Tereyağı'],
      ['Tereyağı', 15, 'Tereyağı', 'Bol'], ['Yoğurt', 60, 'Ekstra', 'Ekstra yoğurt']],
    Lahmacun: [['Kuzu kıyma', 60], ['Domates', 30], ['Soğan', 15], ['Limon', 0.25]],
    'Mantı': [['Mantı hamuru', 200], ['Yoğurt', 100], ['Sarımsak', 5], ['Tereyağı', 15], ['Sarımsak', 0, 'Sos', 'Sarımsaksız yoğurt', 'Sarımsak'],
      ['Tereyağı', 15, 'Ekstra', 'Ekstra tereyağı']],
    'Izgara Tavuk': [['Tavuk but', 220], ['Bulgur', 80], ['Patlıcan', 120]],
    'Köfte Tabağı': [['Dana kıyma', 180], ['Domates', 60], ['Soğan', 20]],
    'Karnıyarık': [['Patlıcan', 250], ['Dana kıyma', 100], ['Pirinç', 80]],
    'Levrek Izgara': [['Levrek', 1], ['Limon', 0.5]],
    'Sebzeli Güveç': [['Patlıcan', 120], ['Domates', 120], ['Pirinç', 80], ['Zeytinyağı', 15]],
    'Künefe': [['Kadayıf', 80], ['Künefe peyniri', 70], ['Antep fıstığı', 15], ['Tereyağı', 30], ['Şeker', 40],
      ['Dondurma', 80, 'Yanında', 'Dondurma'], ['Kaymak', 40, 'Yanında', 'Kaymak']],
    'Fıstıklı Baklava': [['Baklava', 4], ['Antep fıstığı', 10], ['Kaymak', 30]],
    'Fırın Sütlaç': [['Süt', 200], ['Pirinç', 30], ['Şeker', 30]],
    'Türk Kahvesi': [['Türk kahvesi', 7]], 'Demlik Çay': [['Siyah çay', 6]], Ayran: [['Ayran (şişe)', 1]],
    'Taze Portakal Suyu': [['Portakal', 3]], 'Ev Yapımı Limonata': [['Limon', 2], ['Şeker', 30]], 'Maden Suyu': [['Maden suyu', 1]],
  };

  function stock() {
    if (state.stock) return state.stock;
    const at = state.createdAt;
    const data = { ingredients: [], recipes: [], movements: [], nextIngredientId: 1, nextMovementId: 1 };
    for (const [name, unit, cost, low, opening, tracked = true] of STOCK_SEED) {
      const ingredient = { id: data.nextIngredientId++, name, unit, costC: cost * 100, lowStockLevel: tracked ? low : null, trackStock: tracked, archived: false };
      data.ingredients.push(ingredient);
      if (opening) data.movements.push({ id: data.nextMovementId++, ingredientId: ingredient.id, kind: 'COUNT', quantity: opening, unitCostC: ingredient.costC, orderId: null, reason: 'Opening stock', supplier: null, by: 'system', at });
    }
    const byName = new Map(data.ingredients.map((i) => [i.name, i.id]));
    for (const [dish, lines] of Object.entries(RECIPE_SEED)) {
      const item = state.menu.find((m) => m.name === dish);
      if (!item) continue;
      lines.forEach(([ingredient, quantity, group = null, choice = null, replaces = null]) => data.recipes.push({
        menuItemId: item.id, optionGroup: group, optionChoice: choice, ingredientId: byName.get(ingredient), quantity, replacesIngredientId: replaces ? byName.get(replaces) : null,
      }));
    }
    state.stock = data;
    return data;
  }

  const balanceOf = (id) => qty(stock().movements.filter((m) => m.ingredientId === id).reduce((sum, m) => sum + m.quantity, 0));
  const isLow = (i, balance) => i.trackStock && i.lowStockLevel != null && balance <= i.lowStockLevel;
  const isOut = (i, balance) => i.trackStock && balance <= 0;
  const valueC = (i, quantity, costC = i.costC) => (quantity * costC) / BULK[i.unit];

  function findIngredient(id) {
    const ingredient = stock().ingredients.find((i) => i.id === Number(id) && !i.archived);
    if (!ingredient) throw new Problem(404, 'INGREDIENT_NOT_FOUND', `Ingredient ${id} was not found`);
    return ingredient;
  }

  function ingredientView(i) {
    const onHand = balanceOf(i.id);
    return {
      id: i.id, name: i.name, unit: i.unit, bulkUnit: BULK_UNIT[i.unit], cost: toLira(i.costC), lowStockLevel: i.lowStockLevel, trackStock: i.trackStock,
      onHand, value: toLira(valueC(i, Math.max(0, onHand))), low: isLow(i, onHand), out: isOut(i, onHand),
    };
  }

  const movementView = (m) => ({ id: m.id, ingredientId: m.ingredientId, kind: m.kind, quantity: m.quantity, unitCost: toLira(m.unitCostC),
    orderId: m.orderId, reason: m.reason, supplier: m.supplier, by: m.by, at: iso(m.at) });

  function validateIngredient(body, exceptId) {
    const cost = Number(body.cost);
    requireFields({
      name: blank(body.name) ? 'must not be blank' : String(body.name).trim().length > 60 ? 'size must be between 0 and 60' : null,
      unit: !BULK[body.unit] ? 'must not be null' : null,
      cost: body.cost == null || !(cost >= 0) ? 'must be greater than or equal to 0' : null,
      lowStockLevel: body.lowStockLevel != null && !(Number(body.lowStockLevel) >= 0) ? 'must be greater than or equal to 0' : null,
    });
    const name = String(body.name).trim();
    if (stock().ingredients.some((i) => !i.archived && i.id !== exceptId && i.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
      throw new Problem(422, 'INGREDIENT_NAME_TAKEN', `There is already an ingredient called ${name}`, { parameters: { name } });
    }
  }

  const isUsed = (id) => stock().recipes.some((r) => r.ingredientId === id || r.replacesIngredientId === id);

  /** Same as StockService.portion: the dish's own lines, then each picked option adds or swaps in its own. */
  function portion(recipe, options) {
    const out = new Map();
    recipe.filter((r) => !r.optionGroup).forEach((r) => out.set(r.ingredientId, (out.get(r.ingredientId) || 0) + r.quantity));
    for (const option of options || []) {
      for (const r of recipe) {
        if (r.optionGroup && r.optionGroup.toLocaleLowerCase() === option.group.toLocaleLowerCase()
          && r.optionChoice.toLocaleLowerCase() === option.choice.toLocaleLowerCase()) {
          if (r.replacesIngredientId != null) out.delete(r.replacesIngredientId);
          out.set(r.ingredientId, (out.get(r.ingredientId) || 0) + r.quantity);
        }
      }
    }
    return out;
  }

  /** Sells dishes out or back in after stock moved; returns the names that changed. */
  function refreshAvailability(dishIds) {
    const soldOut = [];
    const backOnSale = [];
    for (const id of new Set(dishIds)) {
      const dish = state.menu.find((m) => m.id === id);
      if (!dish) continue;
      const out = stock().recipes.filter((r) => r.menuItemId === id && !r.optionGroup)
        .some((r) => { const i = stock().ingredients.find((x) => x.id === r.ingredientId); return i && isOut(i, balanceOf(i.id)); });
      if (Boolean(dish.stockOut) !== out) {
        dish.stockOut = out;
        (out ? soldOut : backOnSale).push(dish.name);
      }
    }
    return { soldOut: soldOut.sort(), backOnSale: backOnSale.sort() };
  }

  /** Re-checks the dishes made with these ingredients, warns about any that just went low, and tells the screens. */
  function stockMoved(wasLow) {
    const ids = [...wasLow.keys()];
    const low = ids.map((id) => stock().ingredients.find((i) => i.id === id))
      .filter((i) => !wasLow.get(i.id) && isLow(i, balanceOf(i.id))).map((i) => i.name).sort();
    const dishes = stock().recipes.filter((r) => !r.optionGroup && ids.includes(r.ingredientId)).map((r) => r.menuItemId);
    stockChanged({ lowIngredients: low, ...refreshAvailability(dishes) });
  }

  function stockChanged(change) {
    save();
    const event = { lowIngredients: [], soldOut: [], backOnSale: [], ...change, occurredAt: iso(Date.now()) };
    if (!event.lowIngredients.length && !event.soldOut.length && !event.backOnSale.length) {
      channel?.postMessage({ type: 'stock', stock: state.stock, menu: state.menu, event: null });
      return;
    }
    publish(event, 'stock');
    channel?.postMessage({ type: 'stock', stock: state.stock, menu: state.menu, event });
  }

  /** What a completed order used: voided units were never made, comped ones were. Once per order. */
  function deductForOrder(order) {
    const data = stock();
    if (data.movements.some((m) => m.orderId === order.id)) return;
    const used = new Map();
    for (const line of order.lines) {
      const made = line.quantity - (line.voided || 0);
      const recipe = data.recipes.filter((r) => r.menuItemId === line.menuItemId);
      if (made <= 0 || !recipe.length) continue;
      portion(recipe, line.options).forEach((quantity, id) => used.set(id, (used.get(id) || 0) + quantity * made));
    }
    const wasLow = new Map();
    used.forEach((quantity, id) => {
      const ingredient = data.ingredients.find((i) => i.id === id);
      if (!ingredient || quantity <= 0) return;
      wasLow.set(id, isLow(ingredient, balanceOf(id)));
      data.movements.push({ id: data.nextMovementId++, ingredientId: id, kind: 'SALE', quantity: -qty(quantity), unitCostC: ingredient.costC,
        orderId: order.id, reason: null, supplier: null, by: 'system', at: Date.now() });
    });
    if (wasLow.size) stockMoved(wasLow);
  }

  function recipeView(dish) {
    const lines = stock().recipes.filter((r) => r.menuItemId === dish.id);
    const ingredient = (id) => stock().ingredients.find((i) => i.id === id);
    const costC = lines.filter((r) => !r.optionGroup).reduce((sum, r) => sum + valueC(ingredient(r.ingredientId), r.quantity), 0);
    return {
      menuItemId: dish.id, name: dish.name, price: toLira(dish.priceC),
      lines: lines.map((r) => ({ ingredientId: r.ingredientId, ingredient: ingredient(r.ingredientId).name, unit: ingredient(r.ingredientId).unit, quantity: r.quantity,
        optionGroup: r.optionGroup, optionChoice: r.optionChoice, replacesIngredientId: r.replacesIngredientId, cost: toLira(valueC(ingredient(r.ingredientId), r.quantity)) })),
      cost: toLira(costC), foodCostPercent: lines.length ? Math.round((costC * 1000) / dish.priceC) / 10 : null,
    };
  }

  function stockReport(fromDay, toDay) {
    const from = new Date(`${fromDay}T00:00:00`).getTime();
    const to = new Date(`${toDay}T00:00:00`).getTime() + 24 * 60 * MINUTE;
    if (!(to > from) || to - from > 366 * 24 * 60 * MINUTE) throw new Problem(422, 'REPORT_PERIOD_INVALID', 'Pick a period of one day to a year', { parameters: { max: 366 } });
    const moved = stock().movements.filter((m) => m.at >= from && m.at < to);
    const rows = stock().ingredients.filter((i) => !i.archived || moved.some((m) => m.ingredientId === i.id)).map((i) => {
      const of = (kind) => moved.filter((m) => m.ingredientId === i.id && m.kind === kind);
      const q = (kind) => qty(of(kind).reduce((sum, m) => sum + m.quantity, 0));
      const v = (kind) => toLira(of(kind).reduce((sum, m) => sum + valueC(i, m.quantity, m.unitCostC), 0));
      return { ingredientId: i.id, name: i.name, unit: i.unit, sold: -q('SALE'), soldCost: -v('SALE'), wasted: -q('WASTE'), wasteCost: -v('WASTE'),
        delivered: q('DELIVERY'), deliveredCost: v('DELIVERY'), countDifference: q('COUNT'), varianceCost: v('COUNT'), onHand: balanceOf(i.id) };
    });
    const sum = (field) => Math.round(rows.reduce((total, r) => total + r[field], 0) * 100) / 100;
    return { from: iso(from), to: iso(to), rows, soldCost: sum('soldCost'), wasteCost: sum('wasteCost'), deliveredCost: sum('deliveredCost'), varianceCost: sum('varianceCost') };
  }

  // ------------------------------------------------------------------------------------------- delivery platforms
  // Mirrors ChannelService in its demo mode: every platform is a simulation, orders are made up from mapped dishes,
  // an unmapped item stops its order, and status or sold-out updates are only recorded.
  const CHANNELS = { YEMEKSEPETI: ['Yemeksepeti', 'YS-'], GETIR: ['Getir Yemek', 'GTR-'], TRENDYOL: ['Trendyol Go', 'TY-'] };
  const CHANNEL_ITEMS = [['Adana Kebap', 'Adana Kebap (Porsiyon)'], ['Lahmacun', 'Lahmacun'], ['İskender', 'İskender Kebap'],
    ['Mercimek Çorbası', 'Mercimek Çorbası'], ['Künefe', 'Künefe'], ['Ayran', 'Ayran 300 ml']];

  function channelState() {
    if (state.channelData) return state.channelData;
    const data = { mappings: [], orders: [], updates: [], nextId: 1 };
    for (const [channel, [, prefix]] of Object.entries(CHANNELS)) {
      CHANNEL_ITEMS.forEach(([dish, name], index) => {
        const item = state.menu.find((m) => m.name === dish);
        if (item) data.mappings.push({ channel, externalId: `${prefix}${1001 + index}`, externalName: name, menuItemId: item.id });
      });
    }
    state.channelData = data;
    return data;
  }

  const channelOf = (value) => {
    const channel = String(value).toUpperCase();
    if (!CHANNELS[channel]) throw new Problem(404, 'CHANNEL_NOT_FOUND', `Channel ${value} was not found`);
    return channel;
  };

  function recordUpdate(channel, what, target, value) {
    const data = channelState();
    data.updates.unshift({ channel, what, target, value, at: Date.now() });
    data.updates.length = Math.min(data.updates.length, 60);
  }

  function channelOrderView(record) {
    const mapped = (item) => channelState().mappings.some((m) => m.channel === record.channel && m.externalId === item.id);
    return {
      id: record.id, channel: record.channel, externalId: record.externalId, status: record.status, orderId: record.orderId,
      customerName: record.payload.customer.name, itemCount: record.payload.items.reduce((n, i) => n + i.quantity, 0),
      platformTotal: record.payload.total, ourTotal: record.ourTotal, priceDiffers: record.priceDiffers, problemCode: record.problemCode,
      problem: record.problem, unmapped: record.status === 'ATTENTION' ? record.payload.items.filter((i) => !mapped(i)).map((i) => ({ externalId: i.id, name: i.name })) : [],
      syncedStatus: record.syncedStatus, syncError: null, receivedAt: iso(record.receivedAt),
    };
  }

  /** Same as ChannelService.place: map every item, place it as a takeaway the platform already paid, or wait. */
  function placeChannelOrder(record) {
    const [label] = CHANNELS[record.channel];
    try {
      const items = record.payload.items.map((item) => {
        const mapping = channelState().mappings.find((m) => m.channel === record.channel && m.externalId === item.id);
        if (!mapping) throw new Problem(422, 'CHANNEL_ITEM_UNMAPPED', `"${item.name}" is not mapped to a dish`, { parameters: { name: item.name } });
        return { menuItemId: mapping.menuItemId, quantity: item.quantity, note: item.note || null, optionIds: [] };
      });
      const lines = buildLines(items);
      const order = createOrder({ type: 'TAKEAWAY', customerName: `${label} · ${record.payload.customer.name}`.slice(0, 80), paymentMethod: 'ONLINE',
        note: `${label} #${record.externalId}` }, lines, null, Date.now(), `channel:${record.channel.toLowerCase()}`);
      order.channel = record.channel;
      lines.forEach((l) => { l.firedAt = order.createdAt; });
      state.orders.push(order);
      attach(state, order, order.createdAt);
      const bill = billFor(order);
      const payment = newPayment(state, 'ONLINE', 'SUCCEEDED', money(bill).available, 0, Date.now(), `channel:${record.channel.toLowerCase()}`);
      payment.provider = record.channel;
      bill.payments.push(payment);
      Object.assign(record, { status: 'PLACED', orderId: order.id, ourTotal: toLira(order.pricing.total),
        priceDiffers: order.pricing.total !== toCents(record.payload.total), problemCode: null, problem: null });
      changed(order, 'CREATED', null);
    } catch (error) {
      if (!(error instanceof Problem)) throw error;
      Object.assign(record, { status: 'ATTENTION', problemCode: error.code, problem: error.message });
      save();
    }
  }

  function pushChannelStatus(order, status) {
    const record = order.channel && channelState().orders.find((o) => o.orderId === order.id);
    const value = { PREPARING: 'ACCEPTED', READY: 'READY', COMPLETED: 'PICKED_UP', CANCELLED: 'CANCELLED' }[status];
    if (!record || !value) return;
    record.syncedStatus = value;
    recordUpdate(record.channel, 'status', record.externalId, value);
  }

  function pushAvailability(dish) {
    const available = dish.available && !dish.archived && !dish.stockOut;
    channelState().mappings.filter((m) => m.menuItemId === dish.id)
      .forEach((m) => recordUpdate(m.channel, 'availability', m.externalId, available ? 'ON' : 'OFF'));
  }

  // ------------------------------------------------------------------------------------------- legal documents
  // Mirrors FiscalService with simulated devices: one document per closed bill, an ÖKC receipt for cash and card, an
  // e-Arşiv invoice when part was paid online or the guest asked for one. Nothing is declared to GİB.
  function fiscalState() {
    state.fiscal ??= { documents: [], nextId: 1, receipts: 0, invoices: 0 };
    return state.fiscal;
  }

  function isTckn(v) {
    if (!/^[1-9][0-9]{10}$/.test(v)) return false;
    const d = [...v].map(Number);
    const tenth = (((d[0] + d[2] + d[4] + d[6] + d[8]) * 7 - (d[1] + d[3] + d[5] + d[7])) % 10 + 10) % 10;
    return d[9] === tenth && d[10] === d.slice(0, 10).reduce((a, b) => a + b, 0) % 10;
  }

  function isVkn(v) {
    if (!/^[0-9]{10}$/.test(v)) return false;
    let sum = 0;
    for (let i = 0; i < 9; i++) {
      const tmp = (Number(v[i]) + 9 - i) % 10;
      sum += tmp === 9 ? 9 : (tmp * 2 ** (9 - i)) % 9;
    }
    return Number(v[9]) === (10 - (sum % 10)) % 10;
  }

  const maskTaxId = (v) => (v ? '•'.repeat(v.length - 3) + v.slice(-3) : null);

  function fiscalView(d) {
    return {
      id: d.id, billId: d.billId, kind: d.kind, status: d.status, provider: d.status === 'ISSUED' ? 'SIMULATED' : null, reference: d.reference,
      documentNumber: d.number, pdfUrl: null, total: d.totalC != null ? toLira(d.totalC) : null, tax: d.taxC != null ? toLira(d.taxC) : null,
      buyerName: d.buyerName, buyerTaxId: maskTaxId(d.buyerTaxId), attempts: d.attempts, lastError: null, nextAttemptAt: null,
      createdAt: iso(d.createdAt), issuedAt: d.issuedAt ? iso(d.issuedAt) : null, simulated: true,
    };
  }

  function issueFiscal(bill) {
    const data = fiscalState();
    let doc = data.documents.find((d) => d.billId === bill.id);
    if (doc && doc.status !== 'WAITING') return;
    const online = bill.payments.some((p) => p.status === 'SUCCEEDED' && p.method === 'ONLINE');
    if (!doc) {
      doc = { id: data.nextId++, billId: bill.id, kind: online ? 'E_ARSIV' : 'RECEIPT', status: 'PENDING', createdAt: Date.now(), attempts: 0 };
      data.documents.push(doc);
    }
    const orders = ordersOf(bill).filter((o) => o.status !== 'CANCELLED');
    const sequence = doc.kind === 'RECEIPT' ? ++data.receipts : ++data.invoices;
    Object.assign(doc, {
      status: 'ISSUED', attempts: doc.attempts + 1, issuedAt: Date.now(),
      totalC: orders.reduce((sum, o) => sum + o.pricing.total, 0), taxC: orders.reduce((sum, o) => sum + o.pricing.tax, 0),
      reference: `SIM-${doc.kind === 'RECEIPT' ? 'OKC' : 'EARSIV'}-${sequence}`,
      number: doc.kind === 'RECEIPT' ? `F${String(sequence).padStart(6, '0')}` : `SIM${new Date().getFullYear()}${String(sequence).padStart(9, '0')}`,
    });
    publish({ documentId: doc.id, billId: bill.id, status: doc.status, occurredAt: iso(Date.now()) }, 'fiscal');
  }

  const dayRange = (day) => {
    const from = new Date(`${day}T00:00:00`).getTime();
    return [from, from + 24 * 60 * MINUTE];
  };
  const todayIso = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

  // ------------------------------------------------------------------------------------------- two-step sign-in
  // Mirrors TwoFactorService with the browser's Web Crypto: RFC 6238 codes, replay protection, recovery codes.
  const totpState = () => (state.totp ??= {});
  const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const base32 = (bytes) => {
    let bits = 0; let value = 0; let out = '';
    for (const byte of bytes) { value = (value << 8) | byte; bits += 8; while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; } }
    return bits > 0 ? out + B32[(value << (5 - bits)) & 31] : out;
  };
  const unbase32 = (text) => {
    let bits = 0; let value = 0; const out = [];
    for (const c of text) { value = (value << 5) | B32.indexOf(c); bits += 5; if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; } }
    return new Uint8Array(out);
  };

  async function totpCode(secret, step) {
    const key = await crypto.subtle.importKey('raw', unbase32(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
    const counter = new DataView(new ArrayBuffer(8));
    counter.setUint32(4, step);
    counter.setUint32(0, Math.floor(step / 2 ** 32));
    const hash = new Uint8Array(await crypto.subtle.sign('HMAC', key, counter.buffer));
    const offset = hash[19] & 15;
    const binary = ((hash[offset] & 127) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
    return String(binary % 1000000).padStart(6, '0');
  }

  /** @return the matching step, or null */
  async function verifyTotp(entry, code) {
    if (!/^\d{6}$/.test(String(code || ''))) return null;
    const now = Math.floor(Date.now() / 30000);
    for (let step = now - 1; step <= now + 1; step++) {
      if (step > (entry.lastStep || 0) && await totpCode(entry.secret, step) === code) return step;
    }
    return null;
  }

  async function checkSecondFactor(entry, code) {
    const step = await verifyTotp(entry, String(code || '').trim());
    if (step != null) { entry.lastStep = step; save(); return true; }
    const normal = String(code || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    const index = (entry.recovery || []).indexOf(normal);
    if (normal.length === 10 && index >= 0) { entry.recovery.splice(index, 1); save(); return true; }
    return false;
  }

  function newRecoveryCodes() {
    const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789';
    return Array.from({ length: 10 }, () => Array.from(crypto.getRandomValues(new Uint8Array(10)), (b) => alphabet[b % alphabet.length]).join(''));
  }

  function requireFields(errors) {
    const list = Object.entries(errors).filter(([, message]) => message).map(([field, message]) => ({ field, message }));
    if (list.length) throw new Problem(400, 'VALIDATION_FAILED', 'One or more fields are invalid.', { errors: list });
  }

  function findOrder(id) {
    const order = state.orders.find((o) => o.id === Number(id));
    if (!order) throw new Problem(404, 'ORDER_NOT_FOUND', `Order ${id} was not found`, { parameters: { id: String(id) } });
    return order;
  }

  function findDish(id) {
    const dish = state.menu.find((m) => m.id === Number(id) && !m.archived);
    if (!dish) throw new Problem(404, 'MENU_ITEM_NOT_FOUND', `Menu item ${id} was not found`, { parameters: { id: String(id) } });
    return dish;
  }

  function validateDish(body, exceptId) {
    requireFields({
      name: blank(body.name) ? 'must not be blank' : null,
      category: blank(body.category) ? 'must not be null' : null,
      price: !(Number(body.price) > 0) ? 'must be greater than or equal to 0.01' : null,
      prepTimeMinutes: body.prepTimeMinutes != null && (body.prepTimeMinutes < 0 || body.prepTimeMinutes > 240) ? 'must be between 0 and 240' : null,
    });
    if (body.optionGroups != null) validateOptions(body.optionGroups);
    if (body.stationCode != null) requireStations([body.stationCode]);
    const name = String(body.name).trim();
    if (state.menu.some((m) => !m.archived && m.id !== exceptId && m.name.toLowerCase() === name.toLowerCase())) {
      throw new Problem(422, 'MENU_ITEM_NAME_TAKEN', `A menu item named '${name}' already exists`, { parameters: { name } });
    }
  }

  function validateOptions(groups) {
    const invalid = (message) => new Problem(422, 'MENU_OPTIONS_INVALID', message);
    if (groups.length > 8) throw invalid('A dish can have at most 8 option groups');
    const names = new Set();
    for (const group of groups) {
      const name = String(group.name || '').trim();
      const choices = group.choices || [];
      if (!name || names.has(name.toLowerCase())) throw invalid('Option groups need distinct names');
      names.add(name.toLowerCase());
      if (!choices.length || choices.length > 12) throw invalid(`${name} needs 1 to 12 choices`);
      if (!(group.minSelect >= 0 && group.minSelect <= group.maxSelect && group.maxSelect <= choices.length)) throw invalid(`${name}: min ≤ max ≤ choices`);
      const choiceNames = new Set(choices.map((c) => String(c.name || '').trim().toLowerCase()));
      if (choiceNames.has('') || choiceNames.size !== choices.length) throw invalid(`${name}: choices need distinct names`);
      if (choices.some((c) => Number(c.priceDelta || 0) < 0)) throw invalid('An option cannot lower the price');
    }
  }

  function applyDish(dish, body) {
    if (body.optionGroups != null) {
      state.nextGroupId ??= 1000;
      state.nextChoiceId ??= 1000;
      dish.optionGroups = body.optionGroups.map((g) => ({
        id: state.nextGroupId++, name: String(g.name).trim(), minSelect: Number(g.minSelect), maxSelect: Number(g.maxSelect),
        choices: g.choices.map((c) => ({ id: state.nextChoiceId++, name: String(c.name).trim(), priceDeltaC: toCents(c.priceDelta || 0) })),
      }));
    }
    dish.optionGroups ??= [];
    Object.assign(dish, {
      name: String(body.name).trim(), description: clean(body.description), category: body.category, priceC: toCents(body.price),
      icon: clean(body.icon), prepTimeMinutes: Number(body.prepTimeMinutes || 0), popular: Boolean(body.popular), tags: [...(body.tags || [])],
    });
    if (body.stationCode != null) dish.stationCode = body.stationCode;
    else dish.stationCode ??= DEFAULT_STATION[dish.category];
  }

  // ------------------------------------------------------------------------------------------- stations
  // Mirrors StationService.
  const stations = () => (state.stations ??= STATIONS.map((station) => ({ ...station })));

  function requireStations(codes) {
    const missing = codes.find((code) => !stations().some((station) => station.code === code));
    if (missing) throw new Problem(422, 'STATION_NOT_FOUND', `There is no station ${missing}`, { parameters: { code: String(missing) } });
  }

  function validateStation(body, exceptId) {
    const target = Number(body.targetMinutes);
    requireFields({
      name: blank(body.name) ? 'must not be blank' : String(body.name).trim().length > 40 ? 'size must be between 0 and 40' : null,
      targetMinutes: !(Number.isInteger(target) && target >= 1 && target <= 240) ? 'must be between 1 and 240' : null,
      code: exceptId == null && body.code != null && !/^[A-Z][A-Z0-9_]{1,19}$/.test(body.code) ? 'must match "[A-Z][A-Z0-9_]{1,19}"' : null,
    });
    const name = String(body.name).trim();
    if (stations().some((station) => station.id !== exceptId && station.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
      throw new Problem(422, 'STATION_NAME_TAKEN', `A station called ${name} exists`, { parameters: { name } });
    }
  }


  // ------------------------------------------------------------------------------------------- bills and payments
  // Mirrors the payment package of the real backend: one bill per dine-in seating or per takeaway/delivery order,
  // payments that can never exceed what is due, refunds for cancelled orders, QR links paid through a simulated
  // provider, and the end-of-day report.
  // Declared as functions, not constants: the demo history is generated (and its bills seeded) before this point.
  function pendingTtl() { return 30 * MINUTE; }
  function linkTtl() { return 2 * 60 * MINUTE; }

  function seedBills(target) {
    const sorted = [...target.orders].sort((a, b) => a.createdAt - b.createdAt);
    for (const order of sorted) {
      if (ACTIVE.includes(order.status)) {
        attach(target, order, order.createdAt);
        continue;
      }
      const at = order.history[order.history.length - 1].at;
      const bill = openBill(target, order, order.createdAt);
      if (order.status === 'COMPLETED') {
        const tipC = order.id % 3 === 0 && order.type !== 'DELIVERY' ? Math.round(order.pricing.total * (5 + (order.id % 6)) / 10000) * 100 : 0;
        const payment = newPayment(target, order.paymentMethod, 'SUCCEEDED', order.pricing.total, tipC, at, order.history[order.history.length - 1].by);
        if (order.paymentMethod === 'CASH') { payment.tenderedC = Math.ceil((order.pricing.total + tipC) / 5000) * 5000; payment.changeC = payment.tenderedC - order.pricing.total - tipC; }
        if (order.paymentMethod === 'CARD') payment.approvalCode = String((order.id * 7919) % 1000000).padStart(6, '0');
        if (order.paymentMethod === 'ONLINE') payment.provider = 'SIMULATED';
        bill.payments.push(payment);
      }
      refresh(bill, at, target);
    }
  }

  function openBill(target, order, at) {
    const bill = { id: target.nextBillId++, status: 'OPEN', table: order.type === 'DINE_IN' && order.table ? { id: order.table.id, code: order.table.code } : null,
      orderIds: [order.id], payments: [], openedAt: at, closedAt: null };
    target.bills.push(bill);
    return bill;
  }

  function newPayment(target, method, status, amountC, tipC, at, by) {
    return { id: target.nextPaymentId++, method, status, amountC, tipC, tenderedC: null, changeC: null, refundedC: 0, approvalCode: null,
      provider: null, link: null, failureReason: null, createdAt: at, completedAt: status === 'SUCCEEDED' ? at : null, createdBy: by, refunds: [] };
  }

  function ordersOf(bill, target = state) {
    return bill.orderIds.map((id) => target.orders.find((o) => o.id === id)).filter(Boolean);
  }

  function hasActive(bill, target = state) {
    return ordersOf(bill, target).some((o) => ACTIVE.includes(o.status));
  }

  function attach(target, order, at) {
    if (order.type === 'DINE_IN' && order.table && ACTIVE.includes(order.status)) {
      const open = target.bills.filter((b) => b.status === 'OPEN' && b.table && b.table.id === order.table.id && hasActive(b, target)).pop();
      if (open) {
        if (!open.orderIds.includes(order.id)) open.orderIds.push(order.id);
        return open;
      }
    }
    return openBill(target, order, at);
  }

  function billFor(order) {
    return state.bills.find((b) => b.orderIds.includes(order.id)) || attach(state, order, Date.now());
  }

  function money(bill, target = state) {
    sweep(bill);
    const total = ordersOf(bill, target).filter((o) => o.status !== 'CANCELLED').reduce((sum, o) => sum + o.pricing.total, 0);
    const ok = bill.payments.filter((p) => p.status === 'SUCCEEDED');
    const paid = ok.reduce((sum, p) => sum + p.amountC - p.refundedC, 0);
    const pending = bill.payments.filter((p) => p.status === 'PENDING').reduce((sum, p) => sum + p.amountC, 0);
    const balance = total - paid;
    return { total, paid, pending, balance, available: Math.max(0, balance - pending), tips: ok.reduce((sum, p) => sum + p.tipC, 0),
      refunded: bill.payments.reduce((sum, p) => sum + p.refundedC, 0) };
  }

  /** Online payments the guest never finished are given up, like the real expiry job does. */
  function sweep(bill) {
    for (const p of bill.payments) {
      if (p.status === 'PENDING' && Date.now() - p.createdAt > pendingTtl()) {
        Object.assign(p, { status: 'EXPIRED', failureReason: 'The guest did not finish paying in time.', completedAt: Date.now() });
      }
    }
  }

  function refresh(bill, at, target = state) {
    if (bill.status !== 'OPEN' || hasActive(bill, target)) return false;
    const m = money(bill, target);
    if (m.balance > 0 || bill.payments.some((p) => p.status === 'PENDING')) return false;
    const allCancelled = ordersOf(bill, target).every((o) => o.status === 'CANCELLED');
    bill.status = allCancelled && m.paid === 0 ? 'VOID' : 'CLOSED';
    bill.closedAt = at;
    // the demo's history is seeded into a fresh state: only live bills get a legal document
    if (bill.status === 'CLOSED' && target === state) issueFiscal(bill);
    return true;
  }

  function checkPayable(bill, amountC, tipC) {
    if (bill.status !== 'OPEN') throw new Problem(409, 'BILL_NOT_OPEN', `Bill #${bill.id} is ${bill.status}`, { parameters: { billId: String(bill.id), status: bill.status } });
    if (!(amountC > 0)) throw new Problem(422, 'PAYMENT_AMOUNT_INVALID', 'A payment must be more than zero');
    const { available } = money(bill);
    if (amountC > available) throw new Problem(422, 'PAYMENT_EXCEEDS_BALANCE', `Only ${toLira(available)} is still due on bill #${bill.id}`, { parameters: { available: toLira(available) } });
    if (tipC < 0 || tipC > amountC) throw new Problem(422, 'TIP_INVALID', 'A tip cannot be negative or larger than the payment');
  }

  function refundOverpayment(bill, reason, at, by) {
    let excess = -money(bill).balance;
    const newest = bill.payments.filter((p) => p.status === 'SUCCEEDED' && p.amountC > p.refundedC).sort((a, b) => b.createdAt - a.createdAt || b.id - a.id);
    for (const p of newest) {
      if (excess <= 0) break;
      const portion = Math.min(excess, p.amountC - p.refundedC);
      giveBack(p, portion, reason, at, by);
      excess -= portion;
    }
  }

  // The simulated provider confirms refunds at once, so every refund here is done straight away.
  function giveBack(payment, amountC, reason, at, by) {
    payment.refundedC += amountC;
    payment.refunds.push({ id: state.nextRefundId++, amountC, status: 'SUCCEEDED', reason, failureReason: null, createdAt: at, createdBy: by });
  }

  /** A paid dine-in bill completes the orders that have been served, as on the real server. */
  function afterPayment(bill, by) {
    if (money(bill).balance <= 0) {
      for (const order of ordersOf(bill)) {
        if (order.type === 'DINE_IN' && order.status === 'SERVED') {
          transition(order, 'COMPLETED', Date.now(), by);
          changed(order, 'STATUS_CHANGED', 'SERVED');
        }
      }
    }
    refresh(bill, Date.now());
  }

  function paymentsChanged(bill, payment, kind = 'PAYMENT') {
    save();
    const m = money(bill);
    const event = { kind, billId: bill.id, billStatus: bill.status, balance: toLira(m.balance), paymentId: payment ? payment.id : null,
      method: payment ? payment.method : null, paymentStatus: payment ? payment.status : null, amount: payment ? toLira(payment.amountC) : null,
      tableCode: bill.table ? bill.table.code : null, occurredAt: iso(Date.now()) };
    publish(event, 'payment');
    const payments = { bills: state.bills, links: state.links, sims: state.sims, closings: state.closings,
      nextBillId: state.nextBillId, nextPaymentId: state.nextPaymentId, nextRefundId: state.nextRefundId };
    channel?.postMessage({ type: 'payments', event, payments });
  }

  const refundView = (r) => ({ id: r.id, amount: toLira(r.amountC), status: r.status, reason: r.reason, failureReason: r.failureReason,
    createdAt: iso(r.createdAt), createdBy: r.createdBy });
  const paymentView = (bill, p) => ({
    id: p.id, billId: bill.id, method: p.method, status: p.status, amount: toLira(p.amountC), tip: toLira(p.tipC),
    tendered: p.tenderedC == null ? null : toLira(p.tenderedC), changeDue: p.changeC == null ? null : toLira(p.changeC),
    refunded: toLira(p.refundedC), refundable: p.status === 'SUCCEEDED' ? toLira(p.amountC - p.refundedC) : 0,
    approvalCode: p.approvalCode, provider: p.provider, viaLink: p.link != null, failureReason: p.failureReason, underReview: false,
    createdAt: iso(p.createdAt), completedAt: p.completedAt ? iso(p.completedAt) : null, createdBy: p.createdBy, refunds: p.refunds.map(refundView),
  });

  function billView(bill) {
    const m = money(bill);
    const orders = ordersOf(bill);
    return {
      id: bill.id, status: bill.status, table: bill.table,
      orders: orders.map((o) => ({ id: o.id, type: o.type, status: o.status, customerName: o.customerName, subtotal: toLira(o.pricing.subtotal),
        total: toLira(o.pricing.total), lines: o.lines.map((l) => ({ name: l.name, icon: l.icon, options: optionText(l), quantity: l.quantity - (l.voided || 0), unitPrice: toLira(l.unitPriceC), lineTotal: toLira(l.lineTotalC) })) })),
      total: toLira(m.total), paid: toLira(m.paid), pending: toLira(m.pending), balance: toLira(m.balance), available: toLira(m.available),
      tips: toLira(m.tips), refunded: toLira(m.refunded), settled: m.balance <= 0,
      preferredMethod: orders.length ? orders[orders.length - 1].paymentMethod : null,
      openedAt: iso(bill.openedAt), closedAt: bill.closedAt ? iso(bill.closedAt) : null,
      payments: bill.payments.map((p) => paymentView(bill, p)),
    };
  }

  function findBill(id) {
    const bill = state.bills.find((b) => b.id === Number(id));
    if (!bill) throw new Problem(404, 'BILL_NOT_FOUND', `Bill ${id} was not found`, { parameters: { id: String(id) } });
    return bill;
  }

  function newToken(bytes = 32) {
    const raw = crypto.getRandomValues(new Uint8Array(bytes));
    return btoa(String.fromCharCode(...raw)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function findLink(token) {
    const link = state.links.find((l) => l.token === token);
    if (!link) throw new Problem(404, 'PAYMENT_LINK_NOT_FOUND', 'Payment link ? was not found', { parameters: { id: '?' } });
    return link;
  }

  function dayReport(date) {
    const from = new Date(`${date}T00:00:00`).getTime();
    const to = from + 24 * 60 * MINUTE;
    const all = state.bills.flatMap((b) => b.payments);
    const taken = all.filter((p) => p.status === 'SUCCEEDED' && p.createdAt >= from && p.createdAt < to);
    const refunds = all.flatMap((p) => p.refunds.map((r) => ({ ...r, method: p.method }))).filter((r) => r.status !== 'FAILED' && r.createdAt >= from && r.createdAt < to);
    const methods = ['CASH', 'CARD', 'ONLINE'].map((method) => {
      const list = taken.filter((p) => p.method === method);
      return { method, count: list.length, amount: list.reduce((s, p) => s + p.amountC, 0), tips: list.reduce((s, p) => s + p.tipC, 0),
        refunds: refunds.filter((r) => r.method === method).reduce((s, r) => s + r.amountC, 0) };
    });
    const sum = (key) => methods.reduce((s, m) => s + m[key], 0);
    const cash = methods[0];
    const summary = {
      date, methods: methods.map((m) => ({ ...m, amount: toLira(m.amount), tips: toLira(m.tips), refunds: toLira(m.refunds) })),
      sales: toLira(sum('amount')), tips: toLira(sum('tips')), refunds: toLira(sum('refunds')),
      expectedCash: toLira(cash.amount + cash.tips - cash.refunds), payments: taken.length,
    };
    return { summary, closing: state.closings[date] || null };
  }

  const today = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

  const paymentRoutes = [
    ['GET', /^bills$/, 'till', () => state.bills.filter((b) => b.status === 'OPEN').sort((a, b) => a.openedAt - b.openedAt).map((bill) => {
      const m = money(bill);
      const orders = ordersOf(bill);
      return { id: bill.id, status: bill.status, tableCode: bill.table ? bill.table.code : null, customerName: orders.length ? orders[0].customerName : null,
        orderIds: [...bill.orderIds], total: toLira(m.total), paid: toLira(m.paid), balance: toLira(m.balance), pending: toLira(m.pending),
        hasActiveOrders: hasActive(bill), openedAt: iso(bill.openedAt) };
    })],
    ['GET', /^bills\/(\d+)$/, 'till', ({ params }) => billView(findBill(params[0]))],
    ['GET', /^bills\/by-order\/(\d+)$/, 'till', ({ params }) => {
      const bill = billFor(findOrder(params[0]));
      save();
      return billView(bill);
    }],
    ['POST', /^bills\/(\d+)\/payments$/, 'till', ({ params, user, headers }, body) => {
      const key = headers['Idempotency-Key'] || headers['idempotency-key'] || null;
      const bill = findBill(params[0]);
      if (key) {
        for (const b of state.bills) {
          const earlier = b.payments.find((p) => p.idempotencyKey === key);
          if (earlier) {
            if (b.id !== bill.id) throw new Problem(422, 'IDEMPOTENCY_KEY_REUSED', 'This Idempotency-Key was already used for another bill');
            return [201, { payment: paymentView(b, earlier), bill: billView(b) }];
          }
        }
      }
      requireFields({
        method: blank(body.method) ? 'must not be null' : body.method === 'ONLINE' ? 'Online payments are taken through a payment link, not at the till.' : null,
        amount: body.amount == null ? 'must not be null' : null,
      });
      const amountC = toCents(body.amount);
      const tipC = toCents(body.tip || 0);
      checkPayable(bill, amountC, tipC);
      const payment = newPayment(state, body.method, 'SUCCEEDED', amountC, tipC, Date.now(), user.username);
      payment.idempotencyKey = key;
      if (body.method === 'CASH') {
        const tenderedC = body.tendered == null ? amountC + tipC : toCents(body.tendered);
        if (tenderedC < amountC + tipC) throw new Problem(422, 'CASH_NOT_ENOUGH', 'The cash handed over does not cover the payment', { parameters: { required: toLira(amountC + tipC) } });
        payment.tenderedC = tenderedC;
        payment.changeC = tenderedC - amountC - tipC;
      } else {
        payment.approvalCode = clean(body.approvalCode);
      }
      bill.payments.push(payment);
      afterPayment(bill, user.username);
      paymentsChanged(bill, payment);
      return [201, { payment: paymentView(bill, payment), bill: billView(bill) }];
    }],
    ['POST', /^bills\/(\d+)\/payments\/(\d+)\/release$/, 'till', ({ params }) => {
      const bill = findBill(params[0]);
      const payment = bill.payments.find((p) => p.id === Number(params[1]));
      if (!payment) throw new Problem(404, 'PAYMENT_NOT_FOUND', `Payment ${params[1]} was not found`, { parameters: { id: String(params[1]) } });
      if (payment.status === 'PENDING') {
        const sim = Object.values(state.sims).find((s) => s.paymentId === payment.id);
        if (sim && sim.decision === true) {
          Object.assign(payment, { status: 'SUCCEEDED', completedAt: Date.now() });
          afterPayment(bill, 'guest');
        } else {
          Object.assign(payment, { status: 'EXPIRED', failureReason: 'Released by staff.', completedAt: Date.now() });
        }
        paymentsChanged(bill, payment);
      }
      return billView(bill);
    }],
    ['POST', /^bills\/(\d+)\/links$/, 'till', ({ params, user }, body) => {
      const bill = findBill(params[0]);
      if (bill.status !== 'OPEN') throw new Problem(409, 'BILL_NOT_OPEN', `Bill #${bill.id} is ${bill.status}`, { parameters: { billId: String(bill.id), status: bill.status } });
      const { available } = money(bill);
      if (available <= 0) throw new Problem(422, 'BILL_ALREADY_PAID', `Nothing is left to pay on bill #${bill.id}`);
      const amountC = body && body.amount != null ? toCents(body.amount) : null;
      if (amountC != null && amountC > available) throw new Problem(422, 'PAYMENT_EXCEEDS_BALANCE', `Only ${toLira(available)} is still due`, { parameters: { available: toLira(available) } });
      const link = { token: newToken(), billId: bill.id, amountC, expiresAt: Date.now() + linkTtl(), createdBy: user.username };
      state.links.push(link);
      save();
      channel?.postMessage({ type: 'payments', event: { kind: 'BILL', billId: bill.id }, payments: { links: state.links } });
      const base = `${location.origin}${location.pathname}${location.search}`;
      return [201, { token: link.token, url: `${base}#/pay/${link.token}`, amount: amountC == null ? null : toLira(amountC), expiresAt: iso(link.expiresAt) }];
    }],
    ['POST', /^payments\/(\d+)\/refunds$/, 'manager', ({ params, user }, body) => {
      requirePassword(user);
      requireFields({ amount: body.amount == null ? 'must not be null' : null, reason: blank(body.reason) ? 'must not be blank' : null });
      const bill = state.bills.find((b) => b.payments.some((p) => p.id === Number(params[0])));
      if (!bill) throw new Problem(404, 'PAYMENT_NOT_FOUND', `Payment ${params[0]} was not found`, { parameters: { id: String(params[0]) } });
      const payment = bill.payments.find((p) => p.id === Number(params[0]));
      const amountC = toCents(body.amount);
      if (payment.status !== 'SUCCEEDED') throw new Problem(422, 'PAYMENT_NOT_REFUNDABLE', 'Only successful payments can be refunded', { parameters: { status: payment.status } });
      if (!(amountC > 0) || amountC > payment.amountC - payment.refundedC) {
        throw new Problem(422, 'REFUND_EXCEEDS_PAYMENT', 'Too much', { parameters: { refundable: toLira(payment.amountC - payment.refundedC) } });
      }
      giveBack(payment, amountC, String(body.reason).trim(), Date.now(), user.username);
      paymentsChanged(bill, payment, 'REFUND');
      return [201, paymentView(bill, payment)];
    }],
    ['GET', /^payments\/day$/, 'manager', ({ query }) => dayReport(query.get('date') || today())],
    ['POST', /^payments\/day\/close$/, 'manager', ({ user }, body) => {
      requirePassword(user);
      requireFields({ date: blank(body.date) ? 'must not be null' : null, countedCash: body.countedCash == null ? 'must not be null' : null });
      if (body.date > today()) throw new Problem(422, 'DAY_NOT_OVER', 'A day can only be closed once it has started', { parameters: { date: body.date } });
      if (state.closings[body.date]) throw new Problem(409, 'DAY_ALREADY_CLOSED', `${body.date} has already been closed`, { parameters: { date: body.date } });
      const report = dayReport(body.date);
      const counted = toCents(body.countedCash);
      state.closings[body.date] = { countedCash: toLira(counted), variance: toLira(counted - toCents(report.summary.expectedCash)),
        note: clean(body.note), closedAt: iso(Date.now()), closedBy: user.username };
      save();
      return [201, dayReport(body.date)];
    }],

    // Guests: no sign-in, the link token is the permission.
    ['GET', /^public\/pay\/([A-Za-z0-9_-]+)$/, 'public', ({ params }) => {
      const link = findLink(params[0]);
      const bill = findBill(link.billId);
      const m = money(bill);
      const lines = new Map();
      for (const order of ordersOf(bill).filter((o) => o.status !== 'CANCELLED')) {
        for (const l of order.lines) {
          const key = `${l.name}|${optionText(l)}`;
          const line = lines.get(key) || { name: l.name, icon: l.icon, options: optionText(l), quantity: 0, lineTotal: 0 };
          line.quantity += l.quantity - (l.voided || 0); line.lineTotal += l.lineTotalC; lines.set(key, line);
        }
      }
      const reason = bill.status !== 'OPEN' || m.available <= 0 ? 'PAID' : Date.now() > link.expiresAt ? 'EXPIRED' : null;
      const last = bill.payments.filter((p) => p.link === link.token).pop();
      return {
        restaurant: 'Sofra Bistro', tableCode: bill.table ? bill.table.code : null,
        lines: [...lines.values()].map((l) => ({ ...l, lineTotal: toLira(l.lineTotal) })),
        total: toLira(m.total), paid: toLira(m.paid), due: toLira(m.available),
        share: link.amountC == null ? null : toLira(Math.min(link.amountC, m.available)),
        payable: reason == null, reason, expiresAt: iso(link.expiresAt), provider: 'SIMULATED',
        lastPayment: last ? { status: last.status, amount: toLira(last.amountC), tip: toLira(last.tipC), failureReason: last.failureReason, at: iso(last.completedAt || last.createdAt) } : null,
      };
    }],
    ['POST', /^public\/pay\/([A-Za-z0-9_-]+)\/checkout$/, 'public', ({ params }, body) => {
      const link = findLink(params[0]);
      const bill = findBill(link.billId);
      if (Date.now() > link.expiresAt || bill.status !== 'OPEN') {
        throw new Problem(422, bill.status === 'OPEN' ? 'PAYMENT_LINK_EXPIRED' : 'BILL_NOT_OPEN', 'This payment link can no longer be used. Ask your waiter for a new one.');
      }
      const { available } = money(bill);
      if (available <= 0) throw new Problem(422, 'BILL_ALREADY_PAID', 'This bill has already been paid.');
      const amountC = link.amountC != null ? Math.min(link.amountC, available) : body.amount != null ? toCents(body.amount) : available;
      const tipC = toCents(body.tip || 0);
      checkPayable(bill, amountC, tipC);
      const payment = newPayment(state, 'ONLINE', 'PENDING', amountC, tipC, Date.now(), 'guest');
      payment.provider = 'SIMULATED';
      payment.link = link.token;
      const sim = `sim_${newToken(16).replace(/[-_]/g, 'x')}`;
      payment.providerToken = sim;
      state.sims[sim] = { paymentId: payment.id, link: link.token, decision: null };
      bill.payments.push(payment);
      paymentsChanged(bill, payment);
      return { paymentPageUrl: `/#/sim-checkout/${sim}` };
    }],
    ['GET', /^public\/sim-checkout\/([A-Za-z0-9_]+)$/, 'public', ({ params }) => simView(params[0])],
    ['POST', /^public\/sim-checkout\/([A-Za-z0-9_]+)$/, 'public', ({ params }, body) => {
      const view = simView(params[0]);
      const sim = state.sims[params[0]];
      if (sim.decision == null) sim.decision = Boolean(body.approve);
      save();
      return view;
    }],
    ['POST', /^payments\/callback\/simulated$/, 'public', (_, body) => {
      const sim = state.sims[body.token];
      const bill = sim && state.bills.find((b) => b.payments.some((p) => p.id === sim.paymentId));
      if (sim && bill) {
        const payment = bill.payments.find((p) => p.id === sim.paymentId);
        if (payment.status === 'PENDING' && sim.decision != null) {
          if (sim.decision) {
            Object.assign(payment, { status: 'SUCCEEDED', completedAt: Date.now(), providerPaymentId: `simpay_${payment.id}` });
            refundOverpayment(bill, 'Paid more than was due', Date.now(), 'system');
            afterPayment(bill, 'guest');
          } else {
            Object.assign(payment, { status: 'FAILED', completedAt: Date.now(), failureReason: 'The card was declined (simulated).' });
          }
          paymentsChanged(bill, payment);
        }
      }
      // Like the server: the link token never travels through the provider; the page remembers it.
      return { redirect: '/#/pay/return' };
    }],
  ];

  function simView(token) {
    const sim = state.sims[token];
    const bill = sim && state.bills.find((b) => b.payments.some((p) => p.id === sim.paymentId));
    if (!bill) throw new Problem(404, 'CHECKOUT_NOT_FOUND', 'Checkout ? was not found', { parameters: { id: '?' } });
    const payment = bill.payments.find((p) => p.id === sim.paymentId);
    return { token, amount: toLira(payment.amountC + payment.tipC), description: `Sofra Bistro${bill.table ? ` - ${bill.table.code}` : ''}`,
      callbackUrl: 'api/v1/payments/callback/simulated' };
  }

  const routes = [
    ['GET', /^auth\/totp$/, 'manager', ({ user }) => {
      const entry = totpState()[user.username];
      return { enabled: Boolean(entry?.enabled), available: true, setupRequired: false, recoveryCodesLeft: entry?.enabled ? entry.recovery.length : 0 };
    }],
    ['POST', /^auth\/totp\/setup$/, 'manager', ({ user }, body) => {
      if (USERS[user.username].password !== body.password) throw new Problem(403, 'PASSWORD_INCORRECT', 'The password is not correct.');
      if (totpState()[user.username]?.enabled) throw new Problem(422, 'TOTP_ALREADY_ENABLED', 'Already on');
      const secret = base32(crypto.getRandomValues(new Uint8Array(20)));
      totpState()[user.username] = { secret, enabled: false, lastStep: 0, recovery: [] };
      save();
      return { secret, otpauthUri: `otpauth://totp/Sofra:${user.username}?secret=${secret}&issuer=Sofra&algorithm=SHA1&digits=6&period=30` };
    }],
    ['POST', /^auth\/totp\/enable$/, 'manager', async ({ user }, body) => {
      const entry = totpState()[user.username];
      if (!entry || entry.enabled) throw new Problem(422, 'TOTP_NOT_PENDING', 'Start the setup first');
      const step = await verifyTotp(entry, String(body.code || '').trim());
      if (step == null) throw new Problem(403, 'TOTP_INVALID', 'That code is not right.');
      const codes = newRecoveryCodes();
      Object.assign(entry, { enabled: true, lastStep: step, recovery: codes });
      save();
      return { codes: codes.map((c) => `${c.slice(0, 5)}-${c.slice(5)}`) };
    }],
    ['POST', /^auth\/totp\/disable$/, 'manager', async ({ user }, body) => {
      const entry = totpState()[user.username];
      if (USERS[user.username].password !== body.password) throw new Problem(403, 'PASSWORD_INCORRECT', 'The password is not correct.');
      if (!entry?.enabled || !(await checkSecondFactor(entry, body.code))) throw new Problem(403, 'TOTP_INVALID', 'That code is not right.');
      delete totpState()[user.username];
      save();
      return [204, null];
    }],
    ['POST', /^auth\/totp\/recovery-codes$/, 'manager', async ({ user }, body) => {
      const entry = totpState()[user.username];
      if (!entry?.enabled || !(await checkSecondFactor(entry, body.code))) throw new Problem(403, 'TOTP_INVALID', 'That code is not right.');
      entry.recovery = newRecoveryCodes();
      save();
      return { codes: entry.recovery.map((c) => `${c.slice(0, 5)}-${c.slice(5)}`) };
    }],
    ['POST', /^auth\/login$/, 'public', async (_, body) => {
      const user = USERS[String(body.username || '').toLowerCase()];
      if (!user || user.password !== body.password) throw new Problem(401, 'BAD_CREDENTIALS', 'Invalid username or password.');
      const username = String(body.username).toLowerCase();
      const second = totpState()[username];
      if (second?.enabled) {
        if (blank(body.code)) throw new Problem(403, 'TOTP_REQUIRED', 'Enter the code from your authenticator app.');
        if (!(await checkSecondFactor(second, body.code))) throw new Problem(403, 'TOTP_INVALID', 'That code is not right.');
      }
      return { accessToken: `demo.${username}`, tokenType: 'Bearer', expiresAt: iso(Date.now() + 8 * 60 * MINUTE), user: userView(username, 'pwd') };
    }],
    ['POST', /^auth\/pin-login$/, 'public', ({ headers }, body) => {
      deviceFor(headers);
      const username = String(body.username || '').toLowerCase();
      if (!USERS[username] || !pins()[username] || pins()[username] !== String(body.pin)) throw new Problem(401, 'BAD_CREDENTIALS', 'Invalid username or password.');
      save();
      return { accessToken: `demo.pin.${username}`, tokenType: 'Bearer', expiresAt: iso(Date.now() + 8 * 60 * MINUTE), user: userView(username, 'pin') };
    }],
    ['GET', /^auth\/pin-staff$/, 'public', ({ headers }) => {
      deviceFor(headers);
      return Object.keys(USERS).filter((u) => pins()[u]).map((u) => ({ username: u, displayName: USERS[u].displayName, role: USERS[u].role }))
        .sort((a, b) => a.displayName.localeCompare(b.displayName));
    }],
    ['PUT', /^auth\/pin$/, 'staff', ({ user }, body) => {
      if (USERS[user.username].password !== body.password) throw new Problem(403, 'PASSWORD_INCORRECT', 'The password is not correct.');
      if (!/^\d{4,6}$/.test(String(body.pin || ''))) requireFields({ pin: 'must match "\\d{4,6}"' });
      if (weakPin(String(body.pin))) throw new Problem(422, 'PIN_TOO_SIMPLE', 'Choose a PIN that is not a run or a repeat.');
      pins()[user.username] = String(body.pin);
      save();
      return [204, null];
    }],
    ['GET', /^devices$/, 'manager', () => [...devices()].sort((a, b) => b.createdAt - a.createdAt).map(deviceView)],
    ['POST', /^devices$/, 'manager', ({ user }, body) => {
      requirePassword(user);
      requireFields({ name: blank(body.name) ? 'must not be blank' : null });
      state.nextDeviceId ??= 1;
      const token = Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, '0')).join('');
      const device = { id: state.nextDeviceId++, name: String(body.name).trim().slice(0, 60), token, createdBy: user.username, createdAt: Date.now(), lastSeenAt: null, revokedAt: null };
      devices().push(device);
      save();
      return [201, { device: deviceView(device), token }];
    }],
    ['DELETE', /^devices\/(\d+)$/, 'manager', ({ params, user }) => {
      requirePassword(user);
      const device = devices().find((d) => d.id === Number(params[0]));
      if (!device) throw new Problem(404, 'NOT_FOUND', 'Device not found');
      device.revokedAt ??= Date.now();
      save();
      return deviceView(device);
    }],
    ['GET', /^auth\/me$/, 'staff', ({ user }) => userView(user.username, user.method)],
    // Demo tokens are not real JWTs, so there is nothing to revoke; the call simply succeeds like the real one.
    ['POST', /^auth\/logout$/, 'staff', () => [204, null]],

    ['GET', /^menu$/, 'public', ({ query }) => {
      const needle = (query.get('q') || '').trim().toLocaleLowerCase();
      const order = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'];
      return state.menu.filter((m) => !m.archived)
        .filter((m) => !query.get('category') || m.category === query.get('category'))
        .filter((m) => query.get('includeUnavailable') !== 'false' || m.available)
        .filter((m) => !needle || m.name.toLocaleLowerCase().includes(needle) || (m.description || '').toLocaleLowerCase().includes(needle))
        .sort((a, b) => order.indexOf(a.category) - order.indexOf(b.category) || Number(b.popular) - Number(a.popular) || a.name.localeCompare(b.name))
        .map(menuView);
    }],
    ['GET', /^menu\/(\d+)$/, 'public', ({ params }) => menuView(findDish(params[0]))],
    ['POST', /^menu$/, 'manager', (_, body) => {
      validateDish(body, null);
      const dish = { id: state.nextMenuId++, available: true, archived: false };
      applyDish(dish, body);
      state.menu.push(dish);
      menuChanged();
      return [201, menuView(dish)];
    }],
    ['PUT', /^menu\/(\d+)$/, 'manager', ({ params }, body) => {
      const dish = findDish(params[0]);
      validateDish(body, dish.id);
      applyDish(dish, body);
      menuChanged();
      return menuView(dish);
    }],
    ['PATCH', /^menu\/(\d+)\/availability$/, 'manager', ({ params }, body) => {
      const dish = findDish(params[0]);
      dish.available = Boolean(body.available);
      pushAvailability(dish);
      menuChanged();
      return menuView(dish);
    }],
    ['DELETE', /^menu\/(\d+)$/, 'manager', ({ params }) => {
      const dish = findDish(params[0]);
      dish.archived = true;
      dish.available = false;
      menuChanged();
      return [204, null];
    }],

    ['POST', /^orders\/quote$/, 'till', (_, body) => {
      requireFields({ type: blank(body.type) ? 'must not be null' : null, items: !(body.items || []).length ? 'must not be empty' : null });
      const lines = buildLines(body.items);
      const subtotal = lines.reduce((sum, l) => sum + l.lineTotalC, 0);
      let coupon = null;
      let result = null;
      if (!blank(body.couponCode)) {
        const check = validateCoupon(body.couponCode, subtotal, Date.now());
        coupon = check.coupon || null;
        result = { code: check.code, applied: Boolean(coupon), description: coupon ? coupon.description : null, errorCode: check.error ? check.error[0] : null, message: check.error ? check.error[1] : null };
      }
      const p = price(subtotal, body.type, coupon);
      return {
        lines: lines.map(lineView), pricing: pricingView(p), coupon: result, freeDeliveryThreshold: FREE_DELIVERY_THRESHOLD / 100,
        amountUntilFreeDelivery: body.type === 'DELIVERY' ? toLira(Math.max(0, FREE_DELIVERY_THRESHOLD - (p.subtotal - p.discount))) : 0,
      };
    }],
    ['POST', /^orders$/, 'till', ({ user, headers }, body) => {
      // Same as OrderController: a retry with the key returns the order already placed.
      const key = headers['Idempotency-Key'] || headers['idempotency-key'] || null;
      if (key && !/^[A-Za-z0-9_-]{8,80}$/.test(key)) throw new Problem(422, 'IDEMPOTENCY_KEY_INVALID', "Idempotency-Key must be 8 to 80 letters, digits, '-' or '_'");
      const earlier = key ? state.orders.find((o) => o.idempotencyKey === key) : null;
      if (earlier) {
        if (earlier.createdBy !== user.username) throw new Problem(422, 'IDEMPOTENCY_KEY_REUSED', "This Idempotency-Key was already used for another user's order");
        return orderView(earlier);
      }
      const phone = clean(body.customerPhone);
      requireFields({
        type: blank(body.type) ? 'must not be null' : null,
        customerName: blank(body.customerName) ? 'must not be blank' : null,
        paymentMethod: blank(body.paymentMethod) ? 'must not be null' : null,
        items: !(body.items || []).length ? 'must not be empty' : null,
        tableId: body.type === 'DINE_IN' && body.tableId == null ? 'is required for dine-in orders' : null,
        deliveryAddress: body.type === 'DELIVERY' && blank(body.deliveryAddress) ? 'is required for delivery orders' : null,
        customerPhone: body.type === 'DELIVERY' && !phone ? 'is required for delivery orders'
          : phone && !/^\+?[0-9 ()-]{7,20}$/.test(phone) ? 'must be a valid phone number' : null,
      });
      const lines = buildLines(body.items);
      const subtotal = lines.reduce((sum, l) => sum + l.lineTotalC, 0);
      let coupon = null;
      if (!blank(body.couponCode)) {
        const check = validateCoupon(body.couponCode, subtotal, Date.now());
        if (check.error) throw new Problem(422, check.error[0], check.error[1], { parameters: check.error[2] || { code: check.code } });
        coupon = check.coupon;
      }
      let table = null;
      if (body.type === 'DINE_IN') {
        table = TABLES.find((t) => t.id === Number(body.tableId));
        if (!table) throw new Problem(422, 'TABLE_NOT_FOUND', `Table ${body.tableId} does not exist`, { parameters: { tableId: body.tableId } });
      }
      const order = createOrder({ ...body, table: table ? { ...table } : null }, lines, coupon, Date.now(), user.username);
      // Same as Order.place + holdLaterCourses: everything goes to the kitchen, unless later courses are held.
      const first = Math.min(...lines.map((l) => l.course));
      lines.forEach((l) => { l.firedAt = body.holdCourses && l.course !== first ? null : order.createdAt; });
      if (key) order.idempotencyKey = key;
      state.orders.push(order);
      attach(state, order, order.createdAt);
      changed(order, 'CREATED', null);
      return [201, orderView(order)];
    }],
    ['GET', /^orders$/, 'staff', ({ query }) => {
      const statuses = query.getAll('status');
      const type = query.get('type');
      const needle = (query.get('q') || '').trim().toLocaleLowerCase();
      const from = query.get('from') ? new Date(`${query.get('from')}T00:00:00`).getTime() : null;
      const to = query.get('to') ? new Date(`${query.get('to')}T00:00:00`).getTime() + 24 * 60 * MINUTE : null;
      const digits = needle.replace(/^#/, '');
      const matches = state.orders.filter((o) => (!statuses.length || statuses.includes(o.status))
        && (!type || o.type === type) && (from == null || o.createdAt >= from) && (to == null || o.createdAt < to)
        && (!needle || [o.customerName, o.customerPhone, o.deliveryAddress, o.table && o.table.code]
          .some((field) => field && field.toLocaleLowerCase().includes(needle)) || (/^\d+$/.test(digits) && o.id === Number(digits))))
        .sort((a, b) => b.createdAt - a.createdAt);
      const size = Math.min(100, Number(query.get('size') || 20));
      const page = Number(query.get('page') || 0);
      const totalPages = Math.ceil(matches.length / size);
      return {
        content: matches.slice(page * size, page * size + size).map(orderView), page, size, totalElements: matches.length,
        totalPages, first: page === 0, last: page >= totalPages - 1,
      };
    }],
    ['GET', /^orders\/active$/, 'staff', () => state.orders.filter((o) => ACTIVE.includes(o.status)).sort((a, b) => a.createdAt - b.createdAt).map(orderView)],
    ['GET', /^orders\/(\d+)$/, 'staff', ({ params }) => orderView(findOrder(params[0]))],
    ['POST', /^orders\/(\d+)\/advance$/, 'staff', ({ params, user }) => {
      const order = findOrder(params[0]);
      const next = nextStatus(order.status, order.type);
      if (!next) throw new Problem(409, 'ORDER_NOT_ADVANCEABLE', `Order #${order.id} is ${order.status} and cannot be advanced`, { parameters: { orderId: String(order.id), status: order.status } });
      if (next === 'COMPLETED') {
        const bill = billFor(order);
        const { balance } = money(bill);
        if (balance > 0) throw new Problem(422, 'BILL_NOT_PAID', `Bill #${bill.id} still has ${toLira(balance)} to pay`, { parameters: { billId: bill.id, balance: toLira(balance) } });
      }
      const previous = order.status;
      transition(order, next, Date.now(), user.username);
      if (next === 'COMPLETED') {
        const bill = billFor(order);
        if (refresh(bill, Date.now())) paymentsChanged(bill, null, 'BILL');
        deductForOrder(order);
      }
      pushChannelStatus(order, next);
      changed(order, 'STATUS_CHANGED', previous);
      return orderView(order);
    }],
    ['POST', /^orders\/(\d+)\/cancel$/, 'till', ({ params, user }, body) => {
      requireFields({ reason: blank(body.reason) ? 'must not be blank' : null });
      const order = findOrder(params[0]);
      if (order.status !== 'PENDING' && order.status !== 'PREPARING') {
        throw new Problem(409, 'ORDER_NOT_CANCELLABLE', `Order #${order.id} is ${order.status} and can no longer be cancelled`, { parameters: { orderId: String(order.id), status: order.status } });
      }
      const previous = order.status;
      order.cancellationReason = String(body.reason).trim();
      transition(order, 'CANCELLED', Date.now(), user.username);
      const bill = billFor(order);
      const before = money(bill).refunded;
      refundOverpayment(bill, `Order #${order.id} was cancelled`, Date.now(), user.username);
      if (refresh(bill, Date.now()) || money(bill).refunded !== before) paymentsChanged(bill, null, 'REFUND');
      pushChannelStatus(order, 'CANCELLED');
      changed(order, 'STATUS_CHANGED', previous);
      return orderView(order);
    }],

    ['POST', /^orders\/(\d+)\/lines\/(\d+)\/adjust$/, 'till', ({ params, user }, body) => {
      const order = findOrder(params[0]);
      const index = Number(params[1]) - order.id * 100 - 1;
      const line = order.lines[index];
      if (!line) throw new Problem(404, 'NOT_FOUND', `Order line ${params[1]} was not found`);
      requireFields({ reason: blank(body.reason) ? 'must not be blank' : null, kind: ['VOID', 'COMP'].includes(body.kind) ? null : 'must be VOID or COMP' });
      const units = Number(body.quantity);
      const left = line.quantity - (line.voided || 0) - (line.comped || 0);
      const approver = approvedBy(user, body.approval, body.kind === 'COMP' || order.status !== 'PENDING');
      return adjustOrder(order, user, () => {
        if (!(units >= 1 && units <= left)) throw new Problem(422, 'ADJUST_QUANTITY_INVALID', 'Too many', { parameters: { max: left, name: line.name } });
        if (body.kind === 'VOID' && order.lines.reduce((n, l) => n + l.quantity - (l.voided || 0), 0) - units <= 0) {
          throw new Problem(422, 'VOID_LAST_ITEM', 'Cancel the order instead of voiding everything on it');
        }
        if (body.kind === 'VOID') line.voided = (line.voided || 0) + units; else line.comped = (line.comped || 0) + units;
        line.lineTotalC = line.unitPriceC * (line.quantity - (line.voided || 0) - (line.comped || 0));
        return { kind: body.kind, itemName: line.name, quantity: units, amountC: line.unitPriceC * units, detail: String(body.reason).trim(), approvedBy: approver };
      });
    }],
    ['POST', /^orders\/(\d+)\/courses\/(\d+)\/fire$/, 'till', ({ params, user }) => {
      const order = findOrder(params[0]);
      const course = Number(params[1]);
      if (!(course >= 1 && course <= 3)) {
        throw new Problem(400, 'VALIDATION_FAILED', 'Request parameters are invalid.', { errors: [{ field: 'course', message: 'must be between 1 and 3' }] });
      }
      if (!ACTIVE.includes(order.status)) throw new Problem(409, 'ORDER_NOT_EDITABLE', `Order #${order.id} can no longer be changed`, { parameters: { orderId: String(order.id), status: order.status } });
      const held = order.lines.filter((l) => isHeld(l) && l.course === course);
      if (!held.length) throw new Problem(422, 'NOTHING_TO_FIRE', `Course ${course} has nothing waiting`, { parameters: { course } });
      const at = Date.now();
      held.forEach((l) => { l.firedAt = at; });
      order.updatedAt = at;
      (order.adjustments ??= []).push({ kind: 'FIRE', itemName: null, quantity: course, amountC: null, detail: `course ${course}`, by: user.username, approvedBy: null, at });
      changed(order, 'UPDATED');
      return orderView(order);
    }],
    ['POST', /^orders\/(\d+)\/lines\/(\d+)\/ready$/, 'staff', ({ params, query }) => {
      const order = findOrder(params[0]);
      if (!ACTIVE.includes(order.status)) throw new Problem(409, 'ORDER_NOT_EDITABLE', `Order #${order.id} is ${order.status}`, { parameters: { orderId: String(order.id), status: order.status } });
      const line = order.lines[Number(params[1]) - order.id * 100 - 1];
      if (!line) throw new Problem(404, 'NOT_FOUND', `Order line ${params[1]} was not found`);
      if (line.firedAt === null) throw new Problem(422, 'LINE_NOT_FIRED', 'That dish has not been sent to the kitchen yet');
      line.readyAt = query.get('ready') === 'false' ? null : Date.now();
      order.updatedAt = Date.now();
      changed(order, 'UPDATED');
      return orderView(order);
    }],
    ['GET', /^stations$/, 'staff', () => stations().map((station) => ({ ...station }))],
    ['POST', /^stations$/, 'manager', (_, body) => {
      validateStation(body, null);
      if (body.code == null) throw new Problem(422, 'STATION_CODE_REQUIRED', 'A new station needs a code');
      if (stations().some((station) => station.code === body.code)) throw new Problem(422, 'STATION_CODE_TAKEN', `Station code ${body.code} is taken`, { parameters: { code: body.code } });
      const station = { id: Math.max(0, ...stations().map((x) => x.id)) + 1, code: body.code, name: String(body.name).trim(), targetMinutes: Number(body.targetMinutes) };
      stations().push(station);
      save();
      return [201, { ...station }];
    }],
    ['PUT', /^stations\/(\d+)$/, 'manager', ({ params }, body) => {
      const station = stations().find((x) => x.id === Number(params[0]));
      if (!station) throw new Problem(404, 'NOT_FOUND', `Station ${params[0]} was not found`);
      validateStation(body, station.id);
      Object.assign(station, { name: String(body.name).trim(), targetMinutes: Number(body.targetMinutes) });
      save();
      return { ...station };
    }],
    ['DELETE', /^stations\/(\d+)$/, 'manager', ({ params }) => {
      const station = stations().find((x) => x.id === Number(params[0]));
      if (!station) throw new Problem(404, 'NOT_FOUND', `Station ${params[0]} was not found`);
      if (state.menu.some((m) => !m.archived && (m.stationCode ?? DEFAULT_STATION[m.category]) === station.code)) {
        throw new Problem(409, 'STATION_IN_USE', `Dishes are still made at ${station.name}`, { parameters: { name: station.name } });
      }
      state.stations = stations().filter((x) => x !== station);
      save();
      return [204, null];
    }],
    ['PUT', /^orders\/(\d+)\/discount$/, 'till', ({ params, user }, body) => {
      const order = findOrder(params[0]);
      requireFields({ reason: blank(body.reason) ? 'must not be blank' : null });
      if (body.percent != null && body.amount != null) throw new Problem(422, 'DISCOUNT_INVALID', 'Give a percentage or an amount, not both');
      const approver = approvedBy(user, body.approval, true);
      return adjustOrder(order, user, () => {
        order.manual = body.percent != null ? { percent: Number(body.percent) } : body.amount != null ? { amountC: toCents(body.amount) } : null;
        const what = body.percent != null ? `%${Number(body.percent)}` : body.amount != null ? String(body.amount) : 'removed';
        return { kind: 'DISCOUNT', itemName: null, quantity: null, amountC: order.manual?.amountC ?? null, detail: `${what}: ${String(body.reason).trim()}`, approvedBy: approver };
      });
    }],
    ['POST', /^tables\/(\d+)\/move$/, 'till', ({ params, user }, body) => {
      const from = tableById(params[0]);
      const to = tableById(body.tableId);
      if (from.id === to.id) throw new Problem(422, 'SAME_TABLE', 'Pick a different table');
      const bill = seatingAt(from.id);
      if (!bill) throw new Problem(409, 'TABLE_NOT_SEATED', `Nobody is seated at ${from.code}`, { parameters: { table: from.code } });
      if (seatingAt(to.id) || state.orders.some((o) => o.table?.id === to.id && ACTIVE.includes(o.status))) {
        throw new Problem(422, 'TABLE_OCCUPIED', `Table ${to.code} is taken`, { parameters: { table: to.code } });
      }
      moveOrders(bill, to, 'MOVE', user.username);
      bill.table = { id: to.id, code: to.code };
      ordersOf(bill).forEach((o) => changed(o, 'UPDATED'));
      paymentsChanged(bill, null, 'BILL');
      return billView(bill);
    }],
    ['POST', /^tables\/(\d+)\/merge$/, 'till', ({ params, user }, body) => {
      const from = tableById(params[0]);
      const into = tableById(body.tableId);
      if (from.id === into.id) throw new Problem(422, 'SAME_TABLE', 'Pick a different table');
      const source = seatingAt(from.id);
      const target = seatingAt(into.id);
      if (!source || !target) {
        const empty = source ? into : from;
        throw new Problem(409, 'TABLE_NOT_SEATED', `Nobody is seated at ${empty.code}`, { parameters: { table: empty.code } });
      }
      const m = money(source);
      if (m.paid || m.pending || m.refunded) throw new Problem(422, 'MERGE_SOURCE_HAS_PAYMENTS', 'That table has already paid something');
      moveOrders(source, into, 'MERGE', user.username);
      target.orderIds.push(...source.orderIds);
      source.orderIds = [];
      source.status = 'VOID';
      source.closedAt = Date.now();
      ordersOf(target).forEach((o) => changed(o, 'UPDATED'));
      paymentsChanged(target, null, 'BILL');
      return billView(target);
    }],
    ['GET', /^public\/table\/([A-Za-z0-9_-]{20,100})$/, 'public', ({ params }) => {
      const table = tableForToken(params[0]);
      return { restaurant: 'Sofra Bistro', tableCode: table.code,
        openCalls: ['CALL_WAITER', 'REQUEST_BILL'].filter((kind) => guestRequests().some((r) => r.tableId === table.id && r.kind === kind && r.status === 'OPEN')) };
    }],
    ['POST', /^public\/table\/([A-Za-z0-9_-]{20,100})\/orders$/, 'public', ({ params }, body) => {
      const table = tableForToken(params[0]);
      requireFields({ items: !(body.items || []).length || body.items.length > 20 ? 'size must be between 1 and 20' : null });
      if (guestRequests().some((r) => r.tableId === table.id && r.kind === 'ORDER' && Date.now() - r.createdAt < 15_000)) {
        throw new Problem(429, 'GUEST_SLOW_DOWN', 'A cart was just sent from this table', { parameters: { retryAfterSeconds: 15 } });
      }
      const lines = buildLines(body.items.map((item) => ({ ...item, quantity: Math.min(20, Number(item.quantity)) })));
      const totalC = price(lines.reduce((sum, l) => sum + l.lineTotalC, 0), 'DINE_IN', null).total;
      const request = newGuestRequest(table, 'ORDER', {
        guestName: clean(body.guestName), note: clean(body.note), totalC,
        lines: lines.map((l, i) => ({ menuItemId: l.menuItemId, name: l.name, icon: l.icon, quantity: l.quantity, note: l.note,
          optionIds: l.options.map((o) => o.choiceId), options: optionText(l), lineTotalC: l.lineTotalC, index: i })),
      });
      return [201, guestView(request)];
    }],
    ['POST', /^public\/table\/([A-Za-z0-9_-]{20,100})\/calls$/, 'public', ({ params }, body) => {
      const table = tableForToken(params[0]);
      if (!['CALL_WAITER', 'REQUEST_BILL'].includes(body.kind)) throw new Problem(422, 'GUEST_CALL_INVALID', 'Send a cart to order');
      const waiting = guestRequests().find((r) => r.tableId === table.id && r.kind === body.kind && r.status === 'OPEN');
      return [201, guestView(waiting || newGuestRequest(table, body.kind))];
    }],
    ['GET', /^public\/table\/([A-Za-z0-9_-]{20,100})\/requests\/([0-9a-f-]{36})$/, 'public', ({ params }) => {
      const table = tableForToken(params[0]);
      const request = guestRequests().find((r) => r.publicId === params[1] && r.tableId === table.id);
      if (!request) throw new Problem(404, 'NOT_FOUND', 'Request ? was not found');
      return guestView(request);
    }],
    ['GET', /^guest-requests$/, 'till', () => guestRequests().filter((r) => r.status === 'OPEN').map(staffGuestView)],
    ['POST', /^guest-requests\/(\d+)\/accept$/, 'till', ({ params, user }) => {
      const request = findGuestRequest(params[0]);
      const table = TABLES.find((x) => x.id === request.tableId);
      const lines = buildLines(request.lines.map((l) => ({ menuItemId: l.menuItemId, quantity: l.quantity, note: l.note, optionIds: l.optionIds })));
      const order = createOrder({ type: 'DINE_IN', customerName: request.guestName || `QR ${table.code}`, note: request.note, paymentMethod: 'CARD',
        table: { ...table } }, lines, null, Date.now(), user.username);
      state.orders.push(order);
      attach(state, order, order.createdAt);
      Object.assign(request, { status: 'ACCEPTED', orderId: order.id, handledAt: Date.now(), handledBy: user.username });
      changed(order, 'CREATED', null);
      guestChanged(request);
      return staffGuestView(request);
    }],
    ['POST', /^guest-requests\/(\d+)\/reject$/, 'till', ({ params, user }, body) => {
      requireFields({ reason: blank(body.reason) ? 'must not be blank' : null });
      const request = findGuestRequest(params[0]);
      if (request.kind !== 'ORDER') throw new Problem(409, 'GUEST_REQUEST_HANDLED', 'Not an order');
      Object.assign(request, { status: 'REJECTED', reason: String(body.reason).trim().slice(0, 140), handledAt: Date.now(), handledBy: user.username });
      guestChanged(request);
      return staffGuestView(request);
    }],
    ['POST', /^guest-requests\/(\d+)\/done$/, 'till', ({ params, user }) => {
      const request = findGuestRequest(params[0]);
      if (request.kind === 'ORDER') throw new Problem(409, 'GUEST_REQUEST_NOT_A_CALL', 'Accept or reject an order request');
      Object.assign(request, { status: 'DONE', handledAt: Date.now(), handledBy: user.username });
      guestChanged(request);
      return staffGuestView(request);
    }],
    ['POST', /^tables\/(\d+)\/qr$/, 'manager', ({ params }) => {
      const table = tableById(params[0]);
      for (const [token, tableId] of Object.entries(qr().tokens)) if (tableId === table.id) delete qr().tokens[token];
      const token = Array.from(crypto.getRandomValues(new Uint8Array(24)), (b) => b.toString(16).padStart(2, '0')).join('');
      qr().tokens[token] = table.id;
      qr().issued[table.id] = Date.now();
      save();
      return { tableId: table.id, tableCode: table.code, token, issuedAt: iso(qr().issued[table.id]) };
    }],
    ['GET', /^printers$/, 'manager', () => [...printers()].sort((a, b) => a.name.localeCompare(b.name)).map(printerView)],
    ['POST', /^printers$/, 'manager', (_, body) => {
      validatePrinter(body, null);
      state.nextPrinterId ??= 1;
      const printer = { id: state.nextPrinterId++, name: String(body.name).trim(), host: String(body.host).trim(), port: Number(body.port),
        paperWidth: Number(body.paperWidth), stations: [...new Set(body.stations)].sort(), enabled: Boolean(body.enabled), lastPrintedAt: null, lastError: null };
      printers().push(printer);
      save();
      return [201, printerView(printer)];
    }],
    ['PUT', /^printers\/(\d+)$/, 'manager', ({ params }, body) => {
      const printer = printers().find((p) => p.id === Number(params[0]));
      if (!printer) throw new Problem(404, 'NOT_FOUND', 'Printer not found');
      validatePrinter(body, printer.id);
      Object.assign(printer, { name: String(body.name).trim(), host: String(body.host).trim(), port: Number(body.port), paperWidth: Number(body.paperWidth),
        stations: [...new Set(body.stations)].sort(), enabled: Boolean(body.enabled) });
      save();
      return printerView(printer);
    }],
    ['DELETE', /^printers\/(\d+)$/, 'manager', ({ params }) => {
      state.printers = printers().filter((p) => p.id !== Number(params[0]));
      save();
      return [204, null];
    }],
    ['POST', /^printers\/(\d+)\/test$/, 'manager', ({ params }) => {
      const printer = printers().find((p) => p.id === Number(params[0]));
      if (!printer) throw new Problem(404, 'NOT_FOUND', 'Printer not found');
      printer.lastPrintedAt = iso(Date.now());
      save();
      return [202, { id: Date.now(), printerId: printer.id, printer: printer.name, orderId: null, kind: 'TEST', status: 'PRINTED', attempts: 1, lastError: null }];
    }],
    ['GET', /^printers\/(\d+)\/preview$/, 'manager', ({ params, query }) => {
      const printer = printers().find((p) => p.id === Number(params[0]));
      if (!printer) throw new Problem(404, 'NOT_FOUND', 'Printer not found');
      const order = query.get('orderId') ? findOrder(query.get('orderId')) : null;
      return { printer: printer.name, columns: printer.paperWidth === 58 ? 32 : 48, text: ticketText(printer, order) };
    }],
    ['GET', /^print-jobs$/, 'manager', () => []],
    ['POST', /^orders\/(\d+)\/reprint$/, 'till', ({ params }) => {
      const order = findOrder(params[0]);
      const targets = printers().filter((p) => p.enabled && order.lines.some((l) => l.firedAt !== null && p.stations.includes(lineStation(l))));
      if (!targets.length) throw new Problem(422, 'NO_PRINTER_FOR_ORDER', 'No printer is set up for this order');
      targets.forEach((p) => { p.lastPrintedAt = iso(Date.now()); });
      save();
      return [202, targets.map((p) => ({ printerId: p.id, printer: p.name, orderId: order.id, kind: 'REPRINT', status: 'PRINTED' }))];
    }],
    ['GET', /^floor\/tables$/, 'staff', () => TABLES.map((table) => {
      const open = state.orders.filter((o) => o.type === 'DINE_IN' && o.table && o.table.id === table.id && ACTIVE.includes(o.status))
        .sort((a, b) => a.createdAt - b.createdAt);
      return {
        ...table, qrIssuedAt: qr().issued[table.id] ? iso(qr().issued[table.id]) : null, occupied: open.length > 0, occupiedSince: open.length ? iso(open[0].createdAt) : null,
        openTotal: toLira(open.reduce((sum, o) => sum + o.pricing.total, 0)),
        openOrders: open.map((o) => ({ id: o.id, status: o.status, customerName: o.customerName, itemCount: o.lines.reduce((n, l) => n + l.quantity - (l.voided || 0), 0), total: toLira(o.pricing.total), createdAt: iso(o.createdAt) })),
      };
    })],
    ['GET', /^coupons$/, 'staff', () => COUPONS.filter((c) => !c.validUntil || Date.now() < Date.parse(c.validUntil)).map((c) => ({ ...c }))],
    ['GET', /^fiscal\/documents$/, 'till', ({ query }) => {
      const [from, to] = dayRange(query.get('date') || todayIso());
      return fiscalState().documents.filter((d) => d.createdAt >= from && d.createdAt < to).sort((a, b) => b.createdAt - a.createdAt).map(fiscalView);
    }],
    ['GET', /^fiscal\/bills\/(\d+)$/, 'till', ({ params }) => {
      const doc = fiscalState().documents.find((d) => d.billId === Number(params[0]));
      if (!doc) throw new Problem(404, 'FISCAL_DOCUMENT_NOT_FOUND', `Fiscal document ${params[0]} was not found`);
      return fiscalView(doc);
    }],
    ['POST', /^fiscal\/bills\/(\d+)\/invoice$/, 'till', ({ params }, body) => {
      const taxId = String(body.taxId ?? '').trim();
      requireFields({ name: blank(body.name) ? 'must not be blank' : null, taxId: blank(taxId) ? 'must not be blank' : null });
      if (!(taxId.length === 10 ? isVkn(taxId) : taxId.length === 11 && isTckn(taxId))) {
        throw new Problem(422, 'TAX_ID_INVALID', 'Enter a valid VKN (10 digits) or TCKN (11 digits)');
      }
      const bill = findBill(params[0]);
      const data = fiscalState();
      let doc = data.documents.find((d) => d.billId === bill.id);
      if (doc && doc.status !== 'WAITING') throw new Problem(409, 'FISCAL_ALREADY_ISSUED', 'This bill already has its legal document', { parameters: { billId: bill.id } });
      if (!doc) {
        doc = { id: data.nextId++, billId: bill.id, status: 'WAITING', createdAt: Date.now(), attempts: 0 };
        data.documents.push(doc);
      }
      Object.assign(doc, { kind: 'E_ARSIV', buyerName: String(body.name).trim(), buyerTaxId: taxId, buyerAddress: clean(body.address) });
      if (bill.status === 'CLOSED') issueFiscal(bill);
      save();
      return fiscalView(doc);
    }],
    ['POST', /^fiscal\/documents\/(\d+)\/retry$/, 'till', ({ params }) => {
      const doc = fiscalState().documents.find((d) => d.id === Number(params[0]));
      if (!doc) throw new Problem(404, 'FISCAL_DOCUMENT_NOT_FOUND', `Fiscal document ${params[0]} was not found`);
      throw new Problem(409, 'FISCAL_NOT_RETRYABLE', 'Only a document that was not issued can be tried again', { parameters: { status: doc.status } });
    }],
    ['GET', /^fiscal\/z-report$/, 'manager', ({ query }) => {
      const day = query.get('date') || todayIso();
      const [from, to] = dayRange(day);
      const issued = fiscalState().documents.filter((d) => d.status === 'ISSUED' && d.issuedAt >= from && d.issuedAt < to);
      const sum = (kind) => toLira(issued.filter((d) => d.kind === kind).reduce((total, d) => total + d.totalC, 0));
      return { date: day, receipts: issued.filter((d) => d.kind === 'RECEIPT').length, receiptTotal: sum('RECEIPT'),
        invoices: issued.filter((d) => d.kind === 'E_ARSIV').length, invoiceTotal: sum('E_ARSIV'),
        deviceReceiptTotal: sum('RECEIPT'), difference: 0, waiting: 0 };
    }],
    ['GET', /^channels$/, 'manager', () => Object.entries(CHANNELS).map(([channel, [label]]) => ({
      channel, label, mode: 'SIMULATED', attention: channelState().orders.filter((o) => o.channel === channel && o.status === 'ATTENTION').length,
      mappings: channelState().mappings.filter((m) => m.channel === channel).length,
      recentUpdates: channelState().updates.filter((u) => u.channel === channel).map((u) => ({ what: u.what, target: u.target, value: u.value, at: iso(u.at) })),
    }))],
    ['GET', /^channels\/orders$/, 'manager', () => [...channelState().orders].sort((a, b) => b.receivedAt - a.receivedAt || b.id - a.id).slice(0, 50).map(channelOrderView)],
    ['POST', /^channels\/orders\/(\d+)\/(retry|reject)$/, 'manager', ({ params }) => {
      const record = channelState().orders.find((o) => o.id === Number(params[0]));
      if (!record) throw new Problem(404, 'CHANNEL_ORDER_NOT_FOUND', `Channel order ${params[0]} was not found`);
      if (record.status !== 'ATTENTION') throw new Problem(409, 'CHANNEL_ORDER_NOT_WAITING', `This platform order is already ${record.status}`, { parameters: { status: record.status } });
      if (params[1] === 'retry') {
        placeChannelOrder(record);
      } else {
        record.status = 'REJECTED';
        record.syncedStatus = 'REJECTED';
        recordUpdate(record.channel, 'status', record.externalId, 'REJECTED');
      }
      save();
      return channelOrderView(record);
    }],
    ['GET', /^channels\/([a-z]+)\/mappings$/, 'manager', ({ params }) => {
      const channel = channelOf(params[0]);
      return channelState().mappings.filter((m) => m.channel === channel).sort((a, b) => a.externalName.localeCompare(b.externalName, 'tr'))
        .map((m) => ({ externalId: m.externalId, externalName: m.externalName, menuItemId: m.menuItemId, menuItemName: findDish(m.menuItemId).name }));
    }],
    ['PUT', /^channels\/([a-z]+)\/mappings\/([^/]+)$/, 'manager', ({ params }, body) => {
      const channel = channelOf(params[0]);
      const externalId = decodeURIComponent(params[1]);
      if (!externalId.trim() || externalId.length > 80) throw new Problem(422, 'CHANNEL_ITEM_ID_INVALID', 'A platform item id has 1 to 80 characters');
      requireFields({ externalName: blank(body.externalName) ? 'must not be blank' : null, menuItemId: body.menuItemId == null ? 'must not be null' : null });
      const dish = findDish(body.menuItemId);
      const data = channelState();
      data.mappings = data.mappings.filter((m) => !(m.channel === channel && m.externalId === externalId));
      data.mappings.push({ channel, externalId, externalName: String(body.externalName).trim(), menuItemId: dish.id });
      save();
      return { externalId, externalName: String(body.externalName).trim(), menuItemId: dish.id, menuItemName: dish.name };
    }],
    ['DELETE', /^channels\/([a-z]+)\/mappings\/([^/]+)$/, 'manager', ({ params }) => {
      const channel = channelOf(params[0]);
      const externalId = decodeURIComponent(params[1]);
      channelState().mappings = channelState().mappings.filter((m) => !(m.channel === channel && m.externalId === externalId));
      save();
      return [204, null];
    }],
    ['POST', /^channels\/([a-z]+)\/simulate$/, 'manager', ({ params }, body) => {
      const channel = channelOf(params[0]);
      const withUnmapped = Boolean(body?.withUnmappedItem);
      const mapped = channelState().mappings.filter((m) => m.channel === channel && findDish(m.menuItemId).available && !findDish(m.menuItemId).stockOut);
      if (!mapped.length && !withUnmapped) throw new Problem(422, 'CHANNEL_NOTHING_MAPPED', 'Map at least one dish first');
      const picked = [...mapped].sort(() => Math.random() - 0.5).slice(0, 1 + Math.floor(Math.random() * 2));
      const items = picked.map((m) => ({ id: m.externalId, name: m.externalName, quantity: 1 + Math.floor(Math.random() * 2), unitPrice: toLira(findDish(m.menuItemId).priceC), options: [] }));
      // the platform charges what our menu does, tax included
      let totalC = items.length ? price(items.reduce((sum, i) => sum + toCents(i.unitPrice) * i.quantity, 0), 'TAKEAWAY', null).total : 0;
      if (withUnmapped) {
        items.push({ id: `NEW-${100 + Math.floor(Math.random() * 900)}`, name: 'Günün çorbası', quantity: 1, unitPrice: 150, options: [] });
        totalC += 16500;
      }
      const guests = ['Deniz K.', 'Ayşe Y.', 'Mert A.', 'Selin T.', 'Can B.'];
      const data = channelState();
      const record = { id: data.nextId++, channel, externalId: `SIM-${Math.random().toString(16).slice(2, 10).toUpperCase()}`, status: 'ATTENTION',
        orderId: null, ourTotal: null, priceDiffers: false, problemCode: 'RECEIVED', problem: null, syncedStatus: null, receivedAt: Date.now(),
        payload: { customer: { name: guests[Math.floor(Math.random() * guests.length)] }, items, total: toLira(totalC) } };
      data.orders.push(record);
      placeChannelOrder(record);
      save();
      return [201, channelOrderView(record)];
    }],
    ['GET', /^stock\/ingredients$/, 'manager', () => stock().ingredients.filter((i) => !i.archived).sort((a, b) => a.name.localeCompare(b.name, 'tr')).map(ingredientView)],
    ['POST', /^stock\/ingredients$/, 'manager', (_, body) => {
      validateIngredient(body, null);
      const ingredient = { id: stock().nextIngredientId++, name: String(body.name).trim(), unit: body.unit, costC: toCents(body.cost),
        lowStockLevel: body.lowStockLevel != null ? qty(body.lowStockLevel) : null, trackStock: body.trackStock !== false, archived: false };
      stock().ingredients.push(ingredient);
      save();
      return [201, ingredientView(ingredient)];
    }],
    ['PUT', /^stock\/ingredients\/(\d+)$/, 'manager', ({ params }, body) => {
      const ingredient = findIngredient(params[0]);
      validateIngredient(body, ingredient.id);
      if (body.unit !== ingredient.unit) {
        if (isUsed(ingredient.id) || stock().movements.some((m) => m.ingredientId === ingredient.id)) {
          throw new Problem(409, 'INGREDIENT_UNIT_LOCKED', `${ingredient.name} is already counted in ${ingredient.unit}`, { parameters: { name: ingredient.name } });
        }
        ingredient.unit = body.unit;
      }
      const wasLow = isLow(ingredient, balanceOf(ingredient.id));
      Object.assign(ingredient, { name: String(body.name).trim(), costC: toCents(body.cost), lowStockLevel: body.lowStockLevel != null ? qty(body.lowStockLevel) : null,
        trackStock: body.trackStock !== false });
      stockMoved(new Map([[ingredient.id, wasLow]]));
      return ingredientView(ingredient);
    }],
    ['DELETE', /^stock\/ingredients\/(\d+)$/, 'manager', ({ params }) => {
      const ingredient = findIngredient(params[0]);
      if (isUsed(ingredient.id)) throw new Problem(409, 'INGREDIENT_IN_USE', `${ingredient.name} is in a recipe`, { parameters: { name: ingredient.name } });
      ingredient.archived = true;
      save();
      return [204, null];
    }],
    ['POST', /^stock\/ingredients\/(\d+)\/movements$/, 'manager', ({ params, user }, body) => {
      const ingredient = findIngredient(params[0]);
      const amount = Number(body.quantity);
      requireFields({
        kind: !['SALE', 'WASTE', 'DELIVERY', 'COUNT'].includes(body.kind) ? 'must not be null' : null,
        quantity: body.quantity == null || !(amount >= 0 && amount <= 1_000_000) ? 'must be between 0 and 1000000' : null,
        unit: !INPUT_UNITS[body.unit] ? 'must not be null' : null,
      });
      if (body.kind === 'SALE') throw new Problem(422, 'MOVEMENT_KIND_NOT_ALLOWED', 'Sales are recorded by completed orders, not by hand');
      const [base, factor] = INPUT_UNITS[body.unit];
      if (base !== ingredient.unit) throw new Problem(422, 'UNIT_MISMATCH', `${ingredient.unit} cannot be counted in ${body.unit}`, { parameters: { unit: body.unit, expected: ingredient.unit } });
      const quantity = qty(amount * factor);
      const before = balanceOf(ingredient.id);
      const note = clean(body.reason);
      const movement = { id: null, ingredientId: ingredient.id, kind: body.kind, unitCostC: ingredient.costC, orderId: null, reason: note, supplier: null, by: user.username, at: Date.now() };
      if (body.kind !== 'COUNT' && quantity <= 0) throw new Problem(422, 'QUANTITY_REQUIRED', 'Enter how much');
      if (body.kind === 'WASTE' && !note) throw new Problem(422, 'WASTE_REASON_REQUIRED', 'Say why it was thrown away');
      movement.id = stock().nextMovementId++;
      if (body.kind === 'DELIVERY') {
        if (body.unitCost != null) ingredient.costC = toCents(body.unitCost);
        Object.assign(movement, { quantity, unitCostC: ingredient.costC, supplier: clean(body.supplier) });
      } else if (body.kind === 'WASTE') {
        movement.quantity = -quantity;
      } else {
        movement.quantity = qty(quantity - before);
      }
      stock().movements.push(movement);
      stockMoved(new Map([[ingredient.id, isLow(ingredient, before)]]));
      return [201, movementView(movement)];
    }],
    ['GET', /^stock\/ingredients\/(\d+)\/movements$/, 'manager', ({ params, query }) => {
      const ingredient = findIngredient(params[0]);
      const limit = Math.min(200, Math.max(1, Number(query.get('limit') || 50)));
      return stock().movements.filter((m) => m.ingredientId === ingredient.id).sort((a, b) => b.at - a.at || b.id - a.id).slice(0, limit).map(movementView);
    }],
    ['GET', /^stock\/report$/, 'manager', ({ query }) => {
      const today = new Date();
      const day = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      const to = query.get('to') || day(today);
      return stockReport(query.get('from') || day(new Date(new Date(`${to}T00:00:00`).getTime() - 6 * 24 * 60 * MINUTE)), to);
    }],
    ['GET', /^recipes\/costs$/, 'manager', () => state.menu.filter((m) => !m.archived && stock().recipes.some((r) => r.menuItemId === m.id))
      .map((m) => { const view = recipeView(m); return { menuItemId: m.id, cost: view.cost, foodCostPercent: view.foodCostPercent }; })],
    ['GET', /^recipes\/(\d+)$/, 'manager', ({ params }) => recipeView(findDish(params[0]))],
    ['PUT', /^recipes\/(\d+)$/, 'manager', ({ params }, body) => {
      const dish = findDish(params[0]);
      requireFields({ lines: !Array.isArray(body.lines) ? 'must not be null' : body.lines.length > 40 ? 'size must be between 0 and 40' : null });
      const known = (id) => {
        const ingredient = stock().ingredients.find((i) => i.id === Number(id) && !i.archived);
        if (!ingredient) throw new Problem(422, 'INGREDIENT_NOT_FOUND', `Ingredient ${id} does not exist`, { parameters: { id } });
        return ingredient;
      };
      const own = new Set(body.lines.filter((l) => blank(l.optionGroup) && blank(l.optionChoice)).map((l) => Number(l.ingredientId)));
      const seen = new Set();
      const lines = body.lines.map((line) => {
        if (line.quantity == null || !(Number(line.quantity) >= 0)) {
          throw new Problem(400, 'VALIDATION_FAILED', 'Request body is invalid.', { errors: [{ field: 'lines[].quantity', message: 'must not be null' }] });
        }
        const ingredient = known(line.ingredientId);
        const base = blank(line.optionGroup) && blank(line.optionChoice);
        let group = null;
        let choice = null;
        if (!base) {
          if (blank(line.optionGroup) || blank(line.optionChoice)) throw new Problem(422, 'RECIPE_LINE_INVALID', 'An option line needs both the group and the choice');
          const g = (dish.optionGroups || []).find((x) => x.name.toLocaleLowerCase() === String(line.optionGroup).trim().toLocaleLowerCase());
          const c = g?.choices.find((x) => x.name.toLocaleLowerCase() === String(line.optionChoice).trim().toLocaleLowerCase());
          if (!c) throw new Problem(422, 'RECIPE_OPTION_NOT_FOUND', 'No such option', { parameters: { group: String(line.optionGroup).trim(), choice: String(line.optionChoice).trim() } });
          group = g.name;
          choice = c.name;
        }
        const replaces = line.replacesIngredientId == null ? null : Number(line.replacesIngredientId);
        if (replaces != null && (base || !own.has(known(replaces).id))) throw new Problem(422, 'RECIPE_LINE_INVALID', 'Only an option can replace one of the dish\'s own ingredients');
        if (Number(line.quantity) === 0 && replaces == null) throw new Problem(422, 'RECIPE_QUANTITY_INVALID', 'Enter how much', { parameters: { name: ingredient.name } });
        const key = `${group}|${choice}|${ingredient.id}`;
        if (seen.has(key)) throw new Problem(422, 'RECIPE_DUPLICATE_INGREDIENT', `${ingredient.name} is in the recipe twice`, { parameters: { name: ingredient.name } });
        seen.add(key);
        return { menuItemId: dish.id, optionGroup: group, optionChoice: choice, ingredientId: ingredient.id, quantity: qty(line.quantity), replacesIngredientId: replaces };
      });
      stock().recipes = stock().recipes.filter((r) => r.menuItemId !== dish.id).concat(lines);
      stockChanged(refreshAvailability([dish.id]));
      return recipeView(dish);
    }],
    ['GET', /^analytics\/dashboard$/, 'manager', ({ query }) => {
      const days = Number(query.get('days') || 7);
      if (!(days >= 1 && days <= 90)) {
        throw new Problem(400, 'VALIDATION_FAILED', 'Request parameters are invalid.', { errors: [{ field: 'days', message: 'must be between 1 and 90' }] });
      }
      return dashboard(days);
    }],
    ...paymentRoutes,
  ];

  function json(status, body, contentType = 'application/json') {
    return new Response(body == null ? null : JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
  }

  function eventStream(headers, signal) {
    authenticate(headers, 'staff');
    let controllerRef;
    const stream = new ReadableStream({
      start(controller) {
        controllerRef = controller;
        streams.add(controller);
        controller.enqueue(new TextEncoder().encode('event:connected\ndata:{"subscribers":1}\nretry:3000\n\n'));
      },
      cancel() { streams.delete(controllerRef); },
    });
    signal?.addEventListener('abort', () => {
      streams.delete(controllerRef);
      try { controllerRef.close(); } catch { /* already closed */ }
    }, { once: true });
    return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }

  async function handle(url, init = {}) {
    const resolved = new URL(url, location.href);
    const path = resolved.pathname.replace(/^.*?api\/v1\//, '');
    const method = (init.method || 'GET').toUpperCase();
    const headers = init.headers || {};
    await new Promise((resolve) => setTimeout(resolve, 60 + Math.random() * 90)); // a touch of network latency
    try {
      if (method === 'GET' && path === 'events/orders') return eventStream(headers, init.signal);
      for (const [verb, pattern, access, handler] of routes) {
        const match = verb === method && pattern.exec(path);
        if (!match) continue;
        const user = authenticate(headers, access);
        let body = {};
        const contentType = headers['Content-Type'] || headers['content-type'] || '';
        if (init.body && contentType.startsWith('application/x-www-form-urlencoded')) {
          body = Object.fromEntries(new URLSearchParams(init.body));
        } else if (init.body) {
          try { body = JSON.parse(init.body); } catch { throw new Problem(400, 'MALFORMED_REQUEST', 'The request body is missing or is not valid JSON of the expected shape.'); }
        }
        const result = await handler({ params: match.slice(1), query: resolved.searchParams, user, headers }, body);
        if (result && typeof result.redirect === 'string') return new Response(null, { status: 303, headers: { Location: result.redirect } });
        return Array.isArray(result) && typeof result[0] === 'number' ? json(result[0], result[1]) : json(200, result);
      }
      throw new Problem(404, 'NOT_FOUND', `No endpoint for ${method} /${path}`);
    } catch (error) {
      if (!(error instanceof Problem)) {
        console.error(error);
        return json(500, { status: 500, title: 'Internal server error', detail: 'An unexpected error occurred.', code: 'INTERNAL_ERROR' }, 'application/problem+json');
      }
      return json(error.status, {
        status: error.status, detail: error.message, code: error.code, instance: `/api/v1/${path}`, timestamp: new Date().toISOString(),
        ...(error.extra.parameters ? { parameters: error.extra.parameters } : {}),
        ...(error.extra.errors ? { errors: error.extra.errors } : {}),
      }, 'application/problem+json');
    }
  }

  // ------------------------------------------------------------------------------------------- autopilot
  // Guests keep arriving while the demo is open: every minute or so a new order lands, so the kitchen
  // display and floor plan stay alive even when you only watch. Only one tab drives it.
  const LEADER_KEY = 'sofra.demo.autopilot';
  const tabId = Math.random().toString(36).slice(2);

  function autopilot() {
    try {
      const lease = JSON.parse(localStorage.getItem(LEADER_KEY) || 'null');
      if (lease && lease.tab !== tabId && Date.now() - lease.at < 90_000) return;
      localStorage.setItem(LEADER_KEY, JSON.stringify({ tab: tabId, at: Date.now() }));
    } catch { /* single tab */ }
    if (state.orders.filter((o) => o.status === 'PENDING' || o.status === 'PREPARING').length >= 9) return;
    const dishes = state.menu.filter((m) => m.available && !m.archived);
    const mains = dishes.filter((m) => m.category === 'MAIN');
    const drinks = dishes.filter((m) => m.category === 'DRINK');
    if (!mains.length) return;
    const pick = (list) => list[Math.floor(Math.random() * list.length)];
    const items = [pick(mains), pick(mains), drinks.length ? pick(drinks) : pick(mains)];
    const lines = buildLines(items.map((m) => ({ menuItemId: m.id, quantity: 1 })));
    const freeTables = TABLES.filter((t) => !state.orders.some((o) => o.table && o.table.id === t.id && ACTIVE.includes(o.status)));
    const type = freeTables.length && Math.random() < 0.6 ? 'DINE_IN' : Math.random() < 0.5 ? 'TAKEAWAY' : 'DELIVERY';
    const order = createOrder({
      type, customerName: pick(['Kemal Aksoy', 'Nazlı Er', 'Oğuz Kara', 'Leyla Su', 'Ahmet Tan', 'Julia Weber']),
      customerPhone: type === 'DELIVERY' ? '+90 532 555 12 34' : null, table: type === 'DINE_IN' ? { ...pick(freeTables) } : null,
      deliveryAddress: type === 'DELIVERY' ? 'Caferağa Mah. Moda Cad. 88, Kadıköy' : null, paymentMethod: type === 'DELIVERY' ? 'ONLINE' : 'CARD',
    }, lines, null, Date.now(), 'waiter');
    state.orders.push(order);
    attach(state, order, order.createdAt);
    changed(order, 'CREATED', null);
  }
  setInterval(autopilot, 70_000);

  window.SOFRA_BACKEND = {
    demo: true,
    print: window.SOFRA_DEMO_PRINT !== false,
    fetch: handle,
    reset() {
      state = freshState();
      save();
      location.reload();
    },
  };
})();
