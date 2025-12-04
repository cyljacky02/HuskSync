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

import net.william278.husksync.HuskSync;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Service responsible for all map persistence operations.
 * <p>
 * <h2>Design Invariants</h2>
 * <ul>
 *   <li><b>Locked maps</b> are synchronized across servers. Their pixel content
 *       is persisted to Redis/database, keyed by {@link MapIdentity}.</li>
 *   <li><b>Unlocked maps</b> are local to each server. If carried cross-server,
 *       they become blank to prevent content leakage.</li>
 *   <li><b>Map identity</b> is determined by {@code (originServer, originId)},
 *       stored in item NBT. Numeric map IDs are per-server implementation details.</li>
 *   <li><b>Bindings</b> map a {@link MapIdentity} to local map IDs on each server,
 *       allowing the same logical map to have different numeric IDs across servers.</li>
 * </ul>
 *
 * <h2>Data Flow</h2>
 * <pre>
 * Snapshot Save (locked map):
 *   Item -> Extract MapIdentity from NBT or create new
 *        -> Render canvas -> Save content to Redis + DB
 *        -> Write identity to NBT
 *
 * Snapshot Save (local unlocked map):
 *   Item -> Tag with origin server + original ID in NBT
 *        -> No content persistence (unlocked maps are local)
 *
 * Snapshot Apply (locked map):
 *   Item -> Read MapIdentity from NBT
 *        -> Find or create binding for current server
 *        -> Fetch content from Redis/DB
 *        -> Render into MapView, lock it
 *        -> Update item meta
 *
 * Snapshot Apply (unlocked map on origin server):
 *   Item -> Detect origin == current server
 *        -> Restore original MapView if possible
 *        -> Or create new unlocked MapView, update NBT origin ID
 *
 * Snapshot Apply (unlocked map on foreign server):
 *   Item -> Detect origin != current server
 *        -> Suspend: set map ID to -1 (unknown/blank)
 *        -> Preserve identity in NBT for later restoration
 * </pre>
 */
public interface MapPersistenceService {

    // ─────────────────────────────────────────────────────────────────────────────
    // Configuration
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Check if map persistence is enabled in settings.
     *
     * @return true if the {@code persist_locked_maps} setting is enabled
     */
    boolean isEnabled();

    /**
     * Get the name of the current server.
     *
     * @return the server name from plugin configuration
     */
    @NotNull
    String getCurrentServer();

    // ─────────────────────────────────────────────────────────────────────────────
    // Content Storage
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Save map content for a given identity.
     * Writes to both Redis cache and persistent database.
     *
     * @param identity the map's logical identity
     * @param content  the serialized map content bytes
     */
    @Blocking
    void saveContent(@NotNull MapIdentity identity, @NotNull byte[] content);

    /**
     * Retrieve map content for a given identity.
     * Checks Redis cache first, then database.
     *
     * @param identity the map's logical identity
     * @return the content if found, or empty
     */
    @Blocking
    @NotNull
    Optional<MapContent> getContent(@NotNull MapIdentity identity);

    /**
     * Check if content exists for a given identity.
     *
     * @param identity the map's logical identity
     * @return true if content is stored for this identity
     */
    @Blocking
    boolean hasContent(@NotNull MapIdentity identity);

    // ─────────────────────────────────────────────────────────────────────────────
    // Binding Management
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Get the local map ID bound to a given identity on a target server.
     *
     * @param identity     the map's logical identity
     * @param targetServer the server to look up the binding for
     * @return the bound local map ID, or -1 if not bound
     */
    @Blocking
    int getBoundLocalId(@NotNull MapIdentity identity, @NotNull String targetServer);

    /**
     * Get the local map ID for a given identity on the current server.
     * Shorthand for {@code getBoundLocalId(identity, getCurrentServer())}.
     *
     * @param identity the map's logical identity
     * @return the bound local map ID, or -1 if not bound
     */
    @Blocking
    default int getBoundLocalId(@NotNull MapIdentity identity) {
        return getBoundLocalId(identity, getCurrentServer());
    }

    /**
     * Create or update a binding from a map identity to a local map ID.
     *
     * @param identity     the map's logical identity
     * @param targetServer the server where the local ID exists
     * @param localId      the local map ID on that server
     */
    @Blocking
    void setBinding(@NotNull MapIdentity identity, @NotNull String targetServer, int localId);

    /**
     * Check if a local map ID on this server is bound to any HuskSync map identity.
     * Used to determine if a local mapId is "managed" by HuskSync.
     * <p>
     * This is the <b>reverse lookup</b>: given a local ID, find the identity.
     *
     * @param localId the local map ID to check
     * @return the identity if bound, or empty if this is an unmanaged/vanilla map
     */
    @Blocking
    @NotNull
    Optional<MapIdentity> getIdentityForLocalId(int localId);

    /**
     * Check if a local map ID on this server is managed by HuskSync.
     * Shorthand for {@code getIdentityForLocalId(localId).isPresent()}.
     *
     * @param localId the local map ID to check
     * @return true if this ID is bound to a HuskSync map identity
     */
    @Blocking
    default boolean isManagedLocalId(int localId) {
        return getIdentityForLocalId(localId).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Plugin Access
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Get the plugin instance.
     *
     * @return the HuskSync plugin
     */
    @NotNull
    HuskSync getPlugin();
}
