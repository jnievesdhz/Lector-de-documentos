package com.jnieves.lector

/**
 * Plantillas HTML. Todo se muestra en un WebView, que ya trae
 * zoom con dos dedos, desplazamiento suave y búsqueda de texto.
 */
object Html {

    /**
     * Fuentes libres con las mismas medidas que las de Microsoft (las mismas que usa LibreOffice).
     * Así el texto ocupa el mismo espacio que en PowerPoint o Word y no se monta.
     */
    const val FONT_CSS = """
@font-face{font-family:'Calibri';src:url('file:///android_asset/fonts/Carlito-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Calibri';src:url('file:///android_asset/fonts/Carlito-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Calibri';src:url('file:///android_asset/fonts/Carlito-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Calibri';src:url('file:///android_asset/fonts/Carlito-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Calibri Light';src:url('file:///android_asset/fonts/Carlito-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Calibri Light';src:url('file:///android_asset/fonts/Carlito-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Calibri Light';src:url('file:///android_asset/fonts/Carlito-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Calibri Light';src:url('file:///android_asset/fonts/Carlito-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Carlito';src:url('file:///android_asset/fonts/Carlito-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Carlito';src:url('file:///android_asset/fonts/Carlito-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Carlito';src:url('file:///android_asset/fonts/Carlito-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Carlito';src:url('file:///android_asset/fonts/Carlito-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Cambria';src:url('file:///android_asset/fonts/Caladea-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Cambria';src:url('file:///android_asset/fonts/Caladea-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Cambria';src:url('file:///android_asset/fonts/Caladea-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Cambria';src:url('file:///android_asset/fonts/Caladea-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Caladea';src:url('file:///android_asset/fonts/Caladea-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Caladea';src:url('file:///android_asset/fonts/Caladea-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Caladea';src:url('file:///android_asset/fonts/Caladea-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Caladea';src:url('file:///android_asset/fonts/Caladea-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Arial';src:url('file:///android_asset/fonts/LiberationSans-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Arial';src:url('file:///android_asset/fonts/LiberationSans-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Arial';src:url('file:///android_asset/fonts/LiberationSans-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Arial';src:url('file:///android_asset/fonts/LiberationSans-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Helvetica';src:url('file:///android_asset/fonts/LiberationSans-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Helvetica';src:url('file:///android_asset/fonts/LiberationSans-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Helvetica';src:url('file:///android_asset/fonts/LiberationSans-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Helvetica';src:url('file:///android_asset/fonts/LiberationSans-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Liberation Sans';src:url('file:///android_asset/fonts/LiberationSans-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Liberation Sans';src:url('file:///android_asset/fonts/LiberationSans-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Liberation Sans';src:url('file:///android_asset/fonts/LiberationSans-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Liberation Sans';src:url('file:///android_asset/fonts/LiberationSans-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Times New Roman';src:url('file:///android_asset/fonts/LiberationSerif-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Times New Roman';src:url('file:///android_asset/fonts/LiberationSerif-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Times New Roman';src:url('file:///android_asset/fonts/LiberationSerif-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Times New Roman';src:url('file:///android_asset/fonts/LiberationSerif-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Times';src:url('file:///android_asset/fonts/LiberationSerif-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Times';src:url('file:///android_asset/fonts/LiberationSerif-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Times';src:url('file:///android_asset/fonts/LiberationSerif-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Times';src:url('file:///android_asset/fonts/LiberationSerif-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Liberation Serif';src:url('file:///android_asset/fonts/LiberationSerif-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Liberation Serif';src:url('file:///android_asset/fonts/LiberationSerif-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Liberation Serif';src:url('file:///android_asset/fonts/LiberationSerif-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Liberation Serif';src:url('file:///android_asset/fonts/LiberationSerif-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Courier New';src:url('file:///android_asset/fonts/LiberationMono-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Courier New';src:url('file:///android_asset/fonts/LiberationMono-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Courier New';src:url('file:///android_asset/fonts/LiberationMono-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Courier New';src:url('file:///android_asset/fonts/LiberationMono-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Courier';src:url('file:///android_asset/fonts/LiberationMono-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Courier';src:url('file:///android_asset/fonts/LiberationMono-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Courier';src:url('file:///android_asset/fonts/LiberationMono-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Courier';src:url('file:///android_asset/fonts/LiberationMono-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Liberation Mono';src:url('file:///android_asset/fonts/LiberationMono-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Liberation Mono';src:url('file:///android_asset/fonts/LiberationMono-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Liberation Mono';src:url('file:///android_asset/fonts/LiberationMono-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Liberation Mono';src:url('file:///android_asset/fonts/LiberationMono-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Arial Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Arial Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Arial Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Arial Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-BoldItalic.ttf');font-weight:bold;font-style:italic}
@font-face{font-family:'Liberation Sans Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Regular.ttf');font-weight:normal;font-style:normal}
@font-face{font-family:'Liberation Sans Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Bold.ttf');font-weight:bold;font-style:normal}
@font-face{font-family:'Liberation Sans Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-Italic.ttf');font-weight:normal;font-style:italic}
@font-face{font-family:'Liberation Sans Narrow';src:url('file:///android_asset/fonts/LiberationSansNarrow-BoldItalic.ttf');font-weight:bold;font-style:italic}
"""

    private const val CSS = """
html,body{margin:0;padding:0}
body{background:#f4f4f4;color:#161616;font-family:sans-serif;-webkit-text-size-adjust:100%}
body.pdf{background:#0b0b0e;padding:6px 0}
body.img{background:#0b0b0e}
/* Espacio para las barras de arriba y de abajo del visor */
.spt{height:60px}.spb{height:84px}
body.img .spt,body.img .spb,body.msg .spt,body.msg .spb{display:none}
.pg{width:100%;background:#fff;margin:0 0 6px 0;box-shadow:0 1px 3px rgba(0,0,0,.35)}
.pg img{width:100%;height:100%;display:block}
.pg img:not([src]){visibility:hidden}
.imgwrap{min-height:100vh;display:flex;align-items:center;justify-content:center}
.imgwrap img{max-width:100%;height:auto;display:block}
pre{white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:14px;line-height:1.45;padding:14px;margin:0}
body.msg{background:#0f0f12;color:#e4e4ea}
.msg{padding:120px 24px 48px;text-align:center;font-size:17px;line-height:1.5}
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
table.s{border-collapse:separate;border-spacing:0;font-size:13px;font-family:'Calibri',sans-serif}
table.s td{border-right:1px solid #dadada;border-bottom:1px solid #dadada;padding:3px 6px;white-space:pre;background:#fff;color:#111;vertical-align:bottom}
table.s td.n{text-align:right}
table.s td.b{text-align:center}
table.s td.w{white-space:pre-wrap;max-width:320px}
table.s th{background:#f1f1f1;color:#5a5a5a;font-weight:normal;border-right:1px solid #cfcfcf;border-bottom:1px solid #cfcfcf;padding:3px 6px;position:sticky;top:var(--tb,0px);z-index:2;transition:top .2s}
table.s th.rh{left:0;z-index:1;text-align:right;min-width:28px}
table.s th.corner{left:0;z-index:3}
.sh{display:none}.sh.on{display:block}
.tabs{position:fixed;left:0;right:0;bottom:var(--bb,0px);transition:bottom .2s;display:flex;overflow-x:auto;background:#e9e9e9;border-top:1px solid #bdbdbd;z-index:5}
.tabs button{flex:0 0 auto;border:0;background:none;padding:13px 16px;font-size:15px;color:#333;white-space:nowrap}
.tabs button.on{background:#fff;color:#107c41;font-weight:bold;box-shadow:inset 0 -3px 0 #107c41}
.note{padding:10px 12px;color:#666;font-size:14px}

body.pptx{background:#0b0b0e;padding:8px 6px}
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
.sn{color:#9e9ea8;font-size:13px;text-align:center;margin:4px 0 14px}
.snotes{background:#fff8dc;color:#333;margin:-6px 4px 18px;padding:8px 10px;border-radius:6px;font-size:14px;white-space:pre-wrap;line-height:1.4}

/* Barras del visor visibles: la hoja de cálculo deja sitio arriba y abajo */
body.bars{--tb:60px;--bb:84px}

/*
 Modo oscuro de Word, Excel y texto. Se invierten los colores del documento
 (así también se aclaran los textos negros con color propio) y se vuelven a
 invertir las imágenes y los gráficos para que queden como en el original.
 #f0f0ed invertido da el grafito #0f0f12 de la app.
*/
body.dark{background:#0f0f12;color:#e4e4ea}
body.docx.dark .doc{background:#f0f0ed;box-shadow:none;filter:invert(1) hue-rotate(180deg)}
body.dark .doc img,body.dark .doc .chart{filter:invert(1) hue-rotate(180deg)}
body.sheet.dark .sh{filter:invert(1) hue-rotate(180deg)}
body.sheet.dark table.s td{background:#f0f0ed}
body.sheet.dark table.s th{background:#e7e7e3}
body.sheet.dark .sh img,body.sheet.dark .sh svg{filter:invert(1) hue-rotate(180deg)}
body.sheet.dark .tabs{background:#18181c;border-top-color:#2a2a30}
body.sheet.dark .tabs button{color:#9e9ea8}
body.sheet.dark .tabs button.on{background:#26232f;color:#dcd3ff;box-shadow:inset 0 -3px 0 #c3b4ff}
body.dark .note{color:#9e9ea8}

/* Búsqueda: coincidencias en amarillo suave, la actual en amarillo fuerte */
mark.hl{background:rgba(255,200,87,.42);color:inherit;border-radius:2px;padding:0}
mark.hl.cur{background:#ffc857;color:#1a1206;box-shadow:0 0 0 2px #ffc857}
body.dark .doc mark.hl,body.sheet.dark .sh mark.hl{filter:invert(1) hue-rotate(180deg)}
.pg{position:relative}
.hlr{position:absolute;background:rgba(255,200,87,.38);border-radius:2px;pointer-events:none}
.hlr.cur{background:rgba(255,184,40,.6);box-shadow:0 0 0 2px #ffb828}

/* Miniaturas de diapositivas en el panel lateral */
body.thumbs{background:#18181c !important;padding:10px 8px !important}
body.thumbs .spt,body.thumbs .spb,body.thumbs .sn,body.thumbs .snotes,body.thumbs .note{display:none}
body.thumbs .sl{display:inline-block;width:calc(50% - 10px);margin:5px;vertical-align:top;box-shadow:none;outline:1px solid #2a2a30;border-radius:3px}
body.thumbs .sl.curthumb{outline:2px solid #c3b4ff;outline-offset:2px}
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
  if(document.fonts && document.fonts.ready){ document.fonts.ready.then(function(){ fitText(); }); }
  function fit(){
    var ss=document.querySelectorAll('.sl');
    for(var k=0;k<ss.length;k++){
      var s=ss[k], w=parseFloat(s.getAttribute('data-w'));
      s.firstElementChild.style.transform='scale('+(s.clientWidth/w)+')';
    }
  }
  window.addEventListener('resize',fit);
  fit();

  // Diapositiva actual (para el contador "3 / 42" y para saltar a una diapositiva)
  var ss=document.querySelectorAll('.sl');
  try{ Android.setPageCount(ss.length); }catch(e){}
  function topOf(el){ return el.getBoundingClientRect().top+window.scrollY; }
  var last=-1,tick=false;
  function up(){
    tick=false;
    var y=window.scrollY+window.innerHeight/2,a=0;
    for(var k=0;k<ss.length;k++){ if(topOf(ss[k])<=y) a=k; else break; }
    if(a!==last){ last=a; try{ Android.onPage(a+1); }catch(e){} }
  }
  window.addEventListener('scroll',function(){ if(!tick){ tick=true; requestAnimationFrame(up); } },{passive:true});
  up();
  window.goPage=function(n){ var s=ss[n-1]; if(s){ window.scrollTo(0,Math.max(0,topOf(s)-68)); } };
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
  window.goPage=function(n){
    var d=document.getElementById('p'+(n-1));
    if(d){ window.scrollTo(0,Math.max(0,d.getBoundingClientRect().top+window.scrollY-68)); }
  };
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

    /**
     * Barras del visor: se esconden al bajar leyendo y vuelven al tocar la pantalla
     * o al llegar arriba. También cambia el modo oscuro sin recargar.
     */
    private const val BARS_SCRIPT = """
(function(){
  var lastY=window.scrollY,acc=0;
  window.addEventListener('scroll',function(){
    var y=window.scrollY,d=y-lastY; lastY=y;
    if(y<8){ acc=0; try{ Android.onBars(1); }catch(e){} return; }
    if(d>0){ acc+=d; if(acc>24){ acc=0; try{ Android.onBars(0); }catch(e){} } } else { acc=0; }
  },{passive:true});
  document.addEventListener('click',function(e){
    var t=e.target;
    if(t&&t.closest&&t.closest('a,button,input,select,textarea,label')) return;
    try{ Android.onTap(); }catch(err){}
  });
})();
window.setDark=function(on){ document.body.classList.toggle('dark',!!on); };
window.setBars=function(on){ document.body.classList.toggle('bars',!!on); };
window.getFrac=function(){ var h=document.documentElement.scrollHeight-window.innerHeight; return h>0?window.scrollY/h:0; };
window.goFrac=function(f){ var h=document.documentElement.scrollHeight-window.innerHeight; window.scrollTo(0,Math.max(0,h*f)); };
"""

    /**
     * Búsqueda de texto. En Word, Excel, PowerPoint y texto recorre el documento,
     * sin importar tildes ni mayúsculas, y marca cada coincidencia.
     * En PDF la app manda los recuadros de cada coincidencia (setPdfHits).
     */
    private const val SEARCH_SCRIPT = """
(function(){
  var hits=[],cur=-1,marks=[];
  function norm(s){ return s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase(); }
  function report(){ try{ Android.onFound(hits.length,cur+1); }catch(e){} }
  function clear(){
    for(var k=0;k<marks.length;k++){
      var m=marks[k],p=m.parentNode; if(!p) continue;
      while(m.firstChild) p.insertBefore(m.firstChild,m);
      p.removeChild(m); p.normalize();
    }
    marks=[];
    var o=document.querySelectorAll('.hlr'); for(var k=0;k<o.length;k++) o[k].parentNode.removeChild(o[k]);
    hits=[]; cur=-1;
  }
  function show(i){
    if(!hits.length){ cur=-1; report(); return; }
    cur=((i%hits.length)+hits.length)%hits.length;
    var g=hits[cur];
    var sh=g[0].closest?g[0].closest('.sh'):null;
    if(sh&&!sh.classList.contains('on')&&window.showSheet){
      var all=document.querySelectorAll('.sh');
      for(var k=0;k<all.length;k++){ if(all[k]===sh){ showSheet(k); break; } }
    }
    var prev=document.querySelectorAll('.hl.cur,.hlr.cur'); for(var k=0;k<prev.length;k++) prev[k].classList.remove('cur');
    for(var k=0;k<g.length;k++) g[k].classList.add('cur');
    try{ g[0].scrollIntoView({block:'center',inline:'center'}); }catch(e){ g[0].scrollIntoView(); }
    report();
  }
  function firstVisible(){
    for(var k=0;k<hits.length;k++){ var r=hits[k][0].getBoundingClientRect(); if(r.height>0&&r.top>=64) return k; }
    return 0;
  }
  window.findText=function(q){
    clear();
    var nq=norm(q||'').trim();
    if(!nq){ report(); return; }
    var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
      var p=n.parentNode; if(!p) return 2;
      var t=p.nodeName; if(t==='SCRIPT'||t==='STYLE') return 2;
      if(p.closest&&p.closest('.tabs,.sn')) return 2;
      return 1;
    }});
    var nodes=[]; while(w.nextNode()) nodes.push(w.currentNode);
    for(var k=0;k<nodes.length&&hits.length<2000;k++){
      var node=nodes[k],s=node.nodeValue;
      if(!s||s.length<nq.length||norm(s).indexOf(nq)<0) continue;
      var ns='',map=[];
      for(var i=0;i<s.length;i++){ var c=norm(s.charAt(i)); for(var j=0;j<c.length;j++){ ns+=c.charAt(j); map.push(i); } }
      var found=[],from=0,idx;
      while((idx=ns.indexOf(nq,from))>=0){ found.push([map[idx],map[idx+nq.length-1]+1]); from=idx+nq.length; }
      var mine=[];
      for(var f=found.length-1;f>=0;f--){
        var st=found[f][0],en=found[f][1];
        var mid=node.splitText(st); mid.splitText(en-st);
        var m=document.createElement('mark'); m.className='hl';
        mid.parentNode.insertBefore(m,mid); m.appendChild(mid);
        marks.push(m); mine.push([m]);
      }
      for(var f=mine.length-1;f>=0;f--) hits.push(mine[f]);
    }
    if(hits.length) show(firstVisible()); else report();
  };
  // PDF: lista de [página, [[izq, arriba, ancho, alto], ...]] en fracciones de la página
  window.setPdfHits=function(list){
    clear();
    for(var k=0;k<list.length;k++){
      var pg=document.getElementById('p'+list[k][0]); if(!pg) continue;
      var g=[],rs=list[k][1];
      for(var j=0;j<rs.length;j++){
        var d=document.createElement('div'); d.className='hlr';
        d.style.left=(rs[j][0]*100)+'%'; d.style.top=(rs[j][1]*100)+'%';
        d.style.width=(rs[j][2]*100)+'%'; d.style.height=(rs[j][3]*100)+'%';
        pg.appendChild(d); g.push(d);
      }
      if(g.length) hits.push(g);
    }
    if(hits.length) show(firstVisible()); else report();
  };
  window.findStep=function(d){ if(hits.length) show(cur+d); else report(); };
  window.findClear=function(){ clear(); };

  // Índice de Word (títulos) y hojas de Excel, para el panel lateral
  window.getOutline=function(){
    var hs=document.querySelectorAll('.doc .h1,.doc .h2,.doc .h3'),out=[];
    for(var k=0;k<hs.length&&out.length<400;k++){
      var t=(hs[k].textContent||'').replace(/\s+/g,' ').trim(); if(!t) continue;
      hs[k].setAttribute('data-oi',k);
      var l=/\bh2\b/.test(hs[k].className)?2:(/\bh3\b/.test(hs[k].className)?3:1);
      out.push({i:k,l:l,t:t.substring(0,160)});
    }
    return JSON.stringify(out);
  };
  window.goOutline=function(i){
    var e=document.querySelector('[data-oi="'+i+'"]');
    if(e) window.scrollTo(0,Math.max(0,e.getBoundingClientRect().top+window.scrollY-72));
  };
  window.getSheets=function(){
    var b=document.querySelectorAll('.tabs button'),out=[],on=0;
    for(var k=0;k<b.length;k++){ out.push(b[k].textContent); if(b[k].className==='on') on=k; }
    return JSON.stringify({names:out,on:on});
  };

  // Modo miniaturas (el panel lateral carga la misma presentación en pequeño)
  window.enterThumbs=function(){
    document.body.classList.add('thumbs');
    var ss=document.querySelectorAll('.sl');
    for(var k=0;k<ss.length;k++){
      (function(n){ ss[n].addEventListener('click',function(ev){ ev.stopPropagation(); try{ Android.onThumb(n+1); }catch(e){} }); })(k);
    }
    window.dispatchEvent(new Event('resize'));
  };
  window.markThumb=function(n){
    var ss=document.querySelectorAll('.sl');
    for(var k=0;k<ss.length;k++) ss[k].classList.toggle('curthumb',k===n-1);
    var s=ss[n-1]; if(s) s.scrollIntoView({block:'center'});
  };
})();
"""

    fun page(body: String, script: String, cls: String, minScale: String = "1"): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
            "minimum-scale=" + minScale + ", maximum-scale=8, user-scalable=yes\">" +
            "<style>" + FONT_CSS + CSS + "</style></head><body class=\"" + cls + "\">" +
            "<div class=\"spt\"></div>" + body + "<div class=\"spb\"></div>" +
            "<script>" + SCROLL_SCRIPT + BARS_SCRIPT + SEARCH_SCRIPT + script + "</script></body></html>"

    fun message(html: String): String = page("<div class=\"msg\">$html</div>", "", "msg")
}
