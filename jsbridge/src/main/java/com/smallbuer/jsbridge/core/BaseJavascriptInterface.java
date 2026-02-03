package com.smallbuer.jsbridge.core;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.util.Map;

/**
 * Created on 2019/12/10.
 * @author smallbuer
 * BaseJavascriptInterface
 */
//public abstract class BaseJavascriptInterface {
//
//    private String TAG = "BaseJavascriptInterface";
//    Handler mMainHandler = new Handler(Looper.getMainLooper());
//    private Map<String, OnBridgeCallback> mCallbacks;
//
//    public BaseJavascriptInterface(Map<String, OnBridgeCallback> callbacks) {
//        mCallbacks = callbacks;
//    }
//
//    @JavascriptInterface
//    public String send(String data, String callbackId) {
//        return send(data);
//    }
//
//    @JavascriptInterface
//    public void response(final String data, final String responseId) {
//
//        BridgeLog.d(TAG, "response->"+data + ", responseId: " + responseId + " " + Thread.currentThread().getName());
//
//        if (!TextUtils.isEmpty(responseId)) {
//            mMainHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    OnBridgeCallback function = mCallbacks.remove(responseId);
//                    if (function != null) {
//                            function.onCallBack(data);
//                        }
//                    }
//            });
//        }
//    }
//
//    public abstract String send(String data);
//
//
//
//}


public abstract class BaseJavascriptInterface {
    protected final String TAG = "BaseJavascriptInterface";
    protected final Handler mMainHandler = new Handler(Looper.getMainLooper());
    protected Map<String, OnBridgeCallback> mCallbacks;

    public BaseJavascriptInterface(Map<String, OnBridgeCallback> callbacks) {
        this.mCallbacks = callbacks;
    }

    @JavascriptInterface
    public void response(final String data, final String responseId) {
        if (!TextUtils.isEmpty(responseId)) {
            mMainHandler.post(() -> {
                if (mCallbacks != null) {
                    OnBridgeCallback function = mCallbacks.remove(responseId);
                    if (function != null) {
                        function.onCallBack(data);
                    }
                }
            });
        }
    }

    // 关键：供外部 Activity onDestroy 时调用
    public void release() {
        if (mCallbacks != null) {
            mCallbacks.clear();
        }
        mMainHandler.removeCallbacksAndMessages(null);
    }

    public abstract String send(String data);
}
