// ============================================================
// GRUPO CAIMAN AGRO - Calendario de TROCAS DE DIETA (.ics)
// Edge Function publica que devolve um calendario assinavel.
// O Google Agenda le esta URL sozinho, algumas vezes por dia:
// lote novo cadastrado hoje aparece na agenda sem nenhuma acao.
//
// Assinar no Android:  Google Agenda (no computador) > Outras agendas >
//   "+" > De URL > colar a URL abaixo > Adicionar agenda.
//   A agenda sincroniza automaticamente para o celular.
//
// URL:  https://<PROJETO>.supabase.co/functions/v1/calendario-dietas?t=<TOKEN>
//
// IMPORTANTE - o Google acessa sem cabecalho de autorizacao, entao esta
// funcao PRECISA ser publicada com verify_jwt DESLIGADO:
//   Dashboard > Edge Functions > calendario-dietas > Details > desmarcar
//   "Enforce JWT verification"   (ou: supabase functions deploy calendario-dietas --no-verify-jwt)
// A protecao passa a ser o parametro ?t=, comparado com o secret CAL_TOKEN.
// ============================================================

const TZ = "America/Campo_Grande";

type Cfg = {
  fases: { ad1: number; ad2: number; ad3: number; cre: number };
  dietaFase: Record<string, any>;
};
/* Estes numeros so valem se caiman_prog_trato nao tiver "fases" gravado.
   A verdade e a rampa do Saicon, que e quem manda no vagao — se as duas
   divergirem, a agenda marca a troca num dia e o cocho serve outra dieta. */
const CFG_PADRAO: Cfg = {
  fases: { ad1: 7, ad2: 11, ad3: 0, cre: 22 },
  dietaFase: { ad1: null, ad2: null, ad3: null, cre: null, fin: null },
};

const ROTULO: Record<string, string> = {
  ad1: "Adaptacao 1",
  ad2: "Adaptacao 2",
  ad3: "Adaptacao 3",
  cre: "Crescimento",
  fin: "Final",
};

/* Data efetiva de inicio de uma fase, respeitando o "Segurar fase ate" do lote.

   Copia da regra de faseDoLote() no app: enquanto a data corrente for <= a data
   de segurar, o lote fica na fase anterior; passada essa data ele volta ao
   calendario normal. Ou seja, uma virada que cairia dentro da janela acontece
   no dia seguinte ao fim dela, e as viradas posteriores nao se mexem — a
   terminacao nao atrasa.

   Sem isto a agenda marcava a troca num dia e o app noutro. Foi o que fez o
   evento do A3 aparecer em 01/09 quando o curral so virava em 02/09. */
function inicioEfetivo(dataNatural: string, segurarAte: any, segurarDesde: any): string {
  const ate = String(segurarAte || "").slice(0, 10);
  if (!ate) return dataNatural;
  if (dataNatural > ate) return dataNatural;   // virada depois da janela: nao muda
  const desde = String(segurarDesde || "").slice(0, 10);
  if (desde && dataNatural < desde) return dataNatural;  // virada ja tinha acontecido
  return addDias(ate, 1);
}

function diasEntre(a: string, b: string): number {
  const d1 = new Date(a + "T12:00:00"), d2 = new Date(b + "T12:00:00");
  return Math.round((d2.getTime() - d1.getTime()) / 86400000);
}
function addDias(data: string, n: number): string {
  const d = new Date(data + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
}
function soData(s: string): string {
  return String(s || "").slice(0, 10).replace(/-/g, "");
}
function fmtBR(data: string): string {
  const p = String(data || "").slice(0, 10).split("-");
  return p.length === 3 ? `${p[2]}/${p[1]}/${p[0]}` : String(data || "");
}
// escape exigido pelo formato iCalendar (RFC 5545)
function esc(s: string): string {
  return String(s == null ? "" : s)
    .replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,")
    .replace(/\r?\n/g, "\\n");
}
// linhas de .ics nao podem passar de 75 octetos: dobra com espaco no inicio
function dobrar(linha: string): string {
  if (linha.length <= 73) return linha;
  const partes: string[] = [];
  let resto = linha;
  partes.push(resto.slice(0, 73));
  resto = resto.slice(73);
  while (resto.length > 72) { partes.push(" " + resto.slice(0, 72)); resto = resto.slice(72); }
  if (resto.length) partes.push(" " + resto);
  return partes.join("\r\n");
}


/* ---------- MODELO DE ABATE (aba PROJEÇAO ABATES) — espelha o app ----------
   FS      = (inteiros ? -0,12 : 0) + 1,33 + 0,0036 x (PesoIni x 0,96)
   P.FINAL = femeas: (551,5 - 0,2482xPI' + 0,00119xPI'^2 - 39,84xFS) x (acab/28%)
             machos: (509,6 + 0,4697xPI' - 46,54xFS)            x (acab/28%)
   EM/PV%  = <18m: femeas 7,07 / nelore 6,08 / demais 6,35
             >=18m: inteiros 5,98 / cruzados 5,62 / demais 4,31
   GMD     = polinomio de 2o grau sobre R, por sexo/raca/idade
   PERIODO = (PesoFinal - PesoIni) / GMD                                     */
function zSexo(s: any){return String(s||"Machos").toLowerCase();}
function zFS(pi: number, sexo: any){
  const p=(pi||0)*0.96;
  return (zSexo(sexo).indexOf("inteir")>=0?-0.12:0)+(1.33+0.0036*p);
}
function zPesoFinal(pi: number, sexo: any, acab: number){
  const p=(pi||0)*0.96, fs=zFS(pi,sexo), k=((acab||27)/100)/0.28;
  const s=zSexo(sexo);
  if(s.indexOf("femea")>=0||s.indexOf("fêmea")>=0)
    return (551.5-0.2482*p+0.00119*p*p-39.84*fs)*k;
  return (509.6+0.4697*p-46.54*fs)*k;
}
function zConsEMpct(idade: number, sexo: any, raca: any){
  const s=zSexo(sexo), r=String(raca||"").toLowerCase();
  if((idade||0)<18){
    if(s.indexOf("femea")>=0||s.indexOf("fêmea")>=0)return 0.0707;
    if(r.indexOf("nelor")>=0)return 0.0608;
    return 0.0635;
  }
  if(s.indexOf("inteir")>=0)return 0.0598;
  if(r.indexOf("cruz")>=0)return 0.0562;
  return 0.0431;
}
function zGMD(R: number, idade: number, sexo: any, raca: any){
  const s=zSexo(sexo), r=String(raca||"").toLowerCase(), x=R||0;
  let g;
  if((idade||0)<18){
    if(s.indexOf("femea")>=0||s.indexOf("fêmea")>=0) g=-57.238*x*x+34.414*x-0.7883;
    else if(r.indexOf("nelor")>=0)                   g= 154.48*x*x+13.435*x-0.0094;
    else                                             g=-283.8*x*x+67.536*x-1.7211;
  }else{
    if(s.indexOf("inteir")>=0)      g=-79.95*x*x+41.34*x-0.762;
    else if(r.indexOf("cruz")>=0)   g=-90.5*x*x+43.98*x-0.832;
    else                            g=-0.11*x*x+34.35*x-0.7;
  }
  return g>0?g:0;
}

Deno.serve(async (req) => {
  try {
    // --- porteiro: ?t= tem de bater com o secret CAL_TOKEN ---
    const tokenEsperado = Deno.env.get("CAL_TOKEN") || "";
    const t = new URL(req.url).searchParams.get("t") || "";
    if (!tokenEsperado || t !== tokenEsperado) {
      return new Response("nao autorizado", { status: 401 });
    }

    const url = Deno.env.get("SUPABASE_URL")!;
    const srk = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const hdr = { apikey: srk, Authorization: `Bearer ${srk}` };

    const ler = async (tabela: string, filtro = "") => {
      const r = await fetch(`${url}/rest/v1/${tabela}?select=id,dados${filtro}`, { headers: hdr });
      if (!r.ok) throw new Error(`${tabela}: ${r.status}`);
      return await r.json();
    };
    const chave = async (nome: string, padrao: any) => {
      const rows = await ler("dados_v2", `&id=eq.${encodeURIComponent("EMPRESA::" + nome)}`);
      if (!rows.length) return padrao;
      try { return JSON.parse(rows[0].dados); } catch { return padrao; }
    };

    const lotesRows = await ler("lotes_v2");
    const lotes = lotesRows
      .map((r: any) => { try { return JSON.parse(r.dados); } catch { return null; } })
      .filter((l: any) => l && l.dtE && (l.qtd || 0) > 0 && !l.vendido && l.tipo === "Confinamento");

    const cfgBruta = await chave("caiman_prog_trato", {});
    const cfg: Cfg = {
      fases: { ...CFG_PADRAO.fases, ...(cfgBruta.fases || {}) },
      dietaFase: { ...CFG_PADRAO.dietaFase, ...(cfgBruta.dietaFase || {}) },
    };
    const dietas = await chave("caiman_dietas", []);
    const nomeDieta = (faseKey: string) => {
      const id = cfg.dietaFase?.[faseKey];
      if (!id) return null;
      const d = dietas.find((x: any) => String(x.id) === String(id));
      return d ? String(d.nome || d.id) : null;
    };

    // --- montar os eventos: um por TROCA DE DIETA de cada lote ---
    // Semi-Confinamento nao entra: nao passa por fases, a dieta e fixa no lote.
    const L: string[] = [];
    const carimbo = new Date().toISOString().replace(/[-:]/g, "").split(".")[0] + "Z";
    let n = 0;

    for (const l of lotes) {
      const pasto = String(l.pasto || l.id || "?");
      // dia em que cada fase COMECA, contado da entrada
      const seg = l.segurarFaseAte, segDesde = l.segurarFaseDesde;
      const nat = {
        ad1: l.dtE,
        ad2: addDias(l.dtE, cfg.fases.ad1),
        ad3: addDias(l.dtE, cfg.fases.ad1 + cfg.fases.ad2),
        cre: addDias(l.dtE, cfg.fases.ad1 + cfg.fases.ad2 + cfg.fases.ad3),
        fin: addDias(l.dtE, cfg.fases.ad1 + cfg.fases.ad2 + cfg.fases.ad3 + cfg.fases.cre),
      };
      const inicios: { key: string; data: string; natural: string }[] =
        (["ad1","ad2","ad3","cre","fin"] as const).map((k) => ({
          key: k,
          natural: (nat as any)[k],
          data: k === "ad1" ? l.dtE : inicioEfetivo((nat as any)[k], seg, segDesde),
        }));

      // MESMA regra do calendario do app (renderCalendarioTrato): o dia da entrada
      // nao e troca, e inicio; fase de duracao zero nao gera evento; e duas fases
      // na mesma dieta tambem nao. Assim agenda e app mostram exatamente os
      // mesmos dias.
      // ---- ponto de abate: peso final previsto e data provavel ----
      try{
        const pesoIni = l.pesoE || l.peso || 0;
        if (pesoIni > 0) {
          const sexo = l.sexo || "Machos", raca = l.raca || "", idade = l.idade || 0;
          const acab = l.acabamento || 27;
          const rend = (zSexo(sexo).indexOf("femea") >= 0) ? 0.52 : 0.565;
          /* Prioridade para o que o APP gravou no lote (campos pesoAbate/dtAbate):
             assim agenda e aplicativo mostram exatamente o mesmo numero, mesmo que
             o usuario tenha ajustado algo na tela. So recalcula se faltar. */
          let pesoFinal = parseFloat(l.pesoAbate) || 0;
          let dataAbate = l.dtAbate || "";
          let gmd = parseFloat(l.gmdAbate) || 0;
          let arrobas = parseFloat(l.arrobasAbate) || 0;
          if (!(pesoFinal > 0) || !dataAbate) {
            pesoFinal = zPesoFinal(pesoIni, sexo, acab);
            gmd = zGMD(zConsEMpct(idade, sexo, raca), idade, sexo, raca);
            if (gmd > 0 && pesoFinal > pesoIni) dataAbate = addDias(l.dtE, Math.round((pesoFinal - pesoIni) / gmd));
          }
          if (!(arrobas > 0)) arrobas = (pesoFinal * rend) / 15;
          if (gmd > 0 && pesoFinal > pesoIni && dataAbate) {
            const periodo = diasEntre(l.dtE, dataAbate);
            n++;
            const tituloA = `Curral ${pasto} - PONTO DE ABATE (${Math.round(pesoFinal)} kg)`;
            const corpoA =
              `Peso final previsto: ${Math.round(pesoFinal)} kg\n` +
              `Entrada ${fmtBR(l.dtE)} com ${Math.round(pesoIni)} kg - ${periodo} dias de cocho.\n` +
              `GMD estimado: ${gmd.toFixed(3).replace(".", ",")} kg/dia\n` +
              `Peso morto: ${arrobas.toFixed(2).replace(".", ",")} @/cab (rendimento ${(rend*100).toFixed(1).replace(".", ",")}%)\n` +
              `Lote de ${l.qtd || 0} cabecas - total ${(arrobas*(l.qtd||0)).toFixed(0)} @\n\n` +
              `Projecao pelo modelo do zootecnista. Confirmar com pesagem antes de fechar a escala.\n` +
              `Grupo Caiman Agro - Projeto Confinamento - Fazenda Pinheiral`;
            L.push("BEGIN:VEVENT");
            L.push(dobrar(`UID:caiman-abate-${l.id}@caimanagro`));
            L.push(`DTSTAMP:${carimbo}`);
            L.push(`DTSTART;VALUE=DATE:${soData(dataAbate)}`);
            L.push(`DTEND;VALUE=DATE:${soData(addDias(dataAbate, 1))}`);
            L.push(dobrar(`SUMMARY:${esc(tituloA)}`));
            L.push(dobrar(`DESCRIPTION:${esc(corpoA)}`));
            L.push(dobrar(`LOCATION:${esc(String(l.fazenda || "Fazenda Pinheiral"))}`));
            L.push("TRANSP:TRANSPARENT");
            // dois lembretes: 7 dias antes (fechar escala) e na vespera
            L.push("BEGIN:VALARM"); L.push("TRIGGER:-P7D"); L.push("ACTION:DISPLAY");
            L.push(dobrar(`DESCRIPTION:${esc("Em 7 dias: " + tituloA)}`)); L.push("END:VALARM");
            L.push("BEGIN:VALARM"); L.push("TRIGGER:-PT30H"); L.push("ACTION:DISPLAY");
            L.push(dobrar(`DESCRIPTION:${esc("Amanha: " + tituloA)}`)); L.push("END:VALARM");
            L.push("END:VEVENT");
          }
        }
      }catch(e){ console.error("abate", l.id, e); }

      let anterior: string | null = nomeDieta("ad1");
      let primeiro = true;
      for (const f of inicios) {
        const dur = f.key === "fin" ? 999 : (cfg.fases as any)[f.key];
        if (primeiro) { primeiro = false; continue; }   // pula a ad1 (entrada)
        if (dur === 0) continue;
        const dieta = nomeDieta(f.key);
        if (!dieta) continue;
        if (dieta === anterior) continue;
        n++;
        const titulo = `Curral ${pasto} - trocar para ${dieta}`;
        const corpo =
          `${ROTULO[f.key] || f.key} comeca em ${fmtBR(f.data)}.\n` +
          (f.data !== f.natural
            ? `Adiada por "segurar fase ate ${fmtBR(String(seg).slice(0,10))}" - seria ${fmtBR(f.natural)}.\n`
            : "") +
          `Lote: ${l.qtd || 0} cabecas - entrada ${fmtBR(l.dtE)}.\n` +
          `Programar a troca da dieta no Saicon.\n` +
          (anterior ? `Dieta anterior: ${anterior}.\n` : "") +
          `Grupo Caiman Agro - Projeto Confinamento - Fazenda Pinheiral`;

        L.push("BEGIN:VEVENT");
        L.push(dobrar(`UID:caiman-${l.id}-${f.key}@caimanagro`));
        L.push(`DTSTAMP:${carimbo}`);
        L.push(`DTSTART;VALUE=DATE:${soData(f.data)}`);
        L.push(`DTEND;VALUE=DATE:${soData(addDias(f.data, 1))}`);
        L.push(dobrar(`SUMMARY:${esc(titulo)}`));
        L.push(dobrar(`DESCRIPTION:${esc(corpo)}`));
        L.push(dobrar(`LOCATION:${esc(String(l.fazenda || "Fazenda Pinheiral"))}`));
        L.push("TRANSP:TRANSPARENT");
        // lembrete na vespera as 18h (1 dia - 6h antes do inicio do dia)
        L.push("BEGIN:VALARM");
        L.push("TRIGGER:-PT30H");
        L.push("ACTION:DISPLAY");
        L.push(dobrar(`DESCRIPTION:${esc("Amanha: " + titulo)}`));
        L.push("END:VALARM");
        L.push("END:VEVENT");
        anterior = dieta;
      }
    }

    const ics = [
      "BEGIN:VCALENDAR",
      "VERSION:2.0",
      "PRODID:-//Grupo Caiman Agro//Calendario de dietas//PT-BR",
      "CALSCALE:GREGORIAN",
      "METHOD:PUBLISH",
      "X-WR-CALNAME:Caiman Agro - Dietas e abate",
      `X-WR-TIMEZONE:${TZ}`,
      "X-WR-CALDESC:Trocas de dieta e ponto de abate por curral - gerado pelo aplicativo de gestao",
      "REFRESH-INTERVAL;VALUE=DURATION:PT6H",
      "X-PUBLISHED-TTL:PT6H",
      ...L,
      "END:VCALENDAR",
    ].join("\r\n") + "\r\n";

    return new Response(ics, {
      headers: {
        "Content-Type": "text/calendar; charset=utf-8",
        "Content-Disposition": 'inline; filename="caiman-dietas.ics"',
        "Cache-Control": "public, max-age=1800",
        "X-Eventos": String(n),
      },
    });
  } catch (e) {
    return new Response("erro: " + (e as Error).message, { status: 500 });
  }
});
