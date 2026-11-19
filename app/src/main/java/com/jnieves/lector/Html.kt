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
@media (prefers-color-scheme: dark){
 body{background:#161616;color:#e6e6e6}
 body.pdf{background:#2b2b2b}
 body.img{background:#000}
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

    fun page(body: String, script: String, cls: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
            "minimum-scale=1, maximum-scale=8, user-scalable=yes\">" +
            "<style>" + CSS + "</style></head><body class=\"" + cls + "\">" +
            body + "<script>" + script + "</script></body></html>"

    fun message(html: String): String = page("<div class=\"msg\">$html</div>", "", "msg")
}
