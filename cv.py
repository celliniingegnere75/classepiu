#!/usr/bin/env python3
"""
ClasseViva -> postagenerale
Gira sul telefono (Termux). Legge la bacheca ClasseViva, scarica le circolari nuove
con gli allegati e le invia al progetto Apps Script, che le manda per mail.

Comandi:
  python cv.py test      prova l'accesso e mostra le ultime circolari (non invia nulla)
  python cv.py run       controlla e invia le circolari nuove (usato dalla pianificazione)
  python cv.py invio     imposta indirizzo e codice del ricevitore Apps Script
  python cv.py credenziali  reimposta utente e password ClasseViva
"""
import base64
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request

DIR = os.path.expanduser("~/.classeviva")
CONF = os.path.join(DIR, "config.json")
STATO = os.path.join(DIR, "viste.json")
BASE = "https://web.spaggiari.eu/rest/v1"
HEADERS = {
    "User-Agent": "CVVS/std/4.2.3 Android/12",
    "Z-Dev-Apikey": "Tg1NWEwNGIgIC0K",
    "Content-Type": "application/json",
}


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M") + "  " + msg, flush=True)


def carica(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def salva(path, data):
    os.makedirs(DIR, exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f)
    os.chmod(path, 0o600)


def http(method, url, headers, body=None, binary=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, raw if binary else raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


# ---------------- ClasseViva ----------------

def login(conf):
    code, body = http("POST", BASE + "/auth/login", HEADERS,
                      {"ident": None, "pass": conf["pass"], "uid": conf["user"]})
    if code != 200:
        raise SystemExit("Login non riuscito (HTTP %s): %s" % (code, body[:300]))
    j = json.loads(body)
    if "choices" in j and not j.get("token"):
        log("Profili disponibili: " + ", ".join(c.get("ident", "") for c in j["choices"]))
        code, body = http("POST", BASE + "/auth/login", HEADERS,
                          {"ident": j["choices"][0]["ident"], "pass": conf["pass"], "uid": conf["user"]})
        if code != 200:
            raise SystemExit("Login profilo non riuscito (HTTP %s): %s" % (code, body[:300]))
        j = json.loads(body)
    ident = j["ident"]
    h = dict(HEADERS)
    h["Z-Auth-Token"] = j["token"]
    return {"h": h, "ident": ident, "id": "".join(c for c in ident if c.isdigit())}


def bacheca(s):
    errori = []
    for percorso in ("students", "teachers"):
        code, body = http("GET", "%s/%s/%s/noticeboard" % (BASE, percorso, s["id"]), s["h"])
        if code == 200:
            s["percorso"] = percorso
            return json.loads(body).get("items", [])
        errori.append("%s -> HTTP %s %s" % (percorso, code, body[:150]))
    raise SystemExit("Bacheca non leggibile. " + " | ".join(errori))


def allegati(s, it):
    out = []
    for i, a in enumerate(it.get("attachments") or []):
        num = a.get("attachNum") or (i + 1)
        url = "%s/%s/%s/noticeboard/attach/%s/%s/%s" % (BASE, s["percorso"], s["id"], it["evtCode"], it["pubId"], num)
        code, data = http("GET", url, s["h"], binary=True)
        if code == 200 and isinstance(data, bytes):
            out.append({"nome": a.get("fileName") or "allegato_%d.pdf" % (i + 1),
                        "base64": base64.b64encode(data).decode()})
        else:
            log("Allegato non scaricato (%s): %s" % (code, a.get("fileName", "")))
    return out


def segna_letta(s, it):
    url = "%s/%s/%s/noticeboard/read/%s/%s/101" % (BASE, s["percorso"], s["id"], it["evtCode"], it["pubId"])
    http("POST", url, s["h"], {})


# ---------------- Invio ----------------

def invia(conf, circolare):
    req = urllib.request.Request(
        conf["endpoint"],
        data=json.dumps(dict(circolare, token=conf["token"])).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:  # Apps Script risponde dopo un redirect
        risposta = r.read().decode("utf-8", "replace")
    if '"ok":true' not in risposta.replace(" ", ""):
        raise RuntimeError("Ricevitore: " + risposta[:200])


# ---------------- Comandi ----------------

def chiedi_credenziali(conf):
    print("Credenziali ClasseViva (restano solo su questo telefono)")
    conf["user"] = input("Utente (codice o email): ").strip()
    conf["pass"] = getpass.getpass("Password (non si vede mentre scrivi): ")
    salva(CONF, conf)
    print("Salvate.")


def cmd_test(conf):
    s = login(conf)
    log("Accesso riuscito. Profilo: %s" % s["ident"][0])
    items = bacheca(s)
    log("Bacheca letta (percorso %s). Circolari: %d" % (s["percorso"], len(items)))
    for it in items[:5]:
        print("  - %s | %s | allegati: %d" % (it.get("cntValidFrom", ""), it.get("cntTitle", ""),
                                              len(it.get("attachments") or [])))


def cmd_run(conf):
    if not conf.get("endpoint"):
        raise SystemExit("Manca il ricevitore: esegui prima  python cv.py invio")
    viste = carica(STATO, None)
    primo = viste is None
    viste = viste or {}
    s = login(conf)
    items = bacheca(s)
    nuove = 0
    for it in items:
        chiave = "%s_%s" % (it.get("evtCode"), it.get("pubId"))
        if chiave in viste:
            continue
        if primo:
            viste[chiave] = 1
            continue
        segna_letta(s, it)
        circ = {
            "titolo": it.get("cntTitle", "Circolare"),
            "data": it.get("cntValidFrom", ""),
            "categoria": it.get("cntCategory", ""),
            "testo": it.get("cntText", "") or "",
            "allegati": allegati(s, it),
        }
        invia(conf, circ)
        viste[chiave] = 1
        salva(STATO, viste)
        nuove += 1
        log("Inviata: " + circ["titolo"])
    salva(STATO, viste)
    log("Primo avvio: segnate come già viste %d circolari." % len(items) if primo
        else "Controllo fatto. Circolari nuove: %d" % nuove)


def main():
    conf = carica(CONF, {})
    cmd = sys.argv[1] if len(sys.argv) > 1 else "test"
    if cmd == "credenziali" or not conf.get("user"):
        chiedi_credenziali(conf)
        if cmd == "credenziali":
            return
    if cmd == "test":
        cmd_test(conf)
    elif cmd == "run":
        cmd_run(conf)
    elif cmd == "invio":
        conf["endpoint"] = input("Indirizzo del ricevitore (URL): ").strip()
        conf["token"] = input("Codice segreto: ").strip()
        salva(CONF, conf)
        print("Ricevitore salvato.")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
