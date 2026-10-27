/* Testes da Bia virtual (bia.html) - 27/10/2026.
   Rodar antes de todo push que mexe na bia.html:  node tests/bia_virtual.test.cjs  [--fotos pasta]
   Abre a pagina no Chromium em tela de celular, com a Edge Function Bia e o login do Supabase IMITADOS (dados de exemplo,
   nada real: o repositorio e publico). Confere: entrada (login, PIN do app), paineis, a conversa abrindo o painel certo,
   e que a pagina nao carrega chave secreta. */
const path = require("path"), fs = require("fs");
let chromium;
try { ({ chromium } = require("playwright")); } catch (e) { console.error("Instale o playwright: npm i -g playwright"); process.exit(2); }
const RAIZ = path.resolve(__dirname, "..");
const fotos = process.argv.includes("--fotos") ? process.argv[process.argv.indexOf("--fotos") + 1] : null;
const falhas = [], oks = [];
const verdade = (n, c, d = "") => (c ? oks : falhas).push(c ? n : `${n} ${d}`);

const serie = (base) => Array.from({ length: 30 }, (_, i) => { const d = new Date(Date.UTC(2026, 8, 27 + i)); return [d.toISOString().slice(0, 10), base + 8 * Math.sin(i / 3), base - 4 + i * 0.2]; });
const PAINEL = {
  currais: { ok: true, painel: "currais", dia: "2026-10-26", currais: [
    { curral: "X1", cabecas: 100, dia: 70, peso_entrada: 400, peso_proj: 498, energia_dia: 96, energia_acum: 99, gmd_energia: 1.3, dias_dourada_abaixo: 2, nota_acum_30d: 15, nota_dias: 28, abate: "2026-12-04", dias_abate: 38 },
    { curral: "X2", cabecas: 90, dia: 69, peso_entrada: 380, peso_proj: 477, energia_dia: 104, energia_acum: 98, gmd_energia: 1.35, dias_dourada_abaixo: 0, nota_acum_30d: -5, nota_dias: 27, abate: "2026-12-10", dias_abate: 44 }],
    resumo: { cabecas: 190, pct: 100, acum: 98.5, atencao: ["X1"] } },
  estoque: { ok: true, painel: "estoque", linhas: [
    { produto: "Insumo A", saldo_kg: 12000, conf_prev_dia: 1000, producao_dia: 0, dur_cor: 12 },
    { produto: "Insumo B", saldo_kg: 90000, conf_prev_dia: 1500, producao_dia: 100, dur_cor: 56 }] },
  financeiro: { ok: true, painel: "financeiro", atualizado: "2026-10-27T09:00:00Z",
    contas: [{ cod: "1", vencimento: "28/10/2026", fornecedor: "Fornecedor Exemplo", valor: 1000, situacao: "a vencer" }],
    parcelas: [{ venc: "2026-11-10", banco: "Banco Exemplo", tipo: "Custeio", valor: 5000 }],
    fluxo: [{ mes: "2026-11", receita: 0, racao: 100, contas: 200, financiamentos: 300, saldo_mes: -600, acumulado: -600 }] },
  mercado: { ok: true, painel: "mercado", mercado: { preco_ms: 300.5, data_ms: "2026-10-24", escala_dias: 9 }, futuros: [{ mes: "2026-11", codigo: "TESTE1", preco: 310.25 }],
    comercial: [{ curral: "X1", abate: "2026-12-04", margem_hoje: 10.5, margem_b3: 12.1, codigo_b3: "TESTE2" }] },
  cerebro: { ok: true, painel: "cerebro", atualizado: "2026-10-26", pendencias: ["Pendência de exemplo um", "Pendência de exemplo dois"],
    decisoes: [{ data: "26/10/2026", decisao: "Decisão de exemplo", porque: "Motivo de exemplo" }] },
};
PAINEL.megacaloria = { ...PAINEL.currais, painel: "megacaloria", currais: PAINEL.currais.currais.map((c, i) => ({ ...c, serie: serie(100 - i * 4) })) };

(async () => {
  const html = fs.readFileSync(path.join(RAIZ, "bia.html"), "utf8");
  verdade("sem chave secreta na pagina", !/sk-ant|sk-proj|service_role|SUPABASE_SERVICE|OPENAI_API_KEY|ANTHROPIC_API_KEY/.test(html));
  verdade("sem emoji na pagina", !/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(html));
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const pg = await ctx.newPage();
  const erros = []; pg.on("pageerror", (e) => erros.push(String(e)));
  const pedidos = [];
  await pg.route("**/*", async (r) => {
    const u = r.request().url();
    if (u.startsWith("file:")) return r.continue();
    if (u.includes("/auth/v1/token")) return r.fulfill({ json: { access_token: "a.b.c", refresh_token: "r", expires_in: 3600, user: { email: "teste@caimanagro.app" } } });
    if (u.includes("/functions/v1/Bia")) {
      const q = new URL(u).searchParams, p = q.get("painel");
      pedidos.push({ u, auth: r.request().headers()["authorization"], corpo: r.request().postData() });
      if (p) return r.fulfill({ json: PAINEL[p] });
      const corpo = JSON.parse(r.request().postData() || "{}");
      return r.fulfill({ json: { ok: true, texto: "O X1 está no dia 70, com 498 quilos projetados.", painel: "currais", curral: "X1", audio: null, versao: "teste" } });
    }
    return r.fulfill({ status: 404, body: "" });
  });
  const url = "file://" + path.join(RAIZ, "bia.html");

  /* 1) sem login: pede usuario e senha */
  await pg.goto(url); await pg.waitForTimeout(300);
  verdade("sem sessao abre o login", await pg.isVisible("#g-login"));
  if (fotos) await pg.screenshot({ path: path.join(fotos, "1_login.png") });
  await pg.fill("#g-user", "teste"); await pg.fill("#g-senha", "x"); await pg.click("#g-entrar");
  await pg.waitForTimeout(600);
  verdade("login entra na pagina", await pg.isVisible("#app"));
  const sess = await pg.evaluate(() => JSON.parse(localStorage.getItem("_cag_sess") || "null"));
  verdade("sessao gravada no mesmo formato do app", sess && sess.access_token === "a.b.c" && sess.manter === true && sess.email === "teste@caimanagro.app", JSON.stringify(sess));
  verdade("paineis pedidos com o token do login", pedidos.length >= 6 && pedidos.every((x) => x.auth === "Bearer a.b.c"), JSON.stringify(pedidos.slice(0, 2)));
  await pg.waitForTimeout(500);
  const tiles = await pg.$$eval(".tile", (xs) => xs.map((x) => x.innerText.replace(/\s+/g, " ")));
  verdade("seis paineis em volta do nucleo", tiles.length === 6, String(tiles.length));
  verdade("resumo dos currais", tiles.some((t) => /190 cabeças/.test(t) && /X1 em 38 dias/.test(t)), tiles.join(" | "));
  verdade("resumo da megacaloria com atencao", tiles.some((t) => /100% da meta/.test(t) && /Atenção: X1/.test(t)), tiles.join(" | "));
  verdade("estoque: o que acaba primeiro", tiles.some((t) => /Insumo A/.test(t) && /12 dias/.test(t)), tiles.join(" | "));
  if (fotos) await pg.screenshot({ path: path.join(fotos, "2_inicio.png") });
  if (fotos) await pg.screenshot({ path: path.join(fotos, "2b_inicio_inteira.png"), fullPage: true });

  /* 2) toque no painel abre em tela cheia; fechar volta */
  for (const k of ["currais", "megacaloria", "estoque", "financeiro", "mercado", "cerebro"]) {
    await pg.click(`.tile[data-p="${k}"]`); await pg.waitForTimeout(250);
    const txt = await pg.innerText("#corpo");
    verdade(`painel ${k} abre em tela cheia`, await pg.isVisible("#tela") && txt.length > 20, txt.slice(0, 80));
    if (fotos) await pg.screenshot({ path: path.join(fotos, `3_${k}.png`) });
    await pg.click("#fechar"); await pg.waitForTimeout(200);
    verdade(`painel ${k} fecha`, !(await pg.isVisible("#tela")));
  }
  await pg.click('.tile[data-p="megacaloria"]'); await pg.waitForTimeout(200);
  verdade("megacaloria: duas linhas por curral e legenda", (await pg.$$("#corpo .graf svg path")).length === 4 && /Acumulado do ciclo/.test(await pg.innerText("#corpo")));
  await pg.click("#fechar"); await pg.waitForTimeout(200);

  /* 3) conversa por texto: responde e abre o painel certo, destacando o curral */
  await pg.fill("#txt", "como está o X1?"); await pg.press("#txt", "Enter"); await pg.waitForTimeout(600);
  verdade("resposta na tela", /dia 70/.test(await pg.innerText("#resposta")));
  verdade("abre o painel dos currais com o X1 em destaque", await pg.isVisible("#tela") && (await pg.$$("#corpo .card.foco")).length === 1);
  const ult = pedidos.filter((x) => x.corpo).pop();
  verdade("mensagem enviada com voz ligada", ult && JSON.parse(ult.corpo).mensagem === "como está o X1?" && JSON.parse(ult.corpo).voz === true, ult && ult.corpo);
  if (fotos) await pg.screenshot({ path: path.join(fotos, "4_resposta_painel.png") });
  await pg.click("#fechar"); await pg.waitForTimeout(200);

  /* 4) PIN do app: com PIN ativo, a aba nova pede o PIN */
  const pg2 = await ctx.newPage(); pg2.on("pageerror", (e) => erros.push(String(e)));
  await pg2.goto(url);
  await pg2.evaluate(() => { let h = 5381; const s = "1234|caiman|" + location.hostname; for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) >>> 0; localStorage.setItem("_cag_pin", h.toString(16)); sessionStorage.clear(); });
  await pg2.reload(); await pg2.waitForTimeout(400);
  verdade("com PIN ativo pede o PIN", await pg2.isVisible("#g-pin"));
  if (fotos) await pg2.screenshot({ path: path.join(fotos, "5_pin.png") });
  for (const d of "1111") await pg2.click(`#g-tec button:text-is("${d}")`);
  verdade("PIN errado nao entra", /incorreto/.test(await pg2.innerText("#g-perro")) && !(await pg2.isVisible("#app")));
  for (const d of "1234") await pg2.click(`#g-tec button:text-is("${d}")`);
  await pg2.waitForTimeout(300);
  verdade("PIN certo entra", await pg2.isVisible("#app"));

  /* 5) tela aberta (dobravel aberto) */
  await pg.setViewportSize({ width: 884, height: 1104 }); await pg.waitForTimeout(300);
  if (fotos) await pg.screenshot({ path: path.join(fotos, "6_aberto.png") });
  const larg = await pg.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  verdade("sem rolagem lateral no dobravel aberto", larg);
  await pg.setViewportSize({ width: 344, height: 882 }); await pg.waitForTimeout(300);
  verdade("sem rolagem lateral no dobravel fechado", await pg.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  const alvo = await pg.$$eval("#barra button", (xs) => Math.min(...xs.map((x) => Math.min(x.getBoundingClientRect().width, x.getBoundingClientRect().height))));
  verdade("botoes grandes (48 px ou mais)", alvo >= 48, String(alvo));
  if (fotos) await pg.screenshot({ path: path.join(fotos, "7_fechado.png") });

  verdade("sem erro de javascript", erros.length === 0, erros.join(" | "));
  await b.close();
  console.log(`${oks.length} ok, ${falhas.length} falha(s)`);
  if (falhas.length) { falhas.forEach((f) => console.log("FALHOU: " + f)); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(1); });
