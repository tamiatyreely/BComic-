package com.example.comiclsposed;

import io.github.libxposed.api.XposedModule;

public class ComicModule extends XposedModule {
    @Override
    public void onModuleLoaded() {
        log("ComicModule loaded");
    }
}
