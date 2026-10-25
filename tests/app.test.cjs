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
    R.fechados = ["conf-cad", "conf-det"].map((id) => { const d = document.getElementById(id); return !!d && !d.open; });
    R.currais = cg.querySelectorAll("button[onclick^='abrirAnimaisLote']").length;
    R.curralTxt = cg.innerText.replace(/\s+/g, " ");
    abrirAnimaisLote(encodeURIComponent("Pinheiral"), encodeURIComponent("A1"));
    const ac = document.getElementById("al-corpo").innerText;
    R.paginaCurral = { peso: /PESO PROJETADO HOJE\s*522 kg|Peso projetado hoje\s*522 kg/i.test(ac), titulo: /CURRAL|Curral/.test(ac), ms: /MS por cabe/.test(ac), nota: /Nota de cocho/.test(ac), graf: document.getElementById("al-corpo").querySelectorAll("svg").length, progReal: ((ac.match(/Trato programado x realizado[^\n]*\n?[^\n]*/) || [""])[0]).replace(/\s+/g, " ") };
    R.pesoEnergia = (ac.match(/A caminho do peso projetado[^\n]*\n?[^\n]*/) || [""])[0].replace(/\s+/g, " ");
    fecharAnimaisLote();
    /* 24/10: aviso de peso x data de abate e GMD na saida */
    R.avisoAbate = _pcAvisoAbate({ id: 999, tipo: "Confinamento", pesoE: 418, dtE: "2026-08-11", pesoAbate: 533, dtAbate: "2026-12-07", dtAbateManual: true }).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");
    R.avisoAbateOk = _pcAvisoAbate({ id: 998, tipo: "Confinamento", pesoE: 418, dtE: "2026-08-11", pesoAbate: 584, dtAbate: "2026-12-07", dtAbateManual: true });
    localStorage.setItem("caiman_abates_v1", JSON.stringify([{ id: 1, tipo: "Confinamento", fazenda: "Pinheiral", pasto: "A9", qtd: 100, dataSaida: "2026-09-30", dias: 100, pesoE: 400, fech: { gmd: 1.55, gmdFonte: "embarque", pesoSaida: 555, gmdProj: 1.4, gmdEner: 1.5 } }]));
    R.gmdSaida = _pcGmdSaidaBloco().replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");
    localStorage.removeItem("caiman_abates_v1");
    /* cotacoes do dashboard */
    localStorage.setItem("caiman_arroba_futuros", JSON.stringify({ lido_em: "2026-10-24T12:00:00Z", contratos: [{ mes: "2026-10", codigo: "BGIV26", preco: 383 }, { mes: "2026-11", codigo: "BGIX26", preco: 392 }] }));
    localStorage.setItem("caiman_datagro", JSON.stringify({ sp: [["2026-10-22", 381], ["2026-10-23", 382]], ms: [["2026-10-22", 376], ["2026-10-23", 377]] }));
    renderMercadoBoi();
    const mb = document.getElementById("mercado-boi");
    R.mercado = { svgs: mb.querySelectorAll("svg").length, base: (mb.innerText.match(/Datagro MS\): ([^\n]+)/) || [])[1] || null };
    return R;
  }, F);

  /* 24/10: estoque pelas dietas (mesma conta do Painel: funcoes caiman_est_* do Supabase).
     O teste poe a resposta do servidor em _EI.dados e confere a tela e o PDF. */
  const ei = await p.evaluate(async () => {
    const dias = (de, n) => Array.from({ length: n }, (_, i) => addDias(de, i));
    const pj = dias("2026-10-24", 112);
    const est = [
      { insumo: "Bagaço De Cana", saldo: 271065, preco_kg: 0.28, valor: 75898, consumo_conf: 6532, consumo_prod: 0, acaba_conf: "2026-12-12", dias_conf: 49, acaba_total: "2026-12-12", dias_total: 49, falta_conf_kg: 145174, ultimo_abate: "2027-02-13" },
      { insumo: "Sal Branco", saldo: 3097, preco_kg: 0.91, valor: 2818, consumo_conf: 65, consumo_prod: 369, acaba_conf: "2026-12-10", dias_conf: 47, acaba_total: "2026-10-31", dias_total: 7, falta_conf_kg: 2066, ultimo_abate: "2027-02-13" },
      { insumo: "Ureia", saldo: 24218, preco_kg: 3.25, valor: 78709, consumo_conf: 130, consumo_prod: 199, acaba_conf: null, dias_conf: null, acaba_total: "2027-01-10", dias_total: 78, falta_conf_kg: -13895, ultimo_abate: "2027-02-13" },
    ];
    const proj = [];
    est.forEach((e) => pj.forEach((d, i) => proj.push({ insumo: e.insumo, dia: d, saldo_conf: Math.round(e.saldo - (e.consumo_conf || 1) * i), saldo_total: Math.round(e.saldo - ((e.consumo_conf || 1) + e.consumo_prod) * i) })));
    const passado = dias("2026-09-10", 45), prog = [], uso = [];
    passado.forEach((d, i) => {
      const ms = i < 22 ? 55 : 45;   // metade seca (55%), metade umida (45%): media 50%
      prog.push({ insumo: "Bagaço De Cana", dia: d, programado: 6000, carregado: 6100, ms });
      uso.push({ insumo: "Bagaço De Cana", dia: d, cocho: 6100, racao: 0 });
    });
    const currais = [
      { curral: "A2", cabecas: 131, dieta: "DIETA DE TERMINAÇÃO", desde: "2026-09-27", abate: "2026-12-04" },
      { curral: "A3", cabecas: 131, dieta: "DIETA DE TERMINAÇÃO", desde: "2026-09-27", abate: "2026-12-21" },
      { curral: "B1", cabecas: 132, dieta: "CRESCIMENTO", desde: "2026-10-22", abate: "2027-01-29" },
    ];
    _EI.dados = { estoque: est, proj, uso, prog, currais }; _EI.ts = Date.now();
    irPara("estoque"); await _eiRender();
    const R = {};
    R.lista = document.getElementById("ei-conteudo").innerText.replace(/\s+/g, " ");
    _eiAbrir("Bagaço De Cana"); await _eiRenderInsumo();
    const pg = document.getElementById("ei-pagina");
    R.pagina = pg.innerText.replace(/\s+/g, " ");
    R.svgs = pg.querySelectorAll("svg").length;
    R.ativa = document.getElementById("page-estinsumo").classList.contains("active");
    R.botoes = [...pg.querySelectorAll("button")].map((x) => x.textContent.trim()).join("|");
    let pdf = null; const orig = window._pdfEntregar;
    window._pdfEntregar = (doc, nome) => { pdf = { nome, pags: doc.getNumberOfPages() }; };
    try { await _eiPdf(); } finally { window._pdfEntregar = orig; }
    R.pdf = pdf;
    /* 25/10: aviso de estoque com a mesma conta (sal: cocho 47 dias, com a racao da Cruz Alta 7 dias) */
    R.nomes = [_nomeArq("Relatorio_Lote_A1.pdf", "pdf"), _nomeArq("Tratos_2026-10-23.pdf", "pdf"), _nomeArq("Pesagens Confinamento", "xlsx"), _nomeArq("Bagaço De Cana", "pdf")];
    R.alertasPinh = _estAlertas().filter((x) => /pinheiral/i.test(x.fazenda)).map((x) => x.nome);
    _estAvisoBaixoMostrar(true);
    const mod = document.getElementById("modal-est-aviso");
    R.aviso = mod ? mod.innerText.replace(/\s+/g, " ") : "";
    if (mod) mod.remove();
    return R;
  });
  verdade("estoque pelas dietas: cartao do que comprar (bagaco 145 t ate 13/02)", /Comprar para chegar ao último abate \(13\/02\)/.test(ei.lista) && /145 t/.test(ei.lista), ei.lista.slice(0, 200));
  verdade("estoque pelas dietas: lista com data de fim (bagaco acaba 12/12, ureia chega)", /acaba 12\/12/.test(ei.lista) && /chega ao abate/.test(ei.lista), ei.lista.slice(0, 300));
  verdade("pagina do insumo abre", ei.ativa);
  verdade("pagina do insumo: frase com currais que nao chegam (A3, B1)", /Acaba em 12\/12, antes do abate de A3, B1/.test(ei.pagina), ei.pagina.slice(0, 200));
  verdade("pagina do insumo: falta em R$ (145.174 kg x 0,28 = R$ 40.649)", /R\$ 40\.649/.test(ei.pagina), "");
  verdade("pagina do insumo: currais na ordem de abate, A2 chega e B1 nao", /A2[^]*chega[^]*A3[^]*não · 9 dias sem[^]*B1/.test(ei.pagina), ei.pagina.slice(0, 600));
  verdade("pagina do insumo: media geral de MS 49,9% (22 dias a 55% e 23 a 45%: 2245/45)", /MÉDIA GERAL DE MS 49,9%|Média geral de MS 49,9%/i.test(ei.pagina), "");
  verdade("pagina do insumo: graficos (saldo, projetado x realizado, MS, usado)", ei.svgs >= 4, "(" + ei.svgs + ")");
  igual("arquivos sempre nomeados e com data", ei.nomes, ["Relatorio_Lote_A1_2026-10-24.pdf", "Tratos_2026-10-23.pdf", "Pesagens_Confinamento_2026-10-24.xlsx", "Bagaco_De_Cana_2026-10-24.pdf"]);
  igual("aviso de estoque: nenhum insumo do cocho acaba em 15 dias pelas dietas", ei.alertasPinh, []);
  verdade("aviso de estoque: sal so aperta com a racao da Cruz Alta (31/10)", /Só aperta se continuar batendo ração/.test(ei.aviso) && /Sal Branco/.test(ei.aviso) && /31\/10\/2026/.test(ei.aviso), ei.aviso.slice(0, 300));
  verdade("pagina do insumo: um botao so, Exportar PDF, sem emoji", ei.botoes === "Exportar PDF", ei.botoes);
  verdade("pagina do insumo: PDF no padrao do app", !!ei.pdf && ei.pdf.nome === "Bagaco_De_Cana_2026-10-24.pdf" && ei.pdf.pags >= 1, JSON.stringify(ei.pdf));

  if (process.env.SHOT) {   /* fotos para conferir o visual: SHOT=/pasta node tests/app.test.cjs */
    await p.evaluate(() => { irPara("conf"); renderCurraisGrade(); });
    await (await p.$("#conf-grade")).screenshot({ path: path.join(process.env.SHOT, "currais.png") });
    await p.evaluate(() => abrirAnimaisLote(encodeURIComponent("Pinheiral"), encodeURIComponent("A1")));
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(process.env.SHOT, "curral.png") });
    await p.evaluate(() => abrirAnimaisLote(encodeURIComponent("Cruz Alta"), encodeURIComponent("14B")));
    await p.screenshot({ path: path.join(process.env.SHOT, "pasto.png"), fullPage: true });
    await p.evaluate(() => fecharAnimaisLote());
    await p.evaluate(async () => { irPara("estoque"); await _eiRender(); });
    await (await p.$("#ei-sec")).screenshot({ path: path.join(process.env.SHOT, "estoque.png") });
    await p.evaluate(async () => { _eiAbrir("Bagaço De Cana"); await _eiRenderInsumo(); });
    await p.screenshot({ path: path.join(process.env.SHOT, "insumo.png"), fullPage: true });
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
  igual("confinamento: cadastro e detalhes dos lotes comecam fechados", r.fechados, [true, true]);
  verdade("confinamento: MS/cab de ontem no quadrado (450x4/130 = 13,85 kg)", /13,85 kg/.test(r.curralTxt), r.curralTxt.slice(0, 200));
  verdade("confinamento: peso projetado de hoje no quadrado (418 + 74 dias x 1,4 = 522 kg)", /Peso hoje \(proj\.\) 522 kg/.test(r.curralTxt), r.curralTxt.slice(0, 260));
  verdade("pagina do curral: peso projetado de hoje", r.paginaCurral.peso, "");
  // 24/10: ele pediu a nota em porcentagem: nota -1 = trato +5%
  verdade("confinamento: nota de cocho de hoje em % no quadrado (nota -1 = trato +5%)", /trato \+5%/.test(r.curralTxt), r.curralTxt.slice(0, 200));
  // 24/10: grafico programado x realizado (4 tratos de 700 kg = 2.800 kg contra 4 x 690 = 2.760 kg)
  verdade("pagina do curral: trato programado x realizado (2.800 de 2.760 kg, +1,4%)", /2\.800<?\/?b?>? de 2\.760 kg|2\.800 de 2\.760 kg/.test(r.paginaCurral.progReal) && /\+1,4%/.test(r.paginaCurral.progReal), r.paginaCurral.progReal);
  // 24/10: peso estimado pela energia (126% da meta -> Livio 1,84 kg/dia; 73 dias -> 418 + 134 = 553 kg; projetado 520 kg)
  verdade("pagina do curral: peso estimado pela energia (+32 kg a frente, GMD 1,84)", /\+32 kg/.test(r.pesoEnergia) && /1,84/.test(r.pesoEnergia), r.pesoEnergia);
  verdade("aviso de abate: 533 kg com data de 07/12 (418 + 118 dias x 1,4 = 583 kg, chegaria em 02/11)", /583 kg/.test(r.avisoAbate) && /533 kg/.test(r.avisoAbate) && /02\/11\/2026/.test(r.avisoAbate), r.avisoAbate);
  igual("aviso de abate: some quando peso e data batem", r.avisoAbateOk, "");
  verdade("GMD na saida: real 1,55 x projetado 1,40 x energia 1,50", /1,55/.test(r.gmdSaida) && /1,40/.test(r.gmdSaida) && /1,50/.test(r.gmdSaida), r.gmdSaida);
  verdade("pagina do curral: titulo, MS, nota e graficos", r.paginaCurral.titulo && r.paginaCurral.ms && r.paginaCurral.nota && r.paginaCurral.graf >= 1, JSON.stringify(r.paginaCurral));
  verdade("nenhum erro de JavaScript na pagina", !erros.length, erros.slice(0, 3).join(" | "));

  await b.close();
  console.log(`${oks.length} ok, ${falhas.length} falha(s)`);
  if (falhas.length) { falhas.forEach((f) => console.log("FALHOU: " + f)); process.exit(1); }
})().catch((e) => { console.error("teste quebrou:", e); process.exit(1); });
