// netlify/functions/poll-adisyo.js  (cron-job.org her 1 dakikada bir çağırır)
//   1) Adisyo RecentOrders -> Supabase (yeni/güncel siparişler, kapananlar listeden düşer)
//   2) Kurye "Teslim Edildi" dediği siparişleri Adisyo'da /Deliver ile kapatır
//   3) ~10 dakikada bir Adisyo kuryelerini ve ödeme tiplerini senkronlar (?sync=1 ile hemen)
// ÇOK ŞUBELİ: Adisyo bilgileri her şube için sahip panelinden (veritabanından) gelir; aktif tüm şubeler sırayla çekilir.
// Çağrı: https://SITEN.netlify.app/.netlify/functions/poll-adisyo?key=POLL_KEY   (cron: her 1 dakika, TÜM şubeler)
//        ...?token=OTURUM  (yönetici paneli: sadece o şube; sahip paneli: hepsi)
// Ortam değişkenleri: SUPABASE_URL, SUPABASE_ANON_KEY, INGEST_SECRET, POLL_KEY
//   (Adisyo anahtarları artık ortam değişkeni DEĞİL; sahip panelinde şubeye girilir)
// İsteğe bağlı (telefon siparişi konumu): GOOGLE_GEOCODE_KEY veya STADIA_API_KEY, GEOCODE_CITY (ör. Sivas)

const BASE = 'https://ext.adisyo.com/api/External/v2/';

// "39,74104" -> 39.74104
const num = (v) => {
  if (v === null || v === undefined || v === '') return null;
  const n = parseFloat(String(v).replace(',', '.'));
  return Number.isFinite(n) && n !== 0 ? n : null;
};
// Adisyo saatleri saat dilimsiz gelir; Türkiye saati (UTC+3)
const tr = (d) => (d ? d + '+03:00' : null);

const sb = async (env, fn, args) => {
  const r = await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: 'POST',
    headers: {
      apikey: env.SUPABASE_ANON_KEY,
      Authorization: `Bearer ${env.SUPABASE_ANON_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(args),
  });
  const t = await r.text();
  if (!r.ok) throw new Error(`${fn}: ${t}`);
  return t ? JSON.parse(t) : null;
};

// Tek şubenin tüm işi: sipariş çek, konum bul, teslimleri kapat, (10 dk'da bir) kurye/ödeme senkronu
async function runBranch(env, q, br, secret) {
  const out = { errors: [] };
  const adisyo = async (method, path, body) => {
    const r = await fetch(BASE + path, {
      method,
      headers: {
        'x-api-key': br.key,
        'x-api-secret': br.secret,
        'x-api-consumer': br.consumer || 'KuryeTakip',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return r.json().catch(() => ({ status: -1, message: 'Adisyo cevabı okunamadı (HTTP ' + r.status + ')' }));
  };
  const bsb = (fn, args) => sb(env, fn, { p_secret: secret, p_branch: br.id, ...args });
  const city = br.city;

  let openIds = null;   // Adisyo'da şu an açık siparişler (liste tam geldiyse)
  // ---------- 1) Siparişler ----------
  try {
    const data = await adisyo('GET', 'RecentOrders');
    if (data.status === 601) {
      out.orders = 'atlandı: Adisyo istek limiti (601), sonraki çağrıda denenecek';
    } else if (data.status !== 100) {
      out.orders = { adisyo_status: data.status, message: data.message };
      out.errors.push('RecentOrders: ' + data.status);
    } else {
      const list = Array.isArray(data.data) ? data.data : Object.values(data).find(Array.isArray) || [];
      const all = list
        .filter((o) => o.orderTypeId === 3)
        .map((o) => {
          const c = o.customer || {};
          return {
            id: o.id,
            no: o.orderNumber,
            source: o.externalAppName || o.salesChannelName || null,
            status: o.status,
            payment: o.paymentMethodName,
            payment_id: o.paymentMethodId ?? null,
            total: o.orderTotal,
            cancel: o.orderCancelReason,
            courier: o.deliveryUserName,
            name: [c.customerName, c.customerSurname].filter(Boolean).join(' '),
            phone: c.customerPhone,
            phone2: c.customerPhone2 || null,
            address: c.address,
            region: c.region,
            note: c.addressDescription || o.orderNote || null,
            lat: num(o.customerLatitude),
            lng: num(o.customerLongitude),
            created: tr(o.insertDate),
            prepared: tr(o.preparedDate),
            items: (o.products || [])
              .filter((p) => !p.cancelReason)
              .map((p) => ({
                n: p.productName,
                q: Number(p.quantity) || 1,
                sub: p.parentId ? 1 : 0,
                note: p.productNote || null,
                ex: [...(p.excludedItems || []).map((e) => e.name || e.productName || e), ...(p.excludedComposites || [])].filter((x) => typeof x === 'string'),
                ft: (p.features || []).map((x) => x.featureProductName || x.featureName).filter(Boolean),
              })),
            updated: o.updateDate || '',
          };
        });
      // Aynı sipariş birden fazla gelirse en güncelini tut
      const byId = new Map();
      for (const o of all) {
        const p = byId.get(o.id);
        if (!p || o.updated >= p.updated) byId.set(o.id, o);
      }
      const orders = [...byId.values()];
      const complete = (data.pageCount || 1) <= 1; // liste tam değilse "listeden düştü" hesabı yapılmaz
      if (complete) openIds = new Set(orders.map((o) => o.id));
      const n = await bsb('adk_ingest_orders', { p_orders: orders, p_complete: complete });
      out.orders = { fetched: list.length, package_orders: orders.length, upserted: n, pageCount: data.pageCount };
    }
  } catch (e) {
    out.errors.push('siparis: ' + e.message);
  }

  // ---------- 1b) Konumsuz (telefon) siparişleri adresten bul ----------
  // Önce adres hafızası zaten ingest içinde uygulanır. Kalanlar için geocoding:
  //   GOOGLE_GEOCODE_KEY varsa Google, yoksa STADIA_API_KEY varsa Stadia, ikisi de yoksa OpenStreetMap (Nominatim)
  try {
    const gp = await bsb('adk_geo_pending', {});
    const list2 = (gp && gp.orders) || [];
    if (list2.length) {
      const R = gp.restaurant || {};
      const hasR = R.lat != null && R.lng != null;
      const km = (a, b, c, d) => {
        const r = (x) => (x * Math.PI) / 180, h = Math.sin(r(c - a) / 2) ** 2 + Math.cos(r(a)) * Math.cos(r(c)) * Math.sin(r(d - b) / 2) ** 2;
        return 12742 * Math.asin(Math.sqrt(h));
      };
      const cityQ = city ? ', ' + city : '';
      // Adresi geocoder'ın anlayacağı hale getir: kısaltmaları aç, daire/kat at, parantez/not at
      const clean = (x) => String(x || '').replace(/\([^)]*\)/g, ' ')
        .replace(/\b(mah|mh)\.?(?=\s|$)/gi, 'Mahallesi').replace(/\b(sk|sok)\.?(?=\s|$)/gi, 'Sokak').replace(/\b(cd|cad)\.?(?=\s|$)/gi, 'Caddesi')
        .replace(/\b(blv|bulv)\.?(?=\s|$)/gi, 'Bulvarı')
        .replace(/\b(daire|d|kat|k|apt|apartman[ıi]?|blok)\s*[:.]?\s*\d+[a-zA-Z]?\b/gi, ' ')
        .replace(/\bno\s*[:.]?\s*(\d+)/gi, '$1').replace(/\s+/g, ' ').trim();
      const variants = (o) => {
        const a = clean(o.address), rg = clean(o.region);
        const noNum = a.replace(/\b\d+[a-zA-Z]?\b/g, ' ').replace(/\s+/g, ' ').trim();   // bina no'suz (sokak seviyesi)
        const mah = (a.match(/[^,]*?mahallesi/i) || [''])[0].trim();
        return [...new Set([[a, rg], [noNum, rg], [mah, rg]].map(v => v.filter(Boolean).join(', ')).filter(v => v.length > 3))].map(v => v + cityQ);
      };
      const geocode1 = async (t) => {
        if (env.GOOGLE_GEOCODE_KEY) {
          let u = 'https://maps.googleapis.com/maps/api/geocode/json?language=tr&components=country:TR&address=' + encodeURIComponent(t) + '&key=' + env.GOOGLE_GEOCODE_KEY;
          if (hasR) u += `&bounds=${R.lat - 0.25},${R.lng - 0.25}|${R.lat + 0.25},${R.lng + 0.25}`;
          const j = await (await fetch(u)).json();
          const g = (j.results || [])[0];
          if (!g || g.geometry.location_type === 'APPROXIMATE' || (g.partial_match && !/street_address|premise|route|subpremise/.test((g.types || []).join()))) return null;
          return { lat: g.geometry.location.lat, lng: g.geometry.location.lng };
        }
        if (env.STADIA_API_KEY) {
          let u = 'https://api.stadiamaps.com/geocoding/v1/search?size=1&boundary.country=TR&text=' + encodeURIComponent(t) + '&api_key=' + env.STADIA_API_KEY;
          if (hasR) u += `&focus.point.lat=${R.lat}&focus.point.lon=${R.lng}`;
          const j = await (await fetch(u)).json();
          const f = (j.features || [])[0];
          if (!f || /^(country|region|macroregion|county|macrocounty|localadmin|locality)$/.test(f.properties.layer || '')) return null;
          return { lat: f.geometry.coordinates[1], lng: f.geometry.coordinates[0] };
        }
        let u = 'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=tr&q=' + encodeURIComponent(t);
        if (hasR) u += `&viewbox=${R.lng - 0.25},${R.lat + 0.25},${R.lng + 0.25},${R.lat - 0.25}&bounded=1`;
        const j = await (await fetch(u, { headers: { 'User-Agent': 'KuryeTakip/1.0 (siparis konum)' } })).json();
        const f = (j || [])[0];
        if (!f || /^(city|town|village|state|county|country|administrative)$/.test(f.type || '')) return null;
        return { lat: parseFloat(f.lat), lng: parseFloat(f.lon) };
      };
      const geocode = async (o) => {
        for (const t of variants(o)) {
          const p = await geocode1(t);
          if (p) return p;
          if (!env.GOOGLE_GEOCODE_KEY && !env.STADIA_API_KEY) await new Promise((r) => setTimeout(r, 1100));
        }
        return null;
      };
      out.geocode = { tried: list2.length, found: 0 };
      for (const o of list2) {
        let p = null;
        try { p = await geocode(o); } catch (e) { out.errors.push('geocode: ' + e.message); continue; }
        // restorandan çok uzaksa (yanlış şehir/ilçe) kabul etme
        if (p && hasR && km(R.lat, R.lng, p.lat, p.lng) > 30) p = null;
        await bsb('adk_geo_set', { p_order_id: o.id, p_lat: p ? p.lat : null, p_lng: p ? p.lng : null });
        if (p) out.geocode.found++;
        if (!env.GOOGLE_GEOCODE_KEY && !env.STADIA_API_KEY) await new Promise((r) => setTimeout(r, 1100)); // Nominatim: saniyede 1 istek
      }
    }
  } catch (e) {
    out.errors.push('konum: ' + e.message);
  }

  // ---------- 2) Teslim edilenleri Adisyo'da kapat ----------
  try {
    const pending = (await bsb('adk_pending_deliveries', {})) || [];
    out.deliveries = { pending: pending.length, ok: 0, failed: 0 };
    for (const d of pending) {
      const body = { orderId: d.order_id, paymentType: d.payment_type_id };
      let res = await adisyo('POST', 'Deliver', body);
      // Sipariş henüz "teslimatta" değilse (ör. kuryeyi sadece bizim panelden atadık): önce yola çıkar, sonra teslim et
      if (res.status !== 100 && d.courier_adisyo_id) {
        const od = await adisyo('POST', 'OnDelivery', { orderId: d.order_id, courierId: d.courier_adisyo_id });
        if (od.status === 100) res = await adisyo('POST', 'Deliver', body);
      }
      let ok = res.status === 100;
      // Adisyo hata verdi ama sipariş artık açık siparişler listesinde yoksa: Adisyo'da zaten kapatılmış (ör. kasadan) -> başarılı say
      if (!ok && openIds && !openIds.has(d.order_id)) { ok = true; out.deliveries.already_closed = (out.deliveries.already_closed || 0) + 1; }
      await bsb('adk_mark_delivery', {
        p_order_id: d.order_id, p_ok: ok,
        p_error: ok ? null : String(res.message || 'durum ' + res.status),
      });
      ok ? out.deliveries.ok++ : out.deliveries.failed++;
    }
  } catch (e) {
    out.errors.push('teslim: ' + e.message);
  }

  // ---------- 3) Kurye ve ödeme tipi senkronu (10 dakikada bir veya ?sync=1) ----------
  if (q.sync === '1' || new Date().getMinutes() % 10 === 0) {
    try {
      const [c, p] = await Promise.all([adisyo('GET', 'Couriers'), adisyo('GET', 'PaymentTypes')]);
      const couriers = c.status === 100 ? (c.couriers || []).map((x) => ({ id: x.id, name: x.name, phone: x.phoneNumber })) : null;
      const types = p.status === 100 ? p.paymentTypes || [] : null;
      if (couriers || types) {
        out.sync = await bsb('adk_sync_meta', { p_couriers: couriers, p_types: types });
      }
      out.sync_adisyo = { couriers: c.status, payment_types: p.status };
    } catch (e) {
      out.errors.push('sync: ' + e.message);
    }
  }

  return out;
}

exports.handler = async (event) => {
  const env = process.env;
  const q = event.queryStringParameters || {};
  const secret = env.INGEST_SECRET;
  let only = null;   // yönetici oturumuyla çağrıldıysa sadece o şube

  // Yetki: cron anahtarı (key), yönetici paneli oturumu ya da sahip oturumu (token)
  if (!(env.POLL_KEY && q.key === env.POLL_KEY)) {
    try {
      if (!q.token) throw new Error('token yok');
      const sc = await sb(env, 'adk_poll_scope', { p_token: q.token });
      if (!sc.all) only = sc.branch;
    } catch (e) {
      return { statusCode: 401, body: 'yetkisiz' };
    }
  }

  const out = { branches: {}, errors: [] };
  try { await sb(env, 'adk_maintenance', { p_secret: secret }); } catch (e) { out.errors.push('temizlik: ' + e.message); }

  let list = [];
  try {
    list = (await sb(env, 'adk_poll_branches', { p_secret: secret })) || [];
  } catch (e) {
    out.errors.push('şubeler: ' + e.message);
    return { statusCode: 500, body: JSON.stringify(out) };
  }
  if (only) list = list.filter((b) => b.id === only);
  if (q.branch) list = list.filter((b) => b.id === q.branch);

  // aynı anda en fazla 4 şube
  const todo = list.filter((b) => {
    if (b.key && b.secret) return true;
    out.branches[b.name] = 'atlandı: Adisyo bilgisi girilmemiş';
    return false;
  });
  let next = 0;
  const worker = async () => {
    while (next < todo.length) {
      const b = todo[next++];
      try {
        const r = await runBranch(env, q, b, secret);
        out.branches[b.tenant + ' / ' + b.name] = r;
        r.errors.forEach((e) => out.errors.push(b.name + ': ' + e));
      } catch (e) {
        out.errors.push(b.name + ': ' + e.message);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, todo.length) }, worker));

  // tek şubelik çağrıda (yönetici paneli) eski çıktı biçimini koru
  if (only && todo.length === 1) {
    const r = out.branches[todo[0].tenant + ' / ' + todo[0].name] || {};
    return { statusCode: out.errors.length ? 500 : 200, body: JSON.stringify({ ...r, errors: out.errors }) };
  }
  return { statusCode: out.errors.length ? 500 : 200, body: JSON.stringify(out) };
};
