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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableItemNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import net.william278.husksync.BukkitHuskSync;
import net.william278.mapdataapi.MapBanner;
import net.william278.mapdataapi.MapData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Bukkit implementation of the Map Persistence Service.
 * <p>
 * Handles all map persistence operations including:
 * <ul>
 *   <li>Persisting locked map content to NBT, Redis, and database</li>
 *   <li>Applying persisted map views to items on inventory load</li>
 *   <li>Managing map ID bindings across servers</li>
 *   <li>Suspending unlocked maps on foreign servers (unknown map, ID -1)</li>
 *   <li>Restoring unlocked maps when player returns to origin server</li>
 * </ul>
 *
 * <h2>Unlocked Map Behavior</h2>
 * <pre>
 * On persist: Tag with {origin, origin_id} in NBT
 *
 * On apply (foreign server):
 *   → Set map ID to -1 (unknown map)
 *   → Keep NBT tags for later restoration
 *   → Player sees unknown/blank map, cannot explore
 *
 * On apply (origin server):
 *   → Restore original MapView by ID
 *   → Player sees their original explored content
 * </pre>
 */
public class BukkitMapPersistenceService implements MapPersistenceService {

    // ─────────────────────────────────────────────────────────────────────────────
    // NBT Keys
    // ─────────────────────────────────────────────────────────────────────────────

    /** NBT compound key for HuskSync persisted locked map data */
    public static final String MAP_DATA_KEY = "husksync:persisted_locked_map";

    /** Legacy NBT key for pixel data (3.7.3 and below) */
    public static final String MAP_LEGACY_PIXEL_DATA_KEY = "husksync:canvas_data";

    /** NBT key for origin server name within MAP_DATA_KEY compound */
    public static final String MAP_ORIGIN_KEY = "origin";

    /** NBT key for original map ID within MAP_DATA_KEY compound */
    public static final String MAP_ID_KEY = "id";

    /** NBT compound key for unlocked map identity (origin + original ID, no pixel data) */
    public static final String MAP_UNLOCKED_KEY = "husksync:unlocked_map";

    /** NBT key for origin server name within MAP_UNLOCKED_KEY compound */
    public static final String UNLOCKED_ORIGIN_KEY = "origin";

    /** NBT key for original map ID within MAP_UNLOCKED_KEY compound */
    public static final String UNLOCKED_ORIGIN_ID_KEY = "origin_id";

    /**
     * Special map ID used for suspended unlocked maps on foreign servers.
     * ID -1 creates an "unknown map" - no map data exists for this ID,
     * so the client displays it as blank. This avoids allocating real map IDs.
     * <p>
     * Naturally reaching -1 would require creating ~4 billion maps (integer overflow),
     * which is practically impossible in normal gameplay.
     */
    public static final int UNKNOWN_MAP_ID = -1;

    // ─────────────────────────────────────────────────────────────────────────────
    // Instance Fields
    // ─────────────────────────────────────────────────────────────────────────────

    private final BukkitHuskSync plugin;

    /** In-memory cache of rendered MapViews, keyed by local map ID */
    private final ConcurrentMap<Integer, MapView> renderedViews = Maps.newConcurrentMap();

    // ─────────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────────

    public BukkitMapPersistenceService(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // MapPersistenceService Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return plugin.getSettings().getSynchronization().isPersistLockedMaps();
    }

    @Override
    @NotNull
    public String getCurrentServer() {
        return plugin.getServerName();
    }

    @Override
    @Blocking
    public void saveContent(@NotNull MapIdentity identity, @NotNull byte[] content) {
        plugin.getRedisManager().setMapData(identity.originServer(), identity.originId(), content);
        plugin.getDatabase().saveMapData(identity.originServer(), identity.originId(), content);
    }

    @Override
    @Blocking
    @NotNull
    public Optional<MapContent> getContent(@NotNull MapIdentity identity) {
        // Check Redis cache first
        final byte[] redisData = plugin.getRedisManager().getMapData(
                identity.originServer(), identity.originId()
        );
        if (redisData != null) {
            return Optional.of(MapContent.of(redisData, true));
        }

        // Check database
        final byte[] dbData = plugin.getDatabase().getMapData(
                identity.originServer(), identity.originId()
        );
        if (dbData != null) {
            // Populate Redis cache for future lookups
            plugin.getRedisManager().setMapData(identity.originServer(), identity.originId(), dbData);
            return Optional.of(MapContent.of(dbData, false));
        }

        return Optional.empty();
    }

    @Override
    @Blocking
    public boolean hasContent(@NotNull MapIdentity identity) {
        return getContent(identity).isPresent();
    }

    @Override
    @Blocking
    public int getBoundLocalId(@NotNull MapIdentity identity, @NotNull String targetServer) {
        // Check Redis first
        final Optional<Integer> redisId = plugin.getRedisManager().getBoundMapId(
                identity.originServer(), identity.originId(), targetServer
        );
        if (redisId.isPresent()) {
            return redisId.get();
        }

        // Check database, populate Redis if found
        final int dbId = plugin.getDatabase().getBoundMapId(
                identity.originServer(), identity.originId(), targetServer
        );
        if (dbId != -1) {
            plugin.getRedisManager().bindMapIds(
                    identity.originServer(), identity.originId(), targetServer, dbId
            );
        }
        return dbId;
    }

    @Override
    @Blocking
    public void setBinding(@NotNull MapIdentity identity, @NotNull String targetServer, int localId) {
        plugin.getRedisManager().bindMapIds(
                identity.originServer(), identity.originId(), targetServer, localId
        );
        plugin.getDatabase().setMapBinding(
                identity.originServer(), identity.originId(), targetServer, localId
        );
    }

    @Override
    @Blocking
    @NotNull
    public Optional<MapIdentity> getIdentityForLocalId(int localId) {
        final String currentServer = getCurrentServer();

        // Check Redis for reverse binding
        Map.Entry<String, Integer> binding = plugin.getRedisManager().getReversedMapBound(currentServer, localId);
        if (binding != null) {
            return Optional.of(new MapIdentity(binding.getKey(), binding.getValue()));
        }

        // Check database for reverse binding
        binding = plugin.getDatabase().getMapBinding(currentServer, localId);
        if (binding != null) {
            // Populate Redis for future lookups
            plugin.getRedisManager().bindMapIds(binding.getKey(), binding.getValue(), currentServer, localId);
            return Optional.of(new MapIdentity(binding.getKey(), binding.getValue()));
        }

        return Optional.empty();
    }

    @Override
    @NotNull
    public BukkitHuskSync getPlugin() {
        return plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Bukkit-Specific: Item Processing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Process items before saving to snapshot.
     * For locked maps: persist content to NBT and database.
     * For unlocked maps: tag with origin server (for later suspend/restore).
     *
     * @param items            the items to process
     * @param delegateRenderer the player to use for rendering map canvases
     * @return the processed items
     */
    @NotNull
    public ItemStack[] persistMaps(@NotNull ItemStack[] items, @NotNull Player delegateRenderer) {
        if (!isEnabled()) {
            return items;
        }
        return forEachMap(items, map -> persistMapItem(map, delegateRenderer));
    }

    /**
     * Process items when applying to player inventory.
     * For locked maps: resolve bindings and render content.
     * For foreign unlocked maps: suspend as unknown map (ID -1).
     *
     * @param items the items to process
     * @return the processed items
     */
    @Nullable
    public ItemStack @NotNull [] applyMaps(@Nullable ItemStack @NotNull [] items) {
        if (!isEnabled()) {
            return items;
        }
        return forEachMap(items, this::applyMapItem);
    }

    /**
     * Render a locked map that is initializing (e.g., in an item frame).
     * Only processes maps that are managed by HuskSync.
     *
     * @param view the MapView being initialized
     */
    public void renderInitializingMap(@NotNull MapView view) {
        if (view.isVirtual()) {
            return;
        }

        final int localId = view.getId();

        // Check if we already have this view cached
        final MapView cachedView = renderedViews.get(localId);
        if (cachedView != null) {
            view.getRenderers().clear();
            view.getRenderers().addAll(cachedView.getRenderers());
            view.setLocked(true);
            view.setScale(MapView.Scale.NORMAL);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            return;
        }

        // Only process if this local ID is managed by HuskSync
        final Optional<MapIdentity> identityOpt = getIdentityForLocalId(localId);
        if (identityOpt.isEmpty()) {
            plugin.debug("Skipping render for non-HuskSync map #%d".formatted(localId));
            return;
        }

        final MapIdentity identity = identityOpt.get();

        // Fetch content using identity (NOT by local ID alone)
        Optional<MapContent> contentOpt = getContent(identity);
        MapData mapData = null;

        if (contentOpt.isPresent()) {
            mapData = deserializeContent(contentOpt.get());
        } else {
            // Try legacy file data
            mapData = readLegacyMapFileData(localId);
        }

        if (mapData == null) {
            World world = view.getWorld() == null ? getDefaultMapWorld() : view.getWorld();
            plugin.debug("Not rendering map: no data for identity %s, local #%d, world %s"
                    .formatted(identity, localId, world.getName()));
            return;
        }

        renderMapView(view, mapData);
        plugin.debug("Rendered initializing map for identity %s, local #%d".formatted(identity, localId));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal: Item Processing
    // ─────────────────────────────────────────────────────────────────────────────

    @NotNull
    private ItemStack[] forEachMap(ItemStack[] items, @NotNull Function<ItemStack, ItemStack> function) {
        for (int i = 0; i < items.length; i++) {
            final ItemStack item = items[i];
            if (item == null) {
                continue;
            }
            if (item.getType() == Material.FILLED_MAP && item.hasItemMeta()) {
                items[i] = function.apply(item);
            } else if (item.getItemMeta() instanceof BlockStateMeta b && b.getBlockState() instanceof Container box
                    && !box.getInventory().isEmpty()) {
                forEachMap(box.getInventory().getContents(), function);
                b.setBlockState(box);
                item.setItemMeta(b);
            } else if (item.getItemMeta() instanceof BundleMeta bundle && bundle.hasItems()) {
                bundle.setItems(List.of(forEachMap(bundle.getItems().toArray(ItemStack[]::new), function)));
                item.setItemMeta(bundle);
            }
        }
        return items;
    }

    @NotNull
    private ItemStack persistMapItem(@NotNull ItemStack map, @NotNull Player delegateRenderer) {
        final MapMeta meta = Objects.requireNonNull((MapMeta) map.getItemMeta());
        if (!meta.hasMapView()) {
            return map;
        }

        final MapView view = meta.getMapView();
        if (view == null || view.getWorld() == null || view.isVirtual()) {
            return map;
        }

        final String currentServer = getCurrentServer();

        // If this is an unlocked map whose origin is another server, it's a suspended placeholder.
        // Do not persist it as a locked map or modify its identity.
        final UnlockedMapInfo unlockedInfo = readUnlockedMapInfo(map);
        if (unlockedInfo != null && !unlockedInfo.isOrigin(currentServer)) {
            return map;
        }

        final int mapId = meta.getMapId();

        if (view.isLocked()) {
            // Persist locked map content
            return persistLockedMapItem(map, meta, view, currentServer, mapId, delegateRenderer);
        } else {
            // Tag unlocked map with origin for later suspend/restore
            return tagUnlockedMap(map, meta, currentServer);
        }
    }

    @NotNull
    private ItemStack persistLockedMapItem(@NotNull ItemStack map, @NotNull MapMeta meta, @NotNull MapView view,
                                           @NotNull String serverName, int mapId, @NotNull Player delegateRenderer) {
        NBT.modify(map, nbt -> {
            // Don't save twice
            if (nbt.hasTag(MAP_DATA_KEY)) {
                return;
            }

            // Render the map canvas
            final int dataVersion = plugin.getDataVersion(plugin.getMinecraftVersion());
            final PersistentMapCanvas canvas = new PersistentMapCanvas(view, dataVersion);
            for (MapRenderer renderer : view.getRenderers()) {
                renderer.render(view, canvas, delegateRenderer);
            }

            // Write identity to NBT
            final ReadWriteNBT mapData = nbt.getOrCreateCompound(MAP_DATA_KEY);
            mapData.setString(MAP_ORIGIN_KEY, serverName);
            mapData.setInteger(MAP_ID_KEY, mapId);

            // Clean up stale unlocked tag if this map was previously unlocked
            if (nbt.hasTag(MAP_UNLOCKED_KEY)) {
                nbt.removeKey(MAP_UNLOCKED_KEY);
                plugin.debug("Removed stale unlocked tag (map is now locked)");
            }

            // Save content to Redis + DB if not already present
            final MapIdentity identity = new MapIdentity(serverName, mapId);
            if (!hasContent(identity)) {
                final byte[] contentBytes = plugin.getDataAdapter().toBytes(new AdaptableMapData(canvas.extractMapData()));
                saveContent(identity, contentBytes);
            }

            plugin.debug("Persisted locked map identity %s".formatted(identity));
        });
        return map;
    }

    /**
     * Info about an unlocked map's origin.
     */
    private record UnlockedMapInfo(@NotNull String origin, int originId) {
        boolean isOrigin(@NotNull String currentServer) {
            return origin.equals(currentServer);
        }
    }

    @NotNull
    private ItemStack tagUnlockedMap(@NotNull ItemStack map, @NotNull MapMeta meta, @NotNull String serverName) {
        final int mapId = meta.getMapId();
        NBT.modify(map, nbt -> {
            // Don't overwrite existing tags
            if (nbt.hasTag(MAP_DATA_KEY) || nbt.hasTag(MAP_UNLOCKED_KEY)) {
                return;
            }

            // Tag with origin server and original map ID
            final ReadWriteNBT unlocked = nbt.getOrCreateCompound(MAP_UNLOCKED_KEY);
            unlocked.setString(UNLOCKED_ORIGIN_KEY, serverName);
            unlocked.setInteger(UNLOCKED_ORIGIN_ID_KEY, mapId);
            plugin.debug("Tagged unlocked map: origin=%s, id=%d".formatted(serverName, mapId));
        });
        return map;
    }

    @Nullable
    private UnlockedMapInfo readUnlockedMapInfo(@NotNull ItemStack map) {
        final String[] origin = {null};
        final int[] originId = {-1};

        NBT.get(map, nbt -> {
            if (nbt.hasTag(MAP_UNLOCKED_KEY)) {
                final ReadableNBT unlocked = nbt.getCompound(MAP_UNLOCKED_KEY);
                if (unlocked != null) {
                    origin[0] = unlocked.getString(UNLOCKED_ORIGIN_KEY);
                    originId[0] = unlocked.getInteger(UNLOCKED_ORIGIN_ID_KEY);
                }
            }
        });

        if (origin[0] != null && !origin[0].isEmpty() && originId[0] != -1) {
            return new UnlockedMapInfo(origin[0], originId[0]);
        }
        return null;
    }

    @NotNull
    private ItemStack applyMapItem(@NotNull ItemStack map) {
        final MapMeta meta = Objects.requireNonNull((MapMeta) map.getItemMeta());
        final String currentServer = getCurrentServer();

        // ─── Check for LOCKED map (has full MAP_DATA_KEY) ───
        final boolean[] hasLockedMapData = {false};
        final String[] originServer = {null};
        final int[] originId = {-1};

        NBT.get(map, nbt -> {
            if (nbt.hasTag(MAP_DATA_KEY)) {
                hasLockedMapData[0] = true;
                final ReadableNBT mapData = nbt.getCompound(MAP_DATA_KEY);
                if (mapData != null) {
                    originServer[0] = mapData.getString(MAP_ORIGIN_KEY);
                    originId[0] = mapData.getInteger(MAP_ID_KEY);
                }
            }
        });

        if (hasLockedMapData[0] && originServer[0] != null && originId[0] != -1) {
            return applyLockedMapItem(map, meta, new MapIdentity(originServer[0], originId[0]));
        }

        // ─── Check for UNLOCKED map ───
        final UnlockedMapInfo unlockedInfo = readUnlockedMapInfo(map);
        if (unlockedInfo != null) {
            if (unlockedInfo.isOrigin(currentServer)) {
                // Back on origin server → RESTORE original map
                return restoreUnlockedMap(map, meta, unlockedInfo);
            } else {
                // Foreign server → SUSPEND (unknown map ID -1, preserve identity)
                return suspendUnlockedMap(map, meta, unlockedInfo);
            }
        }

        // ─── VANILLA map (no HuskSync tags) ───
        return map;
    }

    @NotNull
    private ItemStack applyLockedMapItem(@NotNull ItemStack map, @NotNull MapMeta meta, @NotNull MapIdentity identity) {
        final String currentServer = getCurrentServer();
        final boolean isOrigin = identity.isOrigin(currentServer);

        // Get or create binding for this server
        int localId = isOrigin ? identity.originId() : getBoundLocalId(identity);

        if (localId != -1) {
            // We have a binding - use it
            return applyBoundLockedMap(map, meta, identity, localId, isOrigin);
        } else {
            // No binding yet - create one
            return applyUnboundLockedMap(map, meta, identity, currentServer);
        }
    }

    @NotNull
    private ItemStack applyBoundLockedMap(@NotNull ItemStack map, @NotNull MapMeta meta,
                                          @NotNull MapIdentity identity, int localId, boolean isOrigin) {
        // If on origin server and Bukkit has the view, just use it
        if (isOrigin) {
            MapView view = Bukkit.getMap(localId);
            if (view != null) {
                meta.setMapView(view);
                map.setItemMeta(meta);
                plugin.debug("Applied origin map %s with local ID #%d".formatted(identity, localId));
                return map;
            }
        }

        // Check our rendered cache
        MapView cachedView = renderedViews.get(localId);
        if (cachedView != null) {
            meta.setMapView(cachedView);
            map.setItemMeta(meta);
            plugin.debug("Applied cached map %s with local ID #%d".formatted(identity, localId));
            return map;
        }

        // Need to fetch content and render
        Optional<MapContent> contentOpt = getContent(identity);
        MapData mapData = null;

        if (contentOpt.isPresent()) {
            mapData = deserializeContent(contentOpt.get());
        } else {
            // Try legacy NBT data
            final MapData[] legacyData = {null};
            NBT.get(map, nbt -> {
                if (nbt.hasTag(MAP_LEGACY_PIXEL_DATA_KEY)) {
                    legacyData[0] = readLegacyMapItemData(nbt);
                }
            });
            mapData = legacyData[0];
        }

        if (mapData == null) {
            plugin.debug("No content found for map %s, skipping".formatted(identity));
            return map;
        }

        // Get or create a MapView for this local ID
        MapView view = Bukkit.getMap(localId);

        // Safety check: if the view exists but isn't managed by us, create a new one
        if (view != null && !isOrigin && !renderedViews.containsKey(localId)) {
            // This ID might belong to a vanilla map - check if we actually bound it
            Optional<MapIdentity> boundIdentity = getIdentityForLocalId(localId);
            if (boundIdentity.isEmpty() || !boundIdentity.get().equals(identity)) {
                // The binding is stale or points to a vanilla map - create fresh
                view = Bukkit.createMap(getDefaultMapWorld());
                final int newLocalId = view.getId();
                setBinding(identity, getCurrentServer(), newLocalId);
                plugin.debug("Created new view #%d for map %s (old binding #%d was stale/vanilla)"
                        .formatted(newLocalId, identity, localId));
                localId = newLocalId;
            }
        }

        if (view == null) {
            view = Bukkit.createMap(getDefaultMapWorld());
            localId = view.getId();
            // Only create binding if not on origin server
            if (!isOrigin) {
                setBinding(identity, getCurrentServer(), localId);
            }
        }

        renderMapView(view, mapData);
        meta.setMapView(view);
        map.setItemMeta(meta);
        plugin.debug("Rendered and applied map %s with local ID #%d".formatted(identity, localId));
        return map;
    }

    @NotNull
    private ItemStack applyUnboundLockedMap(@NotNull ItemStack map, @NotNull MapMeta meta,
                                            @NotNull MapIdentity identity, @NotNull String currentServer) {
        // Fetch content
        Optional<MapContent> contentOpt = getContent(identity);
        MapData mapData = null;

        if (contentOpt.isPresent()) {
            mapData = deserializeContent(contentOpt.get());
        } else {
            // Try legacy NBT data
            final MapData[] legacyData = {null};
            NBT.get(map, nbt -> {
                if (nbt.hasTag(MAP_LEGACY_PIXEL_DATA_KEY)) {
                    legacyData[0] = readLegacyMapItemData(nbt);
                }
            });
            mapData = legacyData[0];
        }

        if (mapData == null) {
            plugin.debug("No content found for unbound map %s, skipping".formatted(identity));
            return map;
        }

        // Create a fresh MapView and bind it
        final MapView view = Bukkit.createMap(getDefaultMapWorld());
        renderMapView(view, mapData);
        meta.setMapView(view);
        map.setItemMeta(meta);

        final int localId = view.getId();
        setBinding(identity, currentServer, localId);

        plugin.debug("Created new binding for map %s -> local #%d on %s".formatted(identity, localId, currentServer));
        return map;
    }

    /**
     * Restore an unlocked map when the player returns to its origin server.
     * The map will display its original content again.
     */
    @NotNull
    private ItemStack restoreUnlockedMap(@NotNull ItemStack map, @NotNull MapMeta meta,
                                          @NotNull UnlockedMapInfo info) {
        final MapView originalView = Bukkit.getMap(info.originId());

        if (originalView != null) {
            meta.setMapView(originalView);
            map.setItemMeta(meta);
            plugin.debug("Restored unlocked map to original ID #%d (origin: %s)"
                    .formatted(info.originId(), info.origin()));
        } else {
            // Original MapView no longer exists (world reset, etc.) - create a fresh map
            final MapView freshView = Bukkit.createMap(getDefaultMapWorld());
            freshView.setLocked(false);
            meta.setMapView(freshView);
            map.setItemMeta(meta);

            // Update the NBT to reflect new identity
            final int newId = freshView.getId();
            NBT.modify(map, nbt -> {
                final ReadWriteNBT unlocked = nbt.getOrCreateCompound(MAP_UNLOCKED_KEY);
                unlocked.setInteger(UNLOCKED_ORIGIN_ID_KEY, newId);
            });

            plugin.debug("Original map #%d not found, created fresh map #%d (origin: %s)"
                    .formatted(info.originId(), newId, info.origin()));
        }

        return map;
    }

    /**
     * Suspend an unlocked map when on a foreign server.
     * <p>
     * Instead of creating a real MapView placeholder (which wastes map IDs), we set the
     * map ID to {@link #UNKNOWN_MAP_ID} (-1). This makes it an "unknown map" - the client
     * displays it as blank because no map data exists for ID -1.
     * <p>
     * The original identity is preserved in NBT ({@link #MAP_UNLOCKED_KEY}) for restoration
     * when the player returns to the origin server.
     */
    @SuppressWarnings("deprecation")
    @NotNull
    private ItemStack suspendUnlockedMap(@NotNull ItemStack map, @NotNull MapMeta meta,
                                          @NotNull UnlockedMapInfo info) {
        // If already suspended (ID is -1), nothing to do
        if (meta.getMapId() == UNKNOWN_MAP_ID) {
            plugin.debug("Already suspended unlocked map (origin: %s, id: %d) as unknown map"
                    .formatted(info.origin(), info.originId()));
            return map;
        }

        // Set to unknown map ID - client will show blank (no map_-1.dat exists)
        meta.setMapId(UNKNOWN_MAP_ID);
        map.setItemMeta(meta);

        // NBT tags (MAP_UNLOCKED_KEY) are preserved for restoration later

        plugin.debug("Suspended unlocked map (origin: %s, id: %d) -> unknown map (ID %d)"
                .formatted(info.origin(), info.originId(), UNKNOWN_MAP_ID));

        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal: Rendering
    // ─────────────────────────────────────────────────────────────────────────────

    private void renderMapView(@NotNull MapView view, @NotNull MapData mapData) {
        view.getRenderers().clear();
        view.addRenderer(new PersistentMapRenderer(mapData));
        view.setLocked(true);
        view.setScale(MapView.Scale.NORMAL);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        renderedViews.put(view.getId(), view);
    }

    @Nullable
    private MapData deserializeContent(@NotNull MapContent content) {
        try {
            return plugin.getDataAdapter().fromBytes(content.data(), AdaptableMapData.class)
                    .getData(plugin.getDataVersion(plugin.getMinecraftVersion()));
        } catch (IOException e) {
            plugin.log(Level.WARNING, "Failed to deserialize map content", e);
            return null;
        }
    }

    @Nullable
    private MapData readLegacyMapItemData(@NotNull ReadableItemNBT nbt) {
        try {
            final int dataVersion = plugin.getDataVersion(plugin.getMinecraftVersion());
            return MapData.fromByteArray(dataVersion,
                    Objects.requireNonNull(nbt.getByteArray(MAP_LEGACY_PIXEL_DATA_KEY)));
        } catch (IOException e) {
            plugin.log(Level.WARNING, "Failed to read legacy map NBT data", e);
            return null;
        }
    }

    @Nullable
    private MapData readLegacyMapFileData(int mapId) {
        final Path path = plugin.getDataFolder().toPath().resolve("maps").resolve(mapId + ".dat");
        final File file = path.toFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return MapData.fromNbt(file);
        } catch (IOException e) {
            plugin.log(Level.WARNING, "Failed to read legacy map file for #%d".formatted(mapId), e);
            return null;
        }
    }

    @NotNull
    private static World getDefaultMapWorld() {
        final World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            throw new IllegalStateException("No worlds are loaded on the server!");
        }
        return world;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public: Cache Access
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Get the rendered views cache. Primarily for backwards compatibility.
     */
    @NotNull
    public Map<Integer, MapView> getRenderedViews() {
        return renderedViews;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Inner Classes: MapRenderer and MapCanvas
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A {@link MapRenderer} that renders persisted {@link MapData} to a {@link MapView}.
     */
    @SuppressWarnings("deprecation")
    public static class PersistentMapRenderer extends MapRenderer {

        private final MapData canvasData;

        public PersistentMapRenderer(@NotNull MapData canvasData) {
            super(false);
            this.canvasData = canvasData;
        }

        @Override
        public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
            // Set pixels (note the coordinate order to avoid upside-down rendering)
            for (int i = 0; i < 128; i++) {
                for (int j = 0; j < 128; j++) {
                    canvas.setPixel(j, i, (byte) canvasData.getColorAt(i, j));
                }
            }

            // Set banners and markers
            final MapCursorCollection cursors = canvas.getCursors();
            while (cursors.size() > 0) {
                cursors.removeCursor(cursors.getCursor(0));
            }
            canvasData.getBanners().forEach(banner -> cursors.addCursor(createBannerCursor(banner)));
            canvas.setCursors(cursors);
        }
    }

    @NotNull
    private static MapCursor createBannerCursor(@NotNull MapBanner banner) {
        return new MapCursor(
                (byte) banner.getPosition().getX(),
                (byte) banner.getPosition().getZ(),
                (byte) 8, // Always rotate banners upright
                switch (banner.getColor().toLowerCase(Locale.ENGLISH)) {
                    case "white" -> MapCursor.Type.BANNER_WHITE;
                    case "orange" -> MapCursor.Type.BANNER_ORANGE;
                    case "magenta" -> MapCursor.Type.BANNER_MAGENTA;
                    case "light_blue" -> MapCursor.Type.BANNER_LIGHT_BLUE;
                    case "yellow" -> MapCursor.Type.BANNER_YELLOW;
                    case "lime" -> MapCursor.Type.BANNER_LIME;
                    case "pink" -> MapCursor.Type.BANNER_PINK;
                    case "gray" -> MapCursor.Type.BANNER_GRAY;
                    case "light_gray" -> MapCursor.Type.BANNER_LIGHT_GRAY;
                    case "cyan" -> MapCursor.Type.BANNER_CYAN;
                    case "purple" -> MapCursor.Type.BANNER_PURPLE;
                    case "blue" -> MapCursor.Type.BANNER_BLUE;
                    case "brown" -> MapCursor.Type.BANNER_BROWN;
                    case "green" -> MapCursor.Type.BANNER_GREEN;
                    case "red" -> MapCursor.Type.BANNER_RED;
                    default -> MapCursor.Type.BANNER_BLACK;
                },
                true,
                banner.getText().isEmpty() ? null : banner.getText()
        );
    }

    /**
     * A {@link MapCanvas} implementation for pre-rendering maps to extract {@link MapData}.
     */
    @SuppressWarnings({"deprecation", "removal"})
    public static class PersistentMapCanvas implements MapCanvas {

        private static final String BANNER_PREFIX = "banner_";

        private final int mapDataVersion;
        private final MapView mapView;
        private final int[][] pixels = new int[128][128];
        private MapCursorCollection cursors;

        public PersistentMapCanvas(@NotNull MapView mapView, int mapDataVersion) {
            this.mapDataVersion = mapDataVersion;
            this.mapView = mapView;
        }

        @NotNull
        @Override
        public MapView getMapView() {
            return mapView;
        }

        @NotNull
        @Override
        public MapCursorCollection getCursors() {
            return cursors == null ? (cursors = new MapCursorCollection()) : cursors;
        }

        @Override
        public void setCursors(@NotNull MapCursorCollection cursors) {
            this.cursors = cursors;
        }

        @Override
        @Deprecated
        public void setPixel(int x, int y, byte color) {
            pixels[x][y] = color;
        }

        @Override
        @Deprecated
        public byte getPixel(int x, int y) {
            return (byte) pixels[x][y];
        }

        @Override
        @Deprecated
        public byte getBasePixel(int x, int y) {
            return (byte) pixels[x][y];
        }

        @Override
        public void setPixelColor(int x, int y, @Nullable Color color) {
            pixels[x][y] = color == null ? -1 : MapPalette.matchColor(color);
        }

        @Nullable
        @Override
        public Color getPixelColor(int x, int y) {
            return MapPalette.getColor((byte) pixels[x][y]);
        }

        @NotNull
        @Override
        public Color getBasePixelColor(int x, int y) {
            return MapPalette.getColor((byte) pixels[x][y]);
        }

        @Override
        public void drawImage(int x, int y, @NotNull Image image) {
            // Not implemented
        }

        @Override
        public void drawText(int x, int y, @NotNull MapFont font, @NotNull String text) {
            // Not implemented
        }

        @NotNull
        private String getDimension() {
            return mapView.getWorld() != null ? switch (mapView.getWorld().getEnvironment()) {
                case NETHER -> "minecraft:the_nether";
                case THE_END -> "minecraft:the_end";
                default -> "minecraft:overworld";
            } : "minecraft:overworld";
        }

        /**
         * Extract the map data from the canvas after rendering.
         */
        @NotNull
        public MapData extractMapData() {
            final List<MapBanner> banners = Lists.newArrayList();
            for (int i = 0; i < getCursors().size(); i++) {
                final MapCursor cursor = getCursors().getCursor(i);
                final String type = cursor.getType().getKey().getKey();
                if (type.startsWith(BANNER_PREFIX)) {
                    banners.add(new MapBanner(
                            type.replaceAll(BANNER_PREFIX, ""),
                            cursor.getCaption() == null ? "" : cursor.getCaption(),
                            cursor.getX(),
                            mapView.getWorld() != null ? mapView.getWorld().getSeaLevel() : 128,
                            cursor.getY()
                    ));
                }
            }
            return MapData.fromPixels(mapDataVersion, pixels, getDimension(), (byte) 2, banners, List.of());
        }
    }
}
