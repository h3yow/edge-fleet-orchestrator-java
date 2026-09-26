package com.github.h3yow.edgefleetorchestratorjava.session;

import com.github.h3yow.edgefleetorchestratorjava.model.DeliveryOutcome;

/** 模拟本地设备会话执行结果。 */
public record SessionResponse(DeliveryOutcome outcome, String detail) {
}
