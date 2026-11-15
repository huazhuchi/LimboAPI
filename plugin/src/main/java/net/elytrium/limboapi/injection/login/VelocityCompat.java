/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboapi.injection.login;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.elytrium.commons.utils.reflection.ReflectionException;
import net.elytrium.limboapi.LimboAPI;

/**
 * Runtime compatibility layer for the Velocity internals that changed in 4.3.
 *
 * <p>Velocity 4.3 rewrote the connection registry: {@code VelocityServer#canRegisterConnection(ConnectedPlayer)}
 * was removed, {@code VelocityServer#registerConnection(ConnectedPlayer)} now returns a
 * {@code CompletableFuture<Boolean>} that both checks and performs the registration, and a registered player has
 * to be finalized through {@code PlayerRegistry#finalizeLogin(ConnectedPlayer)} (otherwise the identity lock is
 * kept and the player is kicked after 30 seconds). {@code PlayerInfoForwarding} was also moved from
 * {@code com.velocitypowered.proxy.config} to {@code com.velocitypowered.api.proxy.server}.
 *
 * <p>Everything that changed is resolved reflectively, so the same build keeps working on Velocity 4.2.x
 * (synchronous API) and 4.3+ (asynchronous API).
 */
public final class VelocityCompat {

  private static final Method CAN_REGISTER_CONNECTION;
  private static final Method REGISTER_CONNECTION;
  private static final Method GET_PLAYER_REGISTRY;
  private static final Method FINALIZE_LOGIN;
  private static final Method IS_KICK_EXISTING_PLAYERS;
  private static final Method GET_PLAYER_INFO_FORWARDING_MODE;

  private VelocityCompat() {

  }

  public static boolean canRegisterConnection(VelocityServer server, ConnectedPlayer player) {
    if (CAN_REGISTER_CONNECTION != null) {
      try {
        return (boolean) CAN_REGISTER_CONNECTION.invoke(server, player);
      } catch (Throwable e) {
        throw new ReflectionException(e);
      }
    }

    // Velocity 4.3+ merged the check into the asynchronous registration, so replicate the check that
    // PlayerRegistry#tryRegisterLocked performs before publishing the player.
    if (isKickExistingPlayers(server.getConfiguration())) {
      return true;
    }

    return server.getPlayer(player.getUniqueId()).isEmpty() && server.getPlayer(player.getUsername()).isEmpty();
  }

  public static void registerConnection(VelocityServer server, ConnectedPlayer player, Consumer<Boolean> callback, Executor executor) {
    Object result;
    try {
      result = REGISTER_CONNECTION.invoke(server, player);
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }

    if (result instanceof CompletableFuture<?> future) {
      // Velocity 4.3+ acquires the identity lock and registers the player asynchronously.
      future.whenCompleteAsync((registered, throwable) -> {
        if (throwable != null) {
          LimboAPI.getLogger().error("Exception while registering connection for {}", player, throwable);
          callback.accept(false);
        } else {
          callback.accept(Boolean.TRUE.equals(registered));
        }
      }, executor);
    } else {
      callback.accept(Boolean.TRUE.equals(result));
    }
  }

  public static void finalizeLogin(VelocityServer server, ConnectedPlayer player) {
    if (GET_PLAYER_REGISTRY == null || FINALIZE_LOGIN == null) {
      return;
    }

    try {
      FINALIZE_LOGIN.invoke(GET_PLAYER_REGISTRY.invoke(server), player);
    } catch (Throwable e) {
      LimboAPI.getLogger().error("Failed to finalize login for {}", player, e);
    }
  }

  public static boolean isPlayerInfoForwardingNone(VelocityConfiguration configuration) {
    if (GET_PLAYER_INFO_FORWARDING_MODE == null) {
      return false;
    }

    try {
      Object mode = GET_PLAYER_INFO_FORWARDING_MODE.invoke(configuration);
      return mode instanceof Enum<?> forwarding && "NONE".equals(forwarding.name());
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }
  }

  private static boolean isKickExistingPlayers(VelocityConfiguration configuration) {
    if (IS_KICK_EXISTING_PLAYERS == null) {
      return false;
    }

    try {
      return (boolean) IS_KICK_EXISTING_PLAYERS.invoke(configuration);
    } catch (Throwable e) {
      throw new ReflectionException(e);
    }
  }

  private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
    try {
      return owner.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  static {
    CAN_REGISTER_CONNECTION = findMethod(VelocityServer.class, "canRegisterConnection", ConnectedPlayer.class);
    REGISTER_CONNECTION = findMethod(VelocityServer.class, "registerConnection", ConnectedPlayer.class);
    GET_PLAYER_REGISTRY = findMethod(VelocityServer.class, "getPlayerRegistry");
    IS_KICK_EXISTING_PLAYERS = findMethod(VelocityConfiguration.class, "isKickExistingPlayers");
    GET_PLAYER_INFO_FORWARDING_MODE = findMethod(VelocityConfiguration.class, "getPlayerInfoForwardingMode");

    Method finalizeLogin = null;
    if (GET_PLAYER_REGISTRY != null) {
      finalizeLogin = findMethod(GET_PLAYER_REGISTRY.getReturnType(), "finalizeLogin", ConnectedPlayer.class);
    }
    FINALIZE_LOGIN = finalizeLogin;
  }
}
