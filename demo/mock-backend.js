// In-browser stand-in for the Sofra Spring Boot API, used by the static demo (GitHub Pages / embedded preview).
//
// It implements the same endpoints, payloads, roles, validation and error codes as the real backend — pricing,
// coupons, the order state machine, analytics and the Server-Sent Events stream — so the unmodified web client runs
// against it. State lives in localStorage and is shared live between tabs through a BroadcastChannel, so a waiter
// tab and a chef tab behave like two devices in the same restaurant.
(function () {
  'use strict';

  const STORE_KEY = 'sofra.demo.state.v1';
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

  function price(subtotalC, type, coupon) {
    let discountC = 0;
    if (coupon) {
      discountC = coupon.discountType === 'PERCENTAGE'
        ? halfUp((subtotalC * coupon.discountValue) / 100)
        : toCents(coupon.discountValue);
      discountC = Math.max(0, Math.min(discountC, subtotalC));
      if (coupon.maxDiscount != null) discountC = Math.min(discountC, toCents(coupon.maxDiscount));
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
    const menu = MENU.map((item) => ({ ...item, tags: [...item.tags] }));
    const orders = generateHistory(menu);
    return { createdAt: Date.now(), nextOrderId: 1001 + orders.length, nextMenuId: MENU.length + 1, menu, orders };
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
  function publish(event) {
    const frame = new TextEncoder().encode(`id:${Date.now()}\nevent:order\ndata:${JSON.stringify(event)}\n\n`);
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
      if (!dish.available) throw new Problem(422, 'MENU_ITEM_UNAVAILABLE', `${dish.name} is sold out`, { parameters: { menuItemId: dish.id, name: dish.name } });
      const quantity = Number(item.quantity);
      return {
        menuItemId: dish.id, name: dish.name, icon: dish.icon, category: dish.category, unitPriceC: dish.priceC,
        quantity, note: clean(item.note), lineTotalC: dish.priceC * quantity,
      };
    });
  }

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
      lines: order.lines.map(lineView),
      itemCount: order.lines.reduce((sum, line) => sum + line.quantity, 0),
      pricing: pricingView(order.pricing),
      createdAt: iso(order.createdAt),
      updatedAt: iso(order.updatedAt),
      statusSince: iso(order.history[order.history.length - 1].at),
      createdBy: order.createdBy,
      cancellationReason: order.cancellationReason,
      history: order.history.map((change) => ({ status: change.status, at: iso(change.at), by: change.by })),
    };
  }

  const lineView = (line) => ({
    menuItemId: line.menuItemId, name: line.name, icon: line.icon, category: line.category, unitPrice: toLira(line.unitPriceC),
    quantity: line.quantity, note: line.note, lineTotal: toLira(line.lineTotalC),
  });
  const pricingView = (p) => ({ subtotal: toLira(p.subtotal), discount: toLira(p.discount), tax: toLira(p.tax), deliveryFee: toLira(p.deliveryFee), total: toLira(p.total) });
  const menuView = (m) => ({
    id: m.id, name: m.name, description: m.description, category: m.category, price: toLira(m.priceC), icon: m.icon,
    prepTimeMinutes: m.prepTimeMinutes, popular: m.popular, available: m.available && !m.archived, tags: [...m.tags].sort(),
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
    const username = header.startsWith('Bearer demo.') ? header.slice('Bearer demo.'.length) : null;
    const user = username && USERS[username];
    if (!user) throw new Problem(401, 'UNAUTHORIZED', 'Authentication is required to access this resource.');
    if (!ROLES[access].includes(user.role)) throw new Problem(403, 'FORBIDDEN', 'Your role is not allowed to perform this action.');
    return { username, ...user };
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
    const name = String(body.name).trim();
    if (state.menu.some((m) => !m.archived && m.id !== exceptId && m.name.toLowerCase() === name.toLowerCase())) {
      throw new Problem(422, 'MENU_ITEM_NAME_TAKEN', `A menu item named '${name}' already exists`, { parameters: { name } });
    }
  }

  function applyDish(dish, body) {
    Object.assign(dish, {
      name: String(body.name).trim(), description: clean(body.description), category: body.category, priceC: toCents(body.price),
      icon: clean(body.icon), prepTimeMinutes: Number(body.prepTimeMinutes || 0), popular: Boolean(body.popular), tags: [...(body.tags || [])],
    });
  }

  const routes = [
    ['POST', /^auth\/login$/, 'public', (_, body) => {
      const user = USERS[String(body.username || '').toLowerCase()];
      if (!user || user.password !== body.password) throw new Problem(401, 'BAD_CREDENTIALS', 'Invalid username or password.');
      const username = String(body.username).toLowerCase();
      return { accessToken: `demo.${username}`, tokenType: 'Bearer', expiresAt: iso(Date.now() + 8 * 60 * MINUTE), user: { username, displayName: user.displayName, role: user.role } };
    }],
    ['GET', /^auth\/me$/, 'staff', ({ user }) => ({ username: user.username, displayName: user.displayName, role: user.role })],

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
    ['POST', /^orders$/, 'till', ({ user }, body) => {
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
      state.orders.push(order);
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
      const previous = order.status;
      transition(order, next, Date.now(), user.username);
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
      changed(order, 'STATUS_CHANGED', previous);
      return orderView(order);
    }],

    ['GET', /^floor\/tables$/, 'staff', () => TABLES.map((table) => {
      const open = state.orders.filter((o) => o.type === 'DINE_IN' && o.table && o.table.id === table.id && ACTIVE.includes(o.status))
        .sort((a, b) => a.createdAt - b.createdAt);
      return {
        ...table, occupied: open.length > 0, occupiedSince: open.length ? iso(open[0].createdAt) : null,
        openTotal: toLira(open.reduce((sum, o) => sum + o.pricing.total, 0)),
        openOrders: open.map((o) => ({ id: o.id, status: o.status, customerName: o.customerName, itemCount: o.lines.reduce((n, l) => n + l.quantity, 0), total: toLira(o.pricing.total), createdAt: iso(o.createdAt) })),
      };
    })],
    ['GET', /^coupons$/, 'staff', () => COUPONS.filter((c) => !c.validUntil || Date.now() < Date.parse(c.validUntil)).map((c) => ({ ...c }))],
    ['GET', /^analytics\/dashboard$/, 'manager', ({ query }) => {
      const days = Number(query.get('days') || 7);
      if (!(days >= 1 && days <= 90)) {
        throw new Problem(400, 'VALIDATION_FAILED', 'Request parameters are invalid.', { errors: [{ field: 'days', message: 'must be between 1 and 90' }] });
      }
      return dashboard(days);
    }],
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
        if (init.body) {
          try { body = JSON.parse(init.body); } catch { throw new Problem(400, 'MALFORMED_REQUEST', 'The request body is missing or is not valid JSON of the expected shape.'); }
        }
        const result = handler({ params: match.slice(1), query: resolved.searchParams, user }, body);
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
