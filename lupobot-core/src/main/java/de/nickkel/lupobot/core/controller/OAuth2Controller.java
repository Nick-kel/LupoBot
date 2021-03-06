package de.nickkel.lupobot.core.controller;

import de.nickkel.lupobot.core.LupoBot;
import io.javalin.Javalin;
import io.javalin.http.Context;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;

public class OAuth2Controller {

    public OAuth2Controller(Javalin app) {
        app.routes(() -> {
            path("v1/oauth2", () -> {
                get(this::getUser);
            });
        });
    }

    public void getUser(Context ctx) {
        if (ctx.queryParam("redirect") == null || ctx.queryParam("code") == null) {
            ctx.status(404).result("Not found");
            return;
        }
        ctx.result(LupoBot.getInstance().getRestServer().getOAuth2().getUser(ctx.queryParam("redirect"), ctx.queryParam("code")));
    }
}
