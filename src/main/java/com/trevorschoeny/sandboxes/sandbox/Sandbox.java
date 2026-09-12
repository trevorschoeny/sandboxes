package com.trevorschoeny.sandboxes.sandbox;

import java.nio.file.Path;

/**
 * One sandbox on disk. {@code id} is the folder name under
 * {@code <world>/sandboxes/}; {@code name} is what the player sees.
 *
 * @param id         folder name (a UUID string), also the sandbox's identity
 * @param name       display name; a custom one survives a sync
 * @param lastJoined epoch millis of the last join, 0 for never; orders the list
 * @param dir        the sandbox's world folder
 * @param parentId   level id of the world this is a sandbox of
 */
public record Sandbox(String id, String name, long lastJoined, Path dir, String parentId) {

    /** The level id vanilla opens this sandbox with: {@code <world>/sandboxes/<id>}. */
    public String levelId() {
        return parentId + "/" + SandboxStore.SANDBOX_DIR + "/" + id;
    }
}
