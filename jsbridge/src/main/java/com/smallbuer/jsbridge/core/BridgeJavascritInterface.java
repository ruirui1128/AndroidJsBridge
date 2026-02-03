package com.smallbuer.jsbridge.core;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;

/**
 * Created on 2019/7/10.
 * Author: bigwang
 * Description:
 */
//public class BridgeJavascritInterface extends BaseJavascriptInterface {
//
//    private IWebView mWebView;
//    private BridgeTiny mBridge;
//
//    public BridgeJavascritInterface(Map<String, OnBridgeCallback> callbacks, BridgeTiny bridge, IWebView webView) {
//        super(callbacks);
//        this.mWebView = webView;
//        this.mBridge = bridge;
//    }
//
//    @Override
//    public String send(String data) {
//        return "it is default response";
//    }
//
//    @JavascriptInterface
//    public void handler(final String handlerName, final String data, final String callbackId) {
//        if (TextUtils.isEmpty(handlerName)) {
//            return;
//        }
//        //change to main thread
//        mMainHandler.post(() -> {
//            //higher priority LocalMessageHandlers
//            if (mWebView.getLocalMessageHandlers().containsKey(handlerName)) {
//                BridgeHandler bridgeHandler = mWebView.getLocalMessageHandlers().get(handlerName);
//
//                if (mWebView.getHandlerLogNames().contains(handlerName)) {
//                    bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, "", ""));
//                } else {
//                    bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, handlerName, data));
//                }
//                return;
//            }
//
//            if (mBridge.getMessageHandlers().containsKey(handlerName)) {
//                BridgeHandler bridgeHandler = mBridge.getMessageHandlers().get(handlerName);
//                bridgeHandler.handler(mWebView.getContext(), data, new CallBack(callbackId, handlerName, data));
//            }
//        });
//
//    }
//
//    public class CallBack extends CallBackFunction {
//        private String callbackId;
//
//        public CallBack(String callbackId, String handlerName, String params) {
//            this.callbackId = callbackId;
//            this.jsParams = params;
//            this.handlerName = handlerName;
//        }
//
//        @Override
//        public void onCallBack(String data) {
//            mBridge.sendResponse(data, callbackId);
//        }
//    }
//
//}


public class BridgeJavascritInterface extends BaseJavascriptInterface {

    private WeakReference<IWebView> webViewRef;
    private BridgeTiny mBridge;

    public BridgeJavascritInterface(Map<String, OnBridgeCallback> callbacks, BridgeTiny bridge, IWebView webView) {
        super(callbacks);
        this.webViewRef = new WeakReference<>(webView);
        this.mBridge = bridge;
    }

    @Override
    public String send(String data) {
        return "it is default response";
    }

    @JavascriptInterface
    public void handler(final String handlerName, final String data, final String callbackId) {
        if (TextUtils.isEmpty(handlerName)) return;

        mMainHandler.post(() -> {
            IWebView webView = webViewRef.get();
            if (webView == null || mBridge == null) return;

            // 检查 LocalMessageHandlers
            if (webView.getLocalMessageHandlers().containsKey(handlerName)) {
                BridgeHandler bridgeHandler = webView.getLocalMessageHandlers().get(handlerName);
                if (bridgeHandler == null) {
                    Log.e(TAG, "========bridgeHandler is null==========");
                    return;
                }

                if (webView.getHandlerLogNames().contains(handlerName)) {
                    bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, "", ""));
                } else {
                    bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, handlerName, data));
                }

                return;
            }

            // 检查全局 MessageHandlers
            if (mBridge.getMessageHandlers().containsKey(handlerName)) {
                BridgeHandler bridgeHandler = mBridge.getMessageHandlers().get(handlerName);
                if (bridgeHandler == null) {
                    Log.e(TAG, "========bridgeHandler is null 2==========");
                    return;
                }
                if (webView.getHandlerLogNames().contains(handlerName)) {
                    bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, "", ""));
                } else {
                    bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, handlerName, data));
                }
            }
        });
    }

    /**
     * 优化：改为 static 静态内部类，彻底断开对 BridgeJavascritInterface 的隐式强引用
     */
    public static class CallBack extends CallBackFunction {
        private final String callbackId;
        private final WeakReference<BridgeTiny> bridgeRef;

        public CallBack(BridgeTiny bridge, String callbackId, String handlerName, String params) {
            this.bridgeRef = new WeakReference<>(bridge);
            this.jsParams = params;
            this.callbackId = callbackId;
            this.handlerName = handlerName;
        }

        @Override
        public void onCallBack(String data) {
            BridgeTiny bridge = bridgeRef.get();
            if (bridge != null) {
                bridge.sendResponse(data, callbackId);
            }
        }
    }

    @Override
    public void release() {
        super.release();
        if (webViewRef != null) {
            webViewRef.clear();
        }
        this.mBridge = null; // 断开桥接对象的引用
    }
}