package com.jnieves.lector

/**
 * Plantillas HTML. Todo se muestra en un WebView, que ya trae
 * zoom con dos dedos, desplazamiento suave y búsqueda de texto.
 */
object Html {

    private const val CSS = """
html,body{margin:0;padding:0}
body{background:#f4f4f4;color:#161616;font-family:sans-serif;-webkit-text-size-adjust:100%}
body.pdf{background:#d9d9d9;padding:6px 0}
body.img{background:#111}
.pg{width:100%;background:#fff;margin:0 0 6px 0;box-shadow:0 1px 3px rgba(0,0,0,.35)}
.pg img{width:100%;height:100%;display:block}
.pg img:not([src]){visibility:hidden}
.imgwrap{min-height:100vh;display:flex;align-items:center;justify-content:center}
.imgwrap img{max-width:100%;height:auto;display:block}
pre{white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:14px;line-height:1.45;padding:14px;margin:0}
.msg{padding:48px 24px;text-align:center;font-size:17px;line-height:1.5}
.msg small{opacity:.7}

body.docx{background:#e4e4e4;padding:8px}
.doc{background:#fff;color:#111;max-width:860px;margin:0 auto;padding:22px 18px 28px;box-shadow:0 1px 4px rgba(0,0,0,.3);line-height:1.38;overflow-wrap:break-word}
.doc .p{margin:0 0 .5em 0;white-space:pre-wrap;tab-size:4;min-height:1.1em}
.doc .h1,.doc .h2,.doc .h3,.doc .h4,.doc .h5,.doc .h6{font-weight:bold;margin:.8em 0 .45em;line-height:1.25}
.doc .h1{font-size:1.55em}.doc .h2{font-size:1.3em}.doc .h3{font-size:1.15em}
.doc .li{display:inline-block;min-width:1.5em;padding-right:.3em;text-indent:0}
.doc .tw{overflow-x:auto;margin:.4em 0 .8em}
.doc table.t{border-collapse:collapse;min-width:60%}
.doc table.t td{border:1px solid #9a9a9a;padding:4px 6px;vertical-align:top}
.doc table.t .p{margin:0 0 .2em 0}
.doc img{max-width:100%;height:auto;vertical-align:middle}
.doc .pb{display:block;border-top:1px dashed #b5b5b5;margin:16px -18px}
.doc .tb{display:block;border:1px solid #cfcfcf;border-radius:4px;padding:6px 8px;margin:6px 0;white-space:normal}
.doc a{color:#1155cc}
.doc sup.fn{color:#1155cc;font-size:.7em}
.doc .notes{margin-top:2em;border-top:1px solid #aaa;padding-top:.6em;font-size:.88em}
.doc .note{margin-bottom:.4em}
.doc .note>.p:first-of-type{display:inline}
.noimg{color:#888;font-style:italic}
.doc .chart{display:block;max-width:100%;margin:6px 0;white-space:normal}

body.sheet{background:#fff;color:#111;padding-bottom:56px}
table.s{border-collapse:separate;border-spacing:0;font-size:13px;font-family:sans-serif}
table.s td{border-right:1px solid #dadada;border-bottom:1px solid #dadada;padding:3px 6px;white-space:pre;background:#fff;color:#111;vertical-align:bottom}
table.s td.n{text-align:right}
table.s td.b{text-align:center}
table.s td.w{white-space:pre-wrap;max-width:320px}
table.s th{background:#f1f1f1;color:#5a5a5a;font-weight:normal;border-right:1px solid #cfcfcf;border-bottom:1px solid #cfcfcf;padding:3px 6px;position:sticky;top:0;z-index:2}
table.s th.rh{left:0;z-index:1;text-align:right;min-width:28px}
table.s th.corner{left:0;z-index:3}
.sh{display:none}.sh.on{display:block}
.tabs{position:fixed;left:0;right:0;bottom:0;display:flex;overflow-x:auto;background:#e9e9e9;border-top:1px solid #bdbdbd;z-index:5}
.tabs button{flex:0 0 auto;border:0;background:none;padding:13px 16px;font-size:15px;color:#333;white-space:nowrap}
.tabs button.on{background:#fff;color:#107c41;font-weight:bold;box-shadow:inset 0 -3px 0 #107c41}
.note{padding:10px 12px;color:#666;font-size:14px}

body.pptx{background:#303030;padding:8px 6px}
.sl{position:relative;width:100%;overflow:hidden;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.55)}
.si{position:absolute;left:0;top:0;transform-origin:0 0;overflow:hidden;font-family:sans-serif;color:#000;line-height:1.2}
.shp{position:absolute;box-sizing:border-box}
.shp img{position:absolute;display:block;max-width:none}
.txt{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column;box-sizing:border-box;overflow-wrap:break-word}
.pp{white-space:pre-wrap;margin:0}
.txt[data-nw] .pp{white-space:pre}
.cg{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible}
.bu{display:inline-block;text-indent:0}
.ph{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:#f2f2f2;border:2px dashed #bbb;color:#888;font-size:24px}
table.pt{border-collapse:collapse;table-layout:fixed;width:100%}
table.pt td{overflow:hidden}
.sl a{color:inherit}
.sn{color:#bdbdbd;font-size:13px;text-align:center;margin:4px 0 14px}
.snotes{background:#fff8dc;color:#333;margin:-6px 4px 18px;padding:8px 10px;border-radius:6px;font-size:14px;white-space:pre-wrap;line-height:1.4}

@media (prefers-color-scheme: dark){
 body{background:#161616;color:#e6e6e6}
 body.pdf{background:#2b2b2b}
 body.img{background:#000}
 body.docx{background:#2b2b2b}
 body.sheet{background:#fff;color:#111}
}
"""

    /** Ajusta cada diapositiva al ancho de la pantalla. */
    const val SLIDES_SCRIPT = """
(function(){
  // Encoge el texto que no cabe en su cuadro (PowerPoint lo hace solo; en el celular las fuentes cambian)
  function fitText(){
    var fs=document.querySelectorAll('.txt[data-fit]');
    for(var k=0;k<fs.length;k++){
      var t=fs[k], inner=t.firstElementChild;
      if(!inner) continue;
      inner.style.zoom='';
      var cs=getComputedStyle(t);
      var avail=t.clientHeight-parseFloat(cs.paddingTop)-parseFloat(cs.paddingBottom);
      var h=inner.offsetHeight;
      if(avail>0 && h>avail+1){ inner.style.zoom=Math.max(0.45,avail/h*0.98); }
    }
  }
  fitText();
  function fit(){
    var ss=document.querySelectorAll('.sl');
    for(var k=0;k<ss.length;k++){
      var s=ss[k], w=parseFloat(s.getAttribute('data-w'));
      s.firstElementChild.style.transform='scale('+(s.clientWidth/w)+')';
    }
  }
  window.addEventListener('resize',fit);
  fit();
})();
"""

    const val SHEET_SCRIPT = """
function showSheet(i){
  var s=document.querySelectorAll('.sh'),b=document.querySelectorAll('.tabs button');
  for(var k=0;k<s.length;k++){ s[k].className=(k===i)?'sh on':'sh'; }
  for(var k=0;k<b.length;k++){ b[k].className=(k===i)?'on':''; }
  window.scrollTo(0,0);
}
"""

    /**
     * Carga perezosa de páginas PDF: solo se piden a Android las páginas
     * cercanas a la pantalla. También informa en qué página va el usuario.
     */
    const val PDF_SCRIPT = """
(function(){
  var pages=document.querySelectorAll('.pg');
  var io=new IntersectionObserver(function(es){
    es.forEach(function(e){
      if(e.isIntersecting){
        var d=e.target;
        if(!d.dataset.r){ d.dataset.r='1'; Android.render(parseInt(d.dataset.i,10)); }
      }
    });
  },{rootMargin:'200% 0px'});
  for(var k=0;k<pages.length;k++){ io.observe(pages[k]); }

  window.pageReady=function(i,src){
    var d=document.getElementById('p'+i);
    if(d){ d.firstElementChild.src=src; }
  };
  window.pageFailed=function(i){
    var d=document.getElementById('p'+i);
    if(d){ d.dataset.r=''; }
  };

  var tops=[];
  function measure(){ tops=[]; for(var k=0;k<pages.length;k++){ tops.push(pages[k].offsetTop); } }
  function current(){
    var vv=window.visualViewport;
    var y=vv?(vv.pageTop+vv.height/2):(window.scrollY+window.innerHeight/2);
    var lo=0,hi=tops.length-1,ans=0;
    while(lo<=hi){ var mid=(lo+hi)>>1; if(tops[mid]<=y){ans=mid;lo=mid+1;}else{hi=mid-1;} }
    return ans;
  }
  var last=-1,ticking=false;
  function update(){ ticking=false; var c=current(); if(c!==last){ last=c; Android.onPage(c+1); } }
  function onScroll(){ if(!ticking){ ticking=true; requestAnimationFrame(update); } }
  window.addEventListener('scroll',onScroll,{passive:true});
  window.addEventListener('resize',function(){ measure(); onScroll(); });
  if(window.visualViewport){
    window.visualViewport.addEventListener('scroll',onScroll);
    window.visualViewport.addEventListener('resize',function(){ measure(); onScroll(); });
  }
  measure();
})();
"""

    /** Avisa a la app en qué parte del documento va el usuario. */
    private const val SCROLL_SCRIPT = """
(function(){
  var t=false;
  function r(){
    t=false;
    try{
      var h=document.documentElement.scrollHeight-window.innerHeight;
      Android.onScrollFrac(h>0?window.scrollY/h:0);
    }catch(e){}
  }
  window.addEventListener('scroll',function(){ if(!t){ t=true; requestAnimationFrame(r); } },{passive:true});
})();
"""

    fun page(body: String, script: String, cls: String, minScale: String = "1"): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
            "minimum-scale=" + minScale + ", maximum-scale=8, user-scalable=yes\">" +
            "<style>" + CSS + "</style></head><body class=\"" + cls + "\">" +
            body + "<script>" + SCROLL_SCRIPT + script + "</script></body></html>"

    fun message(html: String): String = page("<div class=\"msg\">$html</div>", "", "msg")
}
