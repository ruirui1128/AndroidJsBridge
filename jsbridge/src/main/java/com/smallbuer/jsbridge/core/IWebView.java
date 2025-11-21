package com.smallbuer.jsbridge.core;

import android.content.Context;

import java.util.Map;
import java.util.Set;

/**
 * Created on 2019/12/10.
 * Author: smallbuer
 * abstract WebView common interface
 */
public interface IWebView {

    void loadUrl(String url);

    void addHandlerLocal(String handlerName, BridgeHandler bridgeHandler);

    Map<String, BridgeHandler> getLocalMessageHandlers();

    Set<String> getHandlerLogNames();

    void addJavascriptInterface(Object obj, String interfaceName);

    void evaluateJavascript(String var1, Object object);

    void callHandler(String handlerName, Object data, OnBridgeCallback responseCallback);

    void removeJavascriptInterface(String cmd);


    Context getContext();

}
