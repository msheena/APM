package com.mixed.mix;

import android.app.Application;
import com.mixed.apm.CustomActivityOnCrash;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CustomActivityOnCrash.Companion.install(this);
    }
}
