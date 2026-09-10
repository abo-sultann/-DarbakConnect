package com.abosultan.darbakconnect;

import android.app.Application;

public class DarbakConnectApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DarbakRuntime.install(this);
    }
}
