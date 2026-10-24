/* Testes do app Caiman Gestao (24/10/2026).
   Ele: "testes automaticos das contas principais, que travam se algum numero mudar sem querer".
   Rodar ANTES de todo push:  node tests/app.test.cjs
   Abre o index.html num Chromium com a data fixa (24/10/2026 12h), poe dados de exemplo no
   localStorage (nada real: o repositorio e publico) e confere as contas. Se uma conta mudar de
   proposito, ajuste o numero esperado aqui no mesmo commit, dizendo por que. */
const path = require("path"), fs = require("fs");
let chromium;
try { ({ chromium } = require("playwright")); } catch (e) { console.error("Instale o playwright: npm i -g playwright"); process.exit(2); }

const RAIZ = path.resolve(__dirname, "..");
const LIBS = [process.env.CAIMAN_LIBS, path.join(RAIZ, "node_modules"), "/tmp/claude-0/-home-claude/c0e441d0-f26b-55f4-b317-86c88eece2a3/scratchpad/node_modules"].filter(Boolean);
const achar = (rel) => LIBS.map((d) => path.join(d, rel)).find((p) => fs.existsSync(p));

const ABATE = { pesoFinal: 548.6, data: "2026-11-14", gmd: 1.379 };   /* valores do modelo de projecao, conferidos em 24/10/2026 */
const falhas = [], oks = [];
function igual(nome, obtido, esperado, tol = 0) {
  const ok = typeof esperado === "number" ? Math.abs(Number(obtido) - esperado) <= tol : JSON.stringify(obtido) === JSON.stringify(esperado);
  (ok ? oks : falhas).push(ok ? nome : `${nome}: esperado ${JSON.stringify(esperado)}, veio ${JSON.stringify(obtido)}`);
}
function verdade(nome, cond, det = "") { (cond ? oks : falhas).push(cond ? nome : `${nome} ${det}`); }

/* ---------- dados de exemplo ---------- */
function fixture() {
  const lotes = [
    { id: 101, tipo: "Recria", fazenda: "Cruz Alta", pasto: "14B", qtd: 30, peso: 290, dtE: "2026-08-26", raca: "Nelore", sexo: "Machos", pesoAbate: 520, gmdAbate: 1.379, dtAbate: "2027-01-23" },
    { id: 102, tipo: "Recria", fazenda: "Cruz Alta", pasto: "9B", qtd: 60, peso: 300, dtE: "2026-02-23" },
    { id: 103, tipo: "Recria", fazenda: "Cruz Alta", pasto: "9B", qtd: 136, peso: 290, dtE: "2026-09-15" },
    { id: 104, tipo: "Semi-Confinamento", fazenda: "Cruz Alta", pasto: "1C", qtd: 95, peso: 342, dtE: "2026-08-22" },
    { id: 201, tipo: "Confinamento", fazenda: "Pinheiral", pasto: "A1", qtd: 130, peso: 418, pesoE: 418, dtE: "2026-08-11", raca: "Nelore", sexo: "Machos" },
  ];
  const animais = [], pes = [];
  for (let i = 0; i < 30; i++) {
    const id = 1000 + i;
    animais.push({ id, eletro: "9820004" + String(id).padStart(8, "0"), sisbov: "", fazenda: "Cruz Alta", pasto: "14B", peso: 290, dtE: "2026-08-26" });
    pes.push({ id: id * 10, animalId: id, data: "2026-08-26", peso: 280 + (i % 10) });
    pes.push({ id: id * 10 + 1, animalId: id, data: "2026-10-20", peso: 280 + (i % 10) + 33 + (i % 5) });   // 55 dias
  }
  for (let i = 0; i < 5; i++) animais.push({ id: 3000 + i, eletro: "98200050000" + i, fazenda: "Pinheiral", pasto: "A1", peso: 418, dtE: "2026-08-11" });
  return { lotes, animais, pes };
}

(async () => {
  const b = await chromium.launch({ executablePath: fs.existsSync("/opt/pw-browsers/chromium") ? undefined : undefined });
  const p = await b.newPage({ viewport: { width: 900, height: 1200 } });
  const erros = [];
  p.on("pageerror", (e) => { if (!/ServiceWorker/i.test(e.message)) erros.push(e.message); });
  p.on("dialog", (d) => d.accept());
  const jspdf = achar("jspdf/dist/jspdf.umd.min.js"), xlsx = achar("xlsx/dist/xlsx.full.min.js");
  if (jspdf) await p.route(/jspdf/, (r) => r.fulfill({ path: jspdf, contentType: "application/javascript" }));
  if (xlsx) await p.route(/xlsx\.full/, (r) => r.fulfill({ path: xlsx, contentType: "application/javascript" }));
  await p.route(/supabase\.co/, (r) => r.fulfill({ status: 503, body: "{}" }));   // nada sai para o servidor
  await p.clock.setFixedTime(new Date("2026-10-24T15:00:00Z"));
  await p.goto("file://" + path.join(RAIZ, "index.html"));
  await p.waitForTimeout(1500);
  const F = fixture();
  const r = await p.evaluate((F) => {
    localStorage.setItem("caiman_lotes_v3", JSON.stringify(F.lotes));
    localStorage.setItem("caiman_animais_v1", JSON.stringify(F.animais));
    localStorage.setItem("caiman_pesagens_sisbov", JSON.stringify(F.pes));
    localStorage.setItem("caiman_sanidade", JSON.stringify([{ id: 1, tipo: "vermifugo35", produto: "Teste", dose: "1 ml", data: "2026-10-10", carencia: 35, dataCarencia: "2026-11-14", alvo: "Recria", alvoFaz: "Cruz Alta", alvoPasto: "14B", animais: [1000, 1001] }]));
    const o = document.getElementById("login-overlay"); if (o) o.style.display = "none";
    const R = {};
    R.hoje = todayStr();
    R.corte = _cargasCorte();
    /* GMD real do pasto = media do GMD individual */
    const g = _gmdRealPasto("Cruz Alta", "14B");
    R.gmd = g && g.gmd; R.gmdN = g && g.n; R.pesoUlt = g && g.pesoUlt; R.dataUlt = g && g.dataUlt;
    _atualizarGmdRealLotes();
    const l14 = getLotes().find((l) => l.id === 101);
    R.l14 = { gmdAbate: l14.gmdAbate, modelo: l14.gmdAbateModelo, dtAbate: l14.dtAbate };
    /* peso de hoje de um boi = ultima pesagem + GMD individual */
    R.pesoHoje = pesoHojeAnimal(getAnimais().find((a) => a.id === 1000));
    /* lotacao: um pasto = um lote (nao soma o lote por cima dos identificados) */
    localStorage.setItem("caiman_areas_pasto", JSON.stringify({ "Cruz Alta|14B": 10 }));
    const lot = calcLotacaoPasto("Cruz Alta", "14B");
    R.lot = lot ? { ua: +lot.totalUA.toFixed(2), uaHa: +lot.uaHa.toFixed(3) } : null;
    /* abate do confinamento pelo modelo */
    const ab = calcAbateLote(getLotes().find((l) => l.id === 201));
    R.abate = ab ? { pesoFinal: ab.pesoFinal, data: ab.data, gmd: ab.gmd, arrobas: ab.arrobas } : null;
    /* estoque da Cruz Alta pela previsao do robo */
    localStorage.setItem("caiman_estoque_v1", JSON.stringify([{ chave: "racao 18@@cruz alta", nome: "RACAO 18 INVERNO 26", fazenda: "Cruz Alta", tipo: "inventario", data: "2026-10-24", kg: 5000 }]));
    _estCA = { ts: Date.now(), data: "2026-10-23", itens: [{ nome: "RACAO 18 INVERNO 26", kg: 17656, mediaDia: 1200, dias: 14.7 }, { nome: "Sal mineral", kg: 900, mediaDia: 30, dias: 30 }] };
    R.estCA = _estLista(null).filter((x) => /cruz/i.test(x.fazenda)).map((x) => [x.nome, x.saldo, x.dias, x.fonte]);
    _estCA = { ts: Date.now(), data: "2026-10-10", itens: _estCA.itens };   // velha (>3 dias): nao vale
    R.estCAVelha = _estLista(null).filter((x) => /cruz/i.test(x.fazenda)).map((x) => [x.nome, x.fonte || "app"]);
    /* conferencia lote x identificacao */
    R.plano = _planoLotesPelaPesagem().length; R.apagar = _lotesParaApagarPelaPesagem().map((x) => x.l.id).sort();
    /* telas */
    irPara("pastos"); renderPastos();
    const grid = document.getElementById("pastos-grid");
    R.tiles = grid.querySelectorAll("button[onclick^='abrirAnimaisLote']").length;
    R.banner = /Conferir e acertar/.test(grid.innerHTML);
    abrirAnimaisLote(encodeURIComponent("Cruz Alta"), encodeURIComponent("14B"));
    const al = document.getElementById("al-corpo").innerText;
    R.pagina = { lote: /Lote/.test(al), san: /Verm/.test(al), mover: /Mover todos/.test(al) };
    fecharAnimaisLote();
    /* confinamento: grade de currais e pagina do curral */
    const tr = [];
    for (let i = 1; i <= 14; i++) { const d = addDias("2026-10-24", -i); for (let n = 1; n <= 4; n++) tr.push({ id: "t" + i + "-" + n, loteId: 201, data: d, dataIni: d, dataFim: d, numTrato: n, kg: 700, kgms: 450, progKg: 690, origem: "saicon-descarga" }); }
    localStorage.setItem("caiman_tratos", JSON.stringify(tr));
    localStorage.setItem("caiman_leitura_cocho", JSON.stringify([{ id: "2026-10-24|A1|1", data: "2026-10-24", lote: "A1", slot: 1, nota: "-1", valor: 5 }]));
    irPara("conf"); renderCurraisGrade();
    const cg = document.getElementById("conf-grade");
    R.currais = cg.querySelectorAll("button[onclick^='abrirAnimaisLote']").length;
    R.curralTxt = cg.innerText.replace(/\s+/g, " ");
    abrirAnimaisLote(encodeURIComponent("Pinheiral"), encodeURIComponent("A1"));
    const ac = document.getElementById("al-corpo").innerText;
    R.paginaCurral = { titulo: /CURRAL|Curral/.test(ac), ms: /MS por cabe/.test(ac), nota: /Nota de cocho/.test(ac), graf: document.getElementById("al-corpo").querySelectorAll("svg").length };
    fecharAnimaisLote();
    /* cotacoes do dashboard */
    localStorage.setItem("caiman_arroba_futuros", JSON.stringify({ lido_em: "2026-10-24T12:00:00Z", contratos: [{ mes: "2026-10", codigo: "BGIV26", preco: 383 }, { mes: "2026-11", codigo: "BGIX26", preco: 392 }] }));
    localStorage.setItem("caiman_datagro", JSON.stringify({ sp: [["2026-10-22", 381], ["2026-10-23", 382]], ms: [["2026-10-22", 376], ["2026-10-23", 377]] }));
    renderMercadoBoi();
    const mb = document.getElementById("mercado-boi");
    R.mercado = { svgs: mb.querySelectorAll("svg").length, base: (mb.innerText.match(/Datagro MS\): ([^\n]+)/) || [])[1] || null };
    return R;
  }, F);

  if (process.env.SHOT) {   /* fotos para conferir o visual: SHOT=/pasta node tests/app.test.cjs */
    await p.evaluate(() => { irPara("conf"); renderCurraisGrade(); });
    await (await p.$("#conf-grade")).screenshot({ path: path.join(process.env.SHOT, "currais.png") });
    await p.evaluate(() => abrirAnimaisLote(encodeURIComponent("Pinheiral"), encodeURIComponent("A1")));
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(process.env.SHOT, "curral.png") });
    await p.evaluate(() => fecharAnimaisLote());
  }
  igual("data fixa do teste", r.hoje, "2026-10-24");
  igual("corte das cargas = 120 dias", r.corte, "2026-06-26");
  igual("GMD real do 14B (media individual)", r.gmd, 0.636, 0.0005);
  igual("bois com GMD no 14B", r.gmdN, 30);
  igual("peso medio na ultima pesagem do 14B", r.pesoUlt, 319.5, 0.05);
  igual("lote 14B: GMD projetado vira o real", r.l14.gmdAbate, 0.636, 0.0005);
  igual("lote 14B: GMD do modelo guardado", r.l14.modelo, 1.379, 0.0001);
  igual("lote 14B: data de abate recalculada (520 - 319,5) / 0,636 = 316 dias de 20/10", r.l14.dtAbate, "2027-09-01");
  igual("peso de hoje do boi 1000", r.pesoHoje.hoje, 315, 0.5);
  igual("lotacao 14B: UA (30 bois x ~322 kg hoje / 450, so os identificados)", r.lot && r.lot.ua, 21.47, 0.05);
  igual("lotacao 14B: UA/ha", r.lot && r.lot.uaHa, 2.147, 0.005);
  verdade("abate do confinamento calculado", !!r.abate, "(calcAbateLote devolveu nada)");
  if (process.env.MOSTRAR) console.log(JSON.stringify(r.abate));
  if (r.abate) { igual("abate A1 (modelo): peso final", r.abate.pesoFinal, ABATE.pesoFinal, 0.05); igual("abate A1 (modelo): data", r.abate.data, ABATE.data); igual("abate A1 (modelo): GMD", r.abate.gmd, ABATE.gmd, 0.0005); }
  igual("estoque Cruz Alta pela previsao do robo", r.estCA, [["RACAO 18 INVERNO 26", 17656, 14.7, "Saicon"], ["Sal mineral", 900, 30, "Saicon"]]);
  igual("previsao velha nao vale", r.estCAVelha, [["RACAO 18 INVERNO 26", "app"]]);
  verdade("conferencia: acha lote para apagar", r.apagar.length >= 1, JSON.stringify(r.apagar));
  verdade("recria: quadrados na grade", r.tiles >= 3, `(${r.tiles})`);
  verdade("recria: aviso de conferencia", r.banner);
  verdade("pagina do pasto: lote, sanitario e acoes", r.pagina.lote && r.pagina.san && r.pagina.mover, JSON.stringify(r.pagina));
  igual("dashboard: 3 graficos de cotacao", r.mercado.svgs, 3);
  igual("dashboard: base B3 - Datagro MS", r.mercado.base, "+R$ 6,00 por @");
  igual("confinamento: um quadrado por curral", r.currais, 1);
  verdade("confinamento: MS/cab de ontem no quadrado (450x4/130 = 13,85 kg)", /13,85 kg/.test(r.curralTxt), r.curralTxt.slice(0, 200));
  verdade("confinamento: nota de cocho de hoje no quadrado", /nota -1/.test(r.curralTxt), r.curralTxt.slice(0, 200));
  verdade("pagina do curral: titulo, MS, nota e graficos", r.paginaCurral.titulo && r.paginaCurral.ms && r.paginaCurral.nota && r.paginaCurral.graf >= 1, JSON.stringify(r.paginaCurral));
  verdade("nenhum erro de JavaScript na pagina", !erros.length, erros.slice(0, 3).join(" | "));

  await b.close();
  console.log(`${oks.length} ok, ${falhas.length} falha(s)`);
  if (falhas.length) { falhas.forEach((f) => console.log("FALHOU: " + f)); process.exit(1); }
})().catch((e) => { console.error("teste quebrou:", e); process.exit(1); });
