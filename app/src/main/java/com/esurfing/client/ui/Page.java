package com.esurfing.client.ui;

import android.view.View;

/** 一个页面（主界面 / 日志 / 设置）。 */
public interface Page {

    View view();

    /** 页面被切到前台。 */
    void onShow();

    /** 页面被切走。 */
    void onHide();
}
