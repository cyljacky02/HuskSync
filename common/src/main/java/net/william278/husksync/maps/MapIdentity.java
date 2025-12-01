/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.maps;

import org.jetbrains.annotations.NotNull;

/**
 * Represents the logical identity of a map across the cluster.
 * <p>
 * A map is uniquely identified by the server it was originally created on
 * and its original map ID on that server. This identity remains stable
 * even as the map is synchronized to other servers with different local IDs.
 * <p>
 * <b>Design Note:</b> This abstraction decouples map identity from the
 * per-server numeric map IDs assigned by Bukkit/Minecraft. The binding
 * tables map from {@code MapIdentity} to local IDs on each server.
 *
 * @param originServer The name of the server where this map was originally created
 * @param originId     The map ID on the origin server
 */
public record MapIdentity(@NotNull String originServer, int originId) {

    /**
     * Creates a MapIdentity for a map on the specified server.
     *
     * @param serverName the server name
     * @param mapId      the map ID on that server
     * @return a new MapIdentity
     */
    @NotNull
    public static MapIdentity of(@NotNull String serverName, int mapId) {
        return new MapIdentity(serverName, mapId);
    }

    /**
     * Check if this identity's origin matches the given server.
     *
     * @param serverName the server name to check
     * @return true if this map originated on the given server
     */
    public boolean isOrigin(@NotNull String serverName) {
        return originServer.equals(serverName);
    }

    @Override
    public String toString() {
        return "MapIdentity{origin=%s, id=%d}".formatted(originServer, originId);
    }
}
