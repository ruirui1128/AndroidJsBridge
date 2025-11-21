package com.smallbuer.jsbridge.core;

/**
 * Created on 2019/12/10.
 * Author: smallbuer
 * Description:
 */
public abstract class CallBackFunction {

    public String handlerName;

    public String jsParams;

    public abstract void onCallBack(String data);

}
