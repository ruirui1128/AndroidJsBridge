package com.smallbuer.jsbridge.core;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.util.Map;
import java.util.Set;

/**
 * Created on 2019/7/10.
 * Author: bigwang
 * Description:
 */
public class BridgeJavascritInterface extends BaseJavascriptInterface {

    private IWebView mWebView;
    private BridgeTiny mBridge;

    public BridgeJavascritInterface(Map<String, OnBridgeCallback> callbacks, BridgeTiny bridge, IWebView webView) {
        super(callbacks);
        this.mWebView = webView;
        this.mBridge = bridge;
    }

    @Override
    public String send(String data) {
        return "it is default response";
    }

    @JavascriptInterface
    public void handler(final String handlerName, final String data, final String callbackId) {
        if (TextUtils.isEmpty(handlerName)) {
            return;
        }
        //change to main thread
        mMainHandler.post(() -> {
            //higher priority LocalMessageHandlers
            if (mWebView.getLocalMessageHandlers().containsKey(handlerName)) {
                BridgeHandler bridgeHandler = mWebView.getLocalMessageHandlers().get(handlerName);

                Set<String> handlerLogNames = mWebView.getHandlerLogNames();
                if (handlerLogNames.contains(handlerName)) {
                    bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, ""));
                } else {
                    bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, handlerName));
                }
                return;
            }

            if (mBridge.getMessageHandlers().containsKey(handlerName)) {
                BridgeHandler bridgeHandler = mBridge.getMessageHandlers().get(handlerName);
                bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, handlerName));
            }
        });

    }

    public class CallBack extends CallBackFunction {
        private String callbackId;

        public CallBack(String callbackId, String handlerName) {
            this.callbackId = callbackId;
            this.handlerName = handlerName;
        }

        @Override
        public void onCallBack(String data) {
            mBridge.sendResponse(data, callbackId);
        }
    }

}
