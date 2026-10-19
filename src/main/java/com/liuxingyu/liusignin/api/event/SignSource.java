package com.liuxingyu.liusignin.api.event;

/** 签到来源。 */
public enum SignSource {
    /** 游戏内命令。 */
    COMMAND,
    /** 签到界面。 */
    GUI,
    /** 进服自动签到。 */
    AUTO,
    /** 补签。 */
    MAKEUP,
    /** 插件 API / 管理操作。 */
    API
}
