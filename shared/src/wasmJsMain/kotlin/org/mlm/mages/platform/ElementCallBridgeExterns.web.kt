package org.mlm.mages.platform

// runs once on first access
@JsFun("""() => {
    if (!globalThis._ec) globalThis._ec = {
        iframe: null, listener: null, onMsg: null, container: null,
        isWidget: function(d) {
            return !!d && typeof d === 'object' && (
                (d.response && d.api === 'toWidget') ||
                (!d.response && d.api === 'fromWidget')
            );
        },
        applyState: function(min) {
            var c = globalThis._ec.container;
            if (!c) return;
            if (min) {
                c.style.cssText = 'position:fixed;width:220px;height:140px;top:120px;left:24px;z-index:999999;border-radius:16px;overflow:hidden;box-shadow:0 8px 24px rgba(0,0,0,0.35);background:#000';
            } else {
                c.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;width:100vw;height:100vh;z-index:999999;border-radius:0;overflow:visible;box-shadow:none;background:#000';
            }
        }
    };
}""")
private external fun ensureEcState()

@JsFun("""(containerId, widgetUrl, onMessage) => {
    globalThis._ec || (globalThis._ec = {iframe:null,listener:null,onMsg:null,container:null,isWidget:function(d){return !!d&&typeof d==='object'&&((d.response&&d.api==='toWidget')||(!d.response&&d.api==='fromWidget'))},applyState:function(m){var c=globalThis._ec.container;if(!c)return;c.style.cssText=m?'position:fixed;width:220px;height:140px;top:120px;left:24px;z-index:999999;border-radius:16px;overflow:hidden;box-shadow:0 8px 24px rgba(0,0,0,0.35);background:#000':'position:fixed;top:0;left:0;right:0;bottom:0;width:100vw;height:100vh;z-index:999999;border-radius:0;overflow:visible;box-shadow:none;background:#000'}});
    var ec = globalThis._ec;
    if (ec.iframe) {
        if (ec.listener) { window.removeEventListener('message', ec.listener); ec.listener = null; }
        if (ec.iframe.parentNode) ec.iframe.parentNode.removeChild(ec.iframe);
        if (ec.container && ec.container.parentNode) ec.container.parentNode.removeChild(ec.container);
        ec.iframe = null; ec.container = null; ec.onMsg = null;
    }
    ec.onMsg = onMessage;
    var container = document.getElementById(containerId);
    if (!container) {
        container = document.createElement('div');
        container.id = containerId;
        document.body.appendChild(container);
    }
    ec.container = container;
    ec.applyState(false);
    container.innerHTML = '';
    var iframe = document.createElement('iframe');
    iframe.src = widgetUrl;
    iframe.style.cssText = 'width:100%;height:100%;border:none;position:absolute;top:0;left:0';
    iframe.allow = 'camera; microphone; display-capture; fullscreen; geolocation';
    iframe.setAttribute('allow', 'camera; microphone; display-capture; fullscreen; geolocation');
    container.appendChild(iframe);
    ec.iframe = iframe;
    ec.listener = function(event) {
        if (ec.onMsg && ec.isWidget(event.data)) {
            try { ec.onMsg(typeof event.data === 'string' ? event.data : JSON.stringify(event.data)); } catch(_) {}
        }
    };
    window.addEventListener('message', ec.listener);
    return true;
}""")
external fun createElementCallIframe(
    containerId: String,
    widgetUrl: String,
    onMessage: (String) -> Unit
): Boolean

@JsFun("""(message) => {
    var ec = globalThis._ec; if (!ec) return false;
    var payload;
    try { payload = JSON.parse(message); } catch(_) { payload = message; }
    if (ec.iframe && ec.iframe.contentWindow) {
        try { ec.iframe.contentWindow.postMessage(payload, '*'); return true; } catch(_) {}
    }
    try { if (window.parent && window.parent !== window) { window.parent.postMessage(payload, '*'); return true; } } catch(_) {}
    return false;
}""")
external fun sendToElementCallIframe(message: String): Boolean

@JsFun("""(originalMessage) => {
    var ec = globalThis._ec; if (!ec) return false;
    try {
        var msg = JSON.parse(originalMessage);
        var response = { api: 'toWidget', widgetId: msg.widgetId || '', requestId: msg.requestId || '', action: msg.action || '', response: {} };
        if (ec.iframe && ec.iframe.contentWindow) { ec.iframe.contentWindow.postMessage(response, '*'); return true; }
    } catch(_) {}
    return false;
}""")
external fun sendElementActionResponse(originalMessage: String): Boolean

@JsFun("""() => {
    var ec = globalThis._ec; if (!ec) return;
    if (ec.listener) { window.removeEventListener('message', ec.listener); ec.listener = null; }
    if (ec.iframe) { if (ec.iframe.parentNode) ec.iframe.parentNode.removeChild(ec.iframe); ec.iframe = null; }
    if (ec.container) { if (ec.container.parentNode) ec.container.parentNode.removeChild(ec.container); ec.container = null; }
    ec.onMsg = null;
}""")
external fun removeElementCallIframe()

@JsFun("""(minimized) => {
    var ec = globalThis._ec; if (ec && ec.applyState) ec.applyState(minimized);
}""")
external fun setElementCallMinimized(minimized: Boolean)