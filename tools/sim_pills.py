# -*- coding: utf-8 -*-
"""Simula el parseo de pills del provider sobre el HTML vivo (semantica jsoup)."""
import re
import urllib.request
from html.parser import HTMLParser

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

class Pills(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_span = False
        self.classes = ""
        self.buf = []
        self.pills = []
    def handle_starttag(self, tag, attrs):
        if tag == "span":
            d = dict(attrs)
            self.classes = d.get("class", "")
            self.in_span = set("text-xs font-black uppercase tracking-widest".split()) <= set(self.classes.split())
            self.buf = []
    def handle_endtag(self, tag):
        if tag == "span" and self.in_span:
            self.pills.append("".join(self.buf).strip())
            self.in_span = False
    def handle_data(self, data):
        if self.in_span:
            self.buf.append(data)

for slug in ["el-inmortal-renegado", "demons-ascension"]:
    req = urllib.request.Request(f"https://donghualife.com/series/{slug}", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        p = r.read().decode("utf-8", "replace")
    hp = Pills()
    hp.feed(p)
    print(slug, "pills:", hp.pills)
    # ahora la logica when del provider
    statusText = ""
    for text in hp.pills:
        if not text:
            continue
        if ("En Emisión" in text or "En Emision" in text or "Emisión" in text or
                "Emision" in text or "Pausa" in text or "Ongoing" in text):
            statusText = text
            break
        if "Finalizado" in text or "Completed" in text:
            statusText = text
            break
    print(slug, "-> statusText:", repr(statusText))
