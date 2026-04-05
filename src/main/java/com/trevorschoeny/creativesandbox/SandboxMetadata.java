package com.trevorschoeny.creativesandbox;

public class SandboxMetadata {
    public String id;            // UUID (folder name suffix)
    public String name;          // Display name, user-editable
    public String parentWorldId; // Folder name of the survival world
    public long lastSynced;      // Epoch millis

    public SandboxMetadata() {}

    public SandboxMetadata(String id, String name, String parentWorldId) {
        this.id = id;
        this.name = name;
        this.parentWorldId = parentWorldId;
        this.lastSynced = System.currentTimeMillis();
    }

    public String folderName() {
        return "__sandbox__" + id;
    }

    public String formattedLastSynced() {
        if (lastSynced == 0) return "Never";
        java.time.LocalDateTime dt = java.time.LocalDateTime.ofEpochSecond(
            lastSynced / 1000, 0, java.time.ZoneOffset.systemDefault().getRules()
                .getOffset(java.time.Instant.ofEpochMilli(lastSynced))
        );
        return dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"));
    }
}
