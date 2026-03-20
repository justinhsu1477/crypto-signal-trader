package com.trader.trading.service;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * User Data Stream 事件分派器
 * 僅做旁路分派，不改變既有 WebSocket 處理流程。
 */
@Slf4j
@Component
public class UserDataEventDispatcher {

    private final List<UserDataEventObserver> observers;

    public UserDataEventDispatcher(List<UserDataEventObserver> observers) {
        this.observers = observers;
    }

    public void dispatch(JsonObject event) {
        if (event == null || observers == null || observers.isEmpty()) {
            return;
        }
        for (UserDataEventObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                log.debug("UserDataEventObserver failed: {}", e.getMessage());
            }
        }
    }
}
