package com.juzizhen.rcode.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class WebServer {

    private static WebServer instance;

    private Javalin app;

    private WebServer() {
    }

    public static WebServer getInstance() {
        if (instance == null) {
            instance = new WebServer();
        }
        return instance;
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/assets/redemptioncodefabric/web";
                staticFiles.location = Location.CLASSPATH;
            });
        }).start(port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
