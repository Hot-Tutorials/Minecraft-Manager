/*
 * MCDocker, an open source Minecraft launcher.
 * Copyright (C) 2021 MCDocker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.mcdocker.launcher.auth.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.mcdocker.launcher.auth.Account;
import io.mcdocker.launcher.auth.AuthenticationException;
import io.mcdocker.launcher.auth.EmailPasswordAuthentication;
import io.mcdocker.launcher.utils.OSUtils;
import io.mcdocker.launcher.utils.http.Method;
import io.mcdocker.launcher.utils.http.RequestBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public class MojangAuth implements EmailPasswordAuthentication {

    private static final Gson gson = new Gson();

    private final JsonObject payload = new JsonObject();

    public MojangAuth() {
        File file = new File(OSUtils.getMinecraftPath() + "clientId.txt"); // Use the same clientToken as the vanilla launcher so other accessTokens aren't invalidated.
        String clientId = UUID.randomUUID().toString();
        try {
            if (!file.exists()) {
                file.mkdir();
                FileWriter fileWriter = new FileWriter(file);
                fileWriter.write(clientId);
                fileWriter.close();
            } else {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                clientId = reader.readLine();
                reader.close();
            }
        } catch (Exception ignored) {}

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        payload.add("agent", agent);
        payload.addProperty("clientToken", clientId);
    }

    @Deprecated
    @Override
    public CompletableFuture<Account> authenticate(Consumer<String> status) {
        return authenticate("<email>", "<password>", status); // TODO: User input.
    }

    @Override
    public CompletableFuture<Account> authenticate(String email, String password, Consumer<String> status) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = payload.deepCopy();
                body.addProperty("username", email);
                body.addProperty("password", password);

                status.accept("Authenticating");
                String res = RequestBuilder.getBuilder()
                        .setURL("https://authserver.mojang.com/authenticate")
                        .setBody(gson.toJson(body))
                        .setMethod(Method.POST)
                        .addHeader("Content-Type", "application/json").addHeader("Accept", "application/json")
                        .addHeader("User-Agent", "MCDocker")
                        .send(false);
                if (res == null) throw new MojangAuthenticationException("Invalid Login");
                JsonObject reply = gson.fromJson(res, JsonObject.class);
                if (!reply.has("accessToken")) throw new MojangAuthenticationException("You do not own Minecraft. Please buy it at minecraft.net");
                String accessToken = reply.get("accessToken").getAsString();

                status.accept("Verifying");
                Account account = getAccount(accessToken);
                if (account == null) throw new MojangAuthenticationException("Minecraft Account could not be found.");

                status.accept("Welcome, " + account.getUsername() + ".");

                return account;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private Account getAccount(String accessToken) {
        String res = RequestBuilder.getBuilder()
                .setURL("https://api.minecraftservices.com/minecraft/profile")
                .setMethod(Method.GET)
                .addHeader("Authorization", "Bearer " + accessToken)
                .send(true);
        JsonObject profile = gson.fromJson(res, JsonObject.class);
        if (profile.has("error")) return null;

        return new Account(profile.get("name").getAsString(), profile.get("id").getAsString(), accessToken, profile.get("skins").getAsJsonArray());
    }

    public static class MojangAuthenticationException extends AuthenticationException {

        public MojangAuthenticationException(String message) {
            super(message);
        }

    }


}