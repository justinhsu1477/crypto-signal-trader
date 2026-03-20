package com.trader.trading.service;

import com.google.gson.JsonObject;

/**
 * User Data Stream 事件旁路觀察者（被動消費，不影響核心流程）
 */
public interface UserDataEventObserver {

    void onEvent(JsonObject event);
}
